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

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link RecordingTransportPostProcessor}.
 *
 * @author Artem Simeshin
 */
class RecordingTransportPostProcessorTests {

	private BoundedTimelineService timelineService;

	private McpClientTrafficRecorder recorder;

	private RecordingTransportPostProcessor postProcessor;

	@BeforeEach
	void setUp() {
		// given
		this.timelineService = new BoundedTimelineService();
		this.recorder = new McpClientTrafficRecorder(this.timelineService);
		this.postProcessor = new RecordingTransportPostProcessor(this.recorder);
	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@DisplayName("rejects null recorder")
		void rejectsNullRecorder() {
			assertThatThrownBy(() -> new RecordingTransportPostProcessor(null))
				.isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	@DisplayName("postProcessAfterInitialization")
	class PostProcessAfterInitialization {

		@Test
		@DisplayName("returns bean unchanged when not a NamedClientMcpTransport")
		void returnsBeanUnchangedWhenNotNamedTransport() {
			// given
			final Object bean = "not-a-transport";

			// when
			final Object result = RecordingTransportPostProcessorTests.this.postProcessor
				.postProcessAfterInitialization(bean, "someBean");

			// then
			assertThat(result).isSameAs(bean);
		}

		@Test
		@DisplayName("returns null bean unchanged")
		void returnsNullBeanUnchanged() {
			// when
			final Object result = RecordingTransportPostProcessorTests.this.postProcessor
				.postProcessAfterInitialization(null, "someBean");

			// then
			assertThat(result).isNull();
		}

		@Test
		@DisplayName("wraps NamedClientMcpTransport bean with recording decorator")
		void wrapsNamedClientMcpTransportBean() {
			// given
			final McpClientTransport mockTransport = mock(McpClientTransport.class);
			given(mockTransport.sendMessage(any(JSONRPCMessage.class))).willReturn(Mono.empty());
			final NamedClientMcpTransport named = new NamedClientMcpTransport("my-client", mockTransport);

			// when
			final Object result = RecordingTransportPostProcessorTests.this.postProcessor
				.postProcessAfterInitialization(named, "namedTransport");

			// then
			assertThat(result).isInstanceOf(NamedClientMcpTransport.class);
			final NamedClientMcpTransport wrapped = (NamedClientMcpTransport) result;
			assertThat(wrapped.name()).isEqualTo("my-client");
			assertThat(wrapped.transport()).isInstanceOf(RecordingMcpClientTransport.class);
			// Verify traffic is captured through the wrapped transport
			final RecordingMcpClientTransport recording = (RecordingMcpClientTransport) wrapped.transport();
			final JSONRPCRequest request = new JSONRPCRequest("2.0", "tools/list", 1, null);
			recording.sendMessage(request).block();
			final List<TimelineEvent> events = RecordingTransportPostProcessorTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).payload().path("clientName").asText()).isEqualTo("my-client");
		}

		@Test
		@DisplayName("detects stdio transport type from delegate class name")
		void detectsStdioTransportType() {
			// given
			final StdioLikeTransport stdioTransport = new StdioLikeTransport();
			final NamedClientMcpTransport named = new NamedClientMcpTransport("stdio-client", stdioTransport);

			// when
			final Object result = RecordingTransportPostProcessorTests.this.postProcessor
				.postProcessAfterInitialization(named, "b");

			// then
			final NamedClientMcpTransport wrapped = (NamedClientMcpTransport) result;
			final RecordingMcpClientTransport recording = (RecordingMcpClientTransport) wrapped.transport();
			assertThat(recording.transportType()).isEqualTo("stdio");
		}

		@Test
		@DisplayName("detects sse transport type from delegate class name")
		void detectsSseTransportType() {
			// given
			final SseLikeTransport sseTransport = new SseLikeTransport();
			final NamedClientMcpTransport named = new NamedClientMcpTransport("sse-client", sseTransport);

			// when
			final Object result = RecordingTransportPostProcessorTests.this.postProcessor
				.postProcessAfterInitialization(named, "b");

			// then
			final NamedClientMcpTransport wrapped = (NamedClientMcpTransport) result;
			final RecordingMcpClientTransport recording = (RecordingMcpClientTransport) wrapped.transport();
			assertThat(recording.transportType()).isEqualTo("sse");
		}

		@Test
		@DisplayName("detects streamable-http transport type from delegate class name")
		void detectsStreamableHttpTransportType() {
			// given
			final StreamableHttpLikeTransport httpTransport = new StreamableHttpLikeTransport();
			final NamedClientMcpTransport named = new NamedClientMcpTransport("http-client", httpTransport);

			// when
			final Object result = RecordingTransportPostProcessorTests.this.postProcessor
				.postProcessAfterInitialization(named, "b");

			// then
			final NamedClientMcpTransport wrapped = (NamedClientMcpTransport) result;
			final RecordingMcpClientTransport recording = (RecordingMcpClientTransport) wrapped.transport();
			assertThat(recording.transportType()).isEqualTo("streamable-http");
		}

	}

	/** Test helper: a transport whose class name contains "Stdio". */
	static class StdioLikeTransport implements McpClientTransport {

		@Override
		public Mono<Void> sendMessage(final JSONRPCMessage message) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
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

	}

	/** Test helper: a transport whose class name contains "Sse". */
	static class SseLikeTransport implements McpClientTransport {

		@Override
		public Mono<Void> sendMessage(final JSONRPCMessage message) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
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

	}

	/** Test helper: a transport whose class name contains "Streamable" and "Http". */
	static class StreamableHttpLikeTransport implements McpClientTransport {

		@Override
		public Mono<Void> sendMessage(final JSONRPCMessage message) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
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

	}

}
