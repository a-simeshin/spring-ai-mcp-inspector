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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RecordingMcpClientTransport}: both traffic directions, outbound
 * JSONRPCResponse pairing for server-initiated requests and connection state tracking.
 *
 * @author Artem Simeshin
 */
class RecordingMcpClientTransportTests {

	private static final McpClientTrafficRecorder.ClientEndpoint ENDPOINT = new McpClientTrafficRecorder.ClientEndpoint(
			"weather", "streamable-http", "https://***@api.example/mcp");

	private final RecordingTimelineService timeline = new RecordingTimelineService();

	private final McpClientTrafficRecorder recorder = new McpClientTrafficRecorder(this.timeline);

	private final ClientConnectionRegistry registry = new ClientConnectionRegistry();

	private final FakeDelegate delegate = new FakeDelegate();

	private final RecordingMcpClientTransport transport = new RecordingMcpClientTransport(this.delegate, ENDPOINT,
			this.recorder, this.registry);

	@Test
	@DisplayName("constructor rejects null arguments")
	void constructorRejectsNulls() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RecordingMcpClientTransport(null, ENDPOINT, this.recorder, this.registry));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RecordingMcpClientTransport(this.delegate, null, this.recorder, this.registry));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RecordingMcpClientTransport(this.delegate, ENDPOINT, null, this.registry));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RecordingMcpClientTransport(this.delegate, ENDPOINT, this.recorder, null));
	}

	@Test
	@DisplayName("outbound request is recorded, counted and forwarded to the delegate")
	void outboundRequestRecordedAndCounted() {
		final JSONRPCRequest request = new JSONRPCRequest("2.0", "tools/call", 1, null);
		this.transport.sendMessage(request).block();
		assertThat(this.delegate.sent).containsExactly(request);
		assertThat(this.timeline.events).hasSize(1);
		assertThat(this.timeline.events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_REQUEST);
		assertThat(this.timeline.events.get(0).payload().get("direction").asString()).isEqualTo("out");
		assertThat(this.registry.snapshot().get(0).outCount()).isEqualTo(1L);
		assertThat(this.registry.snapshot().get(0).inCount()).isZero();
	}

	@Test
	@DisplayName("outbound notification is recorded and forwarded")
	void outboundNotificationRecorded() {
		final JSONRPCNotification notification = new JSONRPCNotification("2.0", "notifications/initialized", null);
		this.transport.sendMessage(notification).block();
		assertThat(this.delegate.sent).containsExactly(notification);
		assertThat(this.timeline.events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_NOTIFICATION);
	}

	@Test
	@DisplayName("outbound JSONRPCResponse without a server request is recorded as orphan")
	void outboundResponseWithoutServerRequestIsOrphan() {
		this.transport.sendMessage(new JSONRPCResponse("2.0", 7, Map.of("ok", true), null)).block();
		assertThat(this.timeline.events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_RESPONSE);
		assertThat(this.timeline.events.get(0).payload().get("orphan").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("server-initiated request inbound then outbound JSONRPCResponse pair with one correlation")
	void outboundResponseToServerInitiatedRequestPairs() {
		this.transport.connect(Function.identity()).block();
		// Server asks the client to sample (inbound request id=42).
		this.delegate.connectedHandler.apply(Mono.just(new JSONRPCRequest("2.0", "sampling/createMessage", 42, null)))
			.block();
		// The client answers through sendMessage (outbound JSONRPCResponse id=42).
		this.transport.sendMessage(new JSONRPCResponse("2.0", 42, Map.of("model", "x"), null)).block();
		assertThat(this.timeline.events).hasSize(2);
		final TimelineEvent serverRequest = this.timeline.events.get(0);
		assertThat(serverRequest.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_REQUEST);
		assertThat(serverRequest.payload().get("direction").asString()).isEqualTo("in");
		final TimelineEvent outboundResponse = this.timeline.events.get(1);
		assertThat(outboundResponse.correlationId()).isEqualTo(serverRequest.correlationId());
		assertThat(outboundResponse.payload().get("orphan")).isNull();
		assertThat(outboundResponse.payload().get("latencyMs")).isNotNull();
		assertThat(this.registry.snapshot().get(0).inCount()).isEqualTo(1L);
		assertThat(this.registry.snapshot().get(0).outCount()).isEqualTo(1L);
	}

	@Test
	@DisplayName("connect marks INITIALIZING at call time and CONNECTED on completion")
	void connectStateTransitions() {
		final AtomicReference<reactor.core.publisher.MonoSink<Void>> sinkRef = new AtomicReference<>();
		this.delegate.connectResult = Mono.create(sinkRef::set);
		this.transport.connect(Function.identity()).subscribe();
		assertThat(state()).isEqualTo(ClientConnectionRegistry.ConnectionState.INITIALIZING);
		sinkRef.get().success();
		assertThat(state()).isEqualTo(ClientConnectionRegistry.ConnectionState.CONNECTED);
	}

	@Test
	@DisplayName("connect failure marks FAILED with the error text")
	void connectFailureMarksFailed() {
		this.delegate.connectResult = Mono.error(new IllegalStateException("boom"));
		assertThatThrownBy(() -> this.transport.connect(Function.identity()).block())
			.isInstanceOf(IllegalStateException.class);
		assertThat(state()).isEqualTo(ClientConnectionRegistry.ConnectionState.FAILED);
		assertThat(this.registry.snapshot().get(0).lastError()).contains("boom");
	}

	@Test
	@DisplayName("inbound response is recorded and counted")
	void inboundResponseRecordedAndCounted() {
		this.transport.connect(Function.identity()).block();
		this.delegate.connectedHandler.apply(Mono.just(new JSONRPCResponse("2.0", 3, Map.of("ok", true), null)))
			.block();
		assertThat(this.timeline.events).hasSize(1);
		assertThat(this.timeline.events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_RESPONSE);
		assertThat(this.timeline.events.get(0).payload().get("direction").asString()).isEqualTo("in");
		assertThat(this.registry.snapshot().get(0).inCount()).isEqualTo(1L);
	}

	@Test
	@DisplayName("null inbound mono is passed through without recording")
	void nullInboundPassthrough() {
		final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> nullSafe = (m) -> (m != null) ? m : Mono.empty();
		this.transport.connect(nullSafe).block();
		this.delegate.connectedHandler.apply(null).block();
		assertThat(this.timeline.events).isEmpty();
		assertThat(this.registry.snapshot().get(0).inCount()).isZero();
	}

	@Test
	@DisplayName("inbound server notification is recorded")
	void inboundNotificationRecorded() {
		this.transport.connect(Function.identity()).block();
		this.delegate.connectedHandler.apply(Mono.just(new JSONRPCNotification("2.0", "notifications/message", null)))
			.block();
		assertThat(this.timeline.events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_NOTIFICATION);
	}

	@Test
	@DisplayName("closeGracefully marks the connection CLOSED")
	void closeGracefullyMarksClosed() {
		this.transport.connect(Function.identity()).block();
		assertThat(state()).isEqualTo(ClientConnectionRegistry.ConnectionState.CONNECTED);
		this.transport.closeGracefully().block();
		assertThat(state()).isEqualTo(ClientConnectionRegistry.ConnectionState.CLOSED);
	}

	@Test
	@DisplayName("exception handler marks FAILED and still delegates to the original handler")
	void exceptionHandlerMarksFailedAndDelegates() {
		final AtomicReference<Boolean> consumed = new AtomicReference<>(false);
		this.transport.connect(Function.identity()).block();
		this.transport.setExceptionHandler((ex) -> consumed.set(Boolean.TRUE));
		this.delegate.exceptionHandler.accept(new RuntimeException("handler blew up"));
		assertThat(consumed.get()).isTrue();
		assertThat(state()).isEqualTo(ClientConnectionRegistry.ConnectionState.FAILED);
		assertThat(this.registry.snapshot().get(0).lastError()).contains("handler blew up");
	}

	@Test
	@DisplayName("protocolVersions and unmarshalFrom delegate")
	void delegatesProtocolVersions() {
		assertThat(this.transport.protocolVersions()).containsExactly("2025-06-18");
		assertThat(this.transport.clientName()).isEqualTo("weather");
	}

	private ClientConnectionRegistry.ConnectionState state() {
		return this.registry.snapshot().get(0).state();
	}

	/**
	 * Test double recording the handler and forwarded messages.
	 */
	private static final class FakeDelegate implements McpClientTransport {

		Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> connectedHandler;

		final List<JSONRPCMessage> sent = new ArrayList<>();

		Mono<Void> connectResult = Mono.empty();

		Consumer<Throwable> exceptionHandler;

		@Override
		public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
			this.connectedHandler = handler;
			return this.connectResult;
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
		public <T> T unmarshalFrom(final Object source, final TypeRef<T> typeRef) {
			return null;
		}

		@Override
		public void setExceptionHandler(final Consumer<Throwable> handler) {
			this.exceptionHandler = handler;
		}

		@Override
		public List<String> protocolVersions() {
			return List.of("2025-06-18");
		}

	}

	/**
	 * Test double capturing appended events in order.
	 */
	private static final class RecordingTimelineService implements TimelineService {

		private final List<TimelineEvent> events = new ArrayList<>();

		@Override
		public void append(final TimelineEvent event) {
			this.events.add(event);
		}

		@Override
		public List<TimelineEvent> query(final TimelineQuery query) {
			return List.of();
		}

		@Override
		public void clear() {
			this.events.clear();
		}

	}

}
