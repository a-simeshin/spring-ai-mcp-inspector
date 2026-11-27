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
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.EntityResponse;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
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
 * Integration-style tests for the SSE connect-hint wiring of {@link ProxyHandler} (issue
 * #250): the handler talks to a real JDK stub server through the real mcp-core streamable
 * transport, so the classified failure bucket and the probe run on the production path.
 * The full trigger matrix lives in the WebMVC twin
 * ({@code io.inspector.mcp.webmvc.proxy.StreamableHttpProxyControllerConnectHintTests});
 * this suite pins the reactive stack parity (AC 8) on the same payload shape.
 */
@DisplayName("ProxyHandler connect hint")
class ProxyHandlerConnectHintTests {

	private static final HandlerStrategies STRATEGIES = HandlerStrategies.withDefaults();

	private HttpServer upstream;

	private ProxySessionRegistry registry;

	private ProxyTransportFactory transportFactory;

	private McpProxy mcpProxy;

	private JsonMapper objectMapper;

	private ProxyHandler handler;

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
		this.handler = new ProxyHandler(this.registry, this.transportFactory, this.mcpProxy, null, this.objectMapper,
				this.properties, null, new McpTrafficRecorder(this.timeline));
	}

	@AfterEach
	void tearDown() {
		if (this.upstream != null) {
			this.upstream.stop(0);
		}
	}

	/** Serves SSE at /sse, the given status at /mcp. */
	private void stubUpstream(final int statusAtMcp, final boolean sseAlive, final String contentType) {
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
			// Alive: keep the stream open; the probe closes it from the client side.
			exchange.sendResponseHeaders(200, -1);
		});
	}

	private ServerRequest toServerRequest(final MockServerHttpRequest request) {
		final MockServerWebExchange exchange = MockServerWebExchange.from(request);
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	private ServerResponse postConnectAndGetResponse(final String url) {
		final MockServerHttpRequest request = MockServerHttpRequest.post("/mcp-inspector-api/mcp?url=" + url)
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
		return this.handler.postMcp(toServerRequest(request)).block(Duration.ofSeconds(10));
	}

	private JsonNode errorOf(final ServerResponse response) {
		return this.objectMapper.valueToTree(((EntityResponse<?>) response).entity()).path("error");
	}

	@Test
	@DisplayName("M1: 404 upstream + live SSE sibling => sse_only hint (payload parity with WebMVC)")
	void m1_sseAliveSiblingAppendsHint() {
		stubUpstream(404, true, "text/event-stream");
		final ServerResponse response = postConnectAndGetResponse(this.upstreamBase + "/mcp");
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		final JsonNode error = errorOf(response);
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
	@DisplayName("M3: 404 upstream + dead SSE => maybe_sse hint")
	void m3_deadSseAppendsWeakHint() {
		stubUpstream(404, false, "application/json");
		final ServerResponse response = postConnectAndGetResponse(this.upstreamBase + "/mcp");
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		final JsonNode error = errorOf(response);
		assertThat(error.path("hint").path("kind").asText()).isEqualTo("maybe_sse");
	}

	@Test
	@DisplayName("M6a: DNS failure (unknown host) => no hint, probe not_run")
	void m6a_dnsFailureNoHintNoProbe() {
		final ServerResponse response = postConnectAndGetResponse("http://no-such-host.invalid:1/mcp");
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		final JsonNode error = errorOf(response);
		assertThat(error.path("reason").asText()).isEqualTo("dns");
		assertThat(error.has("hint")).isFalse();
		final var events = this.timeline.query(TimelineQuery.all());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).payload().path("probeOutcome").asText()).isEqualTo("not_run");
	}

	@Test
	@DisplayName("M6: non-trigger failure (connection refused) => no hint, probe not_run")
	void m6_nonTrigger_noHintNoProbe() {
		// Port 1 on loopback: nothing listens, the connect is refused before any HTTP.
		final ServerResponse response = postConnectAndGetResponse("http://127.0.0.1:1/mcp");
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		final JsonNode error = errorOf(response);
		assertThat(error.has("hint")).isFalse();
		final var events = this.timeline.query(TimelineQuery.all());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).payload().path("probeOutcome").asText()).isEqualTo("not_run");
	}

	@Test
	@DisplayName("timeline records one MCP_CONNECT_FAILED event with the probe outcome")
	void timelineRecordsConnectFailed() {
		stubUpstream(404, true, "text/event-stream");
		postConnectAndGetResponse(this.upstreamBase + "/mcp");
		final var events = this.timeline.query(TimelineQuery.all());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).type()).isEqualTo(TimelineEventType.MCP_CONNECT_FAILED);
		assertThat(events.get(0).payload().path("reason").asText()).isEqualTo("not_found");
		assertThat(events.get(0).payload().path("hintKind").asText()).isEqualTo("sse_only");
		assertThat(events.get(0).payload().path("probeOutcome").asText()).isEqualTo("alive");
		assertThat(events.get(0).payload().path("url").asText()).isEqualTo(this.upstreamBase + "/mcp");
	}

}
