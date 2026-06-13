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

package io.inspector.mcp.webmvc.controller;

import java.security.SecureRandom;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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

/**
 * REST endpoints that back the inspector SPA. All routes are mounted under
 * {@code /mcp-inspector/api} and protected by {@code InspectorAuthFilter}.
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}/api")
public class InspectorRestController {

	private static final Logger LOG = LoggerFactory.getLogger(InspectorRestController.class);

	/**
	 * Shared CSPRNG for OAuth {@code state} generation. {@link SecureRandom} is
	 * thread-safe and expensive to seed, so a single instance is reused.
	 */
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	static final String INSPECTOR_VERSION = "0.1.0";

	static final int JSONRPC_METHOD_NOT_FOUND = -32601;

	static final int JSONRPC_INTERNAL_ERROR = -32603;

	private final McpInspectorProperties properties;

	private final TransportDetector transportDetector;

	private final LoopbackMcpClientFactory loopbackFactory;

	private final InspectorAuthTokenProvider authTokenProvider;

	private final InspectorSseEmitterRegistry emitterRegistry;

	private final InspectorServerPortHolder portHolder;

	private final JsonMapper objectMapper;

	private final InspectorOAuthClient oauthClient;

	private final String serverName;

	private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

	public InspectorRestController(final McpInspectorProperties properties, final TransportDetector transportDetector,
			final LoopbackMcpClientFactory loopbackFactory, final InspectorAuthTokenProvider authTokenProvider,
			final InspectorSseEmitterRegistry emitterRegistry, final InspectorServerPortHolder portHolder,
			final JsonMapper objectMapper, final InspectorOAuthClient oauthClient,
			@Value("${spring.application.name:mcp-server}") final String serverName) {
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
		final DetectedTransport t = this.transportDetector.detect();
		return new ConfigDto(t.type().name(), t.endpoint(), t.messageEndpoint(), t.stack(), this.serverName,
				INSPECTOR_VERSION, this.authTokenProvider.token(), new LinkedHashMap<>());
	}

	@PostMapping(path = "/connect", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> connect(@RequestBody(required = false) final ConnectRequest request) {
		final DetectedTransport detected = this.transportDetector.detect();
		final TransportType type = detected.type();

		// We allocate the session id up-front so the sampling / elicitation handlers
		// can route incoming server requests to the right SSE channel.
		final String sessionId = UUID.randomUUID().toString();
		final SessionStateHolder holder = new SessionStateHolder();
		final InspectorClientHandlers handlers = new InspectorClientHandlers(
				(req) -> handleSamplingRequest(sessionId, holder, req),
				(req) -> handleElicitationRequest(sessionId, holder, req));

		final McpSyncClient client;
		try {
			client = buildLoopbackClient(detected, handlers);
		}
		catch (final Exception ex) {
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
		catch (final Exception ex) {
			try {
				client.close();
			}
			catch (final Exception ignored) {
				/* best-effort */
			}
			LOG.warn("MCP initialize() failed", ex);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", "initialize() failed: " + ex.getMessage()));
		}

		final SessionState state = new SessionState(client);
		holder.state = state;
		this.sessions.put(sessionId, state);
		return ResponseEntity.ok(Map.of("sessionId", sessionId));
	}

	@PostMapping(path = "/jsonrpc", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> jsonrpc(@RequestParam("sessionId") final String sessionId,
			@RequestBody final JsonRpcRelay relay) {

		final SessionState state = this.sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(jsonRpcError(relay, JSONRPC_INTERNAL_ERROR, "Unknown sessionId: " + sessionId));
		}

		try {
			final Object result = dispatch(state, relay);
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("jsonrpc", "2.0");
			body.put("id", (relay != null) ? relay.id() : null);
			body.put("result", result);
			return ResponseEntity.ok(body);
		}
		catch (final UnknownMethodException ex) {
			return ResponseEntity.ok(jsonRpcError(relay, JSONRPC_METHOD_NOT_FOUND, ex.getMessage()));
		}
		catch (final Exception ex) {
			LOG.warn("JSON-RPC relay failure for method {}", (relay != null) ? relay.method() : null, ex);
			return ResponseEntity.ok(jsonRpcError(relay, JSONRPC_INTERNAL_ERROR, ex.getMessage()));
		}
	}

	@GetMapping(path = "/events")
	public SseEmitter events(@RequestParam("sessionId") final String sessionId) {
		return this.emitterRegistry.register(sessionId);
	}

	@DeleteMapping(path = "/session/{id}")
	public ResponseEntity<Void> deleteSession(@PathVariable("id") final String id) {
		final SessionState state = this.sessions.remove(id);
		if (state != null) {
			state.closeQuietly();
		}
		this.emitterRegistry.close(id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Returns the roots currently advertised by the inspector to the backing MCP server.
	 * @param sessionId the inspector session identifier
	 * @return the current roots envelope, or 404 if the session is unknown
	 */
	@GetMapping(path = "/roots", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> getRoots(@RequestParam("sessionId") final String sessionId) {
		final SessionState state = this.sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		return ResponseEntity.ok(new RootsDto(new ArrayList<>(state.roots())));
	}

	/**
	 * Replaces the session's root list and notifies the backing MCP server via
	 * {@code notifications/roots/list_changed}.
	 * @param sessionId the inspector session identifier
	 * @param body the new roots payload
	 * @return the updated roots envelope, or 404 if the session is unknown
	 */
	@PutMapping(path = "/roots", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> putRoots(@RequestParam("sessionId") final String sessionId,
			@RequestBody final RootsDto body) {
		final SessionState state = this.sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		final List<RootDto> next = (body != null && body.roots() != null) ? body.roots() : List.of();
		applyRoots(state, next);
		this.emitterRegistry.broadcast(sessionId, "mcp:roots-list-changed", Map.of("count", next.size()));
		return ResponseEntity.ok(new RootsDto(new ArrayList<>(state.roots())));
	}

	/**
	 * Completes a previously suspended server-to-client request
	 * ({@code sampling/createMessage}, {@code elicitation/create}) with the UI-supplied
	 * result.
	 * @param sessionId the inspector session identifier
	 * @param requestId the pending request identifier to resolve
	 * @param body the result or error payload from the UI
	 * @return 200 on success, 404 if the session is unknown, 410 if the request is gone
	 */
	@PostMapping(path = "/jsonrpc/respond", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> respond(@RequestParam("sessionId") final String sessionId,
			@RequestParam("requestId") final String requestId, @RequestBody final JsonNode body) {
		final SessionState state = this.sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		final boolean completed;
		if (body != null && body.has("error")) {
			completed = state.pendingServerRequests()
				.completeExceptionally(requestId,
						new RuntimeException(body.path("error").path("message").asText("rejected")));
		}
		else {
			final JsonNode result = (body != null && body.has("result")) ? body.get("result") : body;
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
	 * @param sessionId the inspector session identifier
	 * @param req the OAuth initiation request parameters
	 * @return the authorization URL and state token, or 404 if the session is unknown
	 */
	@PostMapping(path = "/oauth/initiate", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> oauthInitiate(@RequestParam("sessionId") final String sessionId,
			@RequestBody final OAuthInitiateRequest req) {
		final SessionState state = this.sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		final String stateToken = randomState();
		state.oauthState(stateToken);
		state.oauthClientId(req.clientId());
		state.oauthRedirectUri(req.redirectUri());
		state.oauthTokenEndpoint(req.tokenEndpoint());
		final String url = this.oauthClient.buildAuthUrl(req.authorizationEndpoint(), req.clientId(), req.redirectUri(),
				req.scope(), stateToken, req.codeChallenge());
		return ResponseEntity.ok(new OAuthInitiateResponse(url, stateToken));
	}

	/**
	 * Exchanges the OAuth authorization {@code code} for a token using the previously
	 * stored token endpoint, validates {@code state}, and caches the token on the
	 * session.
	 * @param sessionId the inspector session identifier
	 * @param code the authorization code from the IdP
	 * @param stateToken the state parameter to validate against the stored value
	 * @param codeVerifier the PKCE code verifier, or {@code null} if not used
	 * @return the token response, or an error status
	 */
	@GetMapping(path = "/oauth/callback", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> oauthCallback(@RequestParam("sessionId") final String sessionId,
			@RequestParam("code") final String code, @RequestParam("state") final String stateToken,
			@RequestParam(value = "codeVerifier", required = false) final String codeVerifier) {
		final SessionState state = this.sessions.get(sessionId);
		if (state == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown sessionId: " + sessionId));
		}
		if (state.oauthState() == null || !state.oauthState().equals(stateToken)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "state mismatch"));
		}
		try {
			final OAuthTokenResponse token = this.oauthClient.exchangeCode(state.oauthTokenEndpoint(),
					state.oauthClientId(), code, state.oauthRedirectUri(), codeVerifier);
			state.oauthToken(token);
			return ResponseEntity.ok(token);
		}
		catch (final Exception ex) {
			LOG.warn("OAuth token exchange failed", ex);
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(Map.of("error", (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName()));
		}
	}

	/* ---------------- helpers ---------------- */

	private McpSyncClient buildLoopbackClient(final DetectedTransport t, final InspectorClientHandlers handlers) {
		final int port = this.portHolder.port();
		return switch (t.type()) {
			case SSE -> this.loopbackFactory.forSse("127.0.0.1", port, "", t.messageEndpoint(), handlers);
			case STREAMABLE -> this.loopbackFactory.forStreamable("127.0.0.1", port, t.endpoint(), handlers);
			case STATELESS -> this.loopbackFactory.forStateless("127.0.0.1", port, t.endpoint(), handlers);
			case STDIO_NO_HTTP, UNKNOWN -> null;
		};
	}

	@SuppressWarnings("unchecked")
	private Object dispatch(final SessionState state, final JsonRpcRelay relay) {
		if (relay == null || relay.method() == null) {
			throw new UnknownMethodException("missing method");
		}
		final McpSyncClient client = state.client();
		final Object params = relay.params();
		return switch (relay.method()) {
			case "tools/list" -> client.listTools();
			case "tools/call" -> {
				final Map<String, Object> p = asMap(params);
				final String name = (String) p.get("name");
				final Object args = p.get("arguments");
				final Map<String, Object> argsMap = (args instanceof Map<?, ?>) ? (Map<String, Object>) args
						: this.objectMapper.convertValue(args, Map.class);
				yield client.callTool(new McpSchema.CallToolRequest(name, argsMap));
			}
			case "resources/list" -> client.listResources();
			case "resources/read" -> {
				final Map<String, Object> p = asMap(params);
				final String uri = (String) p.get("uri");
				yield client.readResource(new McpSchema.ReadResourceRequest(uri));
			}
			case "prompts/list" -> client.listPrompts();
			case "prompts/get" -> {
				final Map<String, Object> p = asMap(params);
				final String name = (String) p.get("name");
				final Object args = p.get("arguments");
				final Map<String, Object> argsMap = (args instanceof Map<?, ?>) ? (Map<String, Object>) args
						: ((args != null) ? this.objectMapper.convertValue(args, Map.class) : Map.of());
				yield client.getPrompt(new McpSchema.GetPromptRequest(name, argsMap));
			}
			case "ping" -> {
				final Object pong = client.ping();
				yield (pong != null) ? pong : Map.of();
			}
			case "logging/setLevel" -> {
				final Map<String, Object> p = asMap(params);
				final String levelStr = String.valueOf(p.get("level")).toUpperCase();
				final McpSchema.LoggingLevel level = McpSchema.LoggingLevel.valueOf(levelStr);
				client.setLoggingLevel(level);
				yield Map.of();
			}
			case "roots/list" -> Map.of("roots", new ArrayList<>(state.roots()));
			default -> throw new UnknownMethodException("Unknown JSON-RPC method: " + relay.method());
		};
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(final Object params) {
		if (params == null) {
			return Map.of();
		}
		if (params instanceof Map<?, ?> m) {
			return (Map<String, Object>) m;
		}
		return this.objectMapper.convertValue(params, Map.class);
	}

	private void applyRoots(final SessionState state, final List<RootDto> next) {
		// 1) update local view
		state.replaceRoots(next);
		// 2) sync with the loopback client and notify the server
		final McpSyncClient client = state.client();
		try {
			// Replace by remove + add — SDK has no bulk API.
			// We do not have a snapshot of previous roots from the client; rely on our
			// copy.
			for (final RootDto r : next) {
				if (r.uri() == null || r.uri().isBlank()) {
					continue;
				}
				client.addRoot(new McpSchema.Root(r.uri(), r.name()));
			}
			client.rootsListChangedNotification();
		}
		catch (final Exception ex) {
			LOG.debug("rootsListChangedNotification failed (likely no server roots capability): {}", ex.toString());
		}
	}

	/**
	 * Bridges a server-initiated {@code sampling/createMessage} request onto the SSE
	 * stream and blocks until the UI POSTs back to {@code /jsonrpc/respond}.
	 * @param sessionId the inspector session identifier
	 * @param holder the mutable forward-declaration of the session state
	 * @param request the sampling request from the MCP server
	 * @return the UI-supplied create-message result
	 */
	private McpSchema.CreateMessageResult handleSamplingRequest(final String sessionId, final SessionStateHolder holder,
			final McpSchema.CreateMessageRequest request) {
		return awaitServerResponse(sessionId, holder, "mcp:sampling-request", request,
				McpSchema.CreateMessageResult.class);
	}

	/**
	 * Bridges a server-initiated {@code elicitation/create} request onto the SSE stream
	 * and blocks until the UI POSTs back to {@code /jsonrpc/respond}.
	 * @param sessionId the inspector session identifier
	 * @param holder the mutable forward-declaration of the session state
	 * @param request the elicitation request from the MCP server
	 * @return the UI-supplied elicit result
	 */
	private McpSchema.ElicitResult handleElicitationRequest(final String sessionId, final SessionStateHolder holder,
			final McpSchema.ElicitRequest request) {
		return awaitServerResponse(sessionId, holder, "mcp:elicitation-request", request, McpSchema.ElicitResult.class);
	}

	private <T> T awaitServerResponse(final String sessionId, final SessionStateHolder holder, final String eventName,
			final Object params, final Class<T> resultType) {
		SessionState state = holder.state;
		// During client.initialize() the holder may not yet point to the session;
		// fall back to the registry once it's populated.
		if (state == null) {
			state = this.sessions.get(sessionId);
		}
		if (state == null) {
			throw new IllegalStateException("session not yet registered for " + eventName);
		}
		final PendingServerRequests pending = state.pendingServerRequests();
		final String requestId = UUID.randomUUID().toString();
		final var future = pending.create(requestId);
		final ObjectNode envelope = this.objectMapper.createObjectNode();
		envelope.put("requestId", requestId);
		envelope.set("params", this.objectMapper.valueToTree(params));
		this.emitterRegistry.broadcast(sessionId, eventName, envelope);
		final long timeoutSeconds = this.properties.getTimeouts().getServerRequest().toSeconds();
		try {
			final JsonNode reply = future.get(timeoutSeconds, TimeUnit.SECONDS);
			return this.objectMapper.treeToValue(reply, resultType);
		}
		catch (final TimeoutException ex) {
			pending.completeExceptionally(requestId, ex);
			throw new RuntimeException("UI did not answer " + eventName + " within " + timeoutSeconds + "s", ex);
		}
		catch (final Exception ex) {
			throw new RuntimeException(eventName + " failed: " + ex.getMessage(), ex);
		}
	}

	private static String randomState() {
		final byte[] buf = new byte[24];
		SECURE_RANDOM.nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	private static Map<String, Object> jsonRpcError(final JsonRpcRelay relay, final int code, final String message) {
		final Map<String, Object> err = new LinkedHashMap<>();
		err.put("code", code);
		err.put("message", (message != null) ? message : "Inspector relay error");
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("jsonrpc", "2.0");
		body.put("id", (relay != null) ? relay.id() : null);
		body.put("error", err);
		return body;
	}

	/**
	 * Visible for tests / shutdown hooks.
	 * @return the live session map
	 */
	ConcurrentMap<String, SessionState> sessions() {
		return this.sessions;
	}

	/**
	 * Sentinel for {@link #dispatch(SessionState, JsonRpcRelay)} when the method is
	 * unknown.
	 */
	private static final class UnknownMethodException extends RuntimeException {

		UnknownMethodException(final String message) {
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

}
