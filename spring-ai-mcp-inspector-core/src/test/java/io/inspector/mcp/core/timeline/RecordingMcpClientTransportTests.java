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

import java.util.List;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link RecordingMcpClientTransport}.
 *
 * @author Artem Simeshin
 */
class RecordingMcpClientTransportTests {

	private BoundedTimelineService timelineService;

	private McpClientTrafficRecorder recorder;

	private McpClientTransport delegate;

	private RecordingMcpClientTransport transport;

	@BeforeEach
	void setUp() {
		// given
		this.timelineService = new BoundedTimelineService();
		this.recorder = new McpClientTrafficRecorder(this.timelineService);
		this.delegate = mock(McpClientTransport.class);
		given(this.delegate.sendMessage(any(JSONRPCMessage.class))).willReturn(Mono.empty());
		given(this.delegate.closeGracefully()).willReturn(Mono.empty());
		this.transport = new RecordingMcpClientTransport(this.delegate, "test-client", "stdio", this.recorder);
	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@DisplayName("rejects null delegate")
		void rejectsNullDelegate() {
			assertThatThrownBy(() -> new RecordingMcpClientTransport(null, "c", "stdio",
					RecordingMcpClientTransportTests.this.recorder))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("rejects null clientName")
		void rejectsNullClientName() {
			assertThatThrownBy(() -> new RecordingMcpClientTransport(RecordingMcpClientTransportTests.this.delegate,
					null, "stdio", RecordingMcpClientTransportTests.this.recorder))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("rejects null trafficRecorder")
		void rejectsNullRecorder() {
			assertThatThrownBy(() -> new RecordingMcpClientTransport(RecordingMcpClientTransportTests.this.delegate,
					"c", "stdio", null))
				.isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	@DisplayName("sendMessage")
	class SendMessage {

		@Test
		@DisplayName("records outbound request and delegates to real transport")
		void recordsOutboundRequestAndDelegates() {
			// given
			final JSONRPCRequest request = new JSONRPCRequest("2.0", "tools/call", 1, null);

			// when
			RecordingMcpClientTransportTests.this.transport.sendMessage(request).block();

			// then
			then(RecordingMcpClientTransportTests.this.delegate).should().sendMessage(request);
			final List<TimelineEvent> events = RecordingMcpClientTransportTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_REQUEST);
			assertThat(events.get(0).payload().path("direction").asText()).isEqualTo("client->server");
		}

		@Test
		@DisplayName("records outbound notification and delegates")
		void recordsOutboundNotificationAndDelegates() {
			// given
			final JSONRPCNotification notification = new JSONRPCNotification("notifications/cancelled", null);

			// when
			RecordingMcpClientTransportTests.this.transport.sendMessage(notification).block();

			// then
			then(RecordingMcpClientTransportTests.this.delegate).should().sendMessage(notification);
			final List<TimelineEvent> events = RecordingMcpClientTransportTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_NOTIFICATION);
		}

		@Test
		@DisplayName("records outbound JSONRPCResponse for server-initiated callbacks")
		void recordsOutboundResponseForServerInitiatedCallbacks() {
			// given
			final JSONRPCResponse response = JSONRPCResponse.result("srv-1", java.util.Map.of());

			// when
			RecordingMcpClientTransportTests.this.transport.sendMessage(response).block();

			// then
			then(RecordingMcpClientTransportTests.this.delegate).should().sendMessage(response);
			final List<TimelineEvent> events = RecordingMcpClientTransportTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_RESPONSE);
		}

	}

	@Nested
	@DisplayName("connect")
	class Connect {

		@Test
		@DisplayName("wraps inbound handler to capture server responses")
		void wrapsInboundHandlerToCaptureResponses() {
			// given
			final JSONRPCResponse response = JSONRPCResponse.result(1, java.util.Map.of());
			given(RecordingMcpClientTransportTests.this.delegate.connect(any(Function.class)))
				.willAnswer((invocation) -> {
					@SuppressWarnings("unchecked")
					final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = invocation.getArgument(0);
					return Mono.fromSupplier(() -> {
						handler.apply(Mono.just(response)).block();
						return (Void) null;
					}).then();
				});
			// Pre-record a request so the response has a correlation
			RecordingMcpClientTransportTests.this.recorder.recordClientRequest("test-client", "stdio",
					new JSONRPCRequest("2.0", "tools/call", 1, null));

			// when
			RecordingMcpClientTransportTests.this.transport.connect((inbound) -> inbound).block();

			// then
			final List<TimelineEvent> events = RecordingMcpClientTransportTests.this.timelineService
				.query(TimelineQuery.all());
			// 1 request + 1 response
			assertThat(events).hasSize(2);
			final TimelineEvent responseEvent = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_RESPONSE)
				.findFirst()
				.orElseThrow();
			assertThat(responseEvent.payload().path("direction").asText()).isEqualTo("server->client");
		}

	}

	@Nested
	@DisplayName("accessors")
	class Accessors {

		@Test
		@DisplayName("returns client name and transport type")
		void returnsClientNameAndTransportType() {
			assertThat(RecordingMcpClientTransportTests.this.transport.clientName()).isEqualTo("test-client");
			assertThat(RecordingMcpClientTransportTests.this.transport.transportType()).isEqualTo("stdio");
			assertThat(RecordingMcpClientTransportTests.this.transport.delegate())
				.isSameAs(RecordingMcpClientTransportTests.this.delegate);
		}

	}

	@Nested
	@DisplayName("delegation")
	class Delegation {

		@Test
		@DisplayName("closeGracefully delegates to real transport")
		void closeGracefullyDelegates() {
			// when
			RecordingMcpClientTransportTests.this.transport.closeGracefully().block();

			// then
			then(RecordingMcpClientTransportTests.this.delegate).should().closeGracefully();
		}

		@Test
		@DisplayName("close delegates to real transport")
		void closeDelegates() {
			// when
			RecordingMcpClientTransportTests.this.transport.close();

			// then
			then(RecordingMcpClientTransportTests.this.delegate).should().close();
		}

	}

}
