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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.timeline.BoundedTimelineService;
import io.inspector.mcp.core.timeline.McpTrafficRecorder;
import io.inspector.mcp.core.timeline.TimelineEventType;
import io.inspector.mcp.core.timeline.TimelineQuery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style tests for the SSE connect-hint wiring of
 * {@link StreamableHttpProxyController} (issue #250): the controller talks to a real JDK
 * stub server through the real mcp-core streamable transport, so the classified failure
 * bucket and the probe run on the production path.
 *
 * <p>
 * Payload shape is asserted field by field against the WebFlux twin
 * ({@code io.inspector.mcp.webflux.proxy.ProxyHandlerConnectHintTests}) - AC 8.
 */
@DisplayName("StreamableHttpProxyController connect hint")
class StreamableHttpProxyControllerConnectHintTests {

	private HttpServer upstream;

	private ProxySessionRegistry registry;

	private ProxyTransportFactory transportFactory;

	private McpProxy mcpProxy;

	private JsonMapper objectMapper;

	private StreamableHttpProxyController controller;

	private McpInspectorProperties properties;

	private BoundedTimelineService timeline;

	private String upstreamBase;

	@BeforeEach
	void setUp() throws IOException {
		this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.upstream.start();
		this.upstreamBase = "http://127.0.0.1:" + this.upstream.getAddress().getPort();
		this.registry = new ProxySessionRegistry();
		this.transportFactory = new ProxyTransportFactory(new JsonMapper());
		this.mcpProxy = new McpProxy(new JsonMapper());
		this.objectMapper = new JsonMapper();
		this.timeline = new BoundedTimelineService(20);
		this.properties = new McpInspectorProperties();
		this.properties.getTimeouts().setStreamableRequest(Duration.ofSeconds(3));
		this.controller = new StreamableHttpProxyController(this.registry, this.transportFactory, this.mcpProxy,
				this.objectMapper, this.properties, null, null, new McpTrafficRecorder(this.timeline));
		final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
		if (this.upstream != null) {
			this.upstream.stop(0);
		}
	}

	/** Serves SSE at /sse, 404 or the given status at /mcp. */
	private void stubUpstream(final int statusAtMcp, final boolean sseAlive, final String contentType,
			final boolean allowSse) {
		final AtomicInteger hits = new AtomicInteger();
		this.upstream.createContext("/mcp", (exchange) -> {
			hits.incrementAndGet();
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			final byte[] body = "{\"error\":\"not found\"}".getBytes();
			exchange.sendResponseHeaders(statusAtMcp, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		this.upstream.createContext("/sse", (exchange) -> {
			if (!sseAlive) {
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			exchange.getResponseHeaders().add("Content-Type", contentType);
			if (allowSse) {
				exchange.getResponseHeaders().add("Allow", "GET, text/event-stream");
			}
			// Alive: keep the stream open; the probe closes it from the client side.
			exchange.sendResponseHeaders(200, -1);
		});
	}

	private JsonNode postConnect() {
		final ResponseEntity<Object> response = this.controller.postMcp(null, this.upstreamBase + "/mcp",
				this.objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		return this.objectMapper.valueToTree(response.getBody()).path("error");
	}

	@Test
	@DisplayName("M1: 404 upstream + live SSE sibling => sse_only hint with probePath /sse")
	void m1_sseAliveSiblingAppendsHint() {
		stubUpstream(404, true, "text/event-stream", false);
		final JsonNode error = postConnect();
		assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
		assertThat(error.path("reason").asText()).isEqualTo("not_found");
		assertThat(error.path("message").asText()).isEqualTo("server responded with 404: check the URL");
		assertThat(error.has("retryable")).isTrue();
		assertThat(error.path("hint").path("kind").asText()).isEqualTo("sse_only");
		assertThat(error.path("hint").path("probePath").asText()).isEqualTo("/sse");
		assertThat(error.path("hint").path("refs").path("upstream").asText())
			.isEqualTo("spring-projects/spring-ai#7207");
		assertThat(error.path("hint").path("refs").path("local").asText()).isEqualTo("#250");
	}

	@Test
	@DisplayName("M2: 405 (Failed to send message bucket) + live SSE => same sse_only hint")
	void m2_405WithSseAliveAppendsHint() {
		stubUpstream(405, true, "text/event-stream", false);
		final JsonNode error = postConnect();
		assertThat(error.path("reason").asText()).isEqualTo("unknown");
		assertThat(error.path("hint").path("kind").asText()).isEqualTo("sse_only");
		assertThat(error.path("hint").path("probePath").asText()).isEqualTo("/sse");
	}

	@Test
	@DisplayName("M3: 404 upstream + dead SSE => maybe_sse hint, original payload intact")
	void m3_deadSseAppendsWeakHint() {
		stubUpstream(404, false, "application/json", false);
		final JsonNode error = postConnect();
		assertThat(error.path("reason").asText()).isEqualTo("not_found");
		assertThat(error.path("message").asText()).isEqualTo("server responded with 404: check the URL");
		assertThat(error.path("hint").path("kind").asText()).isEqualTo("maybe_sse");
		assertThat(error.path("hint").path("probePath").asText()).isEqualTo("/sse");
	}

	@Test
	@DisplayName("M4: 405 bucket + dead SSE => no hint key at all")
	void m4_405DeadSseNoHint() {
		stubUpstream(405, false, "application/json", false);
		final JsonNode error = postConnect();
		assertThat(error.path("reason").asText()).isEqualTo("unknown");
		assertThat(error.has("hint")).isFalse();
	}

	@Test
	@DisplayName("D2: 405 with Allow: text/event-stream on the same path => sse_only")
	void d2_allowHeaderAlive() {
		// /mcp 405 for the SDK POST; the probe GET on /mcp hits the /mcp context too.
		final AtomicInteger probeHits = new AtomicInteger();
		this.upstream.createContext("/probe-me", (exchange) -> {
			probeHits.incrementAndGet();
			exchange.getResponseHeaders().add("Allow", "text/event-stream");
			exchange.sendResponseHeaders(405, -1);
			exchange.close();
		});
		final ResponseEntity<Object> response = this.controller.postMcp(null, this.upstreamBase + "/probe-me",
				this.objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		final JsonNode error = this.objectMapper.valueToTree(response.getBody()).path("error");
		assertThat(error.path("hint").path("kind").asText()).isEqualTo("sse_only");
		assertThat(error.path("hint").path("probePath").asText()).isEqualTo("/probe-me");
	}

	@Test
	@DisplayName("M5: timeline records one MCP_CONNECT_FAILED event with the probe outcome")
	void timelineRecordsConnectFailed() {
		stubUpstream(404, true, "text/event-stream", false);
		postConnect();
		final var events = this.timeline.query(TimelineQuery.all());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).type()).isEqualTo(TimelineEventType.MCP_CONNECT_FAILED);
		assertThat(events.get(0).payload().path("reason").asText()).isEqualTo("not_found");
		assertThat(events.get(0).payload().path("hintKind").asText()).isEqualTo("sse_only");
		assertThat(events.get(0).payload().path("probeOutcome").asText()).isEqualTo("alive");
		assertThat(events.get(0).payload().path("url").asText()).isEqualTo(this.upstreamBase + "/mcp");
	}

	@Test
	@DisplayName("recorder-absent constructor keeps the hint behaviour without timeline writes")
	void recorderAbsentStillHints() {
		this.controller = new StreamableHttpProxyController(this.registry, this.transportFactory, this.mcpProxy,
				this.objectMapper);
		stubUpstream(404, true, "text/event-stream", false);
		final ResponseEntity<Object> response = this.controller.postMcp(null, this.upstreamBase + "/mcp",
				this.objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		final JsonNode error = this.objectMapper.valueToTree(response.getBody()).path("error");
		assertThat(error.path("hint").path("kind").asText()).isEqualTo("sse_only");
	}

}
