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

package io.inspector.mcp.webflux.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.auth.AuthHeaders;
import io.inspector.mcp.core.auth.AuthProfile;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.CustomHeaderNames;
import io.inspector.mcp.core.proxy.InvalidProxyRequestException;
import io.inspector.mcp.core.proxy.LegacySessionAccessWarner;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxyConnectFailure;
import io.inspector.mcp.core.proxy.ProxyErrorDto;
import io.inspector.mcp.core.proxy.ProxyErrorMapper;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTargetResolver;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.proxy.TransportKind;
import io.inspector.mcp.core.task.TaskHandle;
import io.inspector.mcp.core.task.TaskNotCancelableException;
import io.inspector.mcp.core.task.TaskNotFoundException;
import io.inspector.mcp.core.task.TaskService;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.inspector.mcp.webflux.auth.ReactiveSessionOwnerResolver;

/**
 * Reactive handlers for the upstream-compatible proxy endpoints. Mirrors the
 * servlet-stack {@code SseProxyController} / {@code StreamableHttpProxyController} but
 * uses functional routing + {@link ServerSentEvent} streams.
 *
 * <p>
 * Endpoint layout under {@link ProxyConstants#BASE}:
 *
 * <ul>
 * <li>{@code GET /sse} / {@code GET /stdio} — opens a session, returns SSE stream</li>
 * <li>{@code POST /message} — pushes a frame into an open SSE session</li>
 * <li>{@code POST/GET/DELETE /mcp} — Streamable-HTTP transport</li>
 * <li>{@code GET /config} — defaults for the client form</li>
 * <li>{@code GET /health} — liveness</li>
 * <li>{@code POST /fetch} — outbound HTTP proxy</li>
 * </ul>
 *
 * @author Artem Simeshin
 */
