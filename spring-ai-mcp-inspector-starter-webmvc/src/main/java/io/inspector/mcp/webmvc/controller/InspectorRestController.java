/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.controller;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.client.InspectorClientHandlers;
import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.core.client.PendingServerRequests;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.dto.ConfigDto;
import io.inspector.mcp.core.dto.ConnectRequest;
import io.inspector.mcp.core.dto.JsonRpcRelay;
import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.dto.RootsDto;
import io.inspector.mcp.core.oauth.InspectorOAuthClient;
import io.inspector.mcp.core.oauth.OAuthInitiateRequest;
import io.inspector.mcp.core.oauth.OAuthInitiateResponse;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;
import io.inspector.mcp.webmvc.sse.InspectorSseEmitterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST endpoints that back the inspector SPA. All routes are mounted under
 * {@code /mcp-inspector/api} and protected by {@code InspectorAuthFilter}.
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}/api")
public class InspectorRestController {

	private static final Logger LOG = LoggerFactory.getLogger(InspectorRestController.class);

	static final String INSPECTOR_VERSION = "0.1.0";
	static final int JSONRPC_METHOD_NOT_FOUND = -32601;
	static final int JSONRPC_INTERNAL_ERROR = -32603;

	/**
	 * Maximum time we block a transport thread waiting for the UI to answer a server
	 * request.
	 */
	static final long SERVER_REQUEST_TIMEOUT_SECONDS = 120L;

	private final McpInspectorProperties properties;

	private final TransportDetector transportDetector;

	private final LoopbackMcpClientFactory loopbackFactory;

	private final InspectorAuthTokenProvider authTokenProvider;

	private final InspectorSseEmitterRegistry emitterRegistry;

	private final InspectorServerPortHolder portHolder;

	private final ObjectMapper objectMapper;

	private final InspectorOAuthClient oauthClient;

	private final String serverName;

	private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

	public InspectorRestController(McpInspectorProperties properties, TransportDetector transportDetector,
			LoopbackMcpClientFactory loopbackFactory, InspectorAuthTokenProvider authTokenProvider,
			InspectorSseEmitterRegistry emitterRegistry, InspectorServerPortHolder portHolder,
			ObjectMapper objectMapper, InspectorOAuthClient oauthClient,
			@Value("${spring.application.name:mcp-server}") String serverName) {
		this.properties = properties;
		this.transportDetector = transportDetector;
		this.loopbackFactory = loopbackFactory;
		this.authTokenProvider = authTokenProvider;
		this.emitterRegistry = emitterRegistry;
		this.portHolder = portHolder;
		this.objectMapper = objectMapper;
		this.oauthClient = (oauthClient != null) ? oauthClient : new InspectorOAuthClient();
		this.serverName = serverName;
	}

