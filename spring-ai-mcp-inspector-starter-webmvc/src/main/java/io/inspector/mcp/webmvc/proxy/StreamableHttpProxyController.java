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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.spec.McpClientTransport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxyConnectFailure;
import io.inspector.mcp.core.proxy.ProxyConnectFailureException;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTargetResolver;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.task.TaskHandle;
import io.inspector.mcp.core.task.TaskNotCancelableException;
import io.inspector.mcp.core.task.TaskNotFoundException;
import io.inspector.mcp.core.task.TaskService;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;

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
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class StreamableHttpProxyController {

	private static final Logger LOG = LoggerFactory.getLogger(StreamableHttpProxyController.class);

	/**
	 * Error code of the structured connect-failure payload (see
	 * {@link #connectFailureResponse}).
	 */
	private static final String ERROR_CODE_MCP_CONNECT_FAILED = "MCP_CONNECT_FAILED";

	/** Replay buffer size for the {@code targetToBrowser} sink (per session). */
	private static final int REPLAY_BUFFER = 256;

	private final ProxySessionRegistry registry;

	private final ProxyTransportFactory transportFactory;

	private final McpProxy mcpProxy;

	private final JsonMapper objectMapper;

	private final McpInspectorProperties properties;

	private final InspectorServerPortHolder portHolder;

	private final ObjectProvider<TaskService> taskServiceProvider;

	public StreamableHttpProxyController(final ProxySessionRegistry registry,
			final ProxyTransportFactory transportFactory, final McpProxy mcpProxy, final JsonMapper objectMapper) {
		this(registry, transportFactory, mcpProxy, objectMapper, null, null);
	}

	public StreamableHttpProxyController(final ProxySessionRegistry registry,
			final ProxyTransportFactory transportFactory, final McpProxy mcpProxy, final JsonMapper objectMapper,
			final McpInspectorProperties properties) {
		this(registry, transportFactory, mcpProxy, objectMapper, properties, null);
	}

	public StreamableHttpProxyController(final ProxySessionRegistry registry,
			final ProxyTransportFactory transportFactory, final McpProxy mcpProxy, final JsonMapper objectMapper,
			final McpInspectorProperties properties, final InspectorServerPortHolder portHolder) {
		this.registry = registry;
		this.transportFactory = transportFactory;
		this.mcpProxy = mcpProxy;
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.properties = properties;
		this.portHolder = portHolder;
		this.taskServiceProvider = null;
	}

	/**
	 * Full constructor that also accepts an optional {@link TaskService} via an
	 * {@link ObjectProvider}. When no {@link TaskService} bean is available the
	 * controller falls through to the regular relay path for all methods.
	 * @param registry the proxy session registry
	 * @param transportFactory the proxy transport factory
	 * @param mcpProxy the MCP proxy relay
	 * @param objectMapper the JSON object mapper
	 * @param properties the inspector properties
	 * @param portHolder the server port holder
	 * @param taskServiceProvider provider for an optional {@link TaskService}
	 */
	@Autowired
	public StreamableHttpProxyController(final ProxySessionRegistry registry,
			final ProxyTransportFactory transportFactory, final McpProxy mcpProxy, final JsonMapper objectMapper,
			final McpInspectorProperties properties, final InspectorServerPortHolder portHolder,
			final ObjectProvider<TaskService> taskServiceProvider) {
		this.registry = registry;
		this.transportFactory = transportFactory;
		this.mcpProxy = mcpProxy;
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.properties = properties;
		this.portHolder = portHolder;
		this.taskServiceProvider = taskServiceProvider;
	}

	/**
	 * Lazily resolves the optional {@link TaskService} bean. Resolved on first use so the
	 * constructor stays trivially safe (no method calls that could throw).
	 * @return the resolved service, or {@code null} when unavailable
	 */
	private TaskService taskService() {
		return (this.taskServiceProvider != null) ? this.taskServiceProvider.getIfAvailable() : null;
	}

	private int loopbackPort() {
		return (this.portHolder != null) ? this.portHolder.port() : 8080;
	}

	private McpInspectorProperties.Timeouts resolveTimeouts() {
		return (this.properties != null) ? this.properties.getTimeouts() : new McpInspectorProperties.Timeouts();
	}

	@PostMapping(path = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Object> postMcp(
			@RequestHeader(value = ProxyConstants.MCP_SESSION_ID_HEADER, required = false) final String mcpSessionId,
			@RequestParam(value = "url", required = false) final String url, @RequestBody final JsonNode body) {
		if (mcpSessionId == null || mcpSessionId.isBlank()) {
			return openSessionAndForward(url, body);
		}
		final ProxySession session = this.registry.get(mcpSessionId);
		if (session == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body("unknown mcp-session-id: " + mcpSessionId);
		}
		return forwardOnExistingSession(session, body);
	}

	@GetMapping(path = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter getMcp(@RequestHeader(ProxyConstants.MCP_SESSION_ID_HEADER) final String mcpSessionId) {
		final SseEmitter emitter = new SseEmitter(resolveTimeouts().getSseSession().toMillis());
		final ProxySession session = this.registry.get(mcpSessionId);
		if (session == null) {
			return emitErrorAndComplete(emitter, "unknown mcp-session-id: " + mcpSessionId);
		}
		// takeUntilOther: close() cannot complete the sink while another thread owns it,
		// and an emitter left open is what makes graceful shutdown pay its full timeout.
		session.targetToBrowser()
			.asFlux()
			.takeUntilOther(session.closeSignal())
			.subscribe((frame) -> sendEvent(emitter, frame), emitter::completeWithError, emitter::complete);
		return emitter;
	}

	@DeleteMapping("/mcp")
	public ResponseEntity<Void> deleteMcp(
			@RequestHeader(ProxyConstants.MCP_SESSION_ID_HEADER) final String mcpSessionId) {
		final boolean removed = this.registry.removeAndClose(mcpSessionId);
		return removed ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	/**
	 * Brings up a fresh session and forwards the first frame. If the frame is a JSON-RPC
	 * request, blocks for up to the configured
	 * {@code spring.ai.mcp.inspector.timeouts.streamable-request} on the matching
	 * response. Otherwise returns 202.
	 * @param url the streamable-HTTP target URL
	 * @param body the JSON-RPC frame to forward
	 * @return the HTTP response entity
	 */
	private ResponseEntity<Object> openSessionAndForward(final String url, final JsonNode body) {
		// A blank/relative url is the WAF-safe same-origin default — the proxy resolves
		// it
		// to the loopback MCP endpoint server-side (see ProxyTargetResolver). Only an
		// explicit absolute url targets a non-loopback server.
		final ProxySession session;
		try {
			session = openSession(url);
		}
		catch (final ProxyConnectFailureException ex) {
			return connectFailureResponse(ex.failure());
		}
		return relayWithSessionHeader(session, body, true);
	}

	/**
	 * Maps a classified connect failure onto a non-2xx response: 504 Gateway Timeout for
	 * {@link ProxyConnectFailure.Reason#TIMEOUT}, 502 Bad Gateway for every other reason.
	 * The body carries a machine-readable {@code error} payload; stack traces and
	 * internal details stay server-side in the log.
	 * @param failure the classified connect failure (never {@code null})
	 * @return the HTTP response entity
	 */
	private static ResponseEntity<Object> connectFailureResponse(final ProxyConnectFailure failure) {
		final HttpStatus status = (failure.reason() == ProxyConnectFailure.Reason.TIMEOUT) ? HttpStatus.GATEWAY_TIMEOUT
				: HttpStatus.BAD_GATEWAY;
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("error", Map.of("code", ERROR_CODE_MCP_CONNECT_FAILED, "reason", failure.reason().wire(),
					"message", failure.message(), "retryable", Boolean.TRUE)));
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
	 * Forwards a frame to an already-open session. Same request/notification split as
	 * {@link #openSessionAndForward}, minus the session-id response header.
	 * @param session the live proxy session
	 * @param body the JSON-RPC frame to forward
	 * @return the HTTP response entity
	 */
	private ResponseEntity<Object> forwardOnExistingSession(final ProxySession session, final JsonNode body) {
		return relayWithSessionHeader(session, body, false);
	}

	/**
	 * Core relay path shared by the "new session" and "existing session" branches.
	 * @param session live proxy session
	 * @param body incoming JSON-RPC frame
	 * @param includeSessionHeader whether to attach the {@code mcp-session-id} header to
	 * the response (only on the first POST of a session)
	 * @return the HTTP response entity with the relayed result
	 */
	private ResponseEntity<Object> relayWithSessionHeader(final ProxySession session, final JsonNode body,
			final boolean includeSessionHeader) {
		final JsonNode idNode = extractRequestId(body);
		// Notification / response — no answer expected from upstream → 202 Accepted.
		if (idNode == null) {
			final Sinks.EmitResult emitResult = session.browserToTarget().tryEmitNext(body);
			if (emitResult.isFailure()) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body("emit failed: " + emitResult.name());
			}
			session.touch();
			final ResponseEntity.BodyBuilder builder = ResponseEntity.accepted();
			if (includeSessionHeader) {
				builder.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
			}
			return builder.build();
		}
		// Intercept tasks/get, tasks/list and tasks/cancel if a TaskService is available.
		if (taskService() != null) {
			final ResponseEntity<Object> taskResponse = tryHandleTaskMethod(idNode, body);
			if (taskResponse != null) {
				if (includeSessionHeader) {
					final ResponseEntity.BodyBuilder builder = ResponseEntity.status(taskResponse.getStatusCode())
						.contentType(MediaType.APPLICATION_JSON);
					builder.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
					return builder.body(taskResponse.getBody());
				}
				return taskResponse;
			}
		}
		// Request — pre-create the awaiter Mono (replay sink buffers, so even
		// if the upstream answer lands before .block() registers a subscriber,
		// the replay buffer still hands it over). We still emit AFTER preparing
		// the await pipeline so the read-side wiring exists first.
		// Eagerly subscribe to targetToBrowser via Sinks.One so the upstream
		// transport's connect() error (ECONNREFUSED/DNS/timeout) is captured
		// even when the await pipeline is subscribed to later by block().
		// Without this the replay sink may not carry the error to a late
		// subscriber, and the awaiter would block for the full
		// streamable-request timeout instead of failing fast.
		final Duration requestTimeout = resolveTimeouts().getStreamableRequest();
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
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("emit failed: " + emitResult.name());
		}
		session.touch();
		try {
			final JsonNode response = awaiter.block(requestTimeout);
			if (taskService() != null) {
				injectTasksCapability(response, body, includeSessionHeader);
			}
			final ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
			if (includeSessionHeader) {
				builder.header(ProxyConstants.MCP_SESSION_ID_HEADER, session.sessionId());
			}
			return builder.body(response);
		}
		catch (final RuntimeException ex) {
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(ex);
			LOG.warn("proxy[{}] await response failed ({}): {}", session.sessionId(), failure.reason().wire(),
					ex.toString());
			// A failed first POST (the initialize) never returned a session id to the
			// client, so the session is orphaned — tear it down instead of leaking it.
			if (includeSessionHeader) {
				this.registry.removeAndClose(session.sessionId());
			}
			return connectFailureResponse(failure);
		}
	}

	/**
	 * Allocates the {@link ProxySession} and kicks off the {@link McpProxy} pumps.
	 * @param url the streamable-HTTP target URL
	 * @return a live {@link ProxySession}
	 * @throws ProxyConnectFailureException if the transport could not be created
	 */
	private ProxySession openSession(final String url) {
		final String sessionId = UUID.randomUUID().toString();
		final String authorization = inboundAuthorization();
		final Map<String, String> customHeaders = inboundCustomHeaders();
		final McpClientTransport target;
		try {
			final URI resolved = ProxyTargetResolver.resolve(url, loopbackPort(), "/mcp");
			target = (authorization == null && customHeaders.isEmpty()) ? this.transportFactory.openStreamable(resolved)
					: this.transportFactory.openStreamable(resolved, authorization, customHeaders);
		}
		catch (final Exception ex) {
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(ex);
			LOG.warn("proxy[{}] upstream connect failed ({}): {}", sessionId, failure.reason().wire(), ex.toString());
			throw new ProxyConnectFailureException(failure, ex);
		}
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(REPLAY_BUFFER);
		final ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser);
		this.registry.put(session);
		this.mcpProxy.start(session).subscribe((ignored) -> {
		}, (err) -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			this.registry.removeAndClose(sessionId);
		});
		return session;
	}

	/**
	 * Extracts {@code id} from a JSON-RPC frame iff the frame is a <em>request</em> —
	 * i.e. it carries both a {@code method} and an {@code id}.
	 *
	 * <p>
	 * A server→client JSON-RPC <em>response</em> (has {@code result}/{@code error} and an
	 * {@code id} but <strong>no</strong> {@code method}) must take the fire-and-forget
	 * 202-Accepted path, not the relay-and-await path; otherwise the proxy would block
	 * waiting for an "answer" to a frame that is itself the answer. Returns {@code null}
	 * for responses and notifications.
	 * @param body the JSON-RPC frame
	 * @return the {@code id} node when {@code body} is a request, else {@code null}
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
	 * True iff {@code frame.id} equals {@code expected} (by JsonNode equality).
	 * @param frame the JSON-RPC frame to inspect
	 * @param expected the expected id value
	 * @return {@code true} if the frame id matches expected
	 */
	private static boolean matchesId(final JsonNode frame, final JsonNode expected) {
		if (frame == null || !frame.isObject()) {
			return false;
		}
		final JsonNode id = frame.get("id");
		return id != null && !id.isNull() && id.equals(expected);
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
	 * @return a response entity if the method was handled, or {@code null} to fall
	 * through to the regular relay path
	 */
	private ResponseEntity<Object> tryHandleTaskMethod(final JsonNode idNode, final JsonNode body) {
		final JsonNode methodNode = body.get("method");
		if (methodNode == null || !methodNode.isTextual()) {
			return null;
		}
		final String method = methodNode.asText();
		if ("tasks/get".equals(method)) {
			return handleTaskGet(idNode, body);
		}
		if ("tasks/list".equals(method)) {
			return handleTaskList(idNode);
		}
		if ("tasks/cancel".equals(method)) {
			return handleTaskCancel(idNode, body);
		}
		return null;
	}

	/**
	 * Handle a {@code tasks/get} request: look up the task and return its handle.
	 * @param idNode the JSON-RPC request id
	 * @param body the full request body
	 * @return a JSON-RPC response with the task handle, or a JSON-RPC error
	 */
	private ResponseEntity<Object> handleTaskGet(final JsonNode idNode, final JsonNode body) {
		final String taskId = extractTaskId(body);
		if (taskId == null) {
			return jsonRpcError(idNode, -32602, "Invalid params: missing or invalid taskId");
		}
		try {
			final TaskHandle handle = taskService().getTask(taskId);
			return jsonRpcResult(idNode, taskHandleToNode(handle));
		}
		catch (final TaskNotFoundException ex) {
			return jsonRpcError(idNode, -32602, "Failed to retrieve task: " + ex.getMessage());
		}
	}

	/**
	 * Handle a {@code tasks/list} request: return all currently tracked tasks.
	 * @param idNode the JSON-RPC request id
	 * @return a JSON-RPC response with a {@code tasks} array
	 */
	private ResponseEntity<Object> handleTaskList(final JsonNode idNode) {
		final java.util.List<TaskHandle> handles = taskService().listTasks();
		final ArrayNode tasksArray = JsonNodeFactory.instance.arrayNode(handles.size());
		for (final TaskHandle handle : handles) {
			tasksArray.add(taskHandleToNode(handle));
		}
		final ObjectNode result = JsonNodeFactory.instance.objectNode();
		result.set("tasks", tasksArray);
		return jsonRpcResult(idNode, result);
	}

	/**
	 * Handle a {@code tasks/cancel} request: cancel the task and return the updated
	 * handle.
	 * @param idNode the JSON-RPC request id
	 * @param body the full request body
	 * @return a JSON-RPC response with the cancelled task handle, or a JSON-RPC error
	 */
	private ResponseEntity<Object> handleTaskCancel(final JsonNode idNode, final JsonNode body) {
		final String taskId = extractTaskId(body);
		if (taskId == null) {
			return jsonRpcError(idNode, -32602, "Invalid params: missing or invalid taskId");
		}
		try {
			final TaskHandle handle = taskService().cancelTask(taskId);
			return jsonRpcResult(idNode, taskHandleToNode(handle));
		}
		catch (final TaskNotCancelableException ex) {
			return jsonRpcError(idNode, -32602, ex.getMessage());
		}
		catch (final TaskNotFoundException ex) {
			return jsonRpcError(idNode, -32602, "Failed to retrieve task: " + ex.getMessage());
		}
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
	 * @return a 200 OK response with the JSON-RPC envelope
	 */
	private ResponseEntity<Object> jsonRpcResult(final JsonNode idNode, final JsonNode resultNode) {
		final ObjectNode response = this.objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", idNode);
		response.set("result", resultNode);
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
	}

	/**
	 * Build a JSON-RPC error response with the given error code and message.
	 * @param idNode the request id
	 * @param code the JSON-RPC error code
	 * @param message the error message
	 * @return a 200 OK response with the JSON-RPC error envelope (per spec, errors are
	 * returned with HTTP 200)
	 */
	private ResponseEntity<Object> jsonRpcError(final JsonNode idNode, final int code, final String message) {
		final ObjectNode response = this.objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", idNode);
		final ObjectNode error = this.objectMapper.createObjectNode();
		error.put("code", code);
		error.put("message", message);
		response.set("error", error);
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
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
	 * Pushes a single message frame to the SSE emitter; logs/aborts on IO error.
	 * @param emitter the SSE emitter to send to
	 * @param frame the JSON-RPC frame to send
	 */
	private void sendEvent(final SseEmitter emitter, final JsonNode frame) {
		try {
			emitter.send(SseEmitter.event().name("message").data(this.objectMapper.writeValueAsString(frame)));
		}
		catch (final IOException ex) {
			emitter.completeWithError(ex);
		}
	}

	private SseEmitter emitErrorAndComplete(final SseEmitter emitter, final String message) {
		try {
			emitter.send(SseEmitter.event().name("error").data(message));
		}
		catch (final IOException ignored) {
			// best-effort error notice
		}
		emitter.complete();
		return emitter;
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
	 * through untouched.
	 * @param response the upstream initialize response JSON tree
	 * @param body the original JSON-RPC request body
	 * @param includeSessionHeader whether this is the first POST of a session
	 */
	private static void injectTasksCapability(final JsonNode response, final JsonNode body,
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
		final ObjectNode requests = JsonNodeFactory.instance.objectNode();
		final ObjectNode tools = JsonNodeFactory.instance.objectNode();
		tools.set("call", JsonNodeFactory.instance.objectNode());
		requests.set("tools", tools);
		tasks.set("requests", requests);
		capabilities.set("tasks", tasks);
	}

}
