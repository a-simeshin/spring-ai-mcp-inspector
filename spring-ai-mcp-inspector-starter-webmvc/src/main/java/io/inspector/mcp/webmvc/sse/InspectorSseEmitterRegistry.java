/*
 * Copyright 2025-present the original author or authors.
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tracks active {@link SseEmitter} streams keyed by inspector session id.
 *
 * <p>
 * Each inspector UI tab corresponds to exactly one session id and exactly one emitter.
 * The registry is the single point of truth for notification fan-out (server-initiated
 * events that the loopback MCP client receives need to be forwarded to the right browser
 * tab).
 *
 * @author Artem Simeshin
 */
public class InspectorSseEmitterRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(InspectorSseEmitterRegistry.class);

	private final ConcurrentMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

	/**
	 * Registers a new emitter for the given session id, replacing any previous one. Wires
	 * {@code onCompletion}/{@code onTimeout}/{@code onError} to self-eviction.
	 * @param sessionId the inspector session identifier
	 * @return the newly created {@link SseEmitter}
	 */
	public SseEmitter register(final String sessionId) {
		final SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
		emitter.onCompletion(() -> this.emitters.remove(sessionId, emitter));
		emitter.onTimeout(() -> {
			emitter.complete();
			this.emitters.remove(sessionId, emitter);
		});
		emitter.onError((t) -> this.emitters.remove(sessionId, emitter));

		final SseEmitter previous = this.emitters.put(sessionId, emitter);
		if (previous != null) {
			try {
				previous.complete();
			}
			catch (final Exception ignored) {
				/* best-effort */
			}
		}
		return emitter;
	}

	/**
	 * Sends a named SSE event to the emitter registered under {@code sessionId}. On I/O
	 * failure the emitter is removed.
	 * @param sessionId the inspector session identifier
	 * @param name the SSE event name
	 * @param payload the event payload
	 */
	public void broadcast(final String sessionId, final String name, final Object payload) {
		final SseEmitter emitter = this.emitters.get(sessionId);
		if (emitter == null) {
			return;
		}
		try {
			emitter.send(SseEmitter.event().name(name).data(payload));
		}
		catch (final IOException ex) {
			LOG.debug("Failed to send SSE event '{}' to session {}: {}", name, sessionId, ex.toString());
			this.emitters.remove(sessionId, emitter);
			try {
				emitter.completeWithError(ex);
			}
			catch (final Exception ignored) {
				/* best-effort */
			}
		}
	}

	/**
	 * Completes and removes the emitter for the given session.
	 * @param sessionId the inspector session identifier
	 */
	public void close(final String sessionId) {
		final SseEmitter emitter = this.emitters.remove(sessionId);
		if (emitter != null) {
			try {
				emitter.complete();
			}
			catch (final Exception ignored) {
				/* best-effort */
			}
		}
	}

	/**
	 * Returns the current number of active emitters. Exposed mainly for tests.
	 * @return count of registered active emitters
	 */
	public int size() {
		return this.emitters.size();
	}

}
