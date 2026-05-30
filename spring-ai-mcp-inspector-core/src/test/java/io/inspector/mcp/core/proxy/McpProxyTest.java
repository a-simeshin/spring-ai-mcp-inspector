/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link McpProxy}. */
@Epic("MCP Inspector Core")
@Feature("MCP JSON-RPC frame relay")
class McpProxyTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private McpProxy proxy;

	private McpClientTransport transport;

	private Sinks.Many<JsonNode> browserToTarget;

	private Sinks.Many<JsonNode> targetToBrowser;

	private ProxySession session;

	@BeforeEach
	void setUp() {
		this.proxy = new McpProxy(mapper);
		this.transport = mock(McpClientTransport.class);
		when(this.transport.closeGracefully()).thenReturn(Mono.empty());
		this.browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		this.targetToBrowser = Sinks.many().replay().limit(64);
		this.session = new ProxySession("s-1", transport, browserToTarget, targetToBrowser);
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
			when(transport.connect(any())).thenReturn(Mono.empty());
			when(transport.sendMessage(any())).thenReturn(Mono.empty());
			proxy.start(session);
			final JsonNode frame = mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 7)
				.put("method", "tools/list");

			// when
			browserToTarget.tryEmitNext(frame);

			// then
			final ArgumentCaptor<JSONRPCMessage> captor = ArgumentCaptor.forClass(JSONRPCMessage.class);
			verify(transport, timeout(1000)).sendMessage(captor.capture());
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
			when(transport.connect(any())).thenReturn(Mono.empty());
			proxy.start(session);
			final JsonNode malformed = mapper.createObjectNode().put("totally", "not-json-rpc");

			// when
			browserToTarget.tryEmitNext(malformed);

			// then
			verify(transport, never()).sendMessage(any());
		}

		@Test
		@Story("sendMessage failure is contained")
		@Severity(SeverityLevel.NORMAL)
		@Description("a sendMessage failure is logged and the relay keeps forwarding subsequent frames")
		void start_whenSendMessageFails_continuesRelayingNextFrame() {
			// given
			when(transport.connect(any())).thenReturn(Mono.empty());
			when(transport.sendMessage(any())).thenReturn(Mono.error(new RuntimeException("send failed")),
					Mono.empty());
			proxy.start(session);

			// when — two valid frames; the first send errors, the second must still be
			// relayed
			browserToTarget.tryEmitNext(mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "ping"));
			browserToTarget
				.tryEmitNext(mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"));

			// then
			verify(transport, timeout(1000).times(2)).sendMessage(any());
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
			when(transport.connect(handlerCaptor.capture())).thenReturn(Mono.empty());
			final java.time.Instant before = session.lastActivity();

			// when
			proxy.start(session);
			final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = handlerCaptor.getValue();
			final McpSchema.JSONRPCResponse inbound = new McpSchema.JSONRPCResponse("2.0", 7,
					mapper.createObjectNode().put("ok", true), null);
			final Mono<JSONRPCMessage> reply = handler.apply(Mono.just(inbound));

			// then — handler returns empty (proxy never originates a reply)
			StepVerifier.create(reply).verifyComplete();
			// and the inbound frame was emitted to the browser sink
			StepVerifier.create(targetToBrowser.asFlux().next())
				.assertNext(node -> assertThat(node.get("id").asInt()).isEqualTo(7))
				.thenCancel()
				.verify(Duration.ofSeconds(1));
			assertThat(session.lastActivity()).isAfterOrEqualTo(before);
		}

		@Test
		@Story("Browser sink emit failure is contained")
		@Severity(SeverityLevel.NORMAL)
		@Description("when the browser sink is already complete, the connect handler logs the emit failure "
				+ "and still returns an empty reply")
		@SuppressWarnings("unchecked")
		void start_whenBrowserSinkComplete_handlerSwallowsEmitFailure() {
			// given — complete the browser sink so tryEmitNext reports a failure result
			targetToBrowser.tryEmitComplete();
			final ArgumentCaptor<Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>> handlerCaptor = ArgumentCaptor
				.forClass(Function.class);
			when(transport.connect(handlerCaptor.capture())).thenReturn(Mono.empty());
			proxy.start(session);
			final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = handlerCaptor.getValue();
			final McpSchema.JSONRPCResponse inbound = new McpSchema.JSONRPCResponse("2.0", 1,
					mapper.createObjectNode().put("ok", true), null);

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
			when(transport.connect(any())).thenReturn(Mono.empty());

			// when
			final Mono<Void> started = proxy.start(session);

			// then
			StepVerifier.create(started).verifyComplete();
			verify(transport).connect(any());
		}

		@Test
		@Story("Connect failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("a connect() failure is surfaced through the returned Mono")
		void start_whenConnectFails_propagatesError() {
			// given
			when(transport.connect(any())).thenReturn(Mono.error(new IllegalStateException("connect failed")));

			// when
			final Mono<Void> started = proxy.start(session);

			// then
			StepVerifier.create(started)
				.expectErrorMatches(
						err -> err instanceof IllegalStateException && err.getMessage().equals("connect failed"))
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
			when(transport.connect(any())).thenReturn(Mono.empty());

			// when
			final Mono<Void> started = nullMapperProxy.start(session);

			// then
			StepVerifier.create(started).verifyComplete();
		}

	}

}
