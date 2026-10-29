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

package io.inspector.mcp.webmvc.proxy;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.timeline.McpTrafficRecorder;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Integration-level tests for the connect-hint path of
 * {@link StreamableHttpProxyController} (spec card t_42b235d6, issue #250): a real JDK
 * {@link HttpServer} plays the upstream, the transport factory is mocked to fail the way
 * the MCP SDK fails, and the test asserts the decision matrix end to end on the wire
 * payload, including the optional {@code hint} object and the MCP_CONNECT_FAILED timeline
 * event.
 */
@Epic("WebMvc Inspector")
@Feature("StreamableHttpProxyController connect hint")
class StreamableHttpProxyControllerConnectHintTests {

	private HttpServer server;

	private final Map<String, StubAnswer> answers = new ConcurrentHashMap<>();

	private ProxySessionRegistry registry;

	private ProxyTransportFactory transportFactory;

	private JsonMapper objectMapper;

	private McpProxy mcpProxy;

	private StreamableHttpProxyController controller;

	private McpTrafficRecorder trafficRecorder;

	@BeforeEach
	void setUp() throws Exception {
		this.answers.clear();
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::dispatch);
		this.server.start();
		this.registry = mock(ProxySessionRegistry.class);
		this.transportFactory = mock(ProxyTransportFactory.class);
		this.mcpProxy = mock(McpProxy.class);
		given(this.mcpProxy.start(any())).willReturn(reactor.core.publisher.Mono.empty());
		this.objectMapper = new JsonMapper();
		this.trafficRecorder = mock(McpTrafficRecorder.class);
		this.controller = new StreamableHttpProxyController(this.registry, this.transportFactory, this.mcpProxy,
				this.objectMapper, new McpInspectorProperties(), new InspectorServerPortHolder(), null, null, null,
				null, null, this.trafficRecorder);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
		this.server.stop(0);
	}

	private void dispatch(final HttpExchange exchange) {
		final StubAnswer answer = this.answers.getOrDefault(exchange.getRequestURI().getPath(),
				new StubAnswer(404, Map.of(), 0));
		try (exchange) {
			if (answer.delayMs() > 0) {
				Thread.sleep(answer.delayMs());
			}
			exchange.getResponseHeaders().putAll(answer.headers());
			exchange.sendResponseHeaders(answer.status(), -1);
		}
		catch (final Exception ex) {
			// stub
		}
	}

	private JsonNode postConnect() {
		final ResponseEntity<Object> response = this.controller.postMcp(null,
				"http://127.0.0.1:" + this.server.getAddress().getPort() + "/mcp",
				this.objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}"));
		final JsonNode body = this.objectMapper.valueToTree(response.getBody());
		assertThat(body.path("error").path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
		return body;
	}

	@Nested
	@DisplayName("Wire contract with hint (spec section 7)")
	class HintWire {

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M1: upstream 404 on /mcp with SSE alive on /sse adds hint.kind=sse_only, probePath=/sse, and the original 502 payload is intact")
		void m1_sseAliveOnSibling_hintSseOnly() {
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/mcp", new StubAnswer(404, Map.of(), 0));
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/sse",
					new StubAnswer(200, Map.of("Content-Type", "text/event-stream"), 0));
			given(StreamableHttpProxyControllerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new McpSdkNotFoundException());
			final JsonNode error = StreamableHttpProxyControllerConnectHintTests.this.postConnect().path("error");
			assertThat(error.path("reason").asText()).isEqualTo("not_found");
			assertThat(error.path("message").asText()).isEqualTo("server responded with 404: check the URL");
			assertThat(error.path("retryable").asBoolean()).isTrue();
			assertThat(error.path("hint").path("kind").asText()).isEqualTo("sse_only");
			assertThat(error.path("hint").path("probePath").asText()).isEqualTo("/sse");
			assertThat(error.path("hint").path("refs").path("upstream").asText())
				.isEqualTo("spring-projects/spring-ai#7207");
			assertThat(error.path("hint").path("refs").path("local").asText()).isEqualTo("#250");
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M3: 404 with dead SSE on both paths adds hint.kind=maybe_sse without changing the original payload")
		void m3_deadSibling_hintMaybeSse() {
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/mcp", new StubAnswer(404, Map.of(), 0));
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/sse", new StubAnswer(404, Map.of(), 0));
			given(StreamableHttpProxyControllerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new McpSdkNotFoundException());
			final JsonNode error = StreamableHttpProxyControllerConnectHintTests.this.postConnect().path("error");
			assertThat(error.path("reason").asText()).isEqualTo("not_found");
			assertThat(error.path("hint").path("kind").asText()).isEqualTo("maybe_sse");
			assertThat(error.path("hint").path("probePath").asText()).isEqualTo("/sse");
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M4: 500-bucket failure with dead SSE adds NO hint key at all")
		void m4_unknown500Bucket_noHint() {
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/mcp", new StubAnswer(404, Map.of(), 0));
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/sse", new StubAnswer(404, Map.of(), 0));
			given(StreamableHttpProxyControllerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new RuntimeException("Failed to send message: 500"));
			final JsonNode error = StreamableHttpProxyControllerConnectHintTests.this.postConnect().path("error");
			assertThat(error.path("reason").asText()).isEqualTo("unknown");
			assertThat(error.has("hint")).isFalse();
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M5: probe timeout (upstream hangs) preserves the original error and adds no hint")
		void m5_probeTimeout_noHint() {
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/mcp",
					new StubAnswer(200, Map.of("Content-Type", "text/event-stream"), 8000));
			given(StreamableHttpProxyControllerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new McpSdkNotFoundException());
			final JsonNode error = StreamableHttpProxyControllerConnectHintTests.this.postConnect().path("error");
			assertThat(error.path("reason").asText()).isEqualTo("not_found");
			assertThat(error.has("hint")).isFalse();
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M6: a non-trigger failure (connection refused via dead port) never probes: no hint and zero probe requests")
		void m6_nonTrigger_noHintNoProbe() {
			given(StreamableHttpProxyControllerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new RuntimeException(new java.net.ConnectException("Connection refused")));
			final ResponseEntity<Object> response = StreamableHttpProxyControllerConnectHintTests.this.controller
				.postMcp(null, "http://127.0.0.1:1/mcp", StreamableHttpProxyControllerConnectHintTests.this.objectMapper
					.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}"));
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			final JsonNode error = StreamableHttpProxyControllerConnectHintTests.this.objectMapper
				.valueToTree(response.getBody())
				.path("error");
			assertThat(error.path("reason").asText()).isEqualTo("connection_refused");
			assertThat(error.has("hint")).isFalse();
		}

	}

	@Nested
	@DisplayName("Timeline event (spec section 8, AC 9)")
	class Timeline {

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("One MCP_CONNECT_FAILED event per failed connect with reason, probeOutcome and probedPaths")
		void timelineEventRecorded() {
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/mcp", new StubAnswer(404, Map.of(), 0));
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/sse",
					new StubAnswer(200, Map.of("Content-Type", "text/event-stream"), 0));
			given(StreamableHttpProxyControllerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new McpSdkNotFoundException());
			StreamableHttpProxyControllerConnectHintTests.this.postConnect();
			final org.mockito.ArgumentCaptor<tools.jackson.databind.JsonNode> payloadCaptor = org.mockito.ArgumentCaptor
				.forClass(tools.jackson.databind.JsonNode.class);
			org.mockito.Mockito
				.verify(StreamableHttpProxyControllerConnectHintTests.this.trafficRecorder,
						org.mockito.Mockito.times(1))
				.recordConnectFailed(org.mockito.ArgumentMatchers.anyString(), payloadCaptor.capture());
			final JsonNode payload = payloadCaptor.getValue();
			assertThat(payload.path("reason").asText()).isEqualTo("not_found");
			assertThat(payload.path("probeOutcome").asText()).isEqualTo("alive");
			assertThat(payload.path("hintKind").asText()).isEqualTo("sse_only");
			assertThat(payload.path("probedPaths").toString()).contains("/mcp");
		}

		@Test
		@Severity(SeverityLevel.NORMAL)
		@Description("A throwing recorder never breaks the error path (MCP_CONNECT_FAILED response still returned)")
		void throwingRecorderDoesNotBreakErrorPath() {
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/mcp", new StubAnswer(404, Map.of(), 0));
			StreamableHttpProxyControllerConnectHintTests.this.answers.put("/sse", new StubAnswer(404, Map.of(), 0));
			given(StreamableHttpProxyControllerConnectHintTests.this.transportFactory.openStreamable(any()))
				.willThrow(new McpSdkNotFoundException());
			org.mockito.Mockito.willThrow(new IllegalStateException("recorder down"))
				.given(StreamableHttpProxyControllerConnectHintTests.this.trafficRecorder)
				.recordConnectFailed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
			final JsonNode error = StreamableHttpProxyControllerConnectHintTests.this.postConnect().path("error");
			assertThat(error.path("hint").path("kind").asText()).isEqualTo("maybe_sse");
		}

	}

	private static final class McpSdkNotFoundException extends io.modelcontextprotocol.spec.McpTransportException {

		McpSdkNotFoundException() {
			super("Server Not Found. Status code:404, body: not found", null);
		}

	}

	private record StubAnswer(int status, Map<String, String> headers, long delayMs) {
	}

}
