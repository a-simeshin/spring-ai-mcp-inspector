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

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

	/** Age after which an unanswered pending correlation is expired and dropped. */
	static final Duration PENDING_TTL = Duration.ofMinutes(1);

	private final TimelineService timelineService;

	/**
	 * Sequence counter giving insertion order to the pending map (for oldest-first
	 * eviction).
	 */
	private final AtomicLong pendingSequence = new AtomicLong();

	private final ConcurrentHashMap<CorrelationKey, PendingCorrelation> requestCorrelations = new ConcurrentHashMap<>();

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
				storePending(new CorrelationKey(sessionId, id), correlationId, progressTokenOf(rawFrame));
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
			final CorrelationKey key = (id != null) ? new CorrelationKey(sessionId, id) : null;
			final PendingCorrelation pending = (key != null) ? this.requestCorrelations.remove(key) : null;
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
	 * Stores a pending request correlation, keeping the map bounded and fresh: expired
	 * entries are dropped on every store, and when the map is at capacity the
	 * oldest-inserted entry is evicted. Without this, unanswered requests would grow the
	 * map without limit.
	 * @param key the session-scoped request key
	 * @param correlationId the generated correlation id
	 * @param progressToken the request's progress token text, may be {@code null}
	 */
	private void storePending(final CorrelationKey key, final String correlationId, final String progressToken) {
		expirePending();
		while (this.requestCorrelations.size() >= MAX_PENDING_CORRELATIONS) {
			// Evict the oldest-inserted entry (lowest sequence).
			final Map.Entry<CorrelationKey, PendingCorrelation> oldest = this.requestCorrelations.entrySet()
				.stream()
				.min(Comparator
					.comparingLong((Map.Entry<CorrelationKey, PendingCorrelation> e) -> e.getValue().sequence()))
				.orElse(null);
			if (oldest == null) {
				break;
			}
			this.requestCorrelations.remove(oldest.getKey());
			evictProgress(oldest.getKey(), oldest.getValue());
		}
		this.requestCorrelations.put(key, new PendingCorrelation(correlationId, this.pendingSequence.incrementAndGet(),
				Instant.now(), progressToken));
		if (progressToken != null) {
			this.progressCorrelations.put(keyOf(key.sessionId(), progressToken), correlationId);
		}
	}

	private void evictProgress(final CorrelationKey key, final PendingCorrelation value) {
		if (value.progressToken() != null) {
			// best-effort: a stale token lookup simply falls back to a fresh id
			this.progressCorrelations.remove(keyOf(key.sessionId(), value.progressToken()));
		}
	}

	/**
	 * Removes pending correlations whose age exceeds {@link #PENDING_TTL}. Called
	 * opportunistically on every new request, so stale state cannot accumulate.
	 */
	void expirePending() {
		final Instant cutoff = Instant.now().minus(PENDING_TTL);
		this.requestCorrelations.entrySet().removeIf((entry) -> {
			if (entry.getValue().storedAt().isBefore(cutoff)) {
				evictProgress(entry.getKey(), entry.getValue());
				return true;
			}
			return false;
		});
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
		this.requestCorrelations.keySet().removeIf((key) -> key.sessionId().equals(sessionId));
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

	/**
	 * A pending request correlation with the bookkeeping needed for bounded, expiring
	 * storage.
	 *
	 * @param correlationId the generated correlation id
	 * @param sequence insertion order for oldest-first eviction
	 * @param storedAt instant the correlation was created, for TTL expiry
	 * @param progressToken the request's {@code params._meta.progressToken} text, may be
	 * {@code null}
	 */
	record PendingCorrelation(String correlationId, long sequence, Instant storedAt, String progressToken) {

	}

}
