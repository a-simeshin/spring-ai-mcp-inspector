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
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

/**
 * Intercepts JSON-RPC traffic flowing through the MCP proxy, records each message to the
 * {@link TimelineService}, and manages MDC-based correlation.
 *
 * <p>
 * For every new top-level request (a JSON-RPC request with an {@code id}), the recorder
 * generates a correlation ID, stores it in MDC under {@code mcp.correlationId}, and
 * records a {@link TimelineEventType#MCP_JSONRPC_REQUEST} event. When the matching
 * response arrives, it looks up the correlation ID by the session and message ID,
 * restores it in MDC, and records a {@link TimelineEventType#MCP_JSONRPC_RESPONSE} event.
 *
 * <p>
 * The correlation key is {@code (sessionId, requestId)} rather than {@code requestId}
 * alone, so two concurrent proxy sessions that each issue a request with JSON-RPC id
 * {@code 1} do not corrupt each other's correlation.
 *
 * <p>
 * Notifications (JSON-RPC messages without an {@code id}) are recorded as
 * {@link TimelineEventType#MCP_JSONRPC_NOTIFICATION} events. Streaming events are
 * recorded as {@link TimelineEventType#MCP_STREAM_EVENT} events.
 *
 * <p>
 * The correlation ID survives async processing because the mapping between message ID and
 * correlation ID is stored in a concurrent map, not in thread-local state. MDC is set
 * freshly on the thread doing the recording.
 *
 * @author Artem Simeshin
 */
public final class McpTrafficRecorder {

	private static final Logger LOG = LoggerFactory.getLogger(McpTrafficRecorder.class);

	/** MDC key where the correlation ID is stored. */
	static final String MDC_CORRELATION_ID = "mcp.correlationId";

	/** Maximum number of pending request→response correlations before eviction. */
	static final int MAX_PENDING_CORRELATIONS = 1000;

	private final TimelineService timelineService;

	private final ConcurrentHashMap<CorrelationKey, String> requestCorrelations = new ConcurrentHashMap<>();

	/**
	 * Creates a new traffic recorder.
	 * @param timelineService the backing store (must not be {@code null})
	 */
	public McpTrafficRecorder(final TimelineService timelineService) {
		if (timelineService == null) {
			throw new IllegalArgumentException("timelineService must not be null");
		}
		this.timelineService = timelineService;
	}

