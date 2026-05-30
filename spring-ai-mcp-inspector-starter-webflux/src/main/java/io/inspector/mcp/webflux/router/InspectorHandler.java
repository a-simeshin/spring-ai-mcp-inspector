/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux.router;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.bootstrap.BootstrapHtmlRenderer;
import io.inspector.mcp.core.bootstrap.InspectorBootstrap;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapAssembler;
import io.inspector.mcp.core.client.ExternalStdioClientFactory;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.client.InspectorClientHandlers;
import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.core.client.PendingServerRequests;
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
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive HTTP handlers for the inspector REST endpoints. Mirrors the WebMVC controller
 * surface. Blocking MCP SDK calls are off-loaded to {@link Schedulers#boundedElastic()}.
 */
public class InspectorHandler {

	private static final Logger LOG = LoggerFactory.getLogger(InspectorHandler.class);

	private static final String INDEX_RESOURCE = "mcp-inspector-bundle/index.html";

	/**
	 * Maximum time we block a transport thread waiting for the UI to answer a server
	 * request.
	 */
	static final long SERVER_REQUEST_TIMEOUT_SECONDS = 120L;

	private final TransportDetector transportDetector;

	private final LoopbackMcpClientFactory loopbackFactory;

	private final ExternalStdioClientFactory externalStdioFactory;

	private final InspectorAuthTokenProvider tokenProvider;

	private final ObjectMapper objectMapper;

	private final InspectorOAuthClient oauthClient;

	private final McpInspectorProperties properties;

	private final InspectorBootstrapAssembler bootstrapAssembler;

	private final BootstrapHtmlRenderer bootstrapHtmlRenderer;

	private final ConcurrentMap<String, SessionContext> sessions = new ConcurrentHashMap<>();

	private final AtomicInteger listeningPort = new AtomicInteger(-1);

	public InspectorHandler(TransportDetector transportDetector, LoopbackMcpClientFactory loopbackFactory,
			ExternalStdioClientFactory externalStdioFactory, InspectorAuthTokenProvider tokenProvider,
			ObjectMapper objectMapper) {
		this(transportDetector, loopbackFactory, externalStdioFactory, tokenProvider, objectMapper, null, null, null,
				null);
	}

	public InspectorHandler(TransportDetector transportDetector, LoopbackMcpClientFactory loopbackFactory,
			ExternalStdioClientFactory externalStdioFactory, InspectorAuthTokenProvider tokenProvider,
			ObjectMapper objectMapper, InspectorOAuthClient oauthClient) {
		this(transportDetector, loopbackFactory, externalStdioFactory, tokenProvider, objectMapper, oauthClient, null,
				null, null);
	}

	public InspectorHandler(TransportDetector transportDetector, LoopbackMcpClientFactory loopbackFactory,
			ExternalStdioClientFactory externalStdioFactory, InspectorAuthTokenProvider tokenProvider,
			ObjectMapper objectMapper, InspectorOAuthClient oauthClient, McpInspectorProperties properties) {
		this(transportDetector, loopbackFactory, externalStdioFactory, tokenProvider, objectMapper, oauthClient,
				properties, null, null);
	}

	public InspectorHandler(TransportDetector transportDetector, LoopbackMcpClientFactory loopbackFactory,
			ExternalStdioClientFactory externalStdioFactory, InspectorAuthTokenProvider tokenProvider,
			ObjectMapper objectMapper, InspectorOAuthClient oauthClient, McpInspectorProperties properties,
			InspectorBootstrapAssembler bootstrapAssembler, BootstrapHtmlRenderer bootstrapHtmlRenderer) {
		this.transportDetector = transportDetector;
		this.loopbackFactory = loopbackFactory;
		this.externalStdioFactory = externalStdioFactory;
		this.tokenProvider = tokenProvider;
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
		this.oauthClient = (oauthClient != null) ? oauthClient : new InspectorOAuthClient();
		this.properties = properties;
		this.bootstrapAssembler = bootstrapAssembler;
		this.bootstrapHtmlRenderer = bootstrapHtmlRenderer;
	}

	@EventListener
	public void onWebServerStarted(WebServerInitializedEvent event) {
		listeningPort.set(event.getWebServer().getPort());
	}

	/**
	 * Returns the templated {@code index.html} with the typed bootstrap payload injected
	 * as a single inline {@code <script>} block. The injection is performed by
	 * {@link BootstrapHtmlRenderer} so that both stacks render identical bytes.
	 */
	public Mono<ServerResponse> index(ServerRequest request) {
		return Mono.fromCallable(this::renderIndexHtml)
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap(html -> ServerResponse.ok()
				.contentType(MediaType.TEXT_HTML)
				.cacheControl(CacheControl.noStore())
				.bodyValue(html));
	}

	/**
	 * Serves the typed {@link InspectorBootstrap} payload as JSON at
	 * {@code ${spring.ai.mcp.inspector.path}/config}. The same payload that is inlined
	 * into {@code index.html} is exposed here so client-side regression tests and
	 * external probes can read it independently.
	 *
	 * <p>
	 * The endpoint sits at {@code ${path}/config} (outside the {@code ${path}/api/}
	 * prefix) so it is intentionally not behind the inspector auth filter — it has to
	 * deliver the auth token to the SPA before the SPA can authenticate any subsequent
	 * API call.
	 */
	public Mono<ServerResponse> serveConfig(ServerRequest request) {
		return Mono.fromSupplier(() -> {
			if (bootstrapAssembler == null) {
				throw new IllegalStateException("InspectorBootstrapAssembler not wired into InspectorHandler");
			}
			return bootstrapAssembler.assemble();
		})
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap(bootstrap -> ServerResponse.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.cacheControl(CacheControl.noStore())
				.bodyValue(bootstrap));
	}

	/** Returns {@link ConfigDto} with the detected transport, stack and auth token. */
	public Mono<ServerResponse> config(ServerRequest request) {
		return Mono.fromSupplier(() -> {
			DetectedTransport detected = transportDetector.detect();
			return new ConfigDto(detected.type().name(), detected.endpoint(), detected.messageEndpoint(),
					detected.stack(), null, null, tokenProvider.token(), Map.of());
		}).flatMap(dto -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(dto));
	}

	/**
	 * Creates a new inspector session: builds an MCP client (loopback or external stdio),
	 * calls {@code initialize()}, registers the session, returns the session id.
	 */
	public Mono<ServerResponse> connect(ServerRequest request) {
		return request.bodyToMono(ConnectRequest.class)
			.defaultIfEmpty(new ConnectRequest(null))
			.flatMap(body -> Mono.fromCallable(() -> openSession(body)).subscribeOn(Schedulers.boundedElastic()))
			.flatMap(result -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(result))
			.onErrorResume(this::errorResponse);
	}

	/** Relays a JSON-RPC envelope from the UI to the session's MCP client. */
	public Mono<ServerResponse> jsonRpc(ServerRequest request) {
		String sessionId = resolveSessionId(request);
		final String resolvedSessionId = sessionId;

		return request.bodyToMono(JsonRpcRelay.class).flatMap(relay -> {
			if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
				return errorBody(relay.id(), -32600, "missing session id");
			}
			SessionContext ctx = sessions.get(resolvedSessionId);
			if (ctx == null) {
				return errorBody(relay.id(), -32600, "unknown session: " + resolvedSessionId);
			}
			return Mono.fromCallable(() -> dispatch(ctx, relay))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(ex -> Mono.just(jsonRpcError(relay.id(), ex)));
		}).flatMap(body -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body));
	}

	/** Streams server-side notifications for a session via Server-Sent Events. */
	public Mono<ServerResponse> events(ServerRequest request) {
		String sessionId = request.queryParam("session").or(() -> request.queryParam("sessionId")).orElse(null);
		if (sessionId == null || sessionId.isBlank()) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "missing 'session' query parameter"));
		}
		SessionContext ctx = sessions.get(sessionId);
		if (ctx == null) {
			return ServerResponse.notFound().build();
		}
		return ServerResponse.ok()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.body(BodyInserters.fromServerSentEvents(ctx.sink().asFlux()));
	}

	/** Terminates a session: closes the MCP client and completes the SSE sink. */
	public Mono<ServerResponse> deleteSession(ServerRequest request) {
		String id = request.pathVariable("id");
		SessionContext ctx = sessions.remove(id);
		if (ctx != null) {
			ctx.closeQuietly();
		}
		return ServerResponse.noContent().build();
	}

	/**
	 * Returns the roots currently advertised by the inspector to the backing MCP server.
	 */
	public Mono<ServerResponse> getRoots(ServerRequest request) {
		String sessionId = resolveSessionId(request);
		SessionContext ctx = (sessionId != null) ? sessions.get(sessionId) : null;
		if (ctx == null) {
			return ServerResponse.status(404).bodyValue(Map.of("error", "unknown session: " + sessionId));
		}
		return ServerResponse.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(new RootsDto(new ArrayList<>(ctx.roots())));
	}

	/**
	 * Replaces the session's root list and notifies the backing MCP server via
	 * {@code notifications/roots/list_changed}.
	 */
	public Mono<ServerResponse> putRoots(ServerRequest request) {
		String sessionId = resolveSessionId(request);
		SessionContext ctx = (sessionId != null) ? sessions.get(sessionId) : null;
		if (ctx == null) {
			return ServerResponse.status(404).bodyValue(Map.of("error", "unknown session: " + sessionId));
		}
		return request.bodyToMono(RootsDto.class)
			.defaultIfEmpty(new RootsDto(List.of()))
			.flatMap(body -> Mono.fromCallable(() -> {
				applyRoots(ctx, body.roots() != null ? body.roots() : List.of());
				ObjectNode payload = objectMapper.createObjectNode();
				payload.put("count", ctx.roots().size());
				broadcast(ctx, "mcp:roots-list-changed", payload);
				return new RootsDto(new ArrayList<>(ctx.roots()));
			}).subscribeOn(Schedulers.boundedElastic()))
			.flatMap(r -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(r));
	}

	/**
	 * Completes a previously suspended server-to-client request
	 * ({@code sampling/createMessage}, {@code elicitation/create}) with the UI-supplied
	 * result.
	 */
	public Mono<ServerResponse> respond(ServerRequest request) {
		String sessionId = resolveSessionId(request);
		String requestId = request.queryParam("requestId").orElse(null);
		SessionContext ctx = (sessionId != null) ? sessions.get(sessionId) : null;
		if (ctx == null) {
			return ServerResponse.status(404).bodyValue(Map.of("error", "unknown session: " + sessionId));
		}
		if (requestId == null || requestId.isBlank()) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "missing requestId"));
		}
		return request.bodyToMono(JsonNode.class).defaultIfEmpty(objectMapper.createObjectNode()).flatMap(body -> {
			boolean completed;
			if (body.has("error")) {
				completed = ctx.pendingServerRequests()
					.completeExceptionally(requestId,
							new RuntimeException(body.path("error").path("message").asText("rejected")));
			}
			else {
				JsonNode result = body.has("result") ? body.get("result") : body;
				completed = ctx.pendingServerRequests().complete(requestId, result);
			}
			if (!completed) {
				return ServerResponse.status(410).bodyValue(Map.of("error", "no pending request: " + requestId));
			}
			return ServerResponse.ok().bodyValue(Map.of("ok", true));
		});
	}

	/**
	 * Generates a random {@code state} token, builds the IdP authorization URL and stores
	 * the OAuth context on the session for the matching callback.
	 */
	public Mono<ServerResponse> oauthInitiate(ServerRequest request) {
		String sessionId = resolveSessionId(request);
		SessionContext ctx = (sessionId != null) ? sessions.get(sessionId) : null;
		if (ctx == null) {
			return ServerResponse.status(404).bodyValue(Map.of("error", "unknown session: " + sessionId));
		}
		return request.bodyToMono(OAuthInitiateRequest.class).flatMap(req -> {
			String stateToken = randomState();
			ctx.oauthState(stateToken);
			ctx.oauthClientId(req.clientId());
			ctx.oauthRedirectUri(req.redirectUri());
			ctx.oauthTokenEndpoint(req.tokenEndpoint());
			String url = oauthClient.buildAuthUrl(req.authorizationEndpoint(), req.clientId(), req.redirectUri(),
					req.scope(), stateToken, req.codeChallenge());
			return ServerResponse.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(new OAuthInitiateResponse(url, stateToken));
		});
	}

	/**
	 * Exchanges the OAuth authorization {@code code} for a token using the previously
	 * stored token endpoint, validates {@code state}, and caches the token on the
	 * session.
	 */
	public Mono<ServerResponse> oauthCallback(ServerRequest request) {
		String sessionId = resolveSessionId(request);
		SessionContext ctx = (sessionId != null) ? sessions.get(sessionId) : null;
		if (ctx == null) {
			return ServerResponse.status(404).bodyValue(Map.of("error", "unknown session: " + sessionId));
		}
		String code = request.queryParam("code").orElse(null);
		String stateToken = request.queryParam("state").orElse(null);
		String codeVerifier = request.queryParam("codeVerifier").orElse(null);
		if (code == null || stateToken == null) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "missing code or state"));
		}
		if (ctx.oauthState() == null || !ctx.oauthState().equals(stateToken)) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "state mismatch"));
		}
		return Mono
			.fromCallable(() -> oauthClient.exchangeCode(ctx.oauthTokenEndpoint(), ctx.oauthClientId(), code,
					ctx.oauthRedirectUri(), codeVerifier))
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap(token -> {
				ctx.oauthToken(token);
				return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(token);
			})
			.onErrorResume(ex -> {
				LOG.warn("OAuth token exchange failed", ex);
				return ServerResponse.status(502)
					.bodyValue(
							Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
			});
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	private Map<String, Object> openSession(ConnectRequest body) {
		String sessionId = UUID.randomUUID().toString();
		SessionContextHolder holder = new SessionContextHolder();
		InspectorClientHandlers handlers = new InspectorClientHandlers(
				req -> handleSamplingRequest(sessionId, holder, req),
				req -> handleElicitationRequest(sessionId, holder, req));

		McpSyncClient client = (body != null && body.externalCommand() != null && !body.externalCommand().isBlank())
				? externalStdioFactory.forCommand(splitCommand(body.externalCommand()), Map.of())
				: buildLoopbackClient(handlers);

		client.initialize();

		Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer(256, false);
		SessionContext ctx = new SessionContext(client, sink);
		holder.ctx = ctx;
		sessions.put(sessionId, ctx);

		McpSchema.Implementation info = client.getServerInfo();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("sessionId", sessionId);
		result.put("serverName", (info != null) ? info.name() : null);
		result.put("serverVersion", (info != null) ? info.version() : null);
		return result;
	}

	private McpSyncClient buildLoopbackClient(InspectorClientHandlers handlers) {
		DetectedTransport transport = transportDetector.detect();
		int port = listeningPort.get();
		if (port <= 0) {
			throw new IllegalStateException("inspector cannot determine listening port yet");
		}
		String host = "127.0.0.1";
		return switch (transport.type()) {
			case SSE -> loopbackFactory.forSse(host, port, null, transport.messageEndpoint(), handlers);
			case STREAMABLE -> loopbackFactory.forStreamable(host, port, transport.endpoint(), handlers);
			case STATELESS -> loopbackFactory.forStateless(host, port, transport.endpoint(), handlers);
			case STDIO_NO_HTTP -> throw new IllegalStateException(
					"loopback connect not supported for STDIO_NO_HTTP transport — use externalCommand");
			case UNKNOWN -> throw new IllegalStateException(
					"could not auto-detect MCP transport — set spring.ai.mcp.server.protocol");
		};
	}

	private Map<String, Object> dispatch(SessionContext ctx, JsonRpcRelay relay) {
		McpSyncClient client = ctx.client();
		String method = (relay.method() != null) ? relay.method() : "";
		Object result = switch (method) {
			case "initialize" -> client.getCurrentInitializationResult();
			case "ping" -> client.ping();
			case "tools/list" -> client.listTools();
			case "tools/call" ->
				client.callTool(objectMapper.convertValue(relay.params(), McpSchema.CallToolRequest.class));
			case "resources/list" -> client.listResources();
			case "resources/read" ->
				client.readResource(objectMapper.convertValue(relay.params(), McpSchema.ReadResourceRequest.class));
			case "resources/templates/list" -> client.listResourceTemplates();
			case "prompts/list" -> client.listPrompts();
			case "prompts/get" ->
				client.getPrompt(objectMapper.convertValue(relay.params(), McpSchema.GetPromptRequest.class));
			case "roots/list" -> Map.of("roots", new ArrayList<>(ctx.roots()));
			default -> throw new UnsupportedOperationException("method not supported by inspector relay: " + method);
		};
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", relay.id());
		envelope.put("result", result);
		return envelope;
	}

	private void applyRoots(SessionContext ctx, List<RootDto> next) {
		ctx.replaceRoots(next);
		McpSyncClient client = ctx.client();
		try {
			for (RootDto r : next) {
				if (r.uri() == null || r.uri().isBlank()) {
					continue;
				}
				client.addRoot(new McpSchema.Root(r.uri(), r.name()));
			}
			client.rootsListChangedNotification();
		}
		catch (Exception ex) {
			LOG.debug("rootsListChangedNotification failed: {}", ex.toString());
		}
	}

	private McpSchema.CreateMessageResult handleSamplingRequest(String sessionId, SessionContextHolder holder,
			McpSchema.CreateMessageRequest request) {
		return awaitServerResponse(sessionId, holder, "mcp:sampling-request", request,
				McpSchema.CreateMessageResult.class);
	}

	private McpSchema.ElicitResult handleElicitationRequest(String sessionId, SessionContextHolder holder,
			McpSchema.ElicitRequest request) {
		return awaitServerResponse(sessionId, holder, "mcp:elicitation-request", request, McpSchema.ElicitResult.class);
	}

	private <T> T awaitServerResponse(String sessionId, SessionContextHolder holder, String eventName, Object params,
			Class<T> resultType) {
		SessionContext ctx = holder.ctx;
		if (ctx == null) {
			ctx = sessions.get(sessionId);
		}
		if (ctx == null) {
			throw new IllegalStateException("session not yet registered for " + eventName);
		}
		PendingServerRequests pending = ctx.pendingServerRequests();
		String requestId = UUID.randomUUID().toString();
		var future = pending.create(requestId);
		ObjectNode envelope = objectMapper.createObjectNode();
		envelope.put("requestId", requestId);
		envelope.set("params", objectMapper.valueToTree(params));
		broadcast(ctx, eventName, envelope);
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

	private void broadcast(SessionContext ctx, String eventName, Object payload) {
		try {
			String json = objectMapper.writeValueAsString(payload);
			ctx.sink().tryEmitNext(ServerSentEvent.<String>builder().event(eventName).data(json).build());
		}
		catch (Exception ex) {
			LOG.debug("Failed to emit SSE event {}: {}", eventName, ex.toString());
		}
	}

	private static String resolveSessionId(ServerRequest request) {
		String sessionId = request.headers().firstHeader("X-Inspector-Session");
		if (sessionId == null || sessionId.isBlank()) {
			sessionId = request.queryParam("sessionId").or(() -> request.queryParam("session")).orElse(null);
		}
		return sessionId;
	}

	private Map<String, Object> jsonRpcError(Object id, Throwable ex) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", -32000);
		error.put("message", (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName());
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", id);
		envelope.put("error", error);
		return envelope;
	}

	private Mono<Map<String, Object>> errorBody(Object id, int code, String message) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", code);
		error.put("message", message);
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", id);
		envelope.put("error", error);
		return Mono.just(envelope);
	}

	private Mono<ServerResponse> errorResponse(Throwable ex) {
		Map<String, Object> body = Map.of("error",
				(ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName());
		return ServerResponse.status(500).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
	}

	private String renderIndexHtml() throws IOException {
		// Pin the resource to *this class's* classloader rather than the thread-context
		// classloader. In test environments that boot multiple Spring Boot apps in the
		// same JVM (servlet + reactive runs back-to-back), the TCCL can be a stopped
		// Tomcat WebappClassLoader, which raises "Illegal access: this web application
		// instance has been stopped already" when reading classpath resources.
		ClassPathResource resource = new ClassPathResource(INDEX_RESOURCE, InspectorHandler.class.getClassLoader());
		if (!resource.exists()) {
			return "<!doctype html><meta charset=\"utf-8\"><title>MCP Inspector</title>"
					+ "<p>UI assets not bundled. Build the <code>spring-ai-mcp-inspector-ui</code> module.</p>";
		}
		try (InputStream in = resource.getInputStream()) {
			String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (bootstrapAssembler == null || bootstrapHtmlRenderer == null) {
				// Defensive fallback for test setups that wire only the legacy
				// constructor. Returns the raw template untouched — the SPA will
				// still load, just without the inlined bootstrap script.
				return template;
			}
			InspectorBootstrap bootstrap = bootstrapAssembler.assemble();
			return bootstrapHtmlRenderer.renderIndexHtml(template, bootstrap);
		}
	}

	private static java.util.List<String> splitCommand(String command) {
		String trimmed = command.trim();
		if (trimmed.isEmpty()) {
			return java.util.List.of();
		}
		// simple whitespace tokenizer; inspector "external target" commands are expected
		// to be plain executables + args
		return java.util.Arrays.asList(trimmed.split("\\s+"));
	}

	private static String randomState() {
		byte[] buf = new byte[24];
		new java.security.SecureRandom().nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	// package-private for tests
	boolean hasSession(String id) {
		return sessions.containsKey(id);
	}

	int listeningPort() {
		return listeningPort.get();
	}

	/**
	 * Mutable forward-declaration of the per-session context so the sampling /
	 * elicitation handlers (registered before {@code initialize()}) can locate the
	 * matching {@link SessionContext} once it exists.
	 */
	private static final class SessionContextHolder {

		volatile SessionContext ctx;

	}

	@SuppressWarnings("unused")
	private static TransportType[] unusedKeepImport() {
		return TransportType.values();
	}

}
