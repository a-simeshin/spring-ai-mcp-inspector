/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.proxy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Streamable-HTTP transport ports.
 *
 * <p>
 * Endpoint surface (mirrors the MCP Streamable-HTTP spec at <a href=
 * "https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http">spec</a>):
 *
 * <ul>
 * <li>{@code POST /mcp-inspector-api/mcp} without {@code mcp-session-id} header —
 * initiates a new session, forwards the (request) frame to the upstream, returns 200 with
 * the matching JSON-RPC response body and the generated session id in the
 * {@code mcp-session-id} response header.</li>
 * <li>{@code POST /mcp-inspector-api/mcp} with {@code mcp-session-id} — if the frame is a
 * JSON-RPC <em>request</em> (has an {@code id}), waits up to 30s for the matching
 * response from the upstream and returns it as {@code application/json}; otherwise
 * (notification/response — no {@code id}) returns 202 Accepted with no body.</li>
 * <li>{@code GET  /mcp-inspector-api/mcp} with {@code mcp-session-id} — returns an SSE
 * stream of target→browser frames.</li>
 * <li>{@code DELETE /mcp-inspector-api/mcp} with {@code mcp-session-id} — tears down the
 * session.</li>
 * </ul>
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class StreamableHttpProxyController {

	private static final Logger LOG = LoggerFactory.getLogger(StreamableHttpProxyController.class);

	/** SSE inactivity timeout — generous because MCP servers may idle for minutes. */
	private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L;

	/** How long a single POST awaits the matching JSON-RPC response from the upstream. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	/** Replay buffer size for the {@code targetToBrowser} sink (per session). */
	private static final int REPLAY_BUFFER = 256;

	private final ProxySessionRegistry registry;

	private final ProxyTransportFactory transportFactory;

	private final McpProxy mcpProxy;

	private final ObjectMapper objectMapper;

	public StreamableHttpProxyController(ProxySessionRegistry registry, ProxyTransportFactory transportFactory,
			McpProxy mcpProxy, ObjectMapper objectMapper) {
		this.registry = registry;
		this.transportFactory = transportFactory;
		this.mcpProxy = mcpProxy;
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
	}

	@PostMapping(path = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Object> postMcp(
			@RequestHeader(value = ProxyConstants.MCP_SESSION_ID_HEADER, required = false) String mcpSessionId,
			@RequestParam(value = "url", required = false) String url, @RequestBody JsonNode body) {
		if (mcpSessionId == null || mcpSessionId.isBlank()) {
			return openSessionAndForward(url, body);
		}
		ProxySession session = registry.get(mcpSessionId);
		if (session == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body("unknown mcp-session-id: " + mcpSessionId);
		}
		return forwardOnExistingSession(session, body);
	}

	@GetMapping(path = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter getMcp(@RequestHeader(value = ProxyConstants.MCP_SESSION_ID_HEADER) String mcpSessionId) {
		SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
		ProxySession session = registry.get(mcpSessionId);
		if (session == null) {
			return emitErrorAndComplete(emitter, "unknown mcp-session-id: " + mcpSessionId);
		}
		session.targetToBrowser()
			.asFlux()
			.subscribe(frame -> sendEvent(emitter, frame), emitter::completeWithError, emitter::complete);
		return emitter;
	}

	@DeleteMapping("/mcp")
	public ResponseEntity<Void> deleteMcp(
			@RequestHeader(value = ProxyConstants.MCP_SESSION_ID_HEADER) String mcpSessionId) {
		boolean removed = registry.removeAndClose(mcpSessionId);
		return removed ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	/**
	 * Brings up a fresh session and forwards the first frame. If the frame is a JSON-RPC
	 * request, blocks for up to {@link #REQUEST_TIMEOUT} on the matching response.
	 * Otherwise returns 202.
	 */
	private ResponseEntity<Object> openSessionAndForward(String url, JsonNode body) {
		if (url == null || url.isBlank()) {
			return ResponseEntity.badRequest()
				.body("missing required 'url' query parameter for streamable-http transport");
		}
		ProxySession session = openSession(url);
		if (session == null) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("upstream connect failed");
		}
		return relayWithSessionHeader(session, body, true);
	}

	/**
	 * Forwards a frame to an already-open session. Same request/notification split as
	 * {@link #openSessionAndForward}, minus the session-id response header.
	 */
	private ResponseEntity<Object> forwardOnExistingSession(ProxySession session, JsonNode body) {
		return relayWithSessionHeader(session, body, false);
	}

	/**
	 * Core relay path shared by the "new session" and "existing session" branches.
	 * @param session live proxy session
	 * @param body incoming JSON-RPC frame
	 * @param includeSessionHeader whether to attach the {@code mcp-session-id} header to
	 * the response (only on the first POST of a session)
	 */
	private ResponseEntity<Object> relayWithSessionHeader(ProxySession session, JsonNode body,
			boolean includeSessionHeader) {
		JsonNode idNode = extractRequestId(body);
		// Notification / response — no answer expected from upstream → 202 Accepted.
		if (idNode == null) {
			Sinks.EmitResult emitResult = session.browserToTarget().tryEmitNext(body);
			if (emitResult.isFailure()) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body("emit failed: " + emitResult.name());
			}
			session.touch();
			ResponseEntity.BodyBuilder builder = ResponseEntity.accepted();
			if (includeSessionHeader) {
				builder.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
			}
			return builder.build();
		}
		// Request — pre-create the awaiter Mono (replay sink buffers, so even
		// if the upstream answer lands before .block() registers a subscriber,
		// the replay buffer still hands it over). We still emit AFTER preparing
		// the await pipeline so the read-side wiring exists first.
		Mono<JsonNode> awaiter = session.targetToBrowser()
			.asFlux()
			.filter(frame -> matchesId(frame, idNode))
			.next()
			.timeout(REQUEST_TIMEOUT);
		Sinks.EmitResult emitResult = session.browserToTarget().tryEmitNext(body);
		if (emitResult.isFailure()) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("emit failed: " + emitResult.name());
		}
		session.touch();
		try {
			JsonNode response = awaiter.block(REQUEST_TIMEOUT);
			ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
			if (includeSessionHeader) {
				builder.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
			}
			return builder.body(response);
		}
		catch (RuntimeException ex) {
			LOG.warn("proxy[{}] await response failed: {}", session.sessionId(), ex.toString());
			return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
				.body(Map.of("error", "upstream did not respond within " + REQUEST_TIMEOUT.toSeconds() + "s"));
		}
	}

	/**
	 * Allocates the {@link ProxySession} and kicks off the {@link McpProxy} pumps.
	 * Returns {@code null} on transport-construction failure.
	 */
	private ProxySession openSession(String url) {
		String sessionId = UUID.randomUUID().toString();
		McpClientTransport target;
		try {
			target = transportFactory.openStreamable(URI.create(url));
		}
		catch (Exception ex) {
			LOG.warn("proxy[{}] upstream connect failed: {}", sessionId, ex.toString());
			return null;
		}
		Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(REPLAY_BUFFER);
		ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser);
		registry.put(session);
		mcpProxy.start(session).subscribe(ignored -> {
		}, err -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			registry.removeAndClose(sessionId);
		});
		return session;
	}

	/**
	 * Extracts {@code id} from a JSON-RPC frame if present; {@code null} →
	 * notification/response.
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

	/** True iff {@code frame.id} equals {@code expected} (by JsonNode equality). */
	private static boolean matchesId(JsonNode frame, JsonNode expected) {
		if (frame == null || !frame.isObject()) {
			return false;
		}
		JsonNode id = frame.get("id");
		return id != null && !id.isNull() && id.equals(expected);
	}

	/** Pushes a single message frame to the SSE emitter; logs/aborts on IO error. */
	private void sendEvent(SseEmitter emitter, JsonNode frame) {
		try {
			emitter.send(SseEmitter.event().name("message").data(objectMapper.writeValueAsString(frame)));
		}
		catch (IOException ex) {
			emitter.completeWithError(ex);
		}
	}

	private SseEmitter emitErrorAndComplete(SseEmitter emitter, String message) {
		try {
			emitter.send(SseEmitter.event().name("error").data(message));
		}
		catch (IOException ignored) {
			// best-effort error notice
		}
		emitter.complete();
		return emitter;
	}

}
