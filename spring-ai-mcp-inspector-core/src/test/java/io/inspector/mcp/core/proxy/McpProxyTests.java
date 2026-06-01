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

package io.inspector.mcp.core.proxy;

import java.time.Duration;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link McpProxy}. */
@Epic("MCP Inspector Core")
@Feature("MCP JSON-RPC frame relay")
class McpProxyTests {

	private final ObjectMapper mapper = new ObjectMapper();

	private McpProxy proxy;

	private McpClientTransport transport;

	private Sinks.Many<JsonNode> browserToTarget;

	private Sinks.Many<JsonNode> targetToBrowser;

	private ProxySession session;

	@BeforeEach
	void setUp() {
		this.proxy = new McpProxy(this.mapper);
		this.transport = mock(McpClientTransport.class);
		given(this.transport.closeGracefully()).willReturn(Mono.empty());
		this.browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		this.targetToBrowser = Sinks.many().replay().limit(64);
		this.session = new ProxySession("s-1", this.transport, this.browserToTarget, this.targetToBrowser);
	}

	@Nested
	@DisplayName("start() — browser → target")
	class BrowserToTarget {

		@Test
		@Story("Relay browser frame to target")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a JSON frame pushed into browserToTarget is deserialized and forwarded to the target transport")
		void start_relaysBrowserFrameToTargetTransport() {
			// given
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);
			final JsonNode frame = McpProxyTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 7)
				.put("method", "tools/list");

			// when
			McpProxyTests.this.browserToTarget.tryEmitNext(frame);

			// then
			final ArgumentCaptor<JSONRPCMessage> captor = ArgumentCaptor.forClass(JSONRPCMessage.class);
			verify(McpProxyTests.this.transport, timeout(1000)).sendMessage(captor.capture());
			assertThat(captor.getValue()).isInstanceOf(McpSchema.JSONRPCRequest.class);
			final McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) captor.getValue();
			assertThat(request.method()).isEqualTo("tools/list");
		}

		@Test
		@Story("Malformed frame is dropped")
		@Severity(SeverityLevel.NORMAL)
		@Description("a malformed JSON-RPC frame is dropped and never reaches the target transport")
		void start_withMalformedFrame_doesNotForwardToTarget() {
			// given
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);
			final JsonNode malformed = McpProxyTests.this.mapper.createObjectNode().put("totally", "not-json-rpc");

			// when
			McpProxyTests.this.browserToTarget.tryEmitNext(malformed);

			// then
			verify(McpProxyTests.this.transport, never()).sendMessage(any());
		}

		@Test
		@Story("sendMessage failure is contained")
		@Severity(SeverityLevel.NORMAL)
		@Description("a sendMessage failure is logged and the relay keeps forwarding subsequent frames")
		void start_whenSendMessageFails_continuesRelayingNextFrame() {
			// given
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any()))
				.willReturn(Mono.error(new RuntimeException("send failed")), Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// when — two valid frames; the first send errors, the second must still be
			// relayed
			McpProxyTests.this.browserToTarget
				.tryEmitNext(McpProxyTests.this.mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "ping"));
			McpProxyTests.this.browserToTarget.tryEmitNext(McpProxyTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 2)
				.put("method", "tools/list"));

			// then
			verify(McpProxyTests.this.transport, timeout(1000).times(2)).sendMessage(any());
		}

	}

	@Nested
	@DisplayName("start() — target → browser")
	class TargetToBrowser {

		@Test
		@Story("Relay target frame to browser")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the connect handler converts an inbound typed frame to a JsonNode, "
				+ "emits it on targetToBrowser and touches the session")
		@SuppressWarnings("unchecked")
		void start_relaysTargetFrameToBrowserSinkAndTouches() {
			// given
			final ArgumentCaptor<Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>> handlerCaptor = ArgumentCaptor
				.forClass(Function.class);
			given(McpProxyTests.this.transport.connect(handlerCaptor.capture())).willReturn(Mono.empty());
			final java.time.Instant before = McpProxyTests.this.session.lastActivity();

			// when
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);
			final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = handlerCaptor.getValue();
			final McpSchema.JSONRPCResponse inbound = new McpSchema.JSONRPCResponse("2.0", 7,
					McpProxyTests.this.mapper.createObjectNode().put("ok", true), null);
			final Mono<JSONRPCMessage> reply = handler.apply(Mono.just(inbound));

			// then — handler returns empty (proxy never originates a reply)
			StepVerifier.create(reply).verifyComplete();
			// and the inbound frame was emitted to the browser sink
			StepVerifier.create(McpProxyTests.this.targetToBrowser.asFlux().next())
				.assertNext((node) -> assertThat(node.get("id").asInt()).isEqualTo(7))
				.thenCancel()
				.verify(Duration.ofSeconds(1));
			assertThat(McpProxyTests.this.session.lastActivity()).isAfterOrEqualTo(before);
		}

		@Test
		@Story("Browser sink emit failure is contained")
		@Severity(SeverityLevel.NORMAL)
		@Description("when the browser sink is already complete, the connect handler logs the emit failure "
				+ "and still returns an empty reply")
		@SuppressWarnings("unchecked")
		void start_whenBrowserSinkComplete_handlerSwallowsEmitFailure() {
			// given — complete the browser sink so tryEmitNext reports a failure result
			McpProxyTests.this.targetToBrowser.tryEmitComplete();
			final ArgumentCaptor<Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>> handlerCaptor = ArgumentCaptor
				.forClass(Function.class);
			given(McpProxyTests.this.transport.connect(handlerCaptor.capture())).willReturn(Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);
			final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = handlerCaptor.getValue();
			final McpSchema.JSONRPCResponse inbound = new McpSchema.JSONRPCResponse("2.0", 1,
					McpProxyTests.this.mapper.createObjectNode().put("ok", true), null);

			// when
			final Mono<JSONRPCMessage> reply = handler.apply(Mono.just(inbound));

			// then — the handler never originates a reply even when emission fails
			StepVerifier.create(reply).verifyComplete();
		}

	}

	@Nested
	@DisplayName("start() — connect wiring")
	class ConnectWiring {

		@Test
		@Story("Readiness signal")
		@Severity(SeverityLevel.NORMAL)
		@Description("start() returns the Mono produced by the transport's connect() call")
		void start_returnsConnectReadinessMono() {
			// given
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());

			// when
			final Mono<Void> started = McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// then
			StepVerifier.create(started).verifyComplete();
			verify(McpProxyTests.this.transport).connect(any());
		}

		@Test
		@Story("Connect failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("a connect() failure is surfaced through the returned Mono")
		void start_whenConnectFails_propagatesError() {
			// given
			given(McpProxyTests.this.transport.connect(any()))
				.willReturn(Mono.error(new IllegalStateException("connect failed")));

			// when
			final Mono<Void> started = McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// then
			StepVerifier.create(started)
				.expectErrorMatches(
						(err) -> err instanceof IllegalStateException && err.getMessage().equals("connect failed"))
				.verify(Duration.ofSeconds(1));
		}

	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@Story("Null ObjectMapper fallback")
		@Severity(SeverityLevel.MINOR)
		@Description("a null ObjectMapper falls back to a default instance and the proxy still wires connect()")
		void constructor_withNullMapper_stillWiresProxy() {
			// given
			final McpProxy nullMapperProxy = new McpProxy(null);
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());

			// when
			final Mono<Void> started = nullMapperProxy.start(McpProxyTests.this.session);

			// then
			StepVerifier.create(started).verifyComplete();
		}

	}

}
