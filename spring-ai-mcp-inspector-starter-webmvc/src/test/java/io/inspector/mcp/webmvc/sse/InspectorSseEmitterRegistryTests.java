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

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link InspectorSseEmitterRegistry}. */
@Epic("WebMvc Inspector")
@Feature("InspectorSseEmitterRegistry")
class InspectorSseEmitterRegistryTests {

	@Nested
	@DisplayName("broadcast()")
	class Broadcast {

		@Test
		@Story("Event fan-out")
		@Severity(SeverityLevel.CRITICAL)
		@Description("broadcast() sends the event to every emitter registered under the session id")
		@SuppressWarnings("unchecked")
		void broadcastsToAllRegisteredEmitters() throws Exception {
			// given
			final InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();
			final String sessionId = "session-1";
			registry.register(sessionId);
			registry.register(sessionId);

			// Swap the registered emitters with Mockito spies so we can verify a
			// send happened on both subscribers.
			final Field emittersField = InspectorSseEmitterRegistry.class.getDeclaredField("emitters");
			emittersField.setAccessible(true);
			final ConcurrentMap<String, Set<SseEmitter>> emitters = (ConcurrentMap<String, Set<SseEmitter>>) emittersField
				.get(registry);

			final SseEmitter firstSpy = spy(new SseEmitter(Long.MAX_VALUE));
			final SseEmitter secondSpy = spy(new SseEmitter(Long.MAX_VALUE));
			willDoNothing().given(firstSpy).send(any(SseEmitter.SseEventBuilder.class));
			willDoNothing().given(secondSpy).send(any(SseEmitter.SseEventBuilder.class));
			final Set<SseEmitter> subscribers = ConcurrentHashMap.newKeySet();
			subscribers.add(firstSpy);
			subscribers.add(secondSpy);
			emitters.put(sessionId, subscribers);

			// when
			registry.broadcast(sessionId, "notify", "payload");

			// then
			verify(firstSpy, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
			verify(secondSpy, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
		}

		@Test
		@Story("Event fan-out")
		@Severity(SeverityLevel.MINOR)
		@Description("broadcast() is a no-op when no emitter is registered for the session")
		void broadcastWithoutRegistrationIsSafe() {
			// given
			final InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();

			// when — should not throw
			registry.broadcast("missing", "event", "payload");

			// then
			assertThat(registry.size()).isZero();
		}

	}

	@Nested
	@DisplayName("register()")
	class Register {

		@Test
		@Story("Registration")
		@Severity(SeverityLevel.NORMAL)
		@Description("register() keeps every subscriber of a session alive: two open UI panels mean two live emitters")
		void registerKeepsPreviousSubscribersAlive() {
			// given
			final InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();
			final String sessionId = "s1";

			// when
			final SseEmitter first = registry.register(sessionId);
			final SseEmitter second = registry.register(sessionId);

			// then
			assertThat(first).isNotSameAs(second);
			assertThat(registry.size()).isEqualTo(2);
		}

	}

	@Nested
	@DisplayName("close()")
	class Close {

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("close() completes and removes the emitter for the session")
		void closeRemovesEmitter() {
			// given
			final InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();
			registry.register("s1");
			assertThat(registry.size()).isEqualTo(1);

			// when
			registry.close("s1");

			// then
			assertThat(registry.size()).isZero();
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("closeAll() completes and removes every emitter — the shutdown path")
		void closeAllRemovesEveryEmitter() {
			// given
			final InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();
			registry.register("s1");
			registry.register("s2");
			assertThat(registry.size()).isEqualTo(2);

			// when
			registry.closeAll();

			// then
			assertThat(registry.size()).isZero();
		}

	}

}
