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

public final class McpTrafficRecorder {

	private static final Logger LOG = LoggerFactory.getLogger(McpTrafficRecorder.class);

	/** MDC key where the correlation ID is stored. */
	static final String MDC_CORRELATION_ID = "mcp.correlationId";

	/** Maximum number of pending request→response correlations before eviction. */
	static final int MAX_PENDING_CORRELATIONS = 1000;

	private final TimelineService timelineService;

	/**
	 * Pending request→response correlations, bounded LRU with thread-safe access.
	 */
	private final PendingCorrelationStore<CorrelationKey> requestCorrelations;

	/**
	 * Pending progress-token → correlation mappings, session-scoped and TTL-free (cleared
	 * on response/close).
	 */
	private final ConcurrentHashMap<String, String> progressCorrelations = new ConcurrentHashMap<>();

	/**
	 * Creates a new traffic recorder.
	 * @param timelineService the backing store (must not be {@code null})
	 */
	public McpTrafficRecorder(final TimelineService timelineService) {
		if (timelineService == null) {
			throw new IllegalArgumentException("timelineService must not be null");
		}
		this.timelineService = timelineService;
		this.requestCorrelations = new PendingCorrelationStore<>(MAX_PENDING_CORRELATIONS, (key, pending) -> {
			if (pending.progressToken() != null) {
				McpTrafficRecorder.this.progressCorrelations.remove(keyOf(key.sessionId(), pending.progressToken()));
			}
		});
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
			if (id != null) {
				storePending(new CorrelationKey(sessionId, id), correlationId, progressTokenOf(rawFrame));
			}
			final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, sessionId,
					TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), rawFrame);
			appendWithMdc(correlationId, event);
		}
		else if (message instanceof JSONRPCNotification) {
			final String correlationId = UUID.randomUUID().toString();
			final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, sessionId,
					TimelineEventType.MCP_JSONRPC_NOTIFICATION, Instant.now(), rawFrame);
			appendWithMdc(correlationId, event);
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
			final CorrelationKey key = (id != null) ? new CorrelationKey(sessionId, id) : null;
			final PendingCorrelationStore.PendingCorrelation pending = (key != null)
					? this.requestCorrelations.remove(key) : null;
			if (pending != null) {
				evictProgress(key, pending);
			}
			final String correlationId = (pending != null) ? pending.correlationId() : null;
			if (correlationId == null) {
				// No matching request recorded (e.g. server-initiated response),
				// or the event was evicted. Fallback to a fresh ID.
				LOG.debug("traffic: no correlation for response id={}", id);
			}
			final String effectiveCorrelationId = (correlationId != null) ? correlationId
					: UUID.randomUUID().toString();
			final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), effectiveCorrelationId,
					sessionId, TimelineEventType.MCP_JSONRPC_RESPONSE, Instant.now(), rawFrame);
			appendWithMdc(effectiveCorrelationId, event);
		}
		else if (message instanceof JSONRPCNotification notification) {
			// Server-initiated progress frames are the streaming events of an in-flight
			// call: route them through recordStreamEvent with the originating call's
			// correlation when the progress token matches. Any other notification is a
			// plain MCP_JSONRPC_NOTIFICATION.
			if ("notifications/progress".equals(notification.method())) {
				final String token = progressTokenOf(rawFrame);
				final String correlationId = (token != null) ? lookupProgressCorrelation(sessionId, token) : null;
				recordStreamEvent(sessionId, correlationId, rawFrame);
				return;
			}
			final String correlationId = UUID.randomUUID().toString();
			final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, sessionId,
					TimelineEventType.MCP_JSONRPC_NOTIFICATION, Instant.now(), rawFrame);
			appendWithMdc(correlationId, event);
		}
		else {
			LOG.debug("traffic: unexpected inbound message type: {}", message.getClass().getSimpleName());
		}
	}

	/**
	 * Records a stream event (e.g. an SSE chunk from a streamable-HTTP transport) with a
	 * fresh correlation id.
	 * @param sessionId the proxy session identifier (may be {@code null})
	 * @param payload the stream event payload (may be {@code null})
	 */
	public void recordStreamEvent(final String sessionId, final JsonNode payload) {
		recordStreamEvent(sessionId, null, payload);
	}

	/**
	 * Records a stream event, preserving the correlation of the originating call when
	 * known so the frame links to its request/response pair on the timeline.
	 * @param sessionId the proxy session identifier (may be {@code null})
	 * @param originatingCorrelationId correlation id of the in-flight call this frame
	 * belongs to, or {@code null} to generate a fresh one
	 * @param payload the stream event payload (may be {@code null})
	 */
	public void recordStreamEvent(final String sessionId, final String originatingCorrelationId,
			final JsonNode payload) {
		final String correlationId = (originatingCorrelationId != null) ? originatingCorrelationId
				: UUID.randomUUID().toString();
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, sessionId,
				TimelineEventType.MCP_STREAM_EVENT, Instant.now(), payload);
		appendWithMdc(correlationId, event);
	}

	/**
	 * Appends {@code event} to the timeline with {@code correlationId} in the MDC, then
	 * restores the prior MDC value. Restoration happens in a {@code finally} block: a
	 * throwing {@link TimelineService} must never leak the recorder's correlation into
	 * the calling thread's MDC, and must not hide it either.
	 * @param correlationId the correlation id to expose in the MDC during the append
	 * @param event the timeline event to append
	 */
	private void appendWithMdc(final String correlationId, final TimelineEvent event) {
		final String priorMdc = MDC.get(MDC_CORRELATION_ID);
		MDC.put(MDC_CORRELATION_ID, correlationId);
		try {
			this.timelineService.append(event);
		}
		finally {
			if (priorMdc != null) {
				MDC.put(MDC_CORRELATION_ID, priorMdc);
			}
			else {
				MDC.remove(MDC_CORRELATION_ID);
			}
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
	 * Stores a pending request correlation, keeping the map bounded. The
	 * {@link PendingCorrelationStore} handles LRU eviction internally.
	 * @param key the session-scoped request key
	 * @param correlationId the generated correlation id
	 * @param progressToken the request's progress token text, may be {@code null}
	 */
	private void storePending(final CorrelationKey key, final String correlationId, final String progressToken) {
		final PendingCorrelationStore.PendingCorrelation value = new PendingCorrelationStore.PendingCorrelation(
				correlationId, Instant.now(), progressToken);
		this.requestCorrelations.store(key, value);
		if (progressToken != null) {
			this.progressCorrelations.put(keyOf(key.sessionId(), progressToken), correlationId);
		}
	}

	private void evictProgress(final CorrelationKey key, final PendingCorrelationStore.PendingCorrelation value) {
		if (value.progressToken() != null) {
			// best-effort: a stale token lookup simply falls back to a fresh id
			this.progressCorrelations.remove(keyOf(key.sessionId(), value.progressToken()));
		}
	}

	/**
	 * Drops every pending correlation recorded for {@code sessionId}. Invoked by the
	 * proxy when a session closes so abandoned requests leave no residue.
	 * @param sessionId the closed proxy session identifier (may be {@code null})
	 */
	public void clearSession(final String sessionId) {
		if (sessionId == null) {
			return;
		}
		this.requestCorrelations.removeIf((key) -> key.sessionId().equals(sessionId));
		this.progressCorrelations.keySet().removeIf((token) -> token.startsWith(sessionId + "\u0000"));
	}

	/**
	 * Extracts {@code params._meta.progressToken} as a normalised string from a raw
	 * JSON-RPC frame.
	 * @param frame the raw frame (may be {@code null})
	 * @return the token text, or {@code null} when absent or non-scalar
	 */
	private static String progressTokenOf(final JsonNode frame) {
		if (frame == null) {
			return null;
		}
		// Outbound requests carry the token in params._meta.progressToken; the
		// notifications/progress frame carries it directly at params.progressToken.
		JsonNode token = frame.path("params").path("_meta").path("progressToken");
		if (!token.isValueNode()) {
			token = frame.path("params").path("progressToken");
		}
		if (!token.isValueNode()) {
			return null;
		}
		return token.asText();
	}

	/**
	 * Looks up the correlation registered for a progress token; falls back to the
	 * per-session map when no session id is known.
	 * @param sessionId the proxy session identifier (may be {@code null})
	 * @param token the progress token text
	 * @return the originating correlation id, or {@code null} when unknown
	 */
	private String lookupProgressCorrelation(final String sessionId, final String token) {
		final String key = (sessionId != null) ? keyOf(sessionId, token) : null;
		if (key != null) {
			final String found = this.progressCorrelations.get(key);
			if (found != null) {
				return found;
			}
		}
		return this.progressCorrelations.get(keyOf("", token));
	}

	private static String keyOf(final String sessionId, final String token) {
		// NUL separator keeps (s,"a\0b") distinct from ("s\0a","b")
		return sessionId + "\u0000" + token;
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