	/**
	 * Records an outbound (browser → target) JSON-RPC message.
	 *
	 * <p>
	 * For requests with an {@code id}, a UUID correlation ID is generated and stored for
	 * response matching. For notifications (no {@code id}), a correlation ID is generated
	 * per-event.
	 * @param sessionId the proxy session identifier (may be {@code null})
	 * @param message the typed JSON-RPC message to record (must not be {@code null})
	 * @param rawFrame the raw JSON frame as received (may be {@code null})
	 */
	public void recordOutbound(final String sessionId, final JSONRPCMessage message, final JsonNode rawFrame) {
		if (message == null) {
			return;
		}
		if (message instanceof JSONRPCRequest request) {
			final Object id = request.id();
			final String correlationId = UUID.randomUUID().toString();
			final String priorMdc = MDC.get(MDC_CORRELATION_ID);
			if (id != null) {
				final CorrelationKey key = new CorrelationKey(sessionId, id);
				if (this.requestCorrelations.size() >= MAX_PENDING_CORRELATIONS) {
					// Evict the oldest entry to keep the map bounded
					this.requestCorrelations.values().stream().findFirst().ifPresent(
							v -> this.requestCorrelations.values().remove(v));
				}
				this.requestCorrelations.put(key, correlationId);
			}
			try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_CORRELATION_ID, correlationId)) {
				final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, sessionId,
						TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), rawFrame);
				this.timelineService.append(event);
			}
			if (priorMdc != null) {
				MDC.put(MDC_CORRELATION_ID, priorMdc);
			}
		}
		else if (message instanceof JSONRPCNotification) {
			final String correlationId = UUID.randomUUID().toString();
			final String priorMdc = MDC.get(MDC_CORRELATION_ID);
			try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_CORRELATION_ID, correlationId)) {
				final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, sessionId,
						TimelineEventType.MCP_JSONRPC_NOTIFICATION, Instant.now(), rawFrame);
				this.timelineService.append(event);
			}
			if (priorMdc != null) {
				MDC.put(MDC_CORRELATION_ID, priorMdc);
			}
		}
		else {
			LOG.debug("traffic: unexpected outbound message type: {}", message.getClass().getSimpleName());
		}
	}

	/**
	 * Records an inbound (target → browser) JSON-RPC message.
	 *
	 * <p>
	 * For responses, the correlation ID is recovered from the stored mapping by the
	 * JSON-RPC message ID, then cleaned up.
	 * @param sessionId the proxy session identifier (may be {@code null})
	 * @param message the typed JSON-RPC message to record (must not be {@code null})
	 * @param rawFrame the raw JSON frame as serialised for the browser (may be
	 * {@code null})
	 */
	public void recordInbound(final String sessionId, final JSONRPCMessage message, final JsonNode rawFrame) {
		if (message == null) {
			return;
		}
		if (message instanceof JSONRPCResponse response) {
			final Object id = response.id();
			final String correlationId = (id != null)
					? this.requestCorrelations.remove(new CorrelationKey(sessionId, id)) : null;
			if (correlationId == null) {
				// No matching request recorded (e.g. server-initiated response),
				// or the event was evicted. Fallback to a fresh ID.
				LOG.debug("traffic: no correlation for response id={}", id);
			}
			final String effectiveCorrelationId = (correlationId != null) ? correlationId
					: UUID.randomUUID().toString();
			final String priorMdc = MDC.get(MDC_CORRELATION_ID);
			try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_CORRELATION_ID, effectiveCorrelationId)) {
				final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), effectiveCorrelationId,
						sessionId, TimelineEventType.MCP_JSONRPC_RESPONSE, Instant.now(), rawFrame);
				this.timelineService.append(event);
			}
			if (priorMdc != null) {
				MDC.put(MDC_CORRELATION_ID, priorMdc);
			}
		}
		else if (message instanceof JSONRPCNotification) {
			final String correlationId = UUID.randomUUID().toString();
			final String priorMdc = MDC.get(MDC_CORRELATION_ID);
			try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_CORRELATION_ID, correlationId)) {
				final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, sessionId,
						TimelineEventType.MCP_JSONRPC_NOTIFICATION, Instant.now(), rawFrame);
				this.timelineService.append(event);
			}
			if (priorMdc != null) {
				MDC.put(MDC_CORRELATION_ID, priorMdc);
			}
		}
		else {
			LOG.debug("traffic: unexpected inbound message type: {}", message.getClass().getSimpleName());
		}
	}

	/**
	 * Records a stream event (e.g. an SSE chunk from a streamable-HTTP transport).
	 * @param sessionId the proxy session identifier (may be {@code null})
	 * @param payload the stream event payload (may be {@code null})
	 */
	public void recordStreamEvent(final String sessionId, final JsonNode payload) {
		final String correlationId = UUID.randomUUID().toString();
		final String priorMdc = MDC.get(MDC_CORRELATION_ID);
		try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_CORRELATION_ID, correlationId)) {
			final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, sessionId,
					TimelineEventType.MCP_STREAM_EVENT, Instant.now(), payload);
			this.timelineService.append(event);
		}
		if (priorMdc != null) {
			MDC.put(MDC_CORRELATION_ID, priorMdc);
		}
	}

	/**
	 * Returns the number of pending request→response correlations awaiting their matching
	 * response. Exposed for testing.
	 * @return the number of pending correlations
	 */
	public int pendingCorrelations() {
		return this.requestCorrelations.size();
	}

	/**
	 * A session-scoped correlation key. Uses {@code (sessionId, requestId)} so that two
	 * proxy sessions with the same JSON-RPC id do not collide.
	 *
	 * @param sessionId the proxy session identifier, normalised to empty when
	 * {@code null}
	 * @param requestId the JSON-RPC message id
	 */
	record CorrelationKey(String sessionId, Object requestId) {

		CorrelationKey {
			// Normalise null sessionId to empty string so the map key is consistent
			sessionId = (sessionId != null) ? sessionId : "";
		}

	}

}
