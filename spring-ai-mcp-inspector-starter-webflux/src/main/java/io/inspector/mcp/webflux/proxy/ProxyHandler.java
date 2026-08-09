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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTargetResolver;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;

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

	public ProxyHandler(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final TransportDetector transportDetector, final JsonMapper objectMapper) {
		this(registry, transportFactory, mcpProxy, transportDetector, objectMapper, null);
	}

	public ProxyHandler(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final TransportDetector transportDetector, final JsonMapper objectMapper,
			final McpInspectorProperties properties) {
		this.registry = registry;
		this.transportFactory = transportFactory;
		this.mcpProxy = mcpProxy;
		this.transportDetector = transportDetector;
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.properties = properties;
		this.timeouts = (properties != null) ? properties.getTimeouts() : new McpInspectorProperties.Timeouts();
		this.outboundHttpClient = HttpClient.newBuilder().connectTimeout(this.timeouts.getFetchConnect()).build();
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
		return readJsonBody(request).flatMap((body) -> Mono.fromCallable(() -> doFetch(body))
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
		return openProxiedSession(transportType, url, command, args, env, inboundAuthorization(request),
				inboundCustomHeaders(request), contextPath(request));
	}

	public Mono<ServerResponse> openStdio(final ServerRequest request) {
		final String command = request.queryParam("command").orElse(null);
		final String args = request.queryParam("args").orElse(null);
		final String env = request.queryParam("env").orElse(null);
		return openProxiedSession("stdio", null, command, args, env, inboundAuthorization(request),
				inboundCustomHeaders(request), contextPath(request));
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
	 * (comma-separated header names) and returns their inbound values, so they can be
	 * forwarded verbatim to the upstream MCP server.
	 * @param request the incoming request
	 * @return a map of custom header name → value (never {@code null})
	 */
	private static Map<String, String> inboundCustomHeaders(final ServerRequest request) {
		final String named = request.headers().firstHeader("x-custom-auth-headers");
		if (named == null || named.isBlank()) {
			return Map.of();
		}
		final Map<String, String> out = new LinkedHashMap<>();
		for (final String raw : named.split(",")) {
			final String name = raw.trim();
			if (name.isEmpty()) {
				continue;
			}
			final String value = request.headers().firstHeader(name);
			if (value != null) {
				out.put(name, value);
			}
		}
		return out;
	}

	public Mono<ServerResponse> postMessage(final ServerRequest request) {
		final String sessionId = request.queryParam("sessionId").orElse(null);
		if (sessionId == null) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "missing sessionId"));
		}
		final ProxySession session = this.registry.get(sessionId);
		if (session == null) {
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
			final String args, final String env, final String authorization, final Map<String, String> customHeaders,
			final String contextPath) {
		final String sessionId = UUID.randomUUID().toString();
		final McpClientTransport target;
		try {
			target = buildTargetTransport(transportType, url, command, args, env, authorization, customHeaders);
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
		final ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser);
		this.registry.put(session);

		final String proxyBase = (this.properties != null) ? this.properties.getProxyPath() : "/mcp-inspector-api";
		// The prologue must carry the deployment prefix: the browser resolves it with
		// new URL(data, sseUrl), and a path-absolute value replaces the whole path, so
		// without the prefix every first client->server frame would 404.
		final ServerSentEvent<String> prologue = ServerSentEvent.<String>builder()
			.event("endpoint")
			.data(contextPath + proxyBase + "/message?sessionId=" + sessionId)
			.build();

		final var flux = targetToBrowser.asFlux().map((frame) -> {
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
			.startWith(prologue)
			.doOnCancel(() -> this.registry.removeAndClose(sessionId))
			.doOnTerminate(() -> this.registry.removeAndClose(sessionId));

		this.mcpProxy.start(session).subscribe((ignored) -> {
		}, (err) -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			this.registry.removeAndClose(sessionId);
		});

		return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(flux, ServerSentEvent.class);
	}

	private McpClientTransport buildTargetTransport(final String transportType, final String url, final String command,
			final String args, final String env, final String authorization, final Map<String, String> customHeaders) {
		final String type = (transportType != null) ? transportType.toLowerCase() : "sse";
		final boolean noHeaders = authorization == null && (customHeaders == null || customHeaders.isEmpty());
		return switch (type) {
			case "sse" -> {
				final URI target = ProxyTargetResolver.resolve(url, loopbackPort(), "/sse");
				yield noHeaders ? this.transportFactory.openSse(target)
						: this.transportFactory.openSse(target, authorization, customHeaders);
			}
			case "streamable-http" -> {
				final URI target = ProxyTargetResolver.resolve(url, loopbackPort(), "/mcp");
				yield noHeaders ? this.transportFactory.openStreamable(target)
						: this.transportFactory.openStreamable(target, authorization, customHeaders);
			}
			case "stdio" -> {
				if (command == null || command.isBlank()) {
					throw new IllegalArgumentException(
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
			default -> throw new IllegalArgumentException("unsupported transportType: " + transportType);
		};
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
		final Map<String, String> customHeaders = inboundCustomHeaders(request);
		return readJsonBody(request).flatMap((body) -> handlePostMcp(mcpSessionId,
				request.queryParam("url").orElse(null), body, authorization, customHeaders));
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
	 * @return a {@link Mono} emitting the upstream response
	 */
	private Mono<ServerResponse> handlePostMcp(final String mcpSessionId, final String url, final JsonNode body,
			final String authorization, final Map<String, String> customHeaders) {
		if (mcpSessionId == null || mcpSessionId.isBlank()) {
			return openSessionAndRelay(url, body, authorization, customHeaders);
		}
		final ProxySession session = this.registry.get(mcpSessionId);
		if (session == null) {
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
	 * @return a {@link Mono} emitting the upstream response
	 */
	private Mono<ServerResponse> openSessionAndRelay(final String url, final JsonNode body, final String authorization,
			final Map<String, String> customHeaders) {
		// Blank/relative url is the WAF-safe same-origin default — resolved to the
		// loopback MCP endpoint server-side (ProxyTargetResolver); only an explicit
		// absolute url targets a non-loopback server.
		final String sessionId = UUID.randomUUID().toString();
		final boolean noHeaders = authorization == null && (customHeaders == null || customHeaders.isEmpty());
		final McpClientTransport target;
		try {
			final URI resolved = ProxyTargetResolver.resolve(url, loopbackPort(), "/mcp");
			target = noHeaders ? this.transportFactory.openStreamable(resolved)
					: this.transportFactory.openStreamable(resolved, authorization, customHeaders);
		}
		catch (final Exception ex) {
			return ServerResponse.status(HttpStatus.BAD_GATEWAY)
				.bodyValue(Map.of("error", "upstream connect failed: " + ex.getMessage()));
		}
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
		final ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser);
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
		// Pre-create the awaiter Mono first so the replay subscription registers
		// (or has a buffer ready) before we push the request frame.
		final Duration requestTimeout = this.timeouts.getStreamableRequest();
		final Mono<JsonNode> awaiter = session.targetToBrowser()
			.asFlux()
			.filter((frame) -> matchesId(frame, idNode))
			.next()
			.timeout(requestTimeout);
		final Sinks.EmitResult emitResult = session.browserToTarget().tryEmitNext(body);
		if (emitResult.isFailure()) {
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.bodyValue(Map.of("error", "emit failed: " + emitResult.name()));
		}
		session.touch();
		return awaiter.flatMap((node) -> {
			final ServerResponse.BodyBuilder ok = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON);
			if (includeSessionHeader) {
				ok.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
			}
			return ok.bodyValue(node);
		}).onErrorResume((ex) -> {
			LOG.warn("proxy[{}] await response failed: {}", session.sessionId(), ex.toString());
			// A failed first POST (the initialize) never returned a session id to the
			// client, so the session is orphaned — tear it down instead of leaking it.
			if (includeSessionHeader) {
				this.registry.removeAndClose(session.sessionId());
			}
			return ServerResponse.status(HttpStatus.GATEWAY_TIMEOUT)
				.bodyValue(Map.of("error", "upstream did not respond within " + requestTimeout.toSeconds() + "s"));
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
		if (session == null) {
			return ServerResponse.status(HttpStatus.NOT_FOUND)
				.bodyValue(Map.of("error", "unknown mcp-session-id: " + mcpSessionId));
		}
		final var flux = session.targetToBrowser().asFlux().map((frame) -> {
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

}
