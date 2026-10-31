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

package io.inspector.mcp.core.keepalive;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PingDetectingClientTransport}: detection matches only inbound
 * {@code ping} JSON-RPC requests, the delegate sees every frame unchanged, and non-ping
 * frames (responses, notifications, other requests) never trigger the tracker.
 *
 * @author Artem Simeshin
 */
class PingDetectingClientTransportTests {

	@Test
	void inboundPingRequestIsRecorded() {
		final StubTransport delegate = new StubTransport();
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final PingDetectingClientTransport transport = new PingDetectingClientTransport(delegate, tracker);
		final List<JSONRPCMessage> seenBySdkHandler = new CopyOnWriteArrayList<>();

		transport.connect((inbound) -> inbound.doOnNext(seenBySdkHandler::add).then(Mono.empty())).block();

		final JSONRPCRequest ping = new JSONRPCRequest("2.0", "ping", 1, null);
		delegate.emit(ping);

		assertThat(tracker.lastPingAt()).isNotNull();
		assertThat(tracker.recentPings()).hasSize(1);
		assertThat(seenBySdkHandler).containsExactly(ping);
	}

	@Test
	void nonPingRequestIsNotRecorded() {
		final StubTransport delegate = new StubTransport();
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final PingDetectingClientTransport transport = new PingDetectingClientTransport(delegate, tracker);
		transport.connect((inbound) -> inbound.then(Mono.empty())).block();

		delegate.emit(new JSONRPCRequest("2.0", "tools/list", 1, null));

		assertThat(tracker.recentPings()).isEmpty();
	}

	@Test
	void notificationNamedPingIsNotRecorded() {
		final StubTransport delegate = new StubTransport();
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final PingDetectingClientTransport transport = new PingDetectingClientTransport(delegate, tracker);
		transport.connect((inbound) -> inbound.then(Mono.empty())).block();

		// A notification with method "ping" has no id; per MCP spec it is not a ping
		// request and must not be tracked.
		delegate.emit(new JSONRPCNotification("2.0", "ping", null));

		assertThat(tracker.recentPings()).isEmpty();
	}

	@Test
	void inboundResponseIsNotRecorded() {
		final StubTransport delegate = new StubTransport();
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final PingDetectingClientTransport transport = new PingDetectingClientTransport(delegate, tracker);
		transport.connect((inbound) -> inbound.then(Mono.empty())).block();

		delegate.emit(new JSONRPCResponse("2.0", 1, java.util.Map.of("ok", true), null));

		assertThat(tracker.recentPings()).isEmpty();
	}

	@Test
	void sendMessageDelegatesUnchanged() {
		final StubTransport delegate = new StubTransport();
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final PingDetectingClientTransport transport = new PingDetectingClientTransport(delegate, tracker);
		final JSONRPCRequest outbound = new JSONRPCRequest("2.0", "ping", 99, null);
		transport.sendMessage(outbound).block();
		assertThat(delegate.sent).containsExactly(outbound);
		// Outbound inspector-initiated ping must not be tracked as keep-alive.
		assertThat(tracker.recentPings()).isEmpty();
	}

	@Test
	void nullDelegateOrTrackerThrows() {
		final StubTransport delegate = new StubTransport();
		final KeepAliveTracker tracker = new KeepAliveTracker();
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PingDetectingClientTransport(null, tracker))
			.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PingDetectingClientTransport(delegate, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * In-memory {@link McpClientTransport} stub: the SDK side subscribes via
	 * {@link #connect(Function)} and the test publishes frames through
	 * {@link #emit(JSONRPCMessage)}.
	 */
	private static final class StubTransport implements McpClientTransport {

		private final Sinks.Many<JSONRPCMessage> inbound = Sinks.many().multicast().onBackpressureBuffer();

		private final List<JSONRPCMessage> sent = new CopyOnWriteArrayList<>();

		private final AtomicReference<java.util.function.Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

		@Override
		public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
			this.inbound.asFlux().concatMap((msg) -> handler.apply(Mono.just(msg))).subscribe();
			return Mono.empty();
		}

		void emit(final JSONRPCMessage message) {
			this.inbound.tryEmitNext(message);
		}

		@Override
		public Mono<Void> sendMessage(final JSONRPCMessage message) {
			this.sent.add(message);
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(final Object data, final TypeRef<T> typeRef) {
			return null;
		}

		@Override
		public void setExceptionHandler(final java.util.function.Consumer<Throwable> handler) {
			this.exceptionHandler.set(handler);
		}

	}

}
