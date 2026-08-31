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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records MCP client-side traffic events into the {@link TimelineService}.
 *
 * <p>
 * Builds {@link TimelineEvent} instances with client-side metadata (client name,
 * transport type, direction, method, JSON-RPC id, correlation) and appends them to the
 * shared timeline service. Handles request&rarr;response correlation via the
 * {@code mcpc:<clientName>:<jsonrpc-id>} scheme.
 *
 * @author Artem Simeshin
 */
public final class McpClientTrafficRecorder {

	private static final Logger LOG = LoggerFactory.getLogger(McpClientTrafficRecorder.class);

	/** Maximum number of pending request&rarr;response correlations before eviction. */
	static final int MAX_PENDING_CORRELATIONS = 1000;

	/** Payload key for MDC correlation id linking to APP_LOG events. */
	static final String MDC_CORRELATION_KEY = "mdcCorrelationId";

	/** Prefix for client-side correlation ids. */
	static final String CORRELATION_PREFIX = "mcpc:";

	private final TimelineService timelineService;

	/**
	 * Pending request&rarr;response correlations, keyed by
	 * {@code mcpc:<clientName>:<jsonrpc-id>}. When the map exceeds capacity the eldest
	 * entry is evicted.
	 */
	private final LinkedHashMap<String, PendingCorrelation> requestCorrelations;

	/**
	 * Guards all access to {@link #requestCorrelations}, which is a non-thread-safe
	 * {@link LinkedHashMap}.
	 */
	private final Object pendingLock = new Object();

	/**
	 * Creates a new client traffic recorder.
	 * @param timelineService the backing store (must not be {@code null})
	 */
	public McpClientTrafficRecorder(final TimelineService timelineService) {
		if (timelineService == null) {
			throw new IllegalArgumentException("timelineService must not be null");
		}
		this.timelineService = timelineService;
		this.requestCorrelations = new LinkedHashMap<>() {
			@Override
			protected boolean removeEldestEntry(final Map.Entry<String, PendingCorrelation> eldest) {
				return size() > MAX_PENDING_CORRELATIONS;
			}
		};
	}