	@GetMapping(path = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
	public ConfigDto config() {
		DetectedTransport t = transportDetector.detect();
		return new ConfigDto(t.type().name(), t.endpoint(), t.messageEndpoint(), t.stack(), serverName,
				INSPECTOR_VERSION, authTokenProvider.token(), new LinkedHashMap<>());
	}

	@PostMapping(path = "/connect", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> connect(@RequestBody(required = false) ConnectRequest request) {
		DetectedTransport detected = transportDetector.detect();
		TransportType type = detected.type();

		// We allocate the session id up-front so the sampling / elicitation handlers
		// can route incoming server requests to the right SSE channel.
		String sessionId = UUID.randomUUID().toString();
		SessionStateHolder holder = new SessionStateHolder();
		InspectorClientHandlers handlers = new InspectorClientHandlers(
				req -> handleSamplingRequest(sessionId, holder, req),
				req -> handleElicitationRequest(sessionId, holder, req));

		McpSyncClient client;
		try {
			client = buildLoopbackClient(detected, handlers);
		}
		catch (Exception ex) {
			LOG.warn("Failed to build loopback MCP client for {}", type, ex);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", "Failed to build loopback client: " + ex.getMessage()));
		}
		if (client == null) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", "Loopback connection unsupported for transport: " + type));
		}

		try {
			client.initialize();
		}
		catch (Exception ex) {
			try {
				client.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			LOG.warn("MCP initialize() failed", ex);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", "initialize() failed: " + ex.getMessage()));
		}

		SessionState state = new SessionState(client);
		holder.state = state;
		sessions.put(sessionId, state);
		return ResponseEntity.ok(Map.of("sessionId", sessionId));
	}

	@PostMapping(path = "/jsonrpc", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> jsonrpc(@RequestParam("sessionId") String sessionId,
			@RequestBody JsonRpcRelay relay) {

		SessionState state = sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(jsonRpcError(relay, JSONRPC_INTERNAL_ERROR, "Unknown sessionId: " + sessionId));
		}

		try {
			Object result = dispatch(state, relay);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("jsonrpc", "2.0");
			body.put("id", relay != null ? relay.id() : null);
			body.put("result", result);
			return ResponseEntity.ok(body);
		}
		catch (UnknownMethodException ex) {
			return ResponseEntity.ok(jsonRpcError(relay, JSONRPC_METHOD_NOT_FOUND, ex.getMessage()));
		}
		catch (Exception ex) {
			LOG.warn("JSON-RPC relay failure for method {}", relay != null ? relay.method() : null, ex);
			return ResponseEntity.ok(jsonRpcError(relay, JSONRPC_INTERNAL_ERROR, ex.getMessage()));
		}
	}

	@GetMapping(path = "/events")
	public SseEmitter events(@RequestParam("sessionId") String sessionId) {
		return emitterRegistry.register(sessionId);
	}

	@DeleteMapping(path = "/session/{id}")
	public ResponseEntity<Void> deleteSession(@PathVariable("id") String id) {
		SessionState state = sessions.remove(id);
		if (state != null) {
			state.closeQuietly();
		}
		emitterRegistry.close(id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Returns the roots currently advertised by the inspector to the backing MCP server.
	 */
	@GetMapping(path = "/roots", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> getRoots(@RequestParam("sessionId") String sessionId) {
		SessionState state = sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		return ResponseEntity.ok(new RootsDto(new ArrayList<>(state.roots())));
	}

	/**
	 * Replaces the session's root list and notifies the backing MCP server via
	 * {@code notifications/roots/list_changed}.
	 */
	@PutMapping(path = "/roots", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> putRoots(@RequestParam("sessionId") String sessionId, @RequestBody RootsDto body) {
		SessionState state = sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		List<RootDto> next = (body != null && body.roots() != null) ? body.roots() : List.of();
		applyRoots(state, next);
		emitterRegistry.broadcast(sessionId, "mcp:roots-list-changed", Map.of("count", next.size()));
		return ResponseEntity.ok(new RootsDto(new ArrayList<>(state.roots())));
	}

	/**
	 * Completes a previously suspended server-to-client request
	 * ({@code sampling/createMessage}, {@code elicitation/create}) with the UI-supplied
	 * result.
	 */
	@PostMapping(path = "/jsonrpc/respond", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> respond(@RequestParam("sessionId") String sessionId,
			@RequestParam("requestId") String requestId, @RequestBody JsonNode body) {
		SessionState state = sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		boolean completed;
		if (body != null && body.has("error")) {
			completed = state.pendingServerRequests()
				.completeExceptionally(requestId,
						new RuntimeException(body.path("error").path("message").asText("rejected")));
		}
		else {
			JsonNode result = (body != null && body.has("result")) ? body.get("result") : body;
			completed = state.pendingServerRequests().complete(requestId, result);
		}
		if (!completed) {
			return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "no pending request: " + requestId));
		}
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Generates a random {@code state} token, builds the IdP authorization URL and stores
	 * the OAuth context on the session for the matching callback.
	 */
	@PostMapping(path = "/oauth/initiate", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> oauthInitiate(@RequestParam("sessionId") String sessionId,
			@RequestBody OAuthInitiateRequest req) {
		SessionState state = sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		String stateToken = randomState();
		state.oauthState(stateToken);
		state.oauthClientId(req.clientId());
		state.oauthRedirectUri(req.redirectUri());
		state.oauthTokenEndpoint(req.tokenEndpoint());
		String url = oauthClient.buildAuthUrl(req.authorizationEndpoint(), req.clientId(), req.redirectUri(),
				req.scope(), stateToken, req.codeChallenge());
		return ResponseEntity.ok(new OAuthInitiateResponse(url, stateToken));
	}

	/**
	 * Exchanges the OAuth authorization {@code code} for a token using the previously
	 * stored token endpoint, validates {@code state}, and caches the token on the
	 * session.
	 */
	@GetMapping(path = "/oauth/callback", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> oauthCallback(@RequestParam("sessionId") String sessionId,
			@RequestParam("code") String code, @RequestParam("state") String stateToken,
			@RequestParam(value = "codeVerifier", required = false) String codeVerifier) {
		SessionState state = sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		if (state.oauthState() == null || !state.oauthState().equals(stateToken)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "state mismatch"));
		}
		try {
			OAuthTokenResponse token = oauthClient.exchangeCode(state.oauthTokenEndpoint(), state.oauthClientId(), code,
					state.oauthRedirectUri(), codeVerifier);
			state.oauthToken(token);
			return ResponseEntity.ok(token);
		}
		catch (Exception ex) {
			LOG.warn("OAuth token exchange failed", ex);
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
		}
	}

	/* ---------------- helpers ---------------- */

	private McpSyncClient buildLoopbackClient(DetectedTransport t, InspectorClientHandlers handlers) {
		int port = portHolder.port();
		return switch (t.type()) {
			case SSE -> loopbackFactory.forSse("127.0.0.1", port, "", t.messageEndpoint(), handlers);
			case STREAMABLE -> loopbackFactory.forStreamable("127.0.0.1", port, t.endpoint(), handlers);
			case STATELESS -> loopbackFactory.forStateless("127.0.0.1", port, t.endpoint(), handlers);
			case STDIO_NO_HTTP, UNKNOWN -> null;
		};
	}

	@SuppressWarnings("unchecked")
	private Object dispatch(SessionState state, JsonRpcRelay relay) {
		if (relay == null || relay.method() == null) {
			throw new UnknownMethodException("missing method");
		}
		McpSyncClient client = state.client();
		Object params = relay.params();
		return switch (relay.method()) {
			case "tools/list" -> client.listTools();
			case "tools/call" -> {
				Map<String, Object> p = asMap(params);
				String name = (String) p.get("name");
				Object args = p.get("arguments");
				Map<String, Object> argsMap = (args instanceof Map<?, ?>) ? (Map<String, Object>) args
						: objectMapper.convertValue(args, Map.class);
				yield client.callTool(new McpSchema.CallToolRequest(name, argsMap));
			}
			case "resources/list" -> client.listResources();
			case "resources/read" -> {
				Map<String, Object> p = asMap(params);
				String uri = (String) p.get("uri");
				yield client.readResource(new McpSchema.ReadResourceRequest(uri));
			}
			case "prompts/list" -> client.listPrompts();
			case "prompts/get" -> {
				Map<String, Object> p = asMap(params);
				String name = (String) p.get("name");
				Object args = p.get("arguments");
				Map<String, Object> argsMap = (args instanceof Map<?, ?>) ? (Map<String, Object>) args
						: (args == null ? Map.of() : objectMapper.convertValue(args, Map.class));
				yield client.getPrompt(new McpSchema.GetPromptRequest(name, argsMap));
			}
			case "ping" -> {
				Object pong = client.ping();
				yield (pong == null) ? Map.of() : pong;
			}
			case "logging/setLevel" -> {
				Map<String, Object> p = asMap(params);
				String levelStr = String.valueOf(p.get("level")).toUpperCase();
				McpSchema.LoggingLevel level = McpSchema.LoggingLevel.valueOf(levelStr);
				client.setLoggingLevel(level);
				yield Map.of();
			}
			case "roots/list" -> Map.of("roots", new ArrayList<>(state.roots()));
			default -> throw new UnknownMethodException("Unknown JSON-RPC method: " + relay.method());
		};
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object params) {
		if (params == null) {
			return Map.of();
		}
		if (params instanceof Map<?, ?> m) {
			return (Map<String, Object>) m;
		}
		return objectMapper.convertValue(params, Map.class);
	}

	private void applyRoots(SessionState state, List<RootDto> next) {
		// 1) update local view
		state.replaceRoots(next);
		// 2) sync with the loopback client and notify the server
		McpSyncClient client = state.client();
		try {
			// Replace by remove + add — SDK has no bulk API.
			// We do not have a snapshot of previous roots from the client; rely on our
			// copy.
			for (RootDto r : next) {
				if (r.uri() == null || r.uri().isBlank()) {
					continue;
				}
				client.addRoot(new McpSchema.Root(r.uri(), r.name()));
			}
			client.rootsListChangedNotification();
		}
		catch (Exception ex) {
			LOG.debug("rootsListChangedNotification failed (likely no server roots capability): {}", ex.toString());
		}
	}

	/**
	 * Bridges a server-initiated {@code sampling/createMessage} request onto the SSE
	 * stream and blocks until the UI POSTs back to {@code /jsonrpc/respond}.
	 */
	private McpSchema.CreateMessageResult handleSamplingRequest(String sessionId, SessionStateHolder holder,
			McpSchema.CreateMessageRequest request) {
		return awaitServerResponse(sessionId, holder, "mcp:sampling-request", request,
				McpSchema.CreateMessageResult.class);
	}

	/**
	 * Bridges a server-initiated {@code elicitation/create} request onto the SSE stream
	 * and blocks until the UI POSTs back to {@code /jsonrpc/respond}.
	 */
	private McpSchema.ElicitResult handleElicitationRequest(String sessionId, SessionStateHolder holder,
			McpSchema.ElicitRequest request) {
		return awaitServerResponse(sessionId, holder, "mcp:elicitation-request", request, McpSchema.ElicitResult.class);
	}

	private <T> T awaitServerResponse(String sessionId, SessionStateHolder holder, String eventName, Object params,
			Class<T> resultType) {
		SessionState state = holder.state;
		// During client.initialize() the holder may not yet point to the session;
		// fall back to the registry once it's populated.
		if (state == null) {
			state = sessions.get(sessionId);
		}
		if (state == null) {
			throw new IllegalStateException("session not yet registered for " + eventName);
		}
		PendingServerRequests pending = state.pendingServerRequests();
		String requestId = UUID.randomUUID().toString();
		var future = pending.create(requestId);
		ObjectNode envelope = objectMapper.createObjectNode();
		envelope.put("requestId", requestId);
		envelope.set("params", objectMapper.valueToTree(params));
		emitterRegistry.broadcast(sessionId, eventName, envelope);
		try {
			JsonNode reply = future.get(SERVER_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return objectMapper.treeToValue(reply, resultType);
		}
		catch (TimeoutException ex) {
			pending.completeExceptionally(requestId, ex);
			throw new RuntimeException(
					"UI did not answer " + eventName + " within " + SERVER_REQUEST_TIMEOUT_SECONDS + "s", ex);
		}
		catch (Exception ex) {
			throw new RuntimeException(eventName + " failed: " + ex.getMessage(), ex);
		}
	}

	private static String randomState() {
		byte[] buf = new byte[24];
		new java.security.SecureRandom().nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	private static Map<String, Object> jsonRpcError(JsonRpcRelay relay, int code, String message) {
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("code", code);
		err.put("message", message != null ? message : "Inspector relay error");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("jsonrpc", "2.0");
		body.put("id", relay != null ? relay.id() : null);
		body.put("error", err);
		return body;
	}

	/**
	 * Sentinel for {@link #dispatch(SessionState, JsonRpcRelay)} when the method is
	 * unknown.
	 */
	private static final class UnknownMethodException extends RuntimeException {

		UnknownMethodException(String message) {
			super(message);
		}

	}

	/**
	 * Mutable forward-declaration of the per-session state so the sampling / elicitation
	 * handler functions (registered before {@code initialize()}) can locate the right
	 * {@link SessionState} once it exists.
	 */
	private static final class SessionStateHolder {

		volatile SessionState state;

	}

	/** Visible for tests / shutdown hooks. */
	ConcurrentMap<String, SessionState> sessions() {
		return sessions;
	}

}
