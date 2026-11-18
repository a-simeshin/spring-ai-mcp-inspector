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

package io.inspector.mcp.core.timeline;

import java.time.Instant;
import java.util.UUID;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records MCP client-side traffic events into the {@link TimelineService}.
 *
 * <p>
 * Builds {@link TimelineEvent} instances with client-side provenance in the payload
 * ({@code side=client}, client name, {@code direction} out|in from the client's
 * perspective, transport type and sanitized target) and appends them to the shared
 * timeline service. Sessions do not apply to client events, so {@code sessionId} stays
 * {@code null}.
 *
 * <p>
 * Request&rarr;response correlation mirrors {@link McpTrafficRecorder}: pending entries
 * are keyed by {@code mcpc:<clientName>:<jsonrpc-id>} with a fresh UUID generated per
 * request. Both directions of server-initiated exchanges are paired: an inbound
 * {@code sampling/createMessage} request and the client's outbound
 * {@code JSONRPCResponse} answer share one correlation id (stored under a
 * {@code srv:}-prefixed key so client and server ids cannot collide).
 *
 * @author Artem Simeshin
 */
public final class McpClientTrafficRecorder {

	/**
	 * Maximum number of pending request&rarr;response correlations before eviction. One
	 * shared budget across all clients using this recorder instance.
	 */
	static final int MAX_PENDING_CORRELATIONS = 1000;

	/** Prefix for client-side correlation ids. */
	static final String CORRELATION_PREFIX = "mcpc:";

	/** Direction label for traffic sent by the client (client&rarr;server). */
	static final String DIRECTION_OUT = "out";

	/** Direction label for traffic received by the client (server&rarr;client). */
	static final String DIRECTION_IN = "in";

	/** Value of the {@code side} payload field: always {@code client}. */
	static final String SIDE_CLIENT = "client";

	private final TimelineService timelineService;

	private final PendingCorrelationStore<String> requestCorrelations;

	/**
	 * Creates a new client traffic recorder.
	 * @param timelineService the backing store (must not be {@code null})
	 */
	public McpClientTrafficRecorder(final TimelineService timelineService) {
		if (timelineService == null) {
			throw new IllegalArgumentException("timelineService must not be null");
		}
		this.timelineService = timelineService;
		this.requestCorrelations = new PendingCorrelationStore<>(MAX_PENDING_CORRELATIONS);
	}

