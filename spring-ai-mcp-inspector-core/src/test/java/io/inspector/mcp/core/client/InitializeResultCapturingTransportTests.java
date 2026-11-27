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

package io.inspector.mcp.core.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InitializeResultCapturingTransport}.
 *
 * <p>
 * The capture must snapshot the raw initialize result map on the initialize unmarshal
 * call and stay empty for every other call, without disturbing the delegated conversion.
 */
@DisplayName("InitializeResultCapturingTransport")
class InitializeResultCapturingTransportTests {

	private static final TypeRef<McpSchema.InitializeResult> INITIALIZE_REF = McpAsyncClient.INITIALIZE_RESULT_TYPE_REF;

	private static final TypeRef<McpSchema.ListToolsResult> OTHER_REF = new TypeRef<>() {
	};

	private final McpClientTransport delegate = mock(McpClientTransport.class);

	private final InitializeResultCapturingTransport transport = new InitializeResultCapturingTransport(this.delegate);

	@Nested
	@DisplayName("capture")
	class Capture {

		@Test
		@DisplayName("captures the raw map of the initialize unmarshal and delegates the conversion")
		void capturesInitializeResultMap() {
			final Map<String, Object> raw = Map.of("protocolVersion", "2025-06-18", "capabilities",
					Map.of("extensions", Map.of("io.modelcontextprotocol/skills", Map.of("directoryRead", true))));
			final McpSchema.InitializeResult typed = new McpSchema.InitializeResult("2025-06-18",
					McpSchema.ServerCapabilities.builder().build(), new McpSchema.Implementation("s", "1"), null, null);
			given(InitializeResultCapturingTransportTests.this.delegate.unmarshalFrom(raw, INITIALIZE_REF))
				.willReturn(typed);

			final McpSchema.InitializeResult result = InitializeResultCapturingTransportTests.this.transport
				.unmarshalFrom(raw, INITIALIZE_REF);

			assertThat(result).isSameAs(typed);
			assertThat(InitializeResultCapturingTransportTests.this.transport.rawInitializeResult()).isEqualTo(raw);
		}

		@Test
		@DisplayName("non-map initialize data leaves the capture empty")
		void nonMapInitializeDataLeavesCaptureEmpty() {
			given(InitializeResultCapturingTransportTests.this.delegate.unmarshalFrom("junk", INITIALIZE_REF))
				.willThrow(new IllegalArgumentException("junk"));

			try {
				InitializeResultCapturingTransportTests.this.transport.unmarshalFrom("junk", INITIALIZE_REF);
			}
			catch (final IllegalArgumentException ignored) {
				// the delegate's behaviour is preserved
			}

			assertThat(InitializeResultCapturingTransportTests.this.transport.rawInitializeResult()).isNull();
		}

		@Test
		@DisplayName("unmarshal calls for other methods do not touch the capture")
		void otherUnmarshalCallsDoNotCapture() {
			final McpSchema.ListToolsResult typed = new McpSchema.ListToolsResult(List.of(), null);
			given(InitializeResultCapturingTransportTests.this.delegate.unmarshalFrom(Map.of(), OTHER_REF))
				.willReturn(typed);

			InitializeResultCapturingTransportTests.this.transport.unmarshalFrom(Map.of(), OTHER_REF);

			assertThat(InitializeResultCapturingTransportTests.this.transport.rawInitializeResult()).isNull();
		}

		@Test
		@DisplayName("the first initialize frame wins on repeated calls")
		void firstFrameWins() {
			final Map<String, Object> first = Map.of("protocolVersion", "2025-06-18");
			final Map<String, Object> second = Map.of("protocolVersion", "2025-11-25");
			given(InitializeResultCapturingTransportTests.this.delegate.unmarshalFrom(any(), any())).willReturn(null);

			InitializeResultCapturingTransportTests.this.transport.unmarshalFrom(first, INITIALIZE_REF);
			InitializeResultCapturingTransportTests.this.transport.unmarshalFrom(second, INITIALIZE_REF);

			assertThat(InitializeResultCapturingTransportTests.this.transport.rawInitializeResult()).isEqualTo(first);
		}

	}

	@Nested
	@DisplayName("delegation")
	class Delegation {

		@Test
		@DisplayName("connect delegates to the wrapped transport")
		void connectDelegates() {
			final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler = (m) -> m;
			given(InitializeResultCapturingTransportTests.this.delegate.connect(handler)).willReturn(Mono.empty());

			InitializeResultCapturingTransportTests.this.transport.connect(handler);

			then(InitializeResultCapturingTransportTests.this.delegate).should().connect(handler);
		}

		@Test
		@DisplayName("sendMessage delegates to the wrapped transport")
		void sendMessageDelegates() {
			final McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(
					"notifications/initialized");
			given(InitializeResultCapturingTransportTests.this.delegate.sendMessage(notification))
				.willReturn(Mono.empty());

			InitializeResultCapturingTransportTests.this.transport.sendMessage(notification);

			then(InitializeResultCapturingTransportTests.this.delegate).should().sendMessage(notification);
		}

		@Test
		@DisplayName("protocolVersions delegates to the wrapped transport")
		void protocolVersionsDelegate() {
			final List<String> versions = List.of("2025-06-18");
			given(InitializeResultCapturingTransportTests.this.delegate.protocolVersions()).willReturn(versions);

			assertThat(InitializeResultCapturingTransportTests.this.transport.protocolVersions()).isSameAs(versions);
		}

		@Test
		@DisplayName("wrapIfObserved wraps when observed and passes through when not")
		void wrapIfObservedBehaviour() {
			final AtomicReference<InitializeResultCapturingTransport> capture = new AtomicReference<>();
			final McpClientTransport wrapped = InitializeResultCapturingTransport
				.wrapIfObserved(InitializeResultCapturingTransportTests.this.delegate, capture);
			assertThat(wrapped).isInstanceOf(InitializeResultCapturingTransport.class);
			assertThat(capture.get()).isSameAs(wrapped);

			assertThat(InitializeResultCapturingTransport
				.wrapIfObserved(InitializeResultCapturingTransportTests.this.delegate, null))
				.isSameAs(InitializeResultCapturingTransportTests.this.delegate);
		}

		@Test
		@DisplayName("close delegates to the wrapped transport")
		void closeDelegates() {
			InitializeResultCapturingTransportTests.this.transport.close();

			then(InitializeResultCapturingTransportTests.this.delegate).should().close();
		}

		@Test
		@DisplayName("setExceptionHandler delegates to the wrapped transport")
		void exceptionHandlerDelegates() {
			final Consumer<Throwable> handler = (t) -> {
			};

			InitializeResultCapturingTransportTests.this.transport.setExceptionHandler(handler);

			then(InitializeResultCapturingTransportTests.this.delegate).should().setExceptionHandler(handler);
		}

	}

}
