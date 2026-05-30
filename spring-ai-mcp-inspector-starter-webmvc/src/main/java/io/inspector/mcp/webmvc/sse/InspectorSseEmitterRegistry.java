/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 */
public class InspectorSseEmitterRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(InspectorSseEmitterRegistry.class);

	private final ConcurrentMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

	/**
	 * Registers a new emitter for the given session id, replacing any previous one. Wires
	 * {@code onCompletion}/{@code onTimeout}/{@code onError} to self-eviction.
	 */
	public SseEmitter register(String sessionId) {
		SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
		emitter.onCompletion(() -> emitters.remove(sessionId, emitter));
		emitter.onTimeout(() -> {
			emitter.complete();
			emitters.remove(sessionId, emitter);
		});
		emitter.onError(t -> emitters.remove(sessionId, emitter));

		SseEmitter previous = emitters.put(sessionId, emitter);
		if (previous != null) {
			try {
				previous.complete();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
		}
		return emitter;
	}

	/**
	 * Sends a named SSE event to the emitter registered under {@code sessionId}. On I/O
	 * failure the emitter is removed.
	 */
	public void broadcast(String sessionId, String name, Object payload) {
		SseEmitter emitter = emitters.get(sessionId);
		if (emitter == null) {
			return;
		}
		try {
			emitter.send(SseEmitter.event().name(name).data(payload));
		}
		catch (IOException e) {
			LOG.debug("Failed to send SSE event '{}' to session {}: {}", name, sessionId, e.toString());
			emitters.remove(sessionId, emitter);
			try {
				emitter.completeWithError(e);
			}
			catch (Exception ignored) {
				/* best-effort */
			}
		}
	}

	/**
	 * Completes and removes the emitter for the given session.
	 */
	public void close(String sessionId) {
		SseEmitter emitter = emitters.remove(sessionId);
		if (emitter != null) {
			try {
				emitter.complete();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
		}
	}

	/** Returns the current number of active emitters. Exposed mainly for tests. */
	public int size() {
		return emitters.size();
	}

}
