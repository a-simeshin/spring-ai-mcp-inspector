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

package io.inspector.mcp.webflux.router;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import io.inspector.mcp.core.keepalive.KeepAliveTracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit coverage for the registry's unregistered-session paths: every lookup must degrade
 * to {@code null} (or a no-op) instead of throwing, so a browser subscribing ahead of a
 * proxy session open, or after its close, cannot break the SSE stream or the polled
 * snapshot.
 *
 * @author Artem Simeshin
 */
class KeepAliveEventRegistryTests {

	private final KeepAliveEventRegistry registry = new KeepAliveEventRegistry();

	@Test
	void lookupBeforeRegistration_returnsNulls() {
		assertThat(this.registry.sink("missing")).isNull();
		assertThat(this.registry.tracker("missing")).isNull();
	}

	@Test
	void emitAndCloseBeforeRegistration_areNoOps() {
		final ServerSentEvent<String> event = ServerSentEvent.builder("{\"at\":\"2026-10-29T12:00:00Z\"}").build();
		assertThatCode(() -> this.registry.emit("missing", event)).doesNotThrowAnyException();
		assertThatCode(() -> this.registry.close("missing")).doesNotThrowAnyException();
	}

	@Test
	void registeredSession_exposesSinkAndTracker() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final var sink = this.registry.register("session", tracker);

		assertThat(this.registry.sink("session")).isSameAs(sink);
		assertThat(this.registry.tracker("session")).isSameAs(tracker);
	}

	@Test
	void emitWithoutSubscriber_isDroppedWithoutThrowing() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		this.registry.register("session", tracker);
		final ServerSentEvent<String> event = ServerSentEvent.builder("{\"at\":\"2026-10-29T12:00:00Z\"}").build();

		assertThatCode(() -> this.registry.emit("session", event)).doesNotThrowAnyException();
	}

	@Test
	void closeRemovesTheSession() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		this.registry.register("session", tracker);

		this.registry.close("session");

		assertThat(this.registry.sink("session")).isNull();
		assertThat(this.registry.tracker("session")).isNull();
	}

}
