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

package io.inspector.mcp.webflux.router;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.bootstrap.BootstrapHtmlRenderer;
import io.inspector.mcp.core.bootstrap.InspectorBootstrap;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapAssembler;
import io.inspector.mcp.core.client.ExternalStdioClientFactory;
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
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;

/**
 * Reactive HTTP handlers for the inspector REST endpoints. Mirrors the WebMVC controller
 * surface. Blocking MCP SDK calls are off-loaded to {@link Schedulers#boundedElastic()}.
 *
 * @author Artem Simeshin
 */
public class InspectorHandler {

	private static final Logger LOG = LoggerFactory.getLogger(InspectorHandler.class);

	/**
	 * Shared CSPRNG for OAuth {@code state} generation. {@link SecureRandom} is
	 * thread-safe and expensive to seed, so a single instance is reused.
	 */
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private static final String INDEX_RESOURCE = "mcp-inspector-bundle/index.html";

	/** Server namespace of the separate actuator web server, when one is started. */
	private static final String MANAGEMENT_NAMESPACE = "management";

	/** Inspector mount point assumed when no properties bean is wired. */
	private static final String DEFAULT_INSPECTOR_PATH = "/mcp-inspector";

	private final TransportDetector transportDetector;

	private final LoopbackMcpClientFactory loopbackFactory;

	private final ExternalStdioClientFactory externalStdioFactory;

	private final InspectorAuthTokenProvider tokenProvider;

	private final JsonMapper objectMapper;

	private final InspectorOAuthClient oauthClient;

	private final McpInspectorProperties properties;

	private final InspectorBootstrapAssembler bootstrapAssembler;

	private final BootstrapHtmlRenderer bootstrapHtmlRenderer;

	private final ConcurrentMap<String, SessionContext> sessions = new ConcurrentHashMap<>();

	private final AtomicInteger listeningPort = new AtomicInteger(-1);

	public InspectorHandler(final TransportDetector transportDetector, final LoopbackMcpClientFactory loopbackFactory,
			final ExternalStdioClientFactory externalStdioFactory, final InspectorAuthTokenProvider tokenProvider,
			final JsonMapper objectMapper) {
		this(transportDetector, loopbackFactory, externalStdioFactory, tokenProvider, objectMapper, null, null, null,
				null);
	}

	public InspectorHandler(final TransportDetector transportDetector, final LoopbackMcpClientFactory loopbackFactory,
			final ExternalStdioClientFactory externalStdioFactory, final InspectorAuthTokenProvider tokenProvider,
			final JsonMapper objectMapper, final InspectorOAuthClient oauthClient) {
		this(transportDetector, loopbackFactory, externalStdioFactory, tokenProvider, objectMapper, oauthClient, null,
				null, null);
	}

	public InspectorHandler(final TransportDetector transportDetector, final LoopbackMcpClientFactory loopbackFactory,
			final ExternalStdioClientFactory externalStdioFactory, final InspectorAuthTokenProvider tokenProvider,
			final JsonMapper objectMapper, final InspectorOAuthClient oauthClient,
			final McpInspectorProperties properties) {
		this(transportDetector, loopbackFactory, externalStdioFactory, tokenProvider, objectMapper, oauthClient,
				properties, null, null);
	}

	public InspectorHandler(final TransportDetector transportDetector, final LoopbackMcpClientFactory loopbackFactory,
			final ExternalStdioClientFactory externalStdioFactory, final InspectorAuthTokenProvider tokenProvider,
			final JsonMapper objectMapper, final InspectorOAuthClient oauthClient,
			final McpInspectorProperties properties, final InspectorBootstrapAssembler bootstrapAssembler,
			final BootstrapHtmlRenderer bootstrapHtmlRenderer) {
		this.transportDetector = transportDetector;
		this.loopbackFactory = loopbackFactory;
		this.externalStdioFactory = externalStdioFactory;
		this.tokenProvider = tokenProvider;
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.oauthClient = (oauthClient != null) ? oauthClient : new InspectorOAuthClient();
		this.properties = properties;
		this.bootstrapAssembler = bootstrapAssembler;
		this.bootstrapHtmlRenderer = bootstrapHtmlRenderer;
	}

