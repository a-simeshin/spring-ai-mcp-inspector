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

package io.inspector.mcp.core.proxy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link ProxySession#close()} must complete its sinks even when another thread is
 * emitting at the same moment.
 *
 * <p>
 * This is the shutdown case, not an exotic one: {@code SIGTERM} arrives while a session
 * is relaying server notifications. Both sinks are wrapped in Reactor's
 * {@code SinkManySerialized}, whose {@code tryEmitComplete()} returns
 * {@code FAIL_NON_SERIALIZED} the instant another thread owns the sink and does not
 * retry. The old code discarded that result, so the completion was simply lost — the
 * browser's SSE emitter never finished and Boot's graceful shutdown waited out the whole
 * phase anyway.
 *
 * <p>
 * The window is wide because the real subscriber does work inside {@code tryEmitNext} on
 * the emitting thread (serialise JSON, write to the SSE socket); the subscriber below
 * spins for a comparable moment to reproduce that. Measured against the old
 * {@code tryEmitComplete()} implementation: 176 of 200 completions dropped. The assertion
 * is zero.
 */
@Epic("MCP Inspector Core")
@Feature("Proxy session")
class ProxySessionConcurrentCloseTests {

	private static final int ROUNDS = 200;

	@Test
	@DisplayName("close() completes the browser-facing sink even under a concurrent emitter")
	@Story("Session teardown")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Races close() against a thread emitting frames into targetToBrowser 200 times and "
			+ "asserts the subscriber saw onComplete every single time — a dropped completion leaves the "
			+ "browser's SSE stream open and makes graceful shutdown pay its full timeout")
	void close_whileAnotherThreadEmits_alwaysCompletesTheSink() throws Exception {
		int droppedCompletions = 0;

		for (int round = 0; round < ROUNDS; round++) {
			final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
			final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
			final ProxySession session = new ProxySession("s-" + round, closingTransport(), browserToTarget,
					targetToBrowser);

			final AtomicBoolean completed = new AtomicBoolean(false);
			targetToBrowser.asFlux().subscribe((frame) -> burnCpu(), (error) -> {
			}, () -> completed.set(true));

			final AtomicBoolean stop = new AtomicBoolean(false);
			final CountDownLatch emitting = new CountDownLatch(1);
			final Thread emitter = new Thread(() -> {
				final JsonNode frame = JsonNodeFactory.instance.objectNode().put("method", "notifications/message");
				while (!stop.get()) {
					targetToBrowser.tryEmitNext(frame);
					emitting.countDown();
				}
			}, "proxy-session-emitter-" + round);
			emitter.setDaemon(true);
			emitter.start();
			emitting.await(5, TimeUnit.SECONDS);

			session.close();
			stop.set(true);
			emitter.join(TimeUnit.SECONDS.toMillis(5));

			if (!completed.get()) {
				droppedCompletions++;
			}
		}

		assertThat(droppedCompletions).as("completions dropped out of %d rounds", ROUNDS).isZero();
	}

	private static McpClientTransport closingTransport() {
		final McpClientTransport transport = mock(McpClientTransport.class);
		given(transport.closeGracefully()).willReturn(Mono.empty());
		return transport;
	}

	/**
	 * Stands in for the real subscriber's work — JSON serialisation plus an SSE socket
	 * write — which runs inside {@code tryEmitNext} on the emitting thread and is what
	 * makes the sink's critical section long enough to lose a race against.
	 */
	private static void burnCpu() {
		long sink = 0;
		for (int i = 0; i < 2_000; i++) {
			sink += i;
		}
		if (sink == Long.MIN_VALUE) {
			throw new IllegalStateException("unreachable");
		}
	}

}