	/**
	 * Records a client-initiated JSON-RPC request (outbound, client&rarr;server).
	 * @param endpoint the client endpoint provenance (must not be {@code null})
	 * @param request the JSON-RPC request (must not be {@code null})
	 */
	public void recordClientRequest(final ClientEndpoint endpoint, final JSONRPCRequest request) {
		if (request == null) {
			return;
		}
		final Object id = request.id();
		final String correlationId = UUID.randomUUID().toString();
		if (id != null) {
			this.requestCorrelations.store(correlationId(endpoint.clientName(), id),
					new PendingCorrelationStore.PendingCorrelation(correlationId, Instant.now()));
		}
		final ObjectNode payload = buildPayload(endpoint, DIRECTION_OUT, request.method(), id, null);
		append(new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload));
	}

	/**
	 * Records a client-initiated JSON-RPC notification (outbound, client&rarr;server).
	 * @param endpoint the client endpoint provenance (must not be {@code null})
	 * @param notification the JSON-RPC notification (must not be {@code null})
	 */
	public void recordClientNotification(final ClientEndpoint endpoint, final JSONRPCNotification notification) {
		if (notification == null) {
			return;
		}
		final String correlationId = UUID.randomUUID().toString();
		final ObjectNode payload = buildPayload(endpoint, DIRECTION_OUT, notification.method(), null, null);
		append(new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_NOTIFICATION, Instant.now(), payload));
	}

	/**
	 * Records a server JSON-RPC response (inbound, server&rarr;client) answering a
	 * previously recorded client request.
	 * @param endpoint the client endpoint provenance (must not be {@code null})
	 * @param response the JSON-RPC response (must not be {@code null})
	 */
	public void recordClientResponse(final ClientEndpoint endpoint, final JSONRPCResponse response) {
		if (response == null) {
			return;
		}
		final Object id = response.id();
		final String key = (id != null) ? correlationId(endpoint.clientName(), id) : null;
		final PendingCorrelationStore.PendingCorrelation pending = (key != null) ? this.requestCorrelations.remove(key)
				: null;
		if (pending != null) {
			appendResponse(endpoint, DIRECTION_IN, response, id, pending);
			return;
		}
		// Orphan: no matching pending request (evicted or unknown id).
		appendOrphanResponse(endpoint, DIRECTION_IN, response, id);
	}

	/**
	 * Records an outbound JSON-RPC response (client&rarr;server) answering a
	 * server-initiated request (sampling/createMessage, elicitation/create, roots/list).
	 *
	 * <p>
	 * A client does send {@code JSONRPCResponse} outbound through
	 * {@code transport.sendMessage} when answering server-initiated requests, so this
	 * flow is recorded and paired with the inbound request: the lookup uses only the
	 * {@code srv:}-prefixed key, preventing id collisions when both sides use the same
	 * integer id.
	 * @param endpoint the client endpoint provenance (must not be {@code null})
	 * @param response the JSON-RPC response (must not be {@code null})
	 */
	public void recordOutboundResponse(final ClientEndpoint endpoint, final JSONRPCResponse response) {
		if (response == null) {
			return;
		}
		final Object id = response.id();
		final String srvKey = (id != null) ? correlationId(endpoint.clientName(), "srv:" + id) : null;
		final PendingCorrelationStore.PendingCorrelation pending = (srvKey != null)
				? this.requestCorrelations.remove(srvKey) : null;
		if (pending != null) {
			appendResponse(endpoint, DIRECTION_OUT, response, id, pending);
			return;
		}
		// Orphan: no matching server-initiated request was recorded.
		appendOrphanResponse(endpoint, DIRECTION_OUT, response, id);
	}

	/**
	 * Records a server-initiated JSON-RPC request (inbound, server&rarr;client), e.g.
	 * sampling/elicitation/roots/list.
	 * @param endpoint the client endpoint provenance (must not be {@code null})
	 * @param request the JSON-RPC request (must not be {@code null})
	 */
	public void recordServerRequest(final ClientEndpoint endpoint, final JSONRPCRequest request) {
		if (request == null) {
			return;
		}
		final Object id = request.id();
		final String correlationId = UUID.randomUUID().toString();
		if (id != null) {
			this.requestCorrelations.store(correlationId(endpoint.clientName(), "srv:" + id),
					new PendingCorrelationStore.PendingCorrelation(correlationId, Instant.now()));
		}
		final ObjectNode payload = buildPayload(endpoint, DIRECTION_IN, request.method(), id, null);
		append(new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload));
	}

	/**
	 * Records a server-initiated JSON-RPC notification (inbound, server&rarr;client),
	 * e.g. notifications/message, progress, tools/list_changed.
	 * @param endpoint the client endpoint provenance (must not be {@code null})
	 * @param notification the JSON-RPC notification (must not be {@code null})
	 */
	public void recordServerNotification(final ClientEndpoint endpoint, final JSONRPCNotification notification) {
		if (notification == null) {
			return;
		}
		final String correlationId = UUID.randomUUID().toString();
		final ObjectNode payload = buildPayload(endpoint, DIRECTION_IN, notification.method(), null, null);
		append(new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_NOTIFICATION, Instant.now(), payload));
	}

	/**
	 * Returns the number of pending request&rarr;response correlations awaiting their
	 * matching response. Exposed for testing.
	 * @return the number of pending correlations
	 */
	public int pendingCorrelations() {
		return this.requestCorrelations.size();
	}

	private void appendResponse(final ClientEndpoint endpoint, final String direction, final JSONRPCResponse response,
			final Object id, final PendingCorrelationStore.PendingCorrelation pending) {
		final ObjectNode payload = buildPayload(endpoint, direction, null, id, errorMessage(response));
		payload.put("latencyMs", pending.elapsed());
		append(new TimelineEvent(UUID.randomUUID().toString(), pending.correlationId(), null,
				TimelineEventType.MCP_JSONRPC_RESPONSE, Instant.now(), payload));
	}

	private void appendOrphanResponse(final ClientEndpoint endpoint, final String direction,
			final JSONRPCResponse response, final Object id) {
		final String correlationId = UUID.randomUUID().toString();
		final ObjectNode payload = buildPayload(endpoint, direction, null, id, errorMessage(response));
		if (id != null) {
			payload.put("orphan", true);
		}
		append(new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_RESPONSE, Instant.now(), payload));
	}

	private static String errorMessage(final JSONRPCResponse response) {
		return (response.error() != null) ? response.error().message() : null;
	}

	private static String correlationId(final String clientName, final Object requestId) {
		return CORRELATION_PREFIX + clientName + ":" + requestId;
	}

	private static ObjectNode buildPayload(final ClientEndpoint endpoint, final String direction, final String method,
			final Object id, final String error) {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("side", SIDE_CLIENT);
		payload.put("clientName", endpoint.clientName());
		payload.put("direction", direction);
		payload.put("transportType", endpoint.transportType());
		if (endpoint.target() != null) {
			payload.put("target", endpoint.target());
		}
		if (method != null) {
			payload.put("method", method);
		}
		if (id != null) {
			payload.put("id", String.valueOf(id));
		}
		if (error != null) {
			payload.put("error", error);
		}
		return payload;
	}

	private void append(final TimelineEvent event) {
		this.timelineService.append(event);
	}

	/**
	 * Provenance of one captured client connection: the configured client name, a
	 * transport type label and the sanitized connection target.
	 *
	 * @param clientName the MCP client name as configured in
	 * {@code spring.ai.mcp.client.*.connections.<name>}
	 * @param transportType a transport type label, e.g. {@code "stdio"}, {@code "sse"} or
	 * {@code "streamable-http"}
	 * @param target the sanitized target (URL without credentials, or a stdio command);
	 * may be {@code null} when not resolvable
	 */
	public record ClientEndpoint(String clientName, String transportType, String target) {
	}

}