	/**
	 * Records a client-initiated JSON-RPC request (outbound, client&rarr;server).
	 * @param clientName the configured client name (must not be {@code null})
	 * @param transportType the transport type label (must not be {@code null})
	 * @param request the JSON-RPC request (must not be {@code null})
	 */
	public void recordClientRequest(final String clientName, final String transportType, final JSONRPCRequest request) {
		if (request == null) {
			return;
		}
		final Object id = request.id();
		final String correlationId = (id != null) ? correlationId(clientName, id) : UUID.randomUUID().toString();
		if (id != null) {
			storePending(correlationId, Instant.now());
		}
		final ObjectNode payload = buildPayload(clientName, transportType, "client->server", request.method(), id,
				null);
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload);
		this.timelineService.append(event);
	}

	/**
	 * Records a client-initiated JSON-RPC notification (outbound, client&rarr;server).
	 * @param clientName the configured client name (must not be {@code null})
	 * @param transportType the transport type label (must not be {@code null})
	 * @param notification the JSON-RPC notification (must not be {@code null})
	 */
	public void recordClientNotification(final String clientName, final String transportType,
			final JSONRPCNotification notification) {
		if (notification == null) {
			return;
		}
		final String correlationId = UUID.randomUUID().toString();
		final ObjectNode payload = buildPayload(clientName, transportType, "client->server", notification.method(),
				null, null);
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_NOTIFICATION, Instant.now(), payload);
		this.timelineService.append(event);
	}

	/**
	 * Records a server-initiated JSON-RPC response (inbound, server&rarr;client) matching
	 * a previously recorded client request.
	 * @param clientName the configured client name (must not be {@code null})
	 * @param transportType the transport type label (must not be {@code null})
	 * @param response the JSON-RPC response (must not be {@code null})
	 */
	public void recordClientResponse(final String clientName, final String transportType,
			final JSONRPCResponse response) {
		if (response == null) {
			return;
		}
		final Object id = response.id();
		final String corrKey = (id != null) ? correlationId(clientName, id) : null;
		final PendingCorrelation pending;
		synchronized (this.pendingLock) {
			pending = (corrKey != null) ? this.requestCorrelations.remove(corrKey) : null;
		}
		final String correlationId = (pending != null) ? pending.correlationId()
				: fallbackCorrelationId(clientName, id);
		final long latency = (pending != null) ? pending.elapsed() : 0L;
		final ObjectNode payload = buildPayload(clientName, transportType, "server->client", null, id,
				(response.error() != null) ? response.error().message() : null);
		if (pending != null) {
			payload.put("latencyMs", latency);
		}
		if (pending == null && id != null) {
			payload.put("orphan", true);
		}
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_RESPONSE, Instant.now(), payload);
		this.timelineService.append(event);
	}

	/**
	 * Records a server-initiated JSON-RPC request (inbound, server&rarr;client), e.g.
	 * sampling/elicitation/roots/list.
	 * @param clientName the configured client name (must not be {@code null})
	 * @param transportType the transport type label (must not be {@code null})
	 * @param request the JSON-RPC request (must not be {@code null})
	 */
	public void recordServerRequest(final String clientName, final String transportType, final JSONRPCRequest request) {
		if (request == null) {
			return;
		}
		final Object id = request.id();
		final String correlationId = (id != null) ? correlationId(clientName, "srv:" + id)
				: UUID.randomUUID().toString();
		if (id != null) {
			storePending(correlationId, Instant.now());
		}
		final ObjectNode payload = buildPayload(clientName, transportType, "server->client", request.method(), id,
				null);
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload);
		this.timelineService.append(event);
	}

	/**
	 * Records a server-initiated JSON-RPC notification (inbound, server&rarr;client),
	 * e.g. notifications/message, progress, tools/list_changed.
	 * @param clientName the configured client name (must not be {@code null})
	 * @param transportType the transport type label (must not be {@code null})
	 * @param notification the JSON-RPC notification (must not be {@code null})
	 */
	public void recordServerNotification(final String clientName, final String transportType,
			final JSONRPCNotification notification) {
		if (notification == null) {
			return;
		}
		final String correlationId = UUID.randomUUID().toString();
		final ObjectNode payload = buildPayload(clientName, transportType, "server->client", notification.method(),
				null, null);
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.MCP_JSONRPC_NOTIFICATION, Instant.now(), payload);
		this.timelineService.append(event);
	}

	/**
	 * Returns the number of pending request&rarr;response correlations awaiting their
	 * matching response. Exposed for testing.
	 * @return the number of pending correlations
	 */
	public int pendingCorrelations() {
		synchronized (this.pendingLock) {
			return this.requestCorrelations.size();
		}
	}

	/**
	 * Drops every pending correlation recorded for {@code clientName}. Invoked when a
	 * client session closes so abandoned requests leave no residue.
	 * @param clientName the client name whose correlations to clear (may be {@code null})
	 */
	public void clearClient(final String clientName) {
		if (clientName == null) {
			return;
		}
		final String prefix = CORRELATION_PREFIX + clientName + ":";
		synchronized (this.pendingLock) {
			this.requestCorrelations.keySet().removeIf((key) -> key.startsWith(prefix));
		}
	}

	private static String correlationId(final String clientName, final Object requestId) {
		return CORRELATION_PREFIX + clientName + ":" + requestId;
	}

	private static String fallbackCorrelationId(final String clientName, final Object requestId) {
		if (requestId != null) {
			return CORRELATION_PREFIX + clientName + ":" + requestId;
		}
		return UUID.randomUUID().toString();
	}

	private static ObjectNode buildPayload(final String clientName, final String transportType, final String direction,
			final String method, final Object id, final String error) {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("endpoint", "client");
		payload.put("clientName", clientName);
		payload.put("transport", transportType);
		payload.put("direction", direction);
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

	private void storePending(final String correlationId, final Instant timestamp) {
		synchronized (this.pendingLock) {
			this.requestCorrelations.put(correlationId, new PendingCorrelation(correlationId, timestamp));
		}
	}

	/**
	 * A pending request correlation.
	 *
	 * @param correlationId the correlation id
	 * @param timestamp the instant when the request was recorded
	 */
	record PendingCorrelation(String correlationId, Instant timestamp) {

		/**
		 * Returns the elapsed time in milliseconds since this pending entry was created.
		 * @return elapsed milliseconds
		 */
		long elapsed() {
			return java.time.Duration.between(this.timestamp, Instant.now()).toMillis();
		}

	}

}
