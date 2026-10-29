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

package io.inspector.mcp.webmvc.sse;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tracks active {@link SseEmitter} streams keyed by inspector session id.
 *
 * <p>
 * Each inspector UI tab corresponds to exactly one session id, but one session may carry
 * several concurrent emitters: the Connection pane, the History panel and the Timeline
 * tab each open their own event stream. The registry is the single point of truth for
 * notification fan-out (server-initiated events and keep-alive pings are forwarded to
 * every live subscriber of the session).
 *
 * @author Artem Simeshin
 */
public class InspectorSseEmitterRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(InspectorSseEmitterRegistry.class);

	private final ConcurrentMap<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

	/**
	 * Registers an additional emitter for the given session id. Existing subscribers stay
	 * alive: every open UI panel (Connection pane, History, Timeline) keeps its own
	 * stream. Wires {@code onCompletion}/{@code onTimeout}/{@code onError} to
	 * self-eviction.
	 * @param sessionId the inspector session identifier
	 * @return the newly created {@link SseEmitter}
	 */
	public SseEmitter register(final String sessionId) {
		final SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
		emitter.onCompletion(() -> this.remove(sessionId, emitter));
		emitter.onTimeout(() -> {
			emitter.complete();
			this.remove(sessionId, emitter);
		});
		emitter.onError((t) -> this.remove(sessionId, emitter));

		this.emitters.computeIfAbsent(sessionId, (key) -> ConcurrentHashMap.newKeySet()).add(emitter);
		return emitter;
	}

	/**
	 * Drops one emitter of a session, removing the session's set once the last subscriber
	 * is gone.
	 * @param sessionId the inspector session identifier
	 * @param emitter the emitter that is no longer usable
	 */
	private void remove(final String sessionId, final SseEmitter emitter) {
		this.emitters.computeIfPresent(sessionId, (key, sessionEmitters) -> {
			sessionEmitters.remove(emitter);
			return sessionEmitters.isEmpty() ? null : sessionEmitters;
		});
	}

	/**
	 * Sends a named SSE event to every emitter registered under {@code sessionId}. On I/O
	 * failure the failing emitter is removed; the other subscribers keep receiving
	 * events.
	 * @param sessionId the inspector session identifier
	 * @param name the SSE event name
	 * @param payload the event payload
	 */
	public void broadcast(final String sessionId, final String name, final Object payload) {
		final Set<SseEmitter> sessionEmitters = this.emitters.get(sessionId);
		if (sessionEmitters == null || sessionEmitters.isEmpty()) {
			return;
		}
		for (final SseEmitter emitter : List.copyOf(sessionEmitters)) {
			try {
				emitter.send(SseEmitter.event().name(name).data(payload));
			}
			catch (final IOException ex) {
				LOG.debug("Failed to send SSE event '{}' to session {}: {}", name, sessionId, ex.toString());
				this.remove(sessionId, emitter);
				try {
					emitter.completeWithError(ex);
				}
				catch (final Exception ignored) {
					/* best-effort */
				}
			}
		}
	}

	/**
	 * Completes and removes every emitter of the given session.
	 * @param sessionId the inspector session identifier
	 */
	public void close(final String sessionId) {
		final Set<SseEmitter> sessionEmitters = this.emitters.remove(sessionId);
		if (sessionEmitters != null) {
			sessionEmitters.forEach(this::completeQuietly);
		}
	}

	private void completeQuietly(final SseEmitter emitter) {
		try {
			emitter.complete();
		}
		catch (final Exception ignored) {
			/* best-effort */
		}
	}

	/**
	 * Completes and removes every emitter. Called on context close — each emitter pins an
	 * in-flight request that would otherwise stall Boot's graceful shutdown.
	 */
	public void closeAll() {
		this.emitters.keySet().forEach(this::close);
	}

	/**
	 * Returns the current number of active emitters. Exposed mainly for tests.
	 * @return count of registered active emitters
	 */
	public int size() {
		return this.emitters.values().stream().mapToInt(Set::size).sum();
	}

}
