/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.inspector.mcp.webmvc.proxy;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.spec.McpClientTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthHeaders;
import io.inspector.mcp.core.auth.AuthProfile;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxyErrorDto;
import io.inspector.mcp.core.proxy.ProxyErrorMapper;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTargetResolver;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.proxy.TransportKind;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;
import io.inspector.mcp.webmvc.auth.ServletSessionOwnerResolver;

/**
 * Upstream-compatible proxy endpoints for SSE-style sessions.
 *
 * <p>
 * Two routes:
 *
 * <ul>
 * <li>{@code GET /mcp-inspector-api/sse} — opens a server-sent-events stream that bridges
 * the browser to the target transport (SSE, Streamable-HTTP <em>or</em> stdio when
 * {@code transportType=stdio}).</li>
 * <li>{@code POST /mcp-inspector-api/message?sessionId=...} — pushes a single JSON-RPC
 * frame from the browser into the session's {@code browserToTarget} sink.</li>
 * <li>{@code GET /mcp-inspector-api/stdio} — convenience alias for opening a stdio target
 * (upstream exposes it on a separate path).</li>
 * </ul>
 *
 * <p>
 * The SSE response opens with an {@code event: endpoint} prologue that tells the
 * browser-side {@code SSEClientTransport} where to POST messages. Subsequent frames from
 * the target are streamed as {@code event: message}.
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class SseProxyController {

	private static final Logger LOG = LoggerFactory.getLogger(SseProxyController.class);

	private final ProxySessionRegistry registry;

	private final ProxyTransportFactory transportFactory;

	private final McpProxy mcpProxy;

	private final JsonMapper objectMapper;

	private final McpInspectorProperties properties;

	private final InspectorServerPortHolder portHolder;

	private final ServletSessionOwnerResolver sessionOwnerResolver;

	private final AuthProfileStore authProfileStore;

	private final OAuth2ClientCredentialsTokenManager ccTokenManager;

	private final OAuth2AuthCodeTokenExchanger authCodeExchanger;

	public SseProxyController(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final JsonMapper objectMapper, final McpInspectorProperties properties) {
		this(registry, transportFactory, mcpProxy, objectMapper, properties, null, null, null, null, null);
	}

	public SseProxyController(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final JsonMapper objectMapper, final McpInspectorProperties properties,
			final InspectorServerPortHolder portHolder) {
		this(registry, transportFactory, mcpProxy, objectMapper, properties, portHolder, null, null, null, null);
	}

	/**
	 * Full constructor with the D8/D9 wiring: the session-owner resolver (owner id from
	 * the signed cookie) and the owner-scoped auth-profile store + OAuth2 managers used
	 * to resolve the bound profile into transport headers.
	 * @param registry the proxy session registry
	 * @param transportFactory the transport factory
	 * @param mcpProxy the proxy pump
	 * @param objectMapper the JSON mapper
	 * @param properties the inspector properties
	 * @param portHolder the loopback port holder
	 * @param sessionOwnerResolver the signed-cookie owner resolver
	 * @param authProfileStore the owner-scoped profile store
	 * @param ccTokenManager the client-credentials token manager
	 * @param authCodeExchanger the auth-code exchanger
	 */
	@Autowired
	public SseProxyController(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final JsonMapper objectMapper, final McpInspectorProperties properties,
			final InspectorServerPortHolder portHolder, final ServletSessionOwnerResolver sessionOwnerResolver,
			final AuthProfileStore authProfileStore, final OAuth2ClientCredentialsTokenManager ccTokenManager,
			final OAuth2AuthCodeTokenExchanger authCodeExchanger) {
		this.registry = registry;
		this.transportFactory = transportFactory;
		this.mcpProxy = mcpProxy;
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.properties = properties;
		this.portHolder = portHolder;
		this.sessionOwnerResolver = sessionOwnerResolver;
		this.authProfileStore = authProfileStore;
		this.ccTokenManager = ccTokenManager;
		this.authCodeExchanger = authCodeExchanger;
	}

	private int loopbackPort() {
		return (this.portHolder != null) ? this.portHolder.port() : 8080;
	}

	private McpInspectorProperties.Timeouts resolveTimeouts() {
		return (this.properties != null) ? this.properties.getTimeouts() : new McpInspectorProperties.Timeouts();
	}

	/**
	 * Opens an SSE stream and creates a new proxy session backed by a target MCP
	 * transport.
	 *
	 * <p>
	 * Query params:
	 *
	 * <ul>
	 * <li>{@code transportType}: {@code sse} | {@code streamable-http} |
	 * {@code stdio}</li>
	 * <li>{@code url}: target URL for sse/streamable</li>
	 * <li>{@code command}, {@code args}, {@code env}: stdio target</li>
	 * </ul>
	 * @param transportType the transport type ({@code sse}, {@code streamable-http}, or
	 * {@code stdio})
	 * @param url the target URL for SSE or streamable-HTTP transports
	 * @param command the executable for stdio transport
	 * @param args the arguments for stdio transport
	 * @param env the environment variables for stdio transport as JSON
	 * @param request the current request, read for its context path
	 * @return the {@link SseEmitter} for the opened session
	 */
	@GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter openSse(
			@RequestParam(value = "transportType", required = false, defaultValue = "sse") final String transportType,
			@RequestParam(value = "url", required = false) final String url,
			@RequestParam(value = "command", required = false) final String command,
			@RequestParam(value = "args", required = false) final String args,
			@RequestParam(value = "env", required = false) final String env, final HttpServletRequest request) {
		return openSse(transportType, url, command, args, env, null, request);
	}

	/**
	 * {@code GET /sse} variant for a bound auth profile: the {@code profileId} query
	 * parameter selects the owner-scoped profile whose resolved headers are applied to
	 * every upstream request (D8).
	 * @param transportType the transport type ({@code sse}, {@code streamable-http}, or
	 * {@code stdio})
	 * @param url the target URL for SSE or streamable-HTTP transports
	 * @param command the executable for stdio transport
	 * @param args the arguments for stdio transport
	 * @param env the environment variables for stdio transport as JSON
	 * @param profileId the owner-scoped auth profile id, or {@code null}
	 * @param request the current request, read for its context path
	 * @return the {@link SseEmitter} for the opened session
	 */
	@GetMapping(path = "/sse", params = "profileId", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter openSse(
			@RequestParam(value = "transportType", required = false, defaultValue = "sse") final String transportType,
			@RequestParam(value = "url", required = false) final String url,
			@RequestParam(value = "command", required = false) final String command,
			@RequestParam(value = "args", required = false) final String args,
			@RequestParam(value = "env", required = false) final String env,
			@RequestParam(value = "profileId", required = false) final String profileId,
			final HttpServletRequest request) {
		return openProxiedSession(transportType, url, command, args, env, profileId, contextPath(request));
	}

	/**
	 * Convenience alias for stdio targets. Upstream exposes {@code GET /stdio} too.
	 * @param command the executable for stdio transport
	 * @param args the arguments for stdio transport
	 * @param env the environment variables for stdio transport as JSON
	 * @param request the current request, read for its context path
	 * @return the {@link SseEmitter} for the opened session
	 */
	@GetMapping(path = "/stdio", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter openStdio(@RequestParam("command") final String command,
			@RequestParam(value = "args", required = false) final String args,
			@RequestParam(value = "env", required = false) final String env, final HttpServletRequest request) {
		return openProxiedSession("stdio", null, command, args, env, null, contextPath(request));
	}

	/**
	 * {@code GET /stdio} variant for a bound auth profile (D8).
	 * @param command the executable for stdio transport
	 * @param args the arguments for stdio transport
	 * @param env the environment variables for stdio transport as JSON
	 * @param profileId the owner-scoped auth profile id, or {@code null}
	 * @param request the current request, read for its context path
	 * @return the {@link SseEmitter} for the opened session
	 */
	@GetMapping(path = "/stdio", params = "profileId", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter openStdio(@RequestParam("command") final String command,
			@RequestParam(value = "args", required = false) final String args,
			@RequestParam(value = "env", required = false) final String env,
			@RequestParam(value = "profileId", required = false) final String profileId,
			final HttpServletRequest request) {
		return openProxiedSession("stdio", null, command, args, env, profileId, contextPath(request));
	}

	/**
	 * Posts a JSON-RPC frame from the browser into the session's {@code browserToTarget}
	 * sink, which forwards to the upstream transport.
	 * @param sessionId the proxy session identifier
	 * @param body the JSON-RPC frame to forward
	 * @return 202 on success, 404 if the session is unknown, 500 on emit failure
	 */
	@PostMapping(path = "/message", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> postMessage(@RequestParam("sessionId") final String sessionId,
			@RequestBody final JsonNode body) {
		final ProxySession session = this.registry.get(sessionId);
		if (session == null || !isOwnerOf(session)) {
			return ResponseEntity.notFound().build();
		}
		final Sinks.EmitResult result = session.browserToTarget().tryEmitNext(body);
		if (result.isFailure()) {
			LOG.warn("proxy[{}] /message emit failure: {}", sessionId, result.name());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
		session.touch();
		return ResponseEntity.accepted().build();
	}

	// ---------------------------------------------------------------------
	// session bring-up
	// ---------------------------------------------------------------------

	private static String contextPath(final HttpServletRequest request) {
		// A root-mounted application reports "" per the servlet spec; a container
		// reporting "/" instead would yield a protocol-relative "//..." prologue.
		final String contextPath = request.getContextPath();
		return "/".equals(contextPath) ? "" : contextPath;
	}

	private SseEmitter openProxiedSession(final String transportType, final String url, final String command,
			final String args, final String env, final String profileId, final String contextPath) {
		final String sessionId = UUID.randomUUID().toString();
		final SseEmitter emitter = new SseEmitter(resolveTimeouts().getSseSession().toMillis());

		// D8: resolve the owner from the signed session cookie (mint on demand) and the
		// bound profile when a profileId is supplied. A foreign/unknown profileId is a
		// structured 400 — existence is not leaked.
		final String ownerId = resolveOwner();
		final AuthHeaders headers;
		if (profileId != null && !profileId.isBlank()) {
			if (this.authProfileStore == null || this.sessionOwnerResolver == null) {
				throw new IllegalStateException("auth-profile support is not wired");
			}
			final Optional<AuthProfile> profile = this.authProfileStore.resolve(ownerId, profileId);
			if (profile.isEmpty()) {
				return structuredErrorEmitter(emitter,
						new ProxyErrorDto(400, "bad_request", "Invalid or missing auth profile or session reference.",
								"Check the profile fields and profileId, then reconnect.", null));
			}
			headers = AuthHeaders.resolve(profile.get(), profileId, this.ccTokenManager, this.authCodeExchanger);
		}
		else {
			headers = null;
		}

		final AtomicReference<String> authorizationRef = new AtomicReference<>(
				(headers != null) ? headers.authorization() : null);
		final McpClientTransport target;
		try {
			target = buildTargetTransport(transportType, url, command, args, env, headers, authorizationRef);
		}
		catch (final Exception ex) {
			LOG.warn("proxy[{}] failed to build target transport: {}", sessionId, ex.toString());
			emitter.completeWithError(ex);
			return emitter;
		}

		// browserToTarget stays unicast (single consumer in McpProxy);
		// targetToBrowser is multicast-replay so the new Streamable-HTTP code can
		// attach per-request awaiters AND keep the long-lived GET /mcp SSE stream
		// working. For the SSE proxy this is a strict superset of the previous
		// unicast contract.
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
		final ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser,
				authorizationRef);
		if (profileId != null && !profileId.isBlank()) {
			// One-time bind: rejected reuse / foreign ids close the handoff (D4/D8).
			if (!this.authProfileStore.bind(ownerId, profileId, sessionId)) {
				this.registry.removeAndClose(sessionId);
				return structuredErrorEmitter(emitter,
						new ProxyErrorDto(400, "bad_request", "Invalid or missing auth profile or session reference.",
								"Check the profile fields and profileId, then reconnect.", null));
			}
			session.bindProfile(ownerId, profileId);
		}
		this.registry.put(session);

		// SSE prologue — tells SSEClientTransport on the browser where to POST.
		// The value must carry the deployment prefix: the browser resolves it with
		// new URL(data, sseUrl), and a path-absolute value replaces the whole path,
		// so without the prefix every first client->server frame would 404.
		try {
			final String messageEndpoint = contextPath + this.properties.getProxyPath() + "/message?sessionId="
					+ sessionId;
			emitter.send(SseEmitter.event().name("endpoint").data(messageEndpoint));
		}
		catch (final IOException ex) {
			LOG.warn("proxy[{}] failed to send endpoint event: {}", sessionId, ex.toString());
			this.registry.removeAndClose(sessionId);
			emitter.completeWithError(ex);
			return emitter;
		}

		// target → browser pump. takeUntilOther completes the emitter off the session's
		// lock-free close signal: close() can fail to complete the sink itself whenever
		// another thread owns it at that instant, and a never-completed emitter is
		// exactly what makes Boot's graceful shutdown pay its full timeout.
		targetToBrowser.asFlux()
			.takeUntilOther(session.closeSignal())
			.subscribe((frame) -> sendMessageEvent(emitter, sessionId, frame), (err) -> {
				LOG.warn("proxy[{}] target stream errored: {}", sessionId, err.toString());
				completeWithMappedError(emitter, err, TransportKind.SSE, targetUri(transportType, url));
			}, () -> emitter.complete());

		emitter.onCompletion(() -> this.registry.removeAndClose(sessionId));
		emitter.onTimeout(() -> this.registry.removeAndClose(sessionId));
		emitter.onError((ex) -> this.registry.removeAndClose(sessionId));

		// Kick off the proxy. This call subscribes the browser->target pump
		// and registers the inbound handler on the target transport.
		this.mcpProxy.start(session).subscribe((ignored) -> {
		}, (err) -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			// D3: the structured error event must land BEFORE the session teardown —
			// removeAndClose fires the close signal, which completes the emitter, and
			// a completed emitter can no longer send the error frame (same ordering
			// rule the webflux handler documents).
			completeWithMappedError(emitter, err, TransportKind.SSE, targetUri(transportType, url));
			this.registry.removeAndClose(sessionId);
		});

		return emitter;
	}

	/**
	 * Completes the emitter with the D3 contract: a mappable transport failure emits a
	 * structured {@code error} event and completes normally; anything else falls back to
	 * the legacy {@code completeWithError}.
	 * @param emitter the SSE emitter to complete
	 * @param err the transport failure
	 * @param kind the transport kind gating the mapping
	 * @param target the upstream target URI for the D5-redacted {@code url} field
	 */
	private void completeWithMappedError(final SseEmitter emitter, final Throwable err, final TransportKind kind,
			final URI target) {
		final ProxyErrorDto dto = ProxyErrorMapper.map(err, kind);
		if (dto == null) {
			emitter.completeWithError(err);
			return;
		}
		try {
			final ProxyErrorDto withUrl = dto.withUrl(ProxyErrorDto.redactUrl(target));
			emitter.send(SseEmitter.event().name("error").data(this.objectMapper.writeValueAsString(withUrl)));
			emitter.complete();
		}
		catch (final IOException ex) {
			emitter.completeWithError(ex);
		}
	}

	/**
	 * Emits a structured {@code error} event with the DTO body and completes the emitter
	 * normally (the handoff-rejection path).
	 * @param emitter the SSE emitter to complete
	 * @param dto the structured error DTO
	 * @return the completed emitter
	 */
	private SseEmitter structuredErrorEmitter(final SseEmitter emitter, final ProxyErrorDto dto) {
		try {
			emitter.send(SseEmitter.event().name("error").data(this.objectMapper.writeValueAsString(dto)));
		}
		catch (final IOException ex) {
			LOG.warn("proxy: failed to emit structured error event: {}", ex.toString());
		}
		emitter.complete();
		return emitter;
	}

	/**
	 * Resolves the request's session owner via the signed cookie (D8); re-mints on
	 * absent/forged/expired.
	 * @return the validated owner id
	 */
	private String resolveOwner() {
		if (this.sessionOwnerResolver == null) {
			return null;
		}
		return this.sessionOwnerResolver.resolve(currentRequest(), currentResponse());
	}

	/**
	 * Checks whether the current request's owner matches the session's bound owner.
	 * Sessions without a bound profile (no ownerId) remain accessible to all callers,
	 * matching the pre-auth-profile behaviour.
	 * @param session the session to check
	 * @return {@code true} when the caller owns the session or the session has no owner
	 */
	private boolean isOwnerOf(final ProxySession session) {
		final String sessionOwner = session.ownerId();
		if (sessionOwner == null) {
			return true;
		}
		final String callerOwner = resolveOwner();
		return callerOwner != null && callerOwner.equals(sessionOwner);
	}

	private static HttpServletResponse currentResponse() {
		final var attrs = RequestContextHolder.getRequestAttributes();
		return (attrs instanceof ServletRequestAttributes sra) ? sra.getResponse() : null;
	}

	/**
	 * Resolves the upstream target URI for the D5-redacted {@code url} field of error
	 * DTOs. {@code null} for stdio targets (no URL to redact).
	 * @param transportType the transport type
	 * @param url the target URL
	 * @return the resolved target URI, or {@code null}
	 */
	private URI targetUri(final String transportType, final String url) {
		final String type = (transportType != null) ? transportType.toLowerCase() : "sse";
		return switch (type) {
			case "sse" -> ProxyTargetResolver.resolve(url, loopbackPort(), "/sse");
			case "streamable-http" -> ProxyTargetResolver.resolve(url, loopbackPort(), "/mcp");
			default -> null;
		};
	}

	private void sendMessageEvent(final SseEmitter emitter, final String sessionId, final JsonNode frame) {
		try {
			emitter.send(SseEmitter.event().name("message").data(this.objectMapper.writeValueAsString(frame)));
		}
		catch (final IOException ex) {
			LOG.warn("proxy[{}] failed to send SSE message: {}", sessionId, ex.toString());
			emitter.completeWithError(ex);
		}
	}

	private McpClientTransport buildTargetTransport(final String transportType, final String url, final String command,
			final String args, final String env, final AuthHeaders headers,
			final AtomicReference<String> authorizationRef) throws Exception {
		final String type = (transportType != null) ? transportType.toLowerCase() : "sse";
		return switch (type) {
			case "sse" -> {
				final URI target = ProxyTargetResolver.resolve(url, loopbackPort(), "/sse");
				yield (headers != null) ? this.transportFactory.openSseWithAuth(target, headers, authorizationRef)
						: openSseWithInboundHeaders(target);
			}
			case "streamable-http" -> {
				final URI target = ProxyTargetResolver.resolve(url, loopbackPort(), "/mcp");
				yield (headers != null)
						? this.transportFactory.openStreamableWithAuth(target, headers, authorizationRef)
						: openStreamableWithInboundHeaders(target);
			}
			case "stdio" -> {
				if (command == null || command.isBlank()) {
					throw new IllegalArgumentException(
							"missing required 'command' query parameter for stdio transport");
				}
				final List<String> argv = parseArgv(command, args);
				final Map<String, String> environment = parseEnv(env);
				yield this.transportFactory.openStdio(argv, environment);
			}
			default -> throw new IllegalArgumentException("unsupported transportType: " + transportType);
		};
	}

	/**
	 * Opens an SSE transport forwarding the inbound {@code Authorization} header and the
	 * headers named by {@code x-custom-auth-headers} (legacy no-profile path); falls back
	 * to the single-arg overload when nothing needs forwarding.
	 * @param target the resolved target URI
	 * @return a configured SSE transport
	 */
	private McpClientTransport openSseWithInboundHeaders(final URI target) {
		final String authorization = inboundAuthorization();
		final Map<String, String> customHeaders = inboundCustomHeaders();
		final boolean noHeaders = authorization == null && customHeaders.isEmpty();
		return noHeaders ? this.transportFactory.openSse(target)
				: this.transportFactory.openSse(target, authorization, customHeaders);
	}

	/**
	 * Opens a streamable transport forwarding the inbound {@code Authorization} header
	 * and the headers named by {@code x-custom-auth-headers} (legacy no-profile path);
	 * falls back to the single-arg overload when nothing needs forwarding.
	 * @param target the resolved target URI
	 * @return a configured streamable transport
	 */
	private McpClientTransport openStreamableWithInboundHeaders(final URI target) {
		final String authorization = inboundAuthorization();
		final Map<String, String> customHeaders = inboundCustomHeaders();
		final boolean noHeaders = authorization == null && customHeaders.isEmpty();
		return noHeaders ? this.transportFactory.openStreamable(target)
				: this.transportFactory.openStreamable(target, authorization, customHeaders);
	}

	/**
	 * Reads the inbound {@code Authorization} header value, or {@code null} when absent.
	 * @return the forwarded {@code Authorization} value, or {@code null}
	 */
	private static String inboundAuthorization() {
		final HttpServletRequest request = currentRequest();
		return (request != null) ? request.getHeader("Authorization") : null;
	}

	/**
	 * Reads the custom headers named by the {@code x-custom-auth-headers} request header
	 * (comma-separated header names) and returns their inbound values, so they can be
	 * forwarded verbatim to the upstream MCP server.
	 * @return a map of custom header name → value (never {@code null})
	 */
	private static Map<String, String> inboundCustomHeaders() {
		final HttpServletRequest request = currentRequest();
		if (request == null) {
			return Map.of();
		}
		final String named = request.getHeader("x-custom-auth-headers");
		if (named == null || named.isBlank()) {
			return Map.of();
		}
		final Map<String, String> out = new LinkedHashMap<>();
		for (final String raw : named.split(",")) {
			final String name = raw.trim();
			if (name.isEmpty()) {
				continue;
			}
			final String value = request.getHeader(name);
			if (value != null) {
				out.put(name, value);
			}
		}
		return out;
	}

	private static HttpServletRequest currentRequest() {
		final var attrs = RequestContextHolder.getRequestAttributes();
		return (attrs instanceof ServletRequestAttributes sra) ? sra.getRequest() : null;
	}

	/**
	 * Tokenize {@code command} into [exe, ...] and append shell-split {@code args}.
	 * @param command the executable command
	 * @param args optional whitespace-separated arguments
	 * @return the full argument vector
	 */
	private static List<String> parseArgv(final String command, final String args) {
		final List<String> argv = new java.util.ArrayList<>();
		argv.add(command.trim());
		if (args != null && !args.isBlank()) {
			// upstream uses shell-quote; we do a plain whitespace split — good enough
			// for the inspector "stdio target" form which accepts simple args.
			argv.addAll(Arrays.asList(args.trim().split("\\s+")));
		}
		return argv;
	}

	private Map<String, String> parseEnv(final String env) {
		if (env == null || env.isBlank()) {
			return Map.of();
		}
		try {
			final JsonNode node = this.objectMapper.readTree(env);
			final Map<String, String> out = new LinkedHashMap<>();
			node.properties().forEach((e) -> out.put(e.getKey(), e.getValue().asText()));
			return out;
		}
		catch (final Exception ex) {
			LOG.warn("proxy: ignoring malformed env JSON: {}", ex.toString());
			return new HashMap<>();
		}
	}

}
