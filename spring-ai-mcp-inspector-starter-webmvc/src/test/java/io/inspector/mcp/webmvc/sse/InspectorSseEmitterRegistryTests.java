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

import java.lang.reflect.Field;
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
		@Description("broadcast() sends the event to the emitter registered under the session id")
		@SuppressWarnings("unchecked")
		void broadcastsToAllRegisteredEmitters() throws Exception {
			// given
			final InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();
			final String sessionId = "session-1";
			registry.register(sessionId);

			// Swap registered emitter with a Mockito spy so we can verify a send
			// happened.
			final Field emittersField = InspectorSseEmitterRegistry.class.getDeclaredField("emitters");
			emittersField.setAccessible(true);
			final ConcurrentMap<String, SseEmitter> emitters = (ConcurrentMap<String, SseEmitter>) emittersField
				.get(registry);

			final SseEmitter spyEmitter = spy(new SseEmitter(Long.MAX_VALUE));
			willDoNothing().given(spyEmitter).send(any(SseEmitter.SseEventBuilder.class));
			emitters.put(sessionId, spyEmitter);

			// when
			registry.broadcast(sessionId, "notify", "payload");

			// then
			verify(spyEmitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
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
		@Description("register() replaces a previous emitter for the same session id")
		void registerReplacesPreviousEmitter() {
			// given
			final InspectorSseEmitterRegistry registry = new InspectorSseEmitterRegistry();
			final String sessionId = "s1";

			// when
			final SseEmitter first = registry.register(sessionId);
			final SseEmitter second = registry.register(sessionId);

			// then
			assertThat(first).isNotSameAs(second);
			assertThat(registry.size()).isEqualTo(1);
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

	}

}
