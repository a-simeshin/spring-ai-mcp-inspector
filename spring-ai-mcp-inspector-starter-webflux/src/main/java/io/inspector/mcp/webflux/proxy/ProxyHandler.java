/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

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
 */
public class ProxyHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ProxyHandler.class);

	/** Per-request wall-clock budget for awaiting an upstream JSON-RPC response. */
	private static final Duration STREAMABLE_REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private final ProxySessionRegistry registry;

	private final ProxyTransportFactory transportFactory;

	private final McpProxy mcpProxy;

	private final TransportDetector transportDetector;

	private final ObjectMapper objectMapper;

	private final McpInspectorProperties properties;

	private final HttpClient outboundHttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private final AtomicInteger listeningPort = new AtomicInteger(-1);

	public ProxyHandler(ProxySessionRegistry registry, ProxyTransportFactory transportFactory, McpProxy mcpProxy,
			TransportDetector transportDetector, ObjectMapper objectMapper) {
		this(registry, transportFactory, mcpProxy, transportDetector, objectMapper, null);
	}

	public ProxyHandler(ProxySessionRegistry registry, ProxyTransportFactory transportFactory, McpProxy mcpProxy,
			TransportDetector transportDetector, ObjectMapper objectMapper, McpInspectorProperties properties) {
		this.registry = registry;
		this.transportFactory = transportFactory;
		this.mcpProxy = mcpProxy;
		this.transportDetector = transportDetector;
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
		this.properties = properties;
	}

	@EventListener
	public void onWebServerStarted(WebServerInitializedEvent event) {
		listeningPort.set(event.getWebServer().getPort());
	}

	// ---------------------------------------------------------------------
	// health / config / fetch
	// ---------------------------------------------------------------------

	public Mono<ServerResponse> health(ServerRequest request) {
		return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("status", "ok"));
	}

	public Mono<ServerResponse> config(ServerRequest request) {
		DetectedTransport detected = transportDetector.detect();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("defaultEnvironment", Map.of());
		body.put("defaultCommand", "");
		body.put("defaultArgs", "");
		body.put("defaultTransport", mapTransport(detected.type()));
		body.put("defaultServerUrl", buildServerUrl(detected));
		return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
	}

	public Mono<ServerResponse> fetch(ServerRequest request) {
		return request.bodyToMono(JsonNode.class)
			.flatMap(body -> Mono.fromCallable(() -> doFetch(body))
				.flatMap(envelope -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(envelope))
				.onErrorResume(ex -> ServerResponse.status(502)
					.bodyValue(Map.of("error",
							ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()))));
	}

	private Map<String, Object> doFetch(JsonNode body) throws Exception {
		JsonNode urlNode = body.get("url");
		if (urlNode == null || !urlNode.isTextual()) {
			throw new IllegalArgumentException("missing or invalid url");
		}
		URI uri = URI.create(urlNode.asText());
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException("only http/https URLs are allowed");
		}
		JsonNode init = body.get("init");
		String method = (init != null && init.hasNonNull("method")) ? init.get("method").asText("GET") : "GET";
		String reqBody = (init != null && init.hasNonNull("body")) ? init.get("body").asText("") : "";

		HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(30))
			.method(method.toUpperCase(), reqBody.isEmpty() ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(reqBody));
		if (init != null && init.has("headers")) {
			init.get("headers").fields().forEachRemaining(e -> {
				try {
					rb.header(e.getKey(), e.getValue().asText());
				}
				catch (Exception ignored) {
					// restricted header names are silently skipped
				}
			});
		}
		HttpResponse<String> resp = outboundHttpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
		Map<String, String> respHeaders = new LinkedHashMap<>();
		resp.headers().map().forEach((k, v) -> {
			if (!v.isEmpty()) {
				respHeaders.put(k, v.get(0));
			}
		});
		Map<String, Object> out = new LinkedHashMap<>();
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

	public Mono<ServerResponse> openSse(ServerRequest request) {
		String transportType = request.queryParam("transportType").orElse("sse");
		String url = request.queryParam("url").orElse(null);
		String command = request.queryParam("command").orElse(null);
		String args = request.queryParam("args").orElse(null);
		String env = request.queryParam("env").orElse(null);
		return openProxiedSession(transportType, url, command, args, env);
	}

	public Mono<ServerResponse> openStdio(ServerRequest request) {
		String command = request.queryParam("command").orElse(null);
		String args = request.queryParam("args").orElse(null);
		String env = request.queryParam("env").orElse(null);
		return openProxiedSession("stdio", null, command, args, env);
	}

	public Mono<ServerResponse> postMessage(ServerRequest request) {
		String sessionId = request.queryParam("sessionId").orElse(null);
		if (sessionId == null) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "missing sessionId"));
		}
		ProxySession session = registry.get(sessionId);
		if (session == null) {
			return ServerResponse.notFound().build();
		}
		return request.bodyToMono(JsonNode.class).flatMap(body -> {
			Sinks.EmitResult er = session.browserToTarget().tryEmitNext(body);
			if (er.isFailure()) {
				return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.bodyValue(Map.of("error", "emit failed: " + er.name()));
			}
			session.touch();
			return ServerResponse.accepted().build();
		});
	}

	private Mono<ServerResponse> openProxiedSession(String transportType, String url, String command, String args,
			String env) {
		String sessionId = UUID.randomUUID().toString();
		McpClientTransport target;
		try {
			target = buildTargetTransport(transportType, url, command, args, env);
		}
		catch (Exception ex) {
			LOG.warn("proxy[{}] failed to build target transport: {}", sessionId, ex.toString());
			return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
		}
		// browserToTarget stays unicast (single consumer in McpProxy);
		// targetToBrowser is multicast-replay so per-request awaiters in the
		// Streamable-HTTP path can attach while keeping this long-lived SSE
		// subscriber working.
		Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
		ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser);
		registry.put(session);

		String proxyBase = (properties != null) ? properties.getProxyPath() : "/mcp-inspector-api";
		ServerSentEvent<String> prologue = ServerSentEvent.<String>builder()
			.event("endpoint")
			.data(proxyBase + "/message?sessionId=" + sessionId)
			.build();

		var flux = targetToBrowser.asFlux().map(frame -> {
			try {
				return ServerSentEvent.<String>builder()
					.event("message")
					.data(objectMapper.writeValueAsString(frame))
					.build();
			}
			catch (Exception ex) {
				return ServerSentEvent.<String>builder().event("error").data(ex.getMessage()).build();
			}
		})
			.startWith(prologue)
			.doOnCancel(() -> registry.removeAndClose(sessionId))
			.doOnTerminate(() -> registry.removeAndClose(sessionId));

		mcpProxy.start(session).subscribe(ignored -> {
		}, err -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			registry.removeAndClose(sessionId);
		});

		return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(flux, ServerSentEvent.class);
	}

	private McpClientTransport buildTargetTransport(String transportType, String url, String command, String args,
			String env) {
		String type = (transportType == null) ? "sse" : transportType.toLowerCase();
		return switch (type) {
			case "sse" -> transportFactory.openSse(URI.create(requireUrl(url, "sse")));
			case "streamable-http" -> transportFactory.openStreamable(URI.create(requireUrl(url, "streamable-http")));
			case "stdio" -> {
				if (command == null || command.isBlank()) {
					throw new IllegalArgumentException(
							"missing required 'command' query parameter for stdio transport");
				}
				List<String> argv = new ArrayList<>();
				argv.add(command.trim());
				if (args != null && !args.isBlank()) {
					argv.addAll(Arrays.asList(args.trim().split("\\s+")));
				}
				Map<String, String> environment = parseEnv(env);
				yield transportFactory.openStdio(argv, environment);
			}
			default -> throw new IllegalArgumentException("unsupported transportType: " + transportType);
		};
	}

	private static String requireUrl(String url, String type) {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("missing required 'url' query parameter for " + type + " transport");
		}
		return url;
	}

	private Map<String, String> parseEnv(String env) {
		if (env == null || env.isBlank()) {
			return Map.of();
		}
		try {
			JsonNode node = objectMapper.readTree(env);
			Map<String, String> out = new LinkedHashMap<>();
			node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
			return out;
		}
		catch (Exception ex) {
			LOG.warn("proxy: ignoring malformed env JSON: {}", ex.toString());
			return Map.of();
		}
	}

	// ---------------------------------------------------------------------
	// Streamable-HTTP proxy
	// ---------------------------------------------------------------------

	public Mono<ServerResponse> postMcp(ServerRequest request) {
		String mcpSessionId = request.headers().firstHeader(ProxyConstants.MCP_SESSION_ID_HEADER);
		return request.bodyToMono(JsonNode.class)
			.flatMap(body -> handlePostMcp(mcpSessionId, request.queryParam("url").orElse(null), body));
	}

	/**
	 * Spec-compliant POST /mcp dispatcher.
	 *
	 * <p>
	 * If {@code mcpSessionId} is missing — opens a new session, then routes the frame
	 * through {@link #relayAndAwait}. Otherwise looks up the existing session.
	 */
	private Mono<ServerResponse> handlePostMcp(String mcpSessionId, String url, JsonNode body) {
		if (mcpSessionId == null || mcpSessionId.isBlank()) {
			return openSessionAndRelay(url, body);
		}
		ProxySession session = registry.get(mcpSessionId);
		if (session == null) {
			return ServerResponse.status(HttpStatus.NOT_FOUND)
				.bodyValue(Map.of("error", "unknown mcp-session-id: " + mcpSessionId));
		}
		return relayAndAwait(session, body, false);
	}

	/** Builds a new {@link ProxySession} and dispatches the first frame. */
	private Mono<ServerResponse> openSessionAndRelay(String url, JsonNode body) {
		if (url == null || url.isBlank()) {
			return ServerResponse.badRequest()
				.bodyValue(Map.of("error", "missing required 'url' query parameter for streamable-http transport"));
		}
		String sessionId = UUID.randomUUID().toString();
		McpClientTransport target;
		try {
			target = transportFactory.openStreamable(URI.create(url));
		}
		catch (Exception ex) {
			return ServerResponse.status(HttpStatus.BAD_GATEWAY)
				.bodyValue(Map.of("error", "upstream connect failed: " + ex.getMessage()));
		}
		Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
		ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser);
		registry.put(session);
		mcpProxy.start(session).subscribe(ignored -> {
		}, err -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			registry.removeAndClose(sessionId);
		});
		return relayAndAwait(session, body, true);
	}

	/**
	 * Relays {@code body} to the upstream. If the body is a JSON-RPC request (has an
	 * {@code id}), waits up to 30s for the matching response and returns it as
	 * {@code application/json}. Notification/response frames produce a 202 with empty
	 * body.
	 */
	private Mono<ServerResponse> relayAndAwait(ProxySession session, JsonNode body, boolean includeSessionHeader) {
		JsonNode idNode = extractRequestId(body);
		if (idNode == null) {
			Sinks.EmitResult emitResult = session.browserToTarget().tryEmitNext(body);
			if (emitResult.isFailure()) {
				return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.bodyValue(Map.of("error", "emit failed: " + emitResult.name()));
			}
			session.touch();
			ServerResponse.BodyBuilder accepted = ServerResponse.accepted();
			if (includeSessionHeader) {
				accepted.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
			}
			return accepted.build();
		}
		// Pre-create the awaiter Mono first so the replay subscription registers
		// (or has a buffer ready) before we push the request frame.
		Mono<JsonNode> awaiter = session.targetToBrowser()
			.asFlux()
			.filter(frame -> matchesId(frame, idNode))
			.next()
			.timeout(STREAMABLE_REQUEST_TIMEOUT);
		Sinks.EmitResult emitResult = session.browserToTarget().tryEmitNext(body);
		if (emitResult.isFailure()) {
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.bodyValue(Map.of("error", "emit failed: " + emitResult.name()));
		}
		session.touch();
		return awaiter.flatMap(node -> {
			ServerResponse.BodyBuilder ok = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON);
			if (includeSessionHeader) {
				ok.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
			}
			return ok.bodyValue(node);
		}).onErrorResume(ex -> {
			LOG.warn("proxy[{}] await response failed: {}", session.sessionId(), ex.toString());
			return ServerResponse.status(HttpStatus.GATEWAY_TIMEOUT)
				.bodyValue(Map.of("error",
						"upstream did not respond within " + STREAMABLE_REQUEST_TIMEOUT.toSeconds() + "s"));
		});
	}

	/**
	 * Returns the {@code id} node if {@code body} is a JSON-RPC request, else
	 * {@code null}.
	 */
	private static JsonNode extractRequestId(JsonNode body) {
		if (body == null || !body.isObject()) {
			return null;
		}
		JsonNode id = body.get("id");
		if (id == null || id.isNull()) {
			return null;
		}
		return id;
	}

	/** True iff {@code frame.id} equals {@code expected}. */
	private static boolean matchesId(JsonNode frame, JsonNode expected) {
		if (frame == null || !frame.isObject()) {
			return false;
		}
		JsonNode id = frame.get("id");
		return id != null && !id.isNull() && id.equals(expected);
	}

	public Mono<ServerResponse> getMcp(ServerRequest request) {
		String mcpSessionId = request.headers().firstHeader(ProxyConstants.MCP_SESSION_ID_HEADER);
		ProxySession session = (mcpSessionId == null) ? null : registry.get(mcpSessionId);
		if (session == null) {
			return ServerResponse.status(HttpStatus.NOT_FOUND)
				.bodyValue(Map.of("error", "unknown mcp-session-id: " + mcpSessionId));
		}
		var flux = session.targetToBrowser().asFlux().map(frame -> {
			try {
				return ServerSentEvent.<String>builder()
					.event("message")
					.data(objectMapper.writeValueAsString(frame))
					.build();
			}
			catch (Exception ex) {
				return ServerSentEvent.<String>builder().event("error").data(ex.getMessage()).build();
			}
		});
		return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(flux, ServerSentEvent.class);
	}

	public Mono<ServerResponse> deleteMcp(ServerRequest request) {
		String mcpSessionId = request.headers().firstHeader(ProxyConstants.MCP_SESSION_ID_HEADER);
		boolean removed = registry.removeAndClose(mcpSessionId);
		return removed ? ServerResponse.ok().build() : ServerResponse.notFound().build();
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	private static String mapTransport(TransportType type) {
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

	private String buildServerUrl(DetectedTransport detected) {
		int port = listeningPort.get();
		if (port <= 0 || detected == null || detected.type() == TransportType.UNKNOWN
				|| detected.type() == TransportType.STDIO_NO_HTTP) {
			return "";
		}
		String path = detected.endpoint();
		if (path == null || path.isBlank()) {
			path = "/mcp";
		}
		return "http://localhost:" + port + path;
	}

}