	private McpInspectorProperties.Timeouts resolveTimeouts() {
		return (this.properties != null) ? this.properties.getTimeouts() : new McpInspectorProperties.Timeouts();
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

	/**
	 * Returns the templated {@code index.html} with the typed bootstrap payload injected
	 * as a single inline {@code <script>} block. The injection is performed by
	 * {@link BootstrapHtmlRenderer} so that both stacks render identical bytes.
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the HTML response
	 */
	public Mono<ServerResponse> index(final ServerRequest request) {
		final String prefix = contextPath(request);
		return Mono.fromCallable(() -> renderIndexHtml(prefix))
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap((html) -> ServerResponse.ok()
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
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the JSON bootstrap config response
	 */
	public Mono<ServerResponse> serveConfig(final ServerRequest request) {
		final String prefix = contextPath(request);
		return Mono.fromSupplier(() -> {
			if (this.bootstrapAssembler == null) {
				throw new IllegalStateException("InspectorBootstrapAssembler not wired into InspectorHandler");
			}
			return this.bootstrapAssembler.assemble(prefix);
		})
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap((bootstrap) -> ServerResponse.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.cacheControl(CacheControl.noStore())
				.bodyValue(bootstrap));
	}

	/**
	 * Returns {@link ConfigDto} with the detected transport, stack and auth token.
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the config JSON response
	 */
	public Mono<ServerResponse> config(final ServerRequest request) {
		return Mono.fromSupplier(() -> {
			final DetectedTransport detected = this.transportDetector.detect();
			return new ConfigDto(detected.type().name(), detected.endpoint(), detected.messageEndpoint(),
					detected.stack(), null, null, this.tokenProvider.token(), Map.of());
		}).flatMap((dto) -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(dto));
	}

	/**
	 * Creates a new inspector session: builds an MCP client (loopback or external stdio),
	 * calls {@code initialize()}, registers the session, returns the session id.
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the session info JSON response
	 */
	public Mono<ServerResponse> connect(final ServerRequest request) {
		return request.bodyToMono(ConnectRequest.class)
			.defaultIfEmpty(new ConnectRequest(null))
			.flatMap((body) -> Mono.fromCallable(() -> openSession(body)).subscribeOn(Schedulers.boundedElastic()))
			.flatMap((result) -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(result))
			.onErrorResume(this::errorResponse);
	}

	/**
	 * Relays a JSON-RPC envelope from the UI to the session's MCP client.
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the JSON-RPC response envelope
	 */
	public Mono<ServerResponse> jsonRpc(final ServerRequest request) {
		final String sessionId = resolveSessionId(request);
		final String resolvedSessionId = sessionId;

		return request.bodyToMono(JsonRpcRelay.class).flatMap((relay) -> {
			if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
				return errorBody(relay.id(), -32600, "missing session id");
			}
			final SessionContext ctx = this.sessions.get(resolvedSessionId);
			if (ctx == null) {
				return errorBody(relay.id(), -32600, "unknown session: " + resolvedSessionId);
			}
			return Mono.fromCallable(() -> dispatch(ctx, relay))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume((ex) -> Mono.just(jsonRpcError(relay.id(), ex)));
		}).flatMap((body) -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body));
	}

	/**
	 * Streams server-side notifications for a session via Server-Sent Events.
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the SSE stream response
	 */
	public Mono<ServerResponse> events(final ServerRequest request) {
		final String sessionId = request.queryParam("session").or(() -> request.queryParam("sessionId")).orElse(null);
		if (sessionId == null || sessionId.isBlank()) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "missing 'session' query parameter"));
		}
		final SessionContext ctx = this.sessions.get(sessionId);
		if (ctx == null) {
			return ServerResponse.notFound().build();
		}
		return ServerResponse.ok()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.body(BodyInserters.fromServerSentEvents(ctx.sink().asFlux()));
	}

	/**
	 * Terminates a session: closes the MCP client and completes the SSE sink.
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting a 204 no-content response
	 */
	public Mono<ServerResponse> deleteSession(final ServerRequest request) {
		final String id = request.pathVariable("id");
		final SessionContext ctx = this.sessions.remove(id);
		if (ctx != null) {
			ctx.closeQuietly();
		}
		return ServerResponse.noContent().build();
	}

	/**
	 * Returns the roots currently advertised by the inspector to the backing MCP server.
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the roots JSON response
	 */
	public Mono<ServerResponse> getRoots(final ServerRequest request) {
		final String sessionId = resolveSessionId(request);
		final SessionContext ctx = (sessionId != null) ? this.sessions.get(sessionId) : null;
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
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the updated roots JSON response
	 */
	public Mono<ServerResponse> putRoots(final ServerRequest request) {
		final String sessionId = resolveSessionId(request);
		final SessionContext ctx = (sessionId != null) ? this.sessions.get(sessionId) : null;
		if (ctx == null) {
			return ServerResponse.status(404).bodyValue(Map.of("error", "unknown session: " + sessionId));
		}
		return request.bodyToMono(RootsDto.class)
			.defaultIfEmpty(new RootsDto(List.of()))
			.flatMap((body) -> Mono.fromCallable(() -> {
				applyRoots(ctx, (body.roots() != null) ? body.roots() : List.of());
				final ObjectNode payload = this.objectMapper.createObjectNode();
				payload.put("count", ctx.roots().size());
				broadcast(ctx, "mcp:roots-list-changed", payload);
				return new RootsDto(new ArrayList<>(ctx.roots()));
			}).subscribeOn(Schedulers.boundedElastic()))
			.flatMap((r) -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(r));
	}

	/**
	 * Completes a previously suspended server-to-client request
	 * ({@code sampling/createMessage}, {@code elicitation/create}) with the UI-supplied
	 * result.
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the completion result response
	 */
	/**
	 * Reads the request body as a Jackson 2 {@link JsonNode}.
	 *
	 * <p>
	 * Spring Framework 7 routes {@code bodyToMono(JsonNode.class)} through the Jackson 3
	 * reactive codec, which cannot bind to the Jackson 2 {@code com.fasterxml} node tree
	 * the inspector is built on. Decode the raw text and parse it with the inspector's
	 * own Jackson 2 {@link JsonMapper} instead.
	 * @param request the incoming request
	 * @return the parsed body, or an empty {@link Mono} when the request has no body
	 */
	private Mono<JsonNode> readJsonBody(final ServerRequest request) {
		return request.bodyToMono(String.class)
			.flatMap((raw) -> Mono.fromCallable(() -> this.objectMapper.readTree(raw)));
	}

	public Mono<ServerResponse> respond(final ServerRequest request) {
		final String sessionId = resolveSessionId(request);
		final String requestId = request.queryParam("requestId").orElse(null);
		final SessionContext ctx = (sessionId != null) ? this.sessions.get(sessionId) : null;
		if (ctx == null) {
			return ServerResponse.status(404).bodyValue(Map.of("error", "unknown session: " + sessionId));
		}
		if (requestId == null || requestId.isBlank()) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "missing requestId"));
		}
		return readJsonBody(request).defaultIfEmpty(this.objectMapper.createObjectNode()).flatMap((body) -> {
			final boolean completed;
			if (body.has("error")) {
				completed = ctx.pendingServerRequests()
					.completeExceptionally(requestId,
							new RuntimeException(body.path("error").path("message").asText("rejected")));
			}
			else {
				final JsonNode result = body.has("result") ? body.get("result") : body;
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
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the OAuth initiation response
	 */
	public Mono<ServerResponse> oauthInitiate(final ServerRequest request) {
		final String sessionId = resolveSessionId(request);
		final SessionContext ctx = (sessionId != null) ? this.sessions.get(sessionId) : null;
		if (ctx == null) {
			return ServerResponse.status(404).bodyValue(Map.of("error", "unknown session: " + sessionId));
		}
		return request.bodyToMono(OAuthInitiateRequest.class).flatMap((req) -> {
			final String stateToken = randomState();
			ctx.oauthState(stateToken);
			ctx.oauthClientId(req.clientId());
			ctx.oauthRedirectUri(req.redirectUri());
			ctx.oauthTokenEndpoint(req.tokenEndpoint());
			final String url = this.oauthClient.buildAuthUrl(req.authorizationEndpoint(), req.clientId(),
					req.redirectUri(), req.scope(), stateToken, req.codeChallenge());
			return ServerResponse.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(new OAuthInitiateResponse(url, stateToken));
		});
	}

	/**
	 * Exchanges the OAuth authorization {@code code} for a token using the previously
	 * stored token endpoint, validates {@code state}, and caches the token on the
	 * session.
	 * @param request the incoming server request
	 * @return a {@link Mono} emitting the OAuth token response
	 */
	public Mono<ServerResponse> oauthCallback(final ServerRequest request) {
		final String sessionId = resolveSessionId(request);
		final SessionContext ctx = (sessionId != null) ? this.sessions.get(sessionId) : null;
		if (ctx == null) {
			return ServerResponse.status(404).bodyValue(Map.of("error", "unknown session: " + sessionId));
		}
		final String code = request.queryParam("code").orElse(null);
		final String stateToken = request.queryParam("state").orElse(null);
		final String codeVerifier = request.queryParam("codeVerifier").orElse(null);
		if (code == null || stateToken == null) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "missing code or state"));
		}
		if (ctx.oauthState() == null || !ctx.oauthState().equals(stateToken)) {
			return ServerResponse.badRequest().bodyValue(Map.of("error", "state mismatch"));
		}
		return Mono
			.fromCallable(() -> this.oauthClient.exchangeCode(ctx.oauthTokenEndpoint(), ctx.oauthClientId(), code,
					ctx.oauthRedirectUri(), codeVerifier))
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap((token) -> {
				ctx.oauthToken(token);
				return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(token);
			})
			.onErrorResume((ex) -> {
				LOG.warn("OAuth token exchange failed", ex);
				return ServerResponse.status(502)
					.bodyValue(Map.of("error",
							(ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName()));
			});
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	private Map<String, Object> openSession(final ConnectRequest body) {
		final String sessionId = UUID.randomUUID().toString();
		final SessionContextHolder holder = new SessionContextHolder();
		final InspectorClientHandlers handlers = new InspectorClientHandlers(
				(req) -> handleSamplingRequest(sessionId, holder, req),
				(req) -> handleElicitationRequest(sessionId, holder, req),
				(req) -> handleUrlElicitationRequest(sessionId, holder, req));

		final McpSyncClient client = (body != null && body.externalCommand() != null
				&& !body.externalCommand().isBlank())
						? this.externalStdioFactory.forCommand(splitCommand(body.externalCommand()), Map.of())
						: buildLoopbackClient(handlers);

		client.initialize();

		final Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer(256, false);
		final SessionContext ctx = new SessionContext(client, sink);
		holder.ctx = ctx;
		this.sessions.put(sessionId, ctx);

		final McpSchema.Implementation info = client.getServerInfo();
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("sessionId", sessionId);
		result.put("serverName", (info != null) ? info.name() : null);
		result.put("serverVersion", (info != null) ? info.version() : null);
		return result;
	}

	private McpSyncClient buildLoopbackClient(final InspectorClientHandlers handlers) {
		final DetectedTransport transport = this.transportDetector.detect();
		final int port = this.listeningPort.get();
		if (port <= 0) {
			throw new IllegalStateException("inspector cannot determine listening port yet");
		}
		final String host = "127.0.0.1";
		return switch (transport.type()) {
			case SSE -> this.loopbackFactory.forSse(host, port, transport.endpoint(), handlers);
			case STREAMABLE -> this.loopbackFactory.forStreamable(host, port, transport.endpoint(), handlers);
			case STATELESS -> this.loopbackFactory.forStateless(host, port, transport.endpoint(), handlers);
			case STDIO_NO_HTTP -> throw new IllegalStateException(
					"loopback connect not supported for STDIO_NO_HTTP transport — use externalCommand");
			case UNKNOWN -> throw new IllegalStateException(
					"could not auto-detect MCP transport — set spring.ai.mcp.server.protocol");
		};
	}

	private Map<String, Object> dispatch(final SessionContext ctx, final JsonRpcRelay relay) {
		final McpSyncClient client = ctx.client();
		final String method = (relay.method() != null) ? relay.method() : "";
		final Object result = switch (method) {
			case "initialize" -> client.getCurrentInitializationResult();
			case "ping" -> client.ping();
			case "tools/list" -> client.listTools();
			case "tools/call" ->
				client.callTool(this.objectMapper.convertValue(relay.params(), McpSchema.CallToolRequest.class));
			case "resources/list" -> client.listResources();
			case "resources/read" -> client
				.readResource(this.objectMapper.convertValue(relay.params(), McpSchema.ReadResourceRequest.class));
			case "resources/templates/list" -> client.listResourceTemplates();
			case "prompts/list" -> client.listPrompts();
			case "prompts/get" ->
				client.getPrompt(this.objectMapper.convertValue(relay.params(), McpSchema.GetPromptRequest.class));
			case "roots/list" -> Map.of("roots", new ArrayList<>(ctx.roots()));
			default -> throw new UnsupportedOperationException("method not supported by inspector relay: " + method);
		};
		final Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", relay.id());
		envelope.put("result", result);
		return envelope;
	}

	private void applyRoots(final SessionContext ctx, final List<RootDto> next) {
		ctx.replaceRoots(next);
		final McpSyncClient client = ctx.client();
		try {
			for (final RootDto r : next) {
				if (r.uri() == null || r.uri().isBlank()) {
					continue;
				}
				client.addRoot(new McpSchema.Root(r.uri(), r.name()));
			}
			client.rootsListChangedNotification();
		}
		catch (final Exception ex) {
			LOG.debug("rootsListChangedNotification failed: {}", ex.toString());
		}
	}

	private McpSchema.CreateMessageResult handleSamplingRequest(final String sessionId,
			final SessionContextHolder holder, final McpSchema.CreateMessageRequest request) {
		return awaitServerResponse(sessionId, holder, "mcp:sampling-request", request,
				McpSchema.CreateMessageResult.class);
	}

	private McpSchema.ElicitResult handleElicitationRequest(final String sessionId, final SessionContextHolder holder,
			final McpSchema.ElicitRequest request) {
		return awaitServerResponse(sessionId, holder, "mcp:elicitation-request", request, McpSchema.ElicitResult.class);
	}

	/**
	 * Bridges a server-initiated url-mode {@code elicitation/create} request onto the SSE
	 * stream and blocks until the UI POSTs back to the respond endpoint.
	 * @param sessionId the inspector session identifier
	 * @param holder the mutable forward-declaration of the session context
	 * @param request the url elicitation request from the MCP server
	 * @return the UI-supplied elicit result
	 */
	private McpSchema.ElicitResult handleUrlElicitationRequest(final String sessionId,
			final SessionContextHolder holder, final McpSchema.ElicitUrlRequest request) {
		return awaitServerResponse(sessionId, holder, "mcp:elicitation-request", request, McpSchema.ElicitResult.class);
	}

	private <T> T awaitServerResponse(final String sessionId, final SessionContextHolder holder, final String eventName,
			final Object params, final Class<T> resultType) {
		SessionContext ctx = holder.ctx;
		if (ctx == null) {
			ctx = this.sessions.get(sessionId);
		}
		if (ctx == null) {
			throw new IllegalStateException("session not yet registered for " + eventName);
		}
		final PendingServerRequests pending = ctx.pendingServerRequests();
		final String requestId = UUID.randomUUID().toString();
		final var future = pending.create(requestId);
		final ObjectNode envelope = this.objectMapper.createObjectNode();
		envelope.put("requestId", requestId);
		envelope.set("params", this.objectMapper.valueToTree(params));
		broadcast(ctx, eventName, envelope);
		final long timeoutSeconds = resolveTimeouts().getServerRequest().toSeconds();
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

	private void broadcast(final SessionContext ctx, final String eventName, final Object payload) {
		try {
			final String json = this.objectMapper.writeValueAsString(payload);
			ctx.sink().tryEmitNext(ServerSentEvent.<String>builder().event(eventName).data(json).build());
		}
		catch (final Exception ex) {
			LOG.debug("Failed to emit SSE event {}: {}", eventName, ex.toString());
		}
	}

	private static String resolveSessionId(final ServerRequest request) {
		String sessionId = request.headers().firstHeader("X-Inspector-Session");
		if (sessionId == null || sessionId.isBlank()) {
			sessionId = request.queryParam("sessionId").or(() -> request.queryParam("session")).orElse(null);
		}
		return sessionId;
	}

	private Map<String, Object> jsonRpcError(final Object id, final Throwable ex) {
		final Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", -32000);
		error.put("message", (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName());
		final Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", id);
		envelope.put("error", error);
		return envelope;
	}

	private Mono<Map<String, Object>> errorBody(final Object id, final int code, final String message) {
		final Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", code);
		error.put("message", message);
		final Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", id);
		envelope.put("error", error);
		return Mono.just(envelope);
	}

	private Mono<ServerResponse> errorResponse(final Throwable ex) {
		final Map<String, Object> body = Map.of("error",
				(ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName());
		return ServerResponse.status(500).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
	}

	private static String contextPath(final ServerRequest request) {
		// A root-mounted application reports "" ; a "/" would yield protocol-relative
		// "//..." links once concatenated with the inspector path.
		final String contextPath = request.requestPath().contextPath().value();
		return "/".equals(contextPath) ? "" : contextPath;
	}

	private String renderIndexHtml(final String prefix) throws IOException {
		// Pin the resource to *this class's* classloader rather than the thread-context
		// classloader. In test environments that boot multiple Spring Boot apps in the
		// same JVM (servlet + reactive runs back-to-back), the TCCL can be a stopped
		// Tomcat WebappClassLoader, which raises "Illegal access: this web application
		// instance has been stopped already" when reading classpath resources.
		final ClassPathResource resource = new ClassPathResource(INDEX_RESOURCE,
				InspectorHandler.class.getClassLoader());
		if (!resource.exists()) {
			return "<!doctype html><meta charset=\"utf-8\"><title>MCP Inspector</title>"
					+ "<p>UI assets not bundled. Build the <code>spring-ai-mcp-inspector-ui</code> module.</p>";
		}
		try (InputStream in = resource.getInputStream()) {
			final String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (this.bootstrapAssembler == null || this.bootstrapHtmlRenderer == null) {
				// Defensive fallback for test setups that wire only the legacy
				// constructor. Returns the raw template untouched — the SPA will
				// still load, just without the inlined bootstrap script.
				return template;
			}
			final InspectorBootstrap bootstrap = this.bootstrapAssembler.assemble(prefix);
			final String inspectorPath = (this.properties != null) ? this.properties.getPath() : DEFAULT_INSPECTOR_PATH;
			return this.bootstrapHtmlRenderer.renderIndexHtml(template, bootstrap, prefix + inspectorPath);
		}
	}

	private static java.util.List<String> splitCommand(final String command) {
		final String trimmed = command.trim();
		if (trimmed.isEmpty()) {
			return java.util.List.of();
		}
		// simple whitespace tokenizer; inspector "external target" commands are expected
		// to be plain executables + args
		return java.util.Arrays.asList(trimmed.split("\\s+"));
	}

	private static String randomState() {
		final byte[] buf = new byte[24];
		SECURE_RANDOM.nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	// package-private for tests
	boolean hasSession(final String id) {
		return this.sessions.containsKey(id);
	}

	int listeningPort() {
		return this.listeningPort.get();
	}

	@SuppressWarnings("unused")
	private static TransportType[] unusedKeepImport() {
		return TransportType.values();
	}

	/**
	 * Mutable forward-declaration of the per-session context so the sampling /
	 * elicitation handlers (registered before {@code initialize()}) can locate the
	 * matching {@link SessionContext} once it exists.
	 */
	private static final class SessionContextHolder {

		volatile SessionContext ctx;

	}

}
