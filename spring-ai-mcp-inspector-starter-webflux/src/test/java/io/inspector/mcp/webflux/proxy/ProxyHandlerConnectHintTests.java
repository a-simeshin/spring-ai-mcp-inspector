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

package io.inspector.mcp.webflux.proxy;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.EntityResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.timeline.McpTrafficRecorder;
import io.inspector.mcp.core.transport.TransportDetector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * WebFlux integration tests for the connect-hint path of {@link ProxyHandler} (spec card
 * t_42b235d6, issue #250): a real JDK {@link HttpServer} plays the upstream and the test
 * asserts that the JSON error payload shape is IDENTICAL to the WebMvc one (spec section
 * 10) for the matrix cells M1, M3 and the no-probe cell M6.
 */
@Epic("WebFlux Inspector")
@Feature("ProxyHandler connect hint")
class ProxyHandlerConnectHintTests {

	private HttpServer server;

	private final Map<String, StubAnswer> answers = new ConcurrentHashMap<>();

	private ProxySessionRegistry registry;

	private ProxyTransportFactory transportFactory;

	private McpProxy mcpProxy;

	private TransportDetector transportDetector;

	private JsonMapper objectMapper;

	private McpTrafficRecorder trafficRecorder;

	private ProxyHandler handler;

	@BeforeEach
	void setUp() throws Exception {
		this.answers.clear();
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::dispatch);
		this.server.start();
		this.registry = mock(ProxySessionRegistry.class);
		this.transportFactory = mock(ProxyTransportFactory.class);
		this.mcpProxy = mock(McpProxy.class);
		given(this.mcpProxy.start(any())).willReturn(Mono.empty());
		this.transportDetector = mock(TransportDetector.class);
		this.objectMapper = new JsonMapper();
		this.trafficRecorder = mock(McpTrafficRecorder.class);
		this.handler = new ProxyHandler(this.registry, this.transportFactory, this.mcpProxy, this.transportDetector,
				this.objectMapper, new McpInspectorProperties(), null, null, null, null, null, this.trafficRecorder);
	}

	@AfterEach
	void tearDown() {
		this.server.stop(0);
	}

	private void dispatch(final HttpExchange exchange) {
		final StubAnswer answer = this.answers.getOrDefault(exchange.getRequestURI().getPath(),
				new StubAnswer(404, Map.of()));
		try (exchange) {
			answer.headers().forEach(exchange.getResponseHeaders()::add);
			exchange.sendResponseHeaders(answer.status(), -1);
		}
		catch (final Exception ex) {
			// stub
		}
	}

	private JsonNode postConnect() {
		final MockServerHttpRequest httpRequest = MockServerHttpRequest
			.post("/mcp-inspector-api/mcp?url=http://127.0.0.1:" + this.server.getAddress().getPort() + "/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
		final ServerRequest request = ServerRequest.create(MockServerWebExchange.from(httpRequest), STRATEGIES);
		final ServerResponse response = this.handler.postMcp(request).block();
		assertThat(response).isNotNull();
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		@SuppressWarnings("unchecked")
		final Map<String, Object> body = (Map<String, Object>) ((EntityResponse<Object>) response).entity();
		final JsonNode error = this.objectMapper.valueToTree(body).path("error");
		assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
		return error;
	}

	private static final org.springframework.http.codec.ServerCodecConfigurer SERVER_CODECS = org.springframework.http.codec.ServerCodecConfigurer
		.create();

	private static final java.util.List<org.springframework.http.codec.HttpMessageReader<?>> STRATEGIES = SERVER_CODECS
		.getReaders();

	@Nested
	@DisplayName("Wire contract identical to WebMvc (spec section 10)")
	class Matrix {

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M1: upstream 404 with SSE alive on /sse adds hint.kind=sse_only with probePath and refs")
		void m1_sseAliveOnSibling_hintSseOnly() {
			ProxyHandlerConnectHintTests.this.answers.put("/mcp", new StubAnswer(404, Map.of()));
			ProxyHandlerConnectHintTests.this.answers.put("/sse",
					new StubAnswer(200, Map.of("Content-Type", "text/event-stream")));
			given(ProxyHandlerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new McpSdkNotFoundException());
			final JsonNode error = ProxyHandlerConnectHintTests.this.postConnect();
			assertThat(error.path("reason").asText()).isEqualTo("not_found");
			assertThat(error.path("message").asText()).isEqualTo("server responded with 404: check the URL");
			assertThat(error.path("hint").path("kind").asText()).isEqualTo("sse_only");
			assertThat(error.path("hint").path("probePath").asText()).isEqualTo("/sse");
			assertThat(error.path("hint").path("refs").path("upstream").asText())
				.isEqualTo("spring-projects/spring-ai#7207");
			assertThat(error.path("hint").path("refs").path("local").asText()).isEqualTo("#250");
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M3: 404 with dead SSE on both paths adds hint.kind=maybe_sse")
		void m3_deadSibling_hintMaybeSse() {
			ProxyHandlerConnectHintTests.this.answers.put("/mcp", new StubAnswer(404, Map.of()));
			ProxyHandlerConnectHintTests.this.answers.put("/sse", new StubAnswer(404, Map.of()));
			given(ProxyHandlerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new McpSdkNotFoundException());
			final JsonNode error = ProxyHandlerConnectHintTests.this.postConnect();
			assertThat(error.path("hint").path("kind").asText()).isEqualTo("maybe_sse");
			assertThat(error.path("hint").path("probePath").asText()).isEqualTo("/sse");
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M6: DNS failure never probes and adds no hint")
		void m6_dnsFailure_noHintNoProbe() {
			given(ProxyHandlerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new RuntimeException(new java.net.UnknownHostException("no-such-host.invalid")));
			final MockServerHttpRequest httpRequest = MockServerHttpRequest
				.post("/mcp-inspector-api/mcp?url=http://no-such-host.invalid:1/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
			final ServerRequest request = ServerRequest.create(MockServerWebExchange.from(httpRequest), STRATEGIES);
			final ServerResponse response = ProxyHandlerConnectHintTests.this.handler.postMcp(request).block();
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			@SuppressWarnings("unchecked")
			final Map<String, Object> body = (Map<String, Object>) ((EntityResponse<Object>) response).entity();
			final JsonNode error = ProxyHandlerConnectHintTests.this.objectMapper.valueToTree(body).path("error");
			assertThat(error.path("reason").asText()).isEqualTo("dns");
			assertThat(error.has("hint")).isFalse();
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("Timeline: MCP_CONNECT_FAILED recorded once per failed connect with reason/probeOutcome/hintKind")
		void timelineEventRecorded() {
			ProxyHandlerConnectHintTests.this.answers.put("/mcp", new StubAnswer(404, Map.of()));
			ProxyHandlerConnectHintTests.this.answers.put("/sse",
					new StubAnswer(200, Map.of("Content-Type", "text/event-stream")));
			given(ProxyHandlerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new McpSdkNotFoundException());
			ProxyHandlerConnectHintTests.this.postConnect();
			final ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
			verify(ProxyHandlerConnectHintTests.this.trafficRecorder, times(1)).recordConnectFailed(anyString(),
					payloadCaptor.capture());
			final JsonNode payload = payloadCaptor.getValue();
			assertThat(payload.path("reason").asText()).isEqualTo("not_found");
			assertThat(payload.path("probeOutcome").asText()).isEqualTo("alive");
			assertThat(payload.path("hintKind").asText()).isEqualTo("sse_only");
		}

	}

	/** Scripted answer for one upstream path. */
	private record StubAnswer(int status, Map<String, String> headers) {
	}

	private static final class McpSdkNotFoundException extends io.modelcontextprotocol.spec.McpTransportException {

		McpSdkNotFoundException() {
			super("Server Not Found. Status code:404, body: not found", null);
		}

	}

}