public class ProxyHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ProxyHandler.class);

	/** Server namespace of the separate actuator web server, when one is started. */
	private static final String MANAGEMENT_NAMESPACE = "management";

	private final ProxySessionRegistry registry;

	private final ProxyTransportFactory transportFactory;

	private final McpProxy mcpProxy;

	private final TransportDetector transportDetector;

	private final JsonMapper objectMapper;

	private final McpInspectorProperties properties;

	private final McpInspectorProperties.Timeouts timeouts;

	private final HttpClient outboundHttpClient;

	private final AtomicInteger listeningPort = new AtomicInteger(-1);

	/** Page size for intercepted {@code tasks/list} responses (SEP-1686 pagination). */
	private static final int TASKS_LIST_PAGE_SIZE = 100;

	private final TaskService taskService;

	private final ReactiveSessionOwnerResolver sessionOwnerResolver;

	private final AuthProfileStore authProfileStore;

	private final OAuth2ClientCredentialsTokenManager ccTokenManager;

	private final OAuth2AuthCodeTokenExchanger authCodeExchanger;

	public ProxyHandler(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final TransportDetector transportDetector, final JsonMapper objectMapper) {
		this(registry, transportFactory, mcpProxy, transportDetector, objectMapper, null, null, null, null, null, null);
	}

	public ProxyHandler(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final TransportDetector transportDetector, final JsonMapper objectMapper,
			final McpInspectorProperties properties) {
		this(registry, transportFactory, mcpProxy, transportDetector, objectMapper, properties, null, null, null, null,
				null);
	}

	/**
	 * Constructor that also accepts an optional {@link TaskService} for local task-method
	 * interception (no auth wiring).
	 * @param registry the proxy session registry
	 * @param transportFactory the proxy transport factory
	 * @param mcpProxy the proxy relay pump
	 * @param transportDetector the transport detector
	 * @param objectMapper the JSON mapper
	 * @param properties the inspector properties
	 * @param taskService the task service for intercepted methods, or {@code null}
	 */
	public ProxyHandler(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final TransportDetector transportDetector, final JsonMapper objectMapper,
			final McpInspectorProperties properties, final TaskService taskService) {
		this(registry, transportFactory, mcpProxy, transportDetector, objectMapper, properties, null, null, null, null,
				taskService);
	}

	/**
	 * Full constructor with the D8/D9 wiring: the session-owner resolver (owner id from
	 * the signed cookie) and the owner-scoped auth-profile store + OAuth2 managers used
	 * to resolve the bound profile into transport headers.
	 * @param registry the proxy session registry
	 * @param transportFactory the proxy transport factory
	 * @param mcpProxy the proxy pump
	 * @param transportDetector the transport detector
	 * @param objectMapper the JSON mapper
	 * @param properties the inspector properties
	 * @param sessionOwnerResolver the signed-cookie owner resolver
	 * @param authProfileStore the owner-scoped profile store
	 * @param ccTokenManager the client-credentials token manager
	 * @param authCodeExchanger the auth-code exchanger
	 */
	public ProxyHandler(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final TransportDetector transportDetector, final JsonMapper objectMapper,
			final McpInspectorProperties properties, final ReactiveSessionOwnerResolver sessionOwnerResolver,
			final AuthProfileStore authProfileStore, final OAuth2ClientCredentialsTokenManager ccTokenManager,
			final OAuth2AuthCodeTokenExchanger authCodeExchanger) {
		this(registry, transportFactory, mcpProxy, transportDetector, objectMapper, properties, sessionOwnerResolver,
				authProfileStore, ccTokenManager, authCodeExchanger, null);
	}

	/**
	 * Complete constructor: D8/D9 auth wiring plus the optional {@link TaskService} for
	 * local task-method interception.
	 * @param registry the proxy session registry
	 * @param transportFactory the proxy transport factory
	 * @param mcpProxy the proxy relay pump
	 * @param transportDetector the transport detector
	 * @param objectMapper the JSON mapper
	 * @param properties the inspector properties
	 * @param sessionOwnerResolver the signed-cookie owner resolver
	 * @param authProfileStore the owner-scoped profile store
	 * @param ccTokenManager the client-credentials token manager
	 * @param authCodeExchanger the auth-code exchanger
	 * @param taskService the task service for intercepted methods, or {@code null}
	 */
	public ProxyHandler(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final TransportDetector transportDetector, final JsonMapper objectMapper,
			final McpInspectorProperties properties, final ReactiveSessionOwnerResolver sessionOwnerResolver,
			final AuthProfileStore authProfileStore, final OAuth2ClientCredentialsTokenManager ccTokenManager,
			final OAuth2AuthCodeTokenExchanger authCodeExchanger, final TaskService taskService) {
		this.registry = registry;
		this.transportFactory = transportFactory;
		this.mcpProxy = mcpProxy;
		this.transportDetector = transportDetector;
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.properties = properties;
		this.timeouts = (properties != null) ? properties.getTimeouts() : new McpInspectorProperties.Timeouts();
		this.outboundHttpClient = HttpClient.newBuilder().connectTimeout(this.timeouts.getFetchConnect()).build();
		this.taskService = taskService;
		this.sessionOwnerResolver = sessionOwnerResolver;
		this.authProfileStore = authProfileStore;
		this.ccTokenManager = ccTokenManager;
		this.authCodeExchanger = authCodeExchanger;
	}

	@EventListener
	public void onWebServerStarted(final WebServerInitializedEvent event) {
		// Skip the actuator's own server (management.server.port): last-writer-wins
		// would otherwise leave the loopback port pointing at the management port.
		final WebServerApplicationContext context = event.getApplicationContext();
		if (context != null && MANAGEMENT_NAMESPACE.equals(context.getServerNamespace())) {
			return;
		}
		this.listeningPort.set(event.getWebServer().getPort());
	}

	private int loopbackPort() {
		final int port = this.listeningPort.get();
		return (port > 0) ? port : 8080;
	}

	// ---------------------------------------------------------------------
	// health / config / fetch
	// ---------------------------------------------------------------------

	public Mono<ServerResponse> health(final ServerRequest request) {
		return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("status", "ok"));
	}

	public Mono<ServerResponse> config(final ServerRequest request) {
		final DetectedTransport detected = this.transportDetector.detect();
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("defaultEnvironment", Map.of());
		body.put("defaultCommand", "");
		body.put("defaultArgs", "");
		body.put("defaultTransport", mapTransport(detected.type()));
		body.put("defaultServerUrl", buildServerUrl(detected));
		return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
	}

	/**
	 * Reads the request body as a Jackson 2 {@link JsonNode}.
	 *
	 * <p>
	 * Spring Framework 7 routes {@code bodyToMono(JsonNode.class)} through the Jackson 3
	 * reactive codec, which cannot bind to the Jackson 2 {@code com.fasterxml} node tree
	 * this proxy is built on. Decode the raw text instead and parse it with the
	 * inspector's own Jackson 2 {@link JsonMapper} so the whole proxy stays on a single
	 * Jackson.
	 * @param request the incoming request
	 * @return the parsed body, or an empty {@link Mono} when the request has no body
	 */
	private Mono<JsonNode> readJsonBody(final ServerRequest request) {
		return request.bodyToMono(String.class)
			.flatMap((raw) -> Mono.fromCallable(() -> this.objectMapper.readTree(raw)));
	}

	public Mono<ServerResponse> fetch(final ServerRequest request) {
		// doFetch() ends in a blocking HttpClient.send() — keep it off the event loop.
		return readJsonBody(request).flatMap((body) -> Mono.fromCallable(() -> doFetch(body))
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap((envelope) -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(envelope))
			.onErrorResume((ex) -> ServerResponse.status(502)
				.bodyValue(
						Map.of("error", (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName()))));
	}

	private Map<String, Object> doFetch(final JsonNode body) throws Exception {
		final JsonNode urlNode = body.get("url");
		if (urlNode == null || !urlNode.isTextual()) {
			throw new IllegalArgumentException("missing or invalid url");
		}
		final URI uri = URI.create(urlNode.asText());
		final String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException("only http/https URLs are allowed");
		}
		final JsonNode init = body.get("init");
		final String method = (init != null && init.hasNonNull("method")) ? init.get("method").asText("GET") : "GET";
		final String reqBody = (init != null && init.hasNonNull("body")) ? init.get("body").asText("") : "";

		final HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
			.timeout(this.timeouts.getFetchRequest())
			.method(method.toUpperCase(), reqBody.isEmpty() ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(reqBody));
		if (init != null && init.has("headers")) {
			init.get("headers").properties().forEach((e) -> {
				try {
					rb.header(e.getKey(), e.getValue().asText());
				}
				catch (final Exception ignored) {
					// restricted header names are silently skipped
				}
			});
		}
		final HttpResponse<String> resp = this.outboundHttpClient.send(rb.build(),
				HttpResponse.BodyHandlers.ofString());
		final Map<String, String> respHeaders = new LinkedHashMap<>();
		resp.headers().map().forEach((k, v) -> {
			if (!v.isEmpty()) {
				respHeaders.put(k, v.get(0));
			}
		});
		final Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", resp.statusCode() >= 200 && resp.statusCode() < 300);
		out.put("status", resp.statusCode());
		out.put("statusText", "");
		out.put("headers", respHeaders);
		out.put("body", resp.body());
		return out;
	}

	// ---------------------------------------------------------------------
	// SSE proxy
	// ---------------------------------------------------------------------

	public Mono<ServerResponse> openSse(final ServerRequest request) {
		final String transportType = request.queryParam("transportType").orElse("sse");
		final String url = request.queryParam("url").orElse(null);
		final String command = request.queryParam("command").orElse(null);
		final String args = request.queryParam("args").orElse(null);
		final String env = request.queryParam("env").orElse(null);
		final String profileId = request.queryParam("profileId").orElse(null);
		return withCustomHeaders(request, (customHeaders) -> openProxiedSession(transportType, url, command, args, env,
				profileId, inboundAuthorization(request), customHeaders, request));
	}

	public Mono<ServerResponse> openStdio(final ServerRequest request) {
		final String command = request.queryParam("command").orElse(null);
		final String args = request.queryParam("args").orElse(null);
		final String env = request.queryParam("env").orElse(null);
		final String profileId = request.queryParam("profileId").orElse(null);
		return withCustomHeaders(request, (customHeaders) -> openProxiedSession("stdio", null, command, args, env,
				profileId, inboundAuthorization(request), customHeaders, request));
	}

	/**
	 * Returns the request's deployment prefix — the WebFlux base path or a forwarded
	 * proxy prefix — normalised so a root-mounted application contributes nothing.
	 * @param request the incoming request
	 * @return the prefix, or an empty string when the application is root-mounted
	 */
	private static String contextPath(final ServerRequest request) {
		final String contextPath = request.requestPath().contextPath().value();
		return "/".equals(contextPath) ? "" : contextPath;
	}

	/**
	 * Reads the inbound {@code Authorization} header value, or {@code null} when absent.
	 * @param request the incoming request
	 * @return the forwarded {@code Authorization} value, or {@code null}
	 */
	private static String inboundAuthorization(final ServerRequest request) {
		return request.headers().firstHeader("Authorization");
	}

	/**
	 * Reads the custom headers named by the {@code x-custom-auth-headers} request header
	 * (a JSON array of header names, see {@link CustomHeaderNames}) and returns their
	 * inbound values, so they can be forwarded verbatim to the upstream MCP server.
	 * @param request the incoming request
	 * @return a map of custom header name → value (never {@code null})
	 * @throws IllegalArgumentException when the payload is present but malformed
	 */
	private static Map<String, String> inboundCustomHeaders(final ServerRequest request) {
		return CustomHeaderNames.resolve(request.headers().firstHeader(CustomHeaderNames.HEADER),
				request.headers()::firstHeader);
	}

	/**
	 * Runs the session opener with the parsed custom headers, translating a malformed
	 * {@code x-custom-auth-headers} payload into an explicit 400 instead of a session
	 * that silently forwards no custom headers.
	 * @param request the incoming request
	 * @param opener opens the session with the parsed (never {@code null}) custom headers
	 * @return the opener's response, or a 400 when the payload is malformed
	 */
	private static Mono<ServerResponse> withCustomHeaders(final ServerRequest request,
			final java.util.function.Function<Map<String, String>, Mono<ServerResponse>> opener) {
		final Map<String, String> customHeaders;
		try {
			customHeaders = inboundCustomHeaders(request);
		}
		catch (final IllegalArgumentException ex) {
			return ServerResponse.badRequest()
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("error", ex.getMessage()));
		}
		return opener.apply(customHeaders);
	}

	public Mono<ServerResponse> postMessage(final ServerRequest request) {
		final String sessionId = request.queryParam("sessionId").orElse(null);
		if (sessionId == null) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "missing sessionId"));
		}
		final ProxySession session = this.registry.get(sessionId);
		if (session == null || !isOwnerOf(session, request)) {
			return ServerResponse.notFound().build();
		}
		return readJsonBody(request).flatMap((body) -> {
			final Sinks.EmitResult er = session.browserToTarget().tryEmitNext(body);
			if (er.isFailure()) {
				return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.bodyValue(Map.of("error", "emit failed: " + er.name()));
			}
			session.touch();
			return ServerResponse.accepted().build();
		});
	}

	private Mono<ServerResponse> openProxiedSession(final String transportType, final String url, final String command,
			final String args, final String env, final String profileId, final String authorization,
			final Map<String, String> customHeaders, final ServerRequest request) {
		final String sessionId = UUID.randomUUID().toString();
		final String contextPath = contextPath(request);
		// D8: resolve the owner from the signed session cookie (mint on demand) and the
		// bound profile when a profileId is supplied. A foreign/unknown profileId is a
		// structured 400 — existence is not leaked.
		final String ownerId = resolveOwner(request);
		final AuthHeaders headers;
		if (profileId != null && !profileId.isBlank()) {
			if (this.authProfileStore == null || this.sessionOwnerResolver == null) {
				return ServerResponse.badRequest().bodyValue(Map.of("error", "auth-profile support is not wired"));
			}
			final Optional<AuthProfile> profile = this.authProfileStore.resolve(ownerId, profileId);
			if (profile.isEmpty()) {
				return ServerResponse.badRequest()
					.bodyValue(new ProxyErrorDto(400, "bad_request",
							"Invalid or missing auth profile or session reference.",
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
			target = buildTargetTransport(transportType, url, command, args, env, headers, authorizationRef,
					authorization, customHeaders);
		}
		catch (final Exception ex) {
			LOG.warn("proxy[{}] failed to build target transport: {}", sessionId, ex.toString());
			return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
		}
		// browserToTarget stays unicast (single consumer in McpProxy);
		// targetToBrowser is multicast-replay so per-request awaiters in the
		// Streamable-HTTP path can attach while keeping this long-lived SSE
		// subscriber working.
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
		final ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser,
				authorizationRef);
		if (profileId != null && !profileId.isBlank()) {
			// One-time bind: rejected reuse / foreign ids close the handoff (D4/D8).
			if (!this.authProfileStore.bind(ownerId, profileId, sessionId)) {
				return ServerResponse.badRequest()
					.bodyValue(new ProxyErrorDto(400, "bad_request",
							"Invalid or missing auth profile or session reference.",
							"Check the profile fields and profileId, then reconnect.", null));
			}
			session.bindProfile(ownerId, profileId);
		}
		this.registry.put(session);

		final String proxyBase = (this.properties != null) ? this.properties.getProxyPath() : "/mcp-inspector-api";
		// The prologue must carry the deployment prefix: the browser resolves it with
		// new URL(data, sseUrl), and a path-absolute value replaces the whole path, so
		// without the prefix every first client->server frame would 404.
		final ServerSentEvent<String> prologue = ServerSentEvent.<String>builder()
			.event("endpoint")
			.data(contextPath + proxyBase + "/message?sessionId=" + sessionId)
			.build();

		// D3 outbox: structured error events (SSE-only redirect/401/403 mapping) are
		// merged into the stream ahead of the session teardown so the browser sees the
		// DTO, not a silent cut.
		final Sinks.Many<ServerSentEvent<String>> errorEvents = Sinks.many().multicast().onBackpressureBuffer(8);

		// takeUntilOther: the stream ends when the session closes, whether or not
		// close() managed to complete the sink (it cannot when another thread owns it).
		final var flux = Flux.merge(errorEvents.asFlux(),
				targetToBrowser.asFlux()
					.takeUntilOther(session.closeSignal())
					.doOnTerminate(() -> errorEvents.tryEmitComplete())
					.map((frame) -> {
						try {
							return ServerSentEvent.<String>builder()
								.event("message")
								.data(this.objectMapper.writeValueAsString(frame))
								.build();
						}
						catch (final Exception ex) {
							return ServerSentEvent.<String>builder().event("error").data(ex.getMessage()).build();
						}
					})
					.startWith(prologue))
			.doOnCancel(() -> this.registry.removeAndClose(sessionId))
			.doOnTerminate(() -> this.registry.removeAndClose(sessionId));

		this.mcpProxy.start(session).subscribe((ignored) -> {
		}, (err) -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			// D3: emit the structured error event BEFORE the session teardown — the
			// close signal terminates the merged flux, so the DTO must land first.
			final ProxyErrorDto dto = ProxyErrorMapper.map(err, TransportKind.SSE);
			if (dto != null) {
				errorEvents.tryEmitNext(ServerSentEvent.<String>builder()
					.event("error")
					.data(toErrorJson(dto, targetUri(transportType, url)))
					.build());
			}
			errorEvents.tryEmitComplete();
			this.registry.removeAndClose(sessionId);
		});

		return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(flux, ServerSentEvent.class);
	}

	/**
	 * Resolves the request's session owner via the signed cookie (D8); re-mints on
	 * absent/forged/expired.
	 * @param request the current request
	 * @return the validated owner id
	 */
	private String resolveOwner(final ServerRequest request) {
		if (this.sessionOwnerResolver == null) {
			return null;
		}
		return this.sessionOwnerResolver.resolve(request.exchange());
	}

	/**
	 * Checks whether the current request's owner matches the session's bound owner.
	 * Sessions without a bound profile (no ownerId) remain accessible to all callers,
	 * matching the pre-auth-profile behaviour.
	 * @param session the session to check
	 * @param request the current request
	 * @return {@code true} when the caller owns the session or the session has no owner
	 */
	private boolean isOwnerOf(final ProxySession session, final ServerRequest request) {
		final String sessionOwner = session.ownerId();
		if (sessionOwner == null) {
			LegacySessionAccessWarner.warnOnce(session);
			return true;
		}
		final String callerOwner = resolveOwner(request);
		return callerOwner != null && callerOwner.equals(sessionOwner);
	}

	/**
	 * Serialises the D3 error DTO for the SSE {@code error} event, attaching the
	 * D5-redacted target URL.
	 * @param dto the structured error DTO
	 * @param target the upstream target URI
	 * @return the serialised DTO JSON
	 */
	private String toErrorJson(final ProxyErrorDto dto, final URI target) {
		try {
			return this.objectMapper.writeValueAsString(dto.withUrl(ProxyErrorDto.redactUrl(target)));
		}
		catch (final Exception ex) {
			LOG.warn("proxy: failed to serialize error DTO: {}", ex.toString());
			return null;
		}
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

	private McpClientTransport buildTargetTransport(final String transportType, final String url, final String command,
			final String args, final String env, final AuthHeaders headers,
			final AtomicReference<String> authorizationRef, final String authorization,
			final Map<String, String> customHeaders) {
		final String type = (transportType != null) ? transportType.toLowerCase() : "sse";
		return switch (type) {
			case "sse" -> {
				final URI target = ProxyTargetResolver.resolve(url, loopbackPort(), "/sse");
				yield (headers != null) ? this.transportFactory.buildSseWithAuth(target, headers, authorizationRef)
						: openSseWithInboundHeaders(target, authorization, customHeaders);
			}
			case "streamable-http" -> {
				final URI target = ProxyTargetResolver.resolve(url, loopbackPort(), "/mcp");
				yield (headers != null)
						? this.transportFactory.openStreamableWithAuth(target, headers, authorizationRef)
						: openStreamableWithInboundHeaders(target, authorization, customHeaders);
			}
			case "stdio" -> {
				if (command == null || command.isBlank()) {
					throw new InvalidProxyRequestException(
							"missing required 'command' query parameter for stdio transport");
				}
				final List<String> argv = new ArrayList<>();
				argv.add(command.trim());
				if (args != null && !args.isBlank()) {
					argv.addAll(Arrays.asList(args.trim().split("\\s+")));
				}
				final Map<String, String> environment = parseEnv(env);
				yield this.transportFactory.openStdio(argv, environment);
			}
			default -> throw new InvalidProxyRequestException("unsupported transportType: " + transportType);
		};
	}

	private McpClientTransport openSseWithInboundHeaders(final URI target, final String authorization,
			final Map<String, String> customHeaders) {
		final boolean noHeaders = authorization == null && (customHeaders == null || customHeaders.isEmpty());
		return noHeaders ? this.transportFactory.buildSse(target)
				: this.transportFactory.buildSse(target, authorization, customHeaders);
	}

	private McpClientTransport openStreamableWithInboundHeaders(final URI target, final String authorization,
			final Map<String, String> customHeaders) {
		final boolean noHeaders = authorization == null && (customHeaders == null || customHeaders.isEmpty());
		return noHeaders ? this.transportFactory.openStreamable(target)
				: this.transportFactory.openStreamable(target, authorization, customHeaders);
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
			return Map.of();
		}
	}

	// ---------------------------------------------------------------------
	// Streamable-HTTP proxy
	// ---------------------------------------------------------------------

	public Mono<ServerResponse> postMcp(final ServerRequest request) {
		final String mcpSessionId = request.headers().firstHeader(ProxyConstants.MCP_SESSION_ID_HEADER);
		final String authorization = inboundAuthorization(request);
		final String profileId = request.queryParam("profileId").orElse(null);
		return withCustomHeaders(request,
				(parsed) -> readJsonBody(request).flatMap((body) -> handlePostMcp(mcpSessionId,
						request.queryParam("url").orElse(null), body, authorization, parsed, profileId, request)));
	}

	/**
	 * Spec-compliant POST /mcp dispatcher.
	 *
	 * <p>
	 * If {@code mcpSessionId} is missing — opens a new session, then routes the frame
	 * through {@link #relayAndAwait}. Otherwise looks up the existing session.
	 * @param mcpSessionId the {@code mcp-session-id} header value, may be {@code null}
	 * @param url the upstream URL query parameter, may be {@code null}
	 * @param body the JSON-RPC frame received from the browser
	 * @param authorization the inbound {@code Authorization} header to forward upstream,
	 * may be {@code null}
	 * @param customHeaders extra headers (named by {@code x-custom-auth-headers}) to
	 * forward upstream, may be empty
	 * @param profileId the owner-scoped auth profile id, or {@code null}
	 * @param request the current request (owner resolution)
	 * @return a {@link Mono} emitting the upstream response
	 */
	private Mono<ServerResponse> handlePostMcp(final String mcpSessionId, final String url, final JsonNode body,
			final String authorization, final Map<String, String> customHeaders, final String profileId,
			final ServerRequest request) {
		if (mcpSessionId == null || mcpSessionId.isBlank()) {
			return openSessionAndRelay(url, body, authorization, customHeaders, profileId, request);
		}
		final ProxySession session = this.registry.get(mcpSessionId);
		if (session == null || !isOwnerOf(session, request)) {
			return ServerResponse.status(HttpStatus.NOT_FOUND)
				.bodyValue(Map.of("error", "unknown mcp-session-id: " + mcpSessionId));
		}
		return relayAndAwait(session, body, false);
	}

	/**
	 * Builds a new {@link ProxySession} and dispatches the first frame.
	 * @param url the upstream streamable-HTTP URL
	 * @param body the first JSON-RPC frame to relay
	 * @param authorization the inbound {@code Authorization} header to forward upstream,
	 * may be {@code null}
	 * @param customHeaders extra headers (named by {@code x-custom-auth-headers}) to
	 * forward upstream, may be empty
	 * @param profileId the owner-scoped auth profile id, or {@code null}
	 * @param request the current request (owner resolution)
	 * @return a {@link Mono} emitting the upstream response
	 */
	private Mono<ServerResponse> openSessionAndRelay(final String url, final JsonNode body, final String authorization,
			final Map<String, String> customHeaders, final String profileId, final ServerRequest request) {
		// Blank/relative url is the WAF-safe same-origin default — resolved to the
		// loopback MCP endpoint server-side (ProxyTargetResolver); only an explicit
		// absolute url targets a non-loopback server.
		final String sessionId = UUID.randomUUID().toString();
		// D8: resolve the owner from the signed session cookie and the bound profile
		// when a profileId is supplied. A foreign/unknown profileId is a structured 400.
		final String ownerId = resolveOwner(request);
		final AuthHeaders headers;
		if (profileId != null && !profileId.isBlank()) {
			if (this.authProfileStore == null || this.sessionOwnerResolver == null) {
				return ServerResponse.badRequest().bodyValue(Map.of("error", "auth-profile support is not wired"));
			}
			final Optional<AuthProfile> profile = this.authProfileStore.resolve(ownerId, profileId);
			if (profile.isEmpty()) {
				return ServerResponse.badRequest()
					.bodyValue(new ProxyErrorDto(400, "bad_request",
							"Invalid or missing auth profile or session reference.",
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
		final URI resolved;
		try {
			resolved = ProxyTargetResolver.resolve(url, loopbackPort(), "/mcp");
			target = (headers != null)
					? this.transportFactory.openStreamableWithAuth(resolved, headers, authorizationRef)
					: openStreamableWithInboundHeaders(resolved, authorization, customHeaders);
		}
		catch (final Exception ex) {
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(ex);
			LOG.warn("proxy[{}] upstream connect failed ({}): {}", sessionId, failure.reason().wire(), ex.toString());
			final HttpStatus status = (failure.reason() == ProxyConnectFailure.Reason.TIMEOUT)
					? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
			return ServerResponse.status(status)
				.bodyValue(Map.of("error", Map.of("code", "MCP_CONNECT_FAILED", "reason", failure.reason().wire(),
						"message", failure.message(), "retryable", failure.retryable())));
		}
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
		final ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser,
				authorizationRef);
		// D5: remember the resolved upstream target so streamable error DTOs can attach
		// the redacted url (scheme://host[:port]/path, query/fragment stripped).
		session.targetUri(resolved);
		if (profileId != null && !profileId.isBlank()) {
			// One-time bind: rejected reuse / foreign ids fail the handoff (D4/D8).
			if (!this.authProfileStore.bind(ownerId, profileId, sessionId)) {
				return ServerResponse.badRequest()
					.bodyValue(new ProxyErrorDto(400, "bad_request",
							"Invalid or missing auth profile or session reference.",
							"Check the profile fields and profileId, then reconnect.", null));
			}
			session.bindProfile(ownerId, profileId);
		}
		this.registry.put(session);
		this.mcpProxy.start(session).subscribe((ignored) -> {
		}, (err) -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			this.registry.removeAndClose(sessionId);
		});
		return relayAndAwait(session, body, true);
	}

	/**
	 * Relays {@code body} to the upstream. If the body is a JSON-RPC request (has an
	 * {@code id}), waits up to 30s for the matching response and returns it as
	 * {@code application/json}. Notification/response frames produce a 202 with empty
	 * body.
	 * @param session the proxy session to relay through
	 * @param body the JSON-RPC frame to relay
	 * @param includeSessionHeader whether to echo the {@code mcp-session-id} header
	 * @return a {@link Mono} emitting the relay result
	 */
	private Mono<ServerResponse> relayAndAwait(final ProxySession session, final JsonNode body,
			final boolean includeSessionHeader) {
		final JsonNode idNode = extractRequestId(body);
		if (idNode == null) {
			final Sinks.EmitResult emitResult = session.browserToTarget().tryEmitNext(body);
			if (emitResult.isFailure()) {
				return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.bodyValue(Map.of("error", "emit failed: " + emitResult.name()));
			}
			session.touch();
			final ServerResponse.BodyBuilder accepted = ServerResponse.accepted();
			if (includeSessionHeader) {
				accepted.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
			}
			return accepted.build();
		}
		// Intercept tasks/get, tasks/list and tasks/cancel only for sessions whose
		// capabilities.tasks was injected by this proxy (see injectTasksCapability):
		// a third-party upstream that advertises its own tasks capability keeps the
		// plain relay, so its tasks are never answered from the local TaskRegistry.
		if (this.taskService != null && session.isTasksInterceptionEnabled()) {
			final String sessionIdHeader = includeSessionHeader ? session.sessionId() : null;
			final Mono<ServerResponse> taskResponse = tryHandleTaskMethod(idNode, body, sessionIdHeader);
			if (taskResponse != null) {
				// Still a routed frame from the browser: refresh the idle-reaper
				// timestamp, otherwise a session only polled via the Tasks tab is
				// evicted.
				session.touch();
				return taskResponse;
			}
		}
		// Eagerly subscribe to targetToBrowser via Sinks.One so the upstream
		// transport's connect() error (ECONNREFUSED/DNS/timeout) is captured
		// even when the relay Mono is subscribed to later by the WebFlux
		// framework. Without this the replay sink may not carry the error to
		// a late subscriber, and the awaiter would block for the full
		// streamable-request timeout instead of failing fast.
		final Duration requestTimeout = this.timeouts.getStreamableRequest();
		final Sinks.One<JsonNode> awaiterSink = Sinks.one();
		session.targetToBrowser()
			.asFlux()
			.filter((frame) -> matchesId(frame, idNode))
			.next()
			.timeout(requestTimeout)
			.subscribe(awaiterSink::tryEmitValue, awaiterSink::tryEmitError, () -> awaiterSink.tryEmitEmpty());
		final Mono<JsonNode> awaiter = awaiterSink.asMono();
		final Sinks.EmitResult emitResult = session.browserToTarget().tryEmitNext(body);
		if (emitResult.isFailure()) {
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.bodyValue(Map.of("error", "emit failed: " + emitResult.name()));
		}
		session.touch();
		return awaiter.flatMap((node) -> {
			if (ProxyHandler.this.taskService != null) {
				injectTasksCapability(session, node, body, includeSessionHeader);
			}
			final ServerResponse.BodyBuilder ok = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON);
			if (includeSessionHeader) {
				ok.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
			}
			return ok.bodyValue(node);
		}).onErrorResume((ex) -> {
			// D3: a mappable transport failure (401/403 on the initial handshake or a
			// later call) becomes the structured DTO; anything else stays the legacy 504.
			final ProxyErrorDto dto = ProxyErrorMapper.map(ex, TransportKind.STREAMABLE);
			if (dto != null) {
				// D3: a failed first streamable POST (the initialize) never returned a
				// session id to the client, so the registered/bound session is orphaned —
				// tear it down instead of leaking it. The D5-redacted upstream url is
				// attached so the browser sees scheme/host/path without any secret.
				// Also clean up when the session has a bound auth profile (D3/D5: no
				// partial connect state, no orphaned registry entry, no retained profile
				// binding).
				if (includeSessionHeader || session.profileId() != null) {
					this.registry.removeAndClose(session.sessionId());
				}
				return ServerResponse.status(dto.status())
					.bodyValue(dto.withUrl(ProxyErrorDto.redactUrl(session.targetUri())));
			}
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(ex);
			LOG.warn("proxy[{}] await response failed ({}): {}", session.sessionId(), failure.reason().wire(),
					ex.toString());
			// A failed first POST (the initialize) never returned a session id to the
			// client, so the session is orphaned - tear it down instead of leaking it.
			if (includeSessionHeader) {
				this.registry.removeAndClose(session.sessionId());
			}
			final HttpStatus status = (failure.reason() == ProxyConnectFailure.Reason.TIMEOUT)
					? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
			return ServerResponse.status(status)
				.bodyValue(Map.of("error", Map.of("code", "MCP_CONNECT_FAILED", "reason", failure.reason().wire(),
						"message", failure.message(), "retryable", failure.retryable())));
		});
	}

	/**
	 * Returns the {@code id} node iff {@code body} is a JSON-RPC <em>request</em> — i.e.
	 * it carries both a {@code method} and an {@code id}.
	 *
	 * <p>
	 * A server→client JSON-RPC <em>response</em> (has {@code result}/{@code error} and an
	 * {@code id} but <strong>no</strong> {@code method}) must take the fire-and-forget
	 * 202-Accepted path, not relay-and-await; otherwise the proxy would block awaiting an
	 * "answer" to a frame that is itself the answer. Returns {@code null} for responses
	 * and notifications.
	 * @param body the JSON node to inspect
	 * @return the {@code id} field when {@code body} is a request, else {@code null}
	 */
	private static JsonNode extractRequestId(final JsonNode body) {
		if (body == null || !body.isObject()) {
			return null;
		}
		final JsonNode method = body.get("method");
		if (method == null || !method.isTextual()) {
			return null;
		}
		final JsonNode id = body.get("id");
		if (id == null || id.isNull()) {
			return null;
		}
		return id;
	}

	// ---------------------------------------------------------------------
	// Task method interception (tasks/get, tasks/list, tasks/cancel)
	// ---------------------------------------------------------------------

	/**
	 * Checks whether {@code body} is a {@code tasks/get}, {@code tasks/list}, or
	 * {@code tasks/cancel} JSON-RPC request and handles it locally via the
	 * {@link #taskService}.
	 * @param idNode the JSON-RPC request id
	 * @param body the full JSON-RPC request body
	 * @param sessionIdHeader session id to attach as the mcp-session-id response header
	 * on the first POST of a session, or {@code null} to omit the header
	 * @return a response Mono if the method was handled, or {@code null} to fall through
	 * to the regular relay path
	 */
	private Mono<ServerResponse> tryHandleTaskMethod(final JsonNode idNode, final JsonNode body,
			final String sessionIdHeader) {
		final JsonNode methodNode = body.get("method");
		if (methodNode == null || !methodNode.isTextual()) {
			return null;
		}
		final String method = methodNode.asText();
		if ("tasks/get".equals(method)) {
			return handleTaskGet(idNode, body, sessionIdHeader);
		}
		if ("tasks/list".equals(method)) {
			return handleTaskList(idNode, body, sessionIdHeader);
		}
		if ("tasks/cancel".equals(method)) {
			return handleTaskCancel(idNode, body, sessionIdHeader);
		}
		return null;
	}

	/**
	 * Handle a {@code tasks/get} request: look up the task and return its handle.
	 * @param idNode the JSON-RPC request id
	 * @param body the full request body
	 * @param sessionIdHeader session id to attach as the mcp-session-id response header,
	 * or {@code null} to omit it
	 * @return a JSON-RPC response with the task handle, or a JSON-RPC error
	 */
	private Mono<ServerResponse> handleTaskGet(final JsonNode idNode, final JsonNode body,
			final String sessionIdHeader) {
		final String taskId = extractTaskId(body);
		if (taskId == null) {
			return jsonRpcError(idNode, -32602, "Invalid params: missing or invalid taskId", sessionIdHeader);
		}
		// TaskService is a blocking core interface: hop off the calling (possibly
		// event-loop) thread while it runs, and map the outcome onto the JSON-RPC
		// envelope only once it completes.
		return Mono.fromCallable(() -> this.taskService.getTask(taskId))
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap((handle) -> jsonRpcResult(idNode, taskHandleToNode(handle), sessionIdHeader))
			.onErrorResume(TaskNotFoundException.class, (ex) -> jsonRpcError(idNode, -32602,
					"Failed to retrieve task: " + ex.getMessage(), sessionIdHeader));
	}

	/**
	 * Handle a {@code tasks/list} request: return the requested page of tracked tasks,
	 * honoring {@code params.cursor} and emitting {@code nextCursor} while more tasks
	 * remain (see {@link #buildTaskListResult}).
	 * @param idNode the JSON-RPC request id
	 * @param body the full JSON-RPC request body (carries the optional cursor)
	 * @param sessionIdHeader session id to attach as the mcp-session-id response header,
	 * or {@code null} to omit it
	 * @return a JSON-RPC response with a {@code tasks} array
	 */
	private Mono<ServerResponse> handleTaskList(final JsonNode idNode, final JsonNode body,
			final String sessionIdHeader) {
		// TaskService is a blocking core interface: hop off the calling (possibly
		// event-loop) thread while it runs.
		return Mono.fromCallable(this.taskService::listTasks)
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap((handles) -> buildTaskListResult(handles, body, idNode, sessionIdHeader));
	}

	/**
	 * Assemble the {@code tasks/list} result page from the full handle list: honor
	 * {@code params.cursor} (an opaque offset into the ordered task list) and emit
	 * {@code nextCursor} while more tasks remain past the page.
	 * @param handles all currently tracked task handles, in stable order
	 * @param body the full JSON-RPC request body (carries the optional cursor)
	 * @param idNode the JSON-RPC request id
	 * @param sessionIdHeader session id to attach as the mcp-session-id response header,
	 * or {@code null} to omit it
	 * @return a JSON-RPC response with a {@code tasks} page and optional
	 * {@code nextCursor}
	 */
	private static Mono<ServerResponse> buildTaskListResult(final java.util.List<TaskHandle> handles,
			final JsonNode body, final JsonNode idNode, final String sessionIdHeader) {
		final int offset = parseTaskListCursor(handles.size(), body);
		final int end = Math.min(offset + TASKS_LIST_PAGE_SIZE, handles.size());
		final java.util.List<TaskHandle> page = handles.subList(offset, end);
		final ArrayNode tasksArray = JsonNodeFactory.instance.arrayNode(page.size());
		for (final TaskHandle handle : page) {
			tasksArray.add(taskHandleToNode(handle));
		}
		final ObjectNode result = JsonNodeFactory.instance.objectNode();
		result.set("tasks", tasksArray);
		if (end < handles.size()) {
			result.put("nextCursor", String.valueOf(end));
		}
		return jsonRpcResult(idNode, result, sessionIdHeader);
	}

	/**
	 * Parse the opaque {@code params.cursor} (a decimal list offset previously emitted as
	 * {@code nextCursor}) into an offset into the ordered task list. An absent,
	 * non-numeric or out-of-range cursor yields an offset past the end, which produces an
	 * empty page and terminates client pagination.
	 * @param listSize the size of the full ordered handle list
	 * @param body the JSON-RPC request body carrying the optional cursor
	 * @return the offset the requested page starts at
	 */
	private static int parseTaskListCursor(final int listSize, final JsonNode body) {
		final JsonNode params = (body != null) ? body.get("params") : null;
		final JsonNode cursorNode = (params != null && params.isObject()) ? params.get("cursor") : null;
		if (cursorNode != null && cursorNode.isTextual()) {
			try {
				return Math.max(0, Math.min(Integer.parseInt(cursorNode.asText()), listSize));
			}
			catch (final NumberFormatException ex) {
				return listSize;
			}
		}
		return 0;
	}

	/**
	 * Handle a {@code tasks/cancel} request: cancel the task and return the updated
	 * handle.
	 * @param idNode the JSON-RPC request id
	 * @param body the full request body
	 * @param sessionIdHeader session id to attach as the mcp-session-id response header,
	 * or {@code null} to omit it
	 * @return a JSON-RPC response with the cancelled task handle, or a JSON-RPC error
	 */
	private Mono<ServerResponse> handleTaskCancel(final JsonNode idNode, final JsonNode body,
			final String sessionIdHeader) {
		final String taskId = extractTaskId(body);
		if (taskId == null) {
			return jsonRpcError(idNode, -32602, "Invalid params: missing or invalid taskId", sessionIdHeader);
		}
		// TaskService is a blocking core interface: hop off the calling (possibly
		// event-loop) thread while it runs.
		return Mono.fromCallable(() -> this.taskService.cancelTask(taskId))
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap((handle) -> jsonRpcResult(idNode, taskHandleToNode(handle), sessionIdHeader))
			.onErrorResume(TaskNotCancelableException.class,
					(ex) -> jsonRpcError(idNode, -32602, ex.getMessage(), sessionIdHeader))
			.onErrorResume(TaskNotFoundException.class, (ex) -> jsonRpcError(idNode, -32602,
					"Failed to retrieve task: " + ex.getMessage(), sessionIdHeader));
	}

	/**
	 * Extract {@code taskId} from the {@code params} object of a task method call.
	 * @param body the JSON-RPC request body
	 * @return the task id string, or {@code null} if missing or invalid
	 */
	private static String extractTaskId(final JsonNode body) {
		final JsonNode params = body.get("params");
		if (params == null || !params.isObject()) {
			return null;
		}
		final JsonNode taskIdNode = params.get("taskId");
		if (taskIdNode == null || !taskIdNode.isTextual()) {
			return null;
		}
		return taskIdNode.asText();
	}

	/**
	 * Build a successful JSON-RPC response with a result object.
	 * @param idNode the request id
	 * @param resultNode the result object to include
	 * @param sessionIdHeader session id to attach as the mcp-session-id response header,
	 * or {@code null} to omit it
	 * @return a 200 OK Mono with the JSON-RPC envelope
	 */
	private static Mono<ServerResponse> jsonRpcResult(final JsonNode idNode, final JsonNode resultNode,
			final String sessionIdHeader) {
		final ObjectNode response = JsonNodeFactory.instance.objectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", idNode);
		response.set("result", resultNode);
		final ServerResponse.BodyBuilder ok = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON);
		if (sessionIdHeader != null) {
			ok.header(ProxyConstants.MCP_SESSION_ID_HEADER, sessionIdHeader);
		}
		return ok.bodyValue(response);
	}

	/**
	 * Build a JSON-RPC error response with the given error code and message.
	 * @param idNode the request id
	 * @param code the JSON-RPC error code
	 * @param message the error message
	 * @param sessionIdHeader session id to attach as the mcp-session-id response header,
	 * or {@code null} to omit it
	 * @return a 200 OK Mono with the JSON-RPC error envelope (per spec, errors are
	 * returned with HTTP 200)
	 */
	private static Mono<ServerResponse> jsonRpcError(final JsonNode idNode, final int code, final String message,
			final String sessionIdHeader) {
		final ObjectNode response = JsonNodeFactory.instance.objectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", idNode);
		final ObjectNode error = JsonNodeFactory.instance.objectNode();
		error.put("code", code);
		error.put("message", message);
		response.set("error", error);
		final ServerResponse.BodyBuilder ok = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON);
		if (sessionIdHeader != null) {
			ok.header(ProxyConstants.MCP_SESSION_ID_HEADER, sessionIdHeader);
		}
		return ok.bodyValue(response);
	}

	/**
	 * Convert a {@link TaskHandle} to a JSON object node matching the SEP-1686 Task
	 * schema.
	 * @param handle the task handle to serialize
	 * @return a JSON object node with all task fields
	 */
	private static ObjectNode taskHandleToNode(final TaskHandle handle) {
		final ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("taskId", handle.taskId());
		node.put("status", handle.status());
		if (handle.statusMessage() != null) {
			node.put("statusMessage", handle.statusMessage());
		}
		node.put("createdAt", handle.createdAt());
		node.put("lastUpdatedAt", handle.lastUpdatedAt());
		node.put("ttl", handle.ttl());
		node.put("pollInterval", handle.pollInterval());
		return node;
	}

	/**
	 * Returns {@code true} iff {@code frame.id} equals {@code expected}.
	 * @param frame the JSON-RPC frame to test
	 * @param expected the expected id value
	 * @return {@code true} if the frame id matches
	 */
	private static boolean matchesId(final JsonNode frame, final JsonNode expected) {
		if (frame == null || !frame.isObject()) {
			return false;
		}
		final JsonNode id = frame.get("id");
		return id != null && !id.isNull() && id.equals(expected);
	}

	public Mono<ServerResponse> getMcp(final ServerRequest request) {
		final String mcpSessionId = request.headers().firstHeader(ProxyConstants.MCP_SESSION_ID_HEADER);
		final ProxySession session = (mcpSessionId != null) ? this.registry.get(mcpSessionId) : null;
		if (session == null || !isOwnerOf(session, request)) {
			return ServerResponse.status(HttpStatus.NOT_FOUND)
				.bodyValue(Map.of("error", "unknown mcp-session-id: " + mcpSessionId));
		}
		// takeUntilOther: see openProxiedSession — the sink's own completion can be lost.
		final var flux = session.targetToBrowser().asFlux().takeUntilOther(session.closeSignal()).map((frame) -> {
			try {
				return ServerSentEvent.<String>builder()
					.event("message")
					.data(this.objectMapper.writeValueAsString(frame))
					.build();
			}
			catch (final Exception ex) {
				return ServerSentEvent.<String>builder().event("error").data(ex.getMessage()).build();
			}
		});
		return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(flux, ServerSentEvent.class);
	}

	public Mono<ServerResponse> deleteMcp(final ServerRequest request) {
		final String mcpSessionId = request.headers().firstHeader(ProxyConstants.MCP_SESSION_ID_HEADER);
		if (mcpSessionId != null) {
			final ProxySession session = this.registry.get(mcpSessionId);
			if (session == null || !isOwnerOf(session, request)) {
				return ServerResponse.notFound().build();
			}
		}
		final boolean removed = this.registry.removeAndClose(mcpSessionId);
		return (removed) ? ServerResponse.ok().build() : ServerResponse.notFound().build();
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	private static String mapTransport(final TransportType type) {
		if (type == null) {
			return "";
		}
		return switch (type) {
			case SSE -> "sse";
			case STREAMABLE, STATELESS -> "streamable-http";
			case STDIO_NO_HTTP -> "stdio";
			case UNKNOWN -> "";
		};
	}

	private String buildServerUrl(final DetectedTransport detected) {
		final int port = this.listeningPort.get();
		if (port <= 0 || detected == null || detected.type() == TransportType.UNKNOWN
				|| detected.type() == TransportType.STDIO_NO_HTTP) {
			return "";
		}
		String path = detected.endpoint();
		if (path == null || path.isBlank()) {
			path = "/mcp";
		}
		// Relative same-origin path (e.g. "/mcp"), not an absolute
		// http://localhost:<port>
		// — keeps the proxy ?url= WAF-safe; the proxy resolves it to loopback
		// server-side.
		return path;
	}

	// ---------------------------------------------------------------------
	// Initialize response capability injection
	// ---------------------------------------------------------------------

	/**
	 * Injects {@code capabilities.tasks} into an initialize response when absent.
	 *
	 * <p>
	 * The UI Tasks tab is gated by a top-level {@code serverCapabilities.tasks} field in
	 * the initialize response. The demo server's MCP Java SDK does not know about
	 * {@code tasks}, so the proxy adds the field at the JSON level: only when the
	 * response is a successful initialize reply ({@code includeSessionHeader} signals the
	 * first POST of a session, and {@code body.method} equals {@code initialize}) and the
	 * upstream did not already advertise {@code tasks}. Malformed or error responses pass
	 * through untouched. The injected capability advertises only what the proxy serves
	 * itself: {@code list} and {@code cancel}; there is no
	 * {@code tasks.requests.tools.call} because no task-augmented call path and no
	 * {@code tasks/result} serving path exist yet.
	 *
	 * <p>
	 * A successful injection also marks the session for task-method interception via
	 * {@link ProxySession#enableTasksInterception()}: only sessions whose tasks
	 * capability came from this proxy get their {@code tasks/get}, {@code tasks/list} and
	 * {@code tasks/cancel} answered locally by the proxy instead of relayed.
	 * @param session the proxy session being initialized
	 * @param response the upstream initialize response JSON tree
	 * @param body the original JSON-RPC request body
	 * @param includeSessionHeader whether this is the first POST of a session
	 */
	private void injectTasksCapability(final ProxySession session, final JsonNode response, final JsonNode body,
			final boolean includeSessionHeader) {
		if (!includeSessionHeader || body == null) {
			return;
		}
		final JsonNode methodNode = body.get("method");
		if (methodNode == null || !"initialize".equals(methodNode.asText())) {
			return;
		}
		if (response == null || !response.isObject()) {
			return;
		}
		final JsonNode resultNode = response.get("result");
		if (resultNode == null || !resultNode.isObject()) {
			return;
		}
		final JsonNode capabilitiesNode = resultNode.get("capabilities");
		if (capabilitiesNode == null || !capabilitiesNode.isObject()) {
			return;
		}
		final ObjectNode capabilities = (ObjectNode) capabilitiesNode;
		if (capabilities.has("tasks")) {
			return;
		}
		final ObjectNode tasks = JsonNodeFactory.instance.objectNode();
		tasks.set("list", JsonNodeFactory.instance.objectNode());
		tasks.set("cancel", JsonNodeFactory.instance.objectNode());
		capabilities.set("tasks", tasks);
		session.enableTasksInterception();
	}

}
