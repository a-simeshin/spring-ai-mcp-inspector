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

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link InspectorSseEmitterRegistry}. */
class InspectorSseEmitterRegistryTest {

	@Test
	@SuppressWarnings("unchecked")
	void broadcastsToAllRegisteredEmitters() throws Exception {
		InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();
		String sessionId = "session-1";

		registry.register(sessionId);

		// Swap registered emitter with a Mockito spy so we can verify a send happened.
		Field emittersField = InspectorSseEmitterRegistry.class.getDeclaredField("emitters");
		emittersField.setAccessible(true);
		ConcurrentMap<String, SseEmitter> emitters = (ConcurrentMap<String, SseEmitter>) emittersField.get(registry);

		SseEmitter spyEmitter = spy(new SseEmitter(Long.MAX_VALUE));
		doNothing().when(spyEmitter).send(any(SseEmitter.SseEventBuilder.class));
		emitters.put(sessionId, spyEmitter);

		registry.broadcast(sessionId, "notify", "payload");

		verify(spyEmitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
	}

	@Test
	void registerReplacesPreviousEmitter() {
		InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();
		String sessionId = "s1";

		SseEmitter first = registry.register(sessionId);
		SseEmitter second = registry.register(sessionId);

		assertThat(first).isNotSameAs(second);
		assertThat(registry.size()).isEqualTo(1);
	}

	@Test
	void closeRemovesEmitter() {
		InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();
		registry.register("s1");

		assertThat(registry.size()).isEqualTo(1);

		registry.close("s1");

		assertThat(registry.size()).isZero();
	}

	@Test
	void broadcastWithoutRegistrationIsSafe() {
		InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();

		// Should not throw.
		registry.broadcast("missing", "event", "payload");

		assertThat(registry.size()).isZero();
	}

}
