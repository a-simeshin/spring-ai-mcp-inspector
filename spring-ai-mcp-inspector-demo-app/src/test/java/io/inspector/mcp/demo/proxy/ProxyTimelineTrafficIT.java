/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.proxy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.timeline.McpTrafficRecorder;
import io.inspector.mcp.core.timeline.TimelineEvent;
import io.inspector.mcp.core.timeline.TimelineEventType;
import io.inspector.mcp.core.timeline.TimelineQuery;
import io.inspector.mcp.core.timeline.TimelineService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring test for the MCP traffic recorder: with the timeline feature enabled,
 * a JSON-RPC request relayed through the proxy must land in the {@link TimelineService}
 * as an {@link TimelineEventType#MCP_JSONRPC_REQUEST} event, its matching response as a
 * {@link TimelineEventType#MCP_JSONRPC_RESPONSE} event, and the pair must share one
 * {@code correlationId}. This is the contract of issue #53 — without the proxy actually
 * holding a recorder bean, the timeline only ever shows application logs.
 *
 * <p>
 * Lives in the shared {@code demo-app} test-jar so it runs once per stack (webmvc and
 * webflux) through each demo module's Failsafe {@code dependenciesToScan}. The timeline
 * service is read back from the in-process application context rather than over HTTP
 * because the reactive stack exposes no {@code /api/timeline} endpoint.
 *
 * <p>
 * The second test pins the inverse: with the timeline disabled, no
 * {@link McpTrafficRecorder} bean exists and the proxy still serves traffic.
 */
@Epic("Inspector Proxy")
@Feature("Timeline traffic recording")
class ProxyTimelineTrafficIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	private static final Duration BUDGET = Duration.ofSeconds(30);

	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (this.app != null) {
			try {
				this.app.close();
			}
			catch (Exception ignored) {
				// best-effort
			}
			this.app = null;
		}
	}

	@Test
	@DisplayName("timeline enabled: proxied tools/list produces correlated REQUEST and RESPONSE events")
	@Story("Correlated traffic in the timeline")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo with spring.ai.mcp.inspector.timeline.enabled=true, relays one "
			+ "tools/list through the proxy and asserts the timeline holds a matching "
			+ "MCP_JSONRPC_REQUEST / MCP_JSONRPC_RESPONSE pair sharing one correlationId")
	void timelineEnabled_whenRelaying_recordsCorrelatedPair() throws Exception {
		// given
		this.app = ProxyAppHarness.start("STREAMABLE", false, null, "--spring.ai.mcp.inspector.timeline.enabled=true");
		final TimelineService timeline = this.app.getBean(TimelineService.class);
		final int port = ProxyAppHarness.port(this.app);
		final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// when — one initialize + one tools/list through the proxy
		ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		init.set("params",
				MAPPER.createObjectNode()
					.put("protocolVersion", "2025-03-26")
					.set("capabilities", MAPPER.createObjectNode())
					.set("clientInfo", MAPPER.createObjectNode().put("name", "timeline-it").put("version", "0")));
		HttpResponse<String> initResponse = post(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null, init);
		assertThat(initResponse.statusCode())
			.as("initialize through proxy on %s, body=%s", ProxyAppHarness.stack(), initResponse.body())
			.isEqualTo(200);
		final String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse("");
		assertThat(sessionId).as("mcp-session-id on %s", ProxyAppHarness.stack()).isNotBlank();

		ObjectNode toolsList = MAPPER.createObjectNode();
		toolsList.put("jsonrpc", "2.0");
		toolsList.put("method", "tools/list");
		toolsList.put("id", 2);
		HttpResponse<String> toolsResponse = post(proxyBase + "/mcp", sessionId, toolsList);
		assertThat(toolsResponse.statusCode())
			.as("tools/list through proxy on %s, body=%s", ProxyAppHarness.stack(), toolsResponse.body())
			.isEqualTo(200);

		// then — the timeline recorded the relayed pair with a shared correlationId
		final List<TimelineEvent> requests = timeline
			.query(TimelineQuery.builder().eventTypes(List.of(TimelineEventType.MCP_JSONRPC_REQUEST)).build());
		assertThat(requests).as("MCP_JSONRPC_REQUEST events on %s", ProxyAppHarness.stack()).isNotEmpty();

		final List<TimelineEvent> responses = timeline
			.query(TimelineQuery.builder().eventTypes(List.of(TimelineEventType.MCP_JSONRPC_RESPONSE)).build());
		assertThat(responses).as("MCP_JSONRPC_RESPONSE events on %s", ProxyAppHarness.stack()).isNotEmpty();

		// the tools/list request/response pair shares one correlationId
		final String toolsReqCorr = matchingCorrelation(requests, 2);
		assertThat(toolsReqCorr).as("tools/list REQUEST recorded on %s", ProxyAppHarness.stack()).isNotNull();
		final boolean correlated = responses.stream()
			.filter((e) -> matchingPayloadId(e) == 2)
			.anyMatch((e) -> toolsReqCorr.equals(e.correlationId()));
		assertThat(correlated)
			.as("tools/list REQUEST and RESPONSE share correlationId on %s (req=%s)", ProxyAppHarness.stack(),
					toolsReqCorr)
			.isTrue();
	}

	@Test
	@DisplayName("timeline disabled: no recorder bean and the proxy still relays")
	@Story("Proxy works without the timeline")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo with spring.ai.mcp.inspector.timeline.enabled=false, asserts no "
			+ "McpTrafficRecorder bean exists and initialize through the proxy still returns 200")
	void timelineDisabled_proxyStillWorksWithoutRecorderBean() throws Exception {
		// given — the demo's application.yml enables the timeline (d9f96ff), so the
		// disabled case must override it explicitly; harness args are appended last.
		this.app = ProxyAppHarness.start("STREAMABLE", false, null, "--spring.ai.mcp.inspector.timeline.enabled=false");
		assertThat(this.app.getBeanNamesForType(McpTrafficRecorder.class))
			.as("no McpTrafficRecorder bean while timeline is disabled on %s", ProxyAppHarness.stack())
			.isEmpty();

		final int port = ProxyAppHarness.port(this.app);
		final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// when
		ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		init.set("params",
				MAPPER.createObjectNode()
					.put("protocolVersion", "2025-03-26")
					.set("capabilities", MAPPER.createObjectNode())
					.set("clientInfo", MAPPER.createObjectNode().put("name", "timeline-it").put("version", "0")));
		HttpResponse<String> initResponse = post(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null, init);

		// then — proxy functional without the timeline
		assertThat(initResponse.statusCode())
			.as("initialize must succeed with timeline disabled on %s, body=%s", ProxyAppHarness.stack(),
					initResponse.body())
			.isEqualTo(200);
	}

	/**
	 * Correlation id of the REQUEST event whose payload carries the given JSON-RPC id.
	 */
	private static String matchingCorrelation(final List<TimelineEvent> events, final int rpcId) {
		return events.stream()
			.filter((e) -> matchingPayloadId(e) == rpcId)
			.map(TimelineEvent::correlationId)
			.findFirst()
			.orElse(null);
	}

	/** The JSON-RPC {@code id} in an event payload, or -1 when absent/unparseable. */
	private static int matchingPayloadId(final TimelineEvent event) {
		final JsonNode payload = event.payload();
		if (payload == null || payload.path("id").isMissingNode()) {
			return -1;
		}
		return payload.path("id").asInt(-1);
	}

	private static HttpResponse<String> post(String urlWithQuery, String sessionId, ObjectNode body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(urlWithQuery))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
		if (sessionId != null) {
			builder.header("mcp-session-id", sessionId);
		}
		return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
