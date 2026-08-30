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
import java.util.ArrayList;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring test for the MCP traffic recorder: with the timeline feature enabled,
 * a JSON-RPC request relayed through the proxy must appear in {@code GET
 * ${path}/api/timeline} as an {@code MCP_JSONRPC_REQUEST} event, its matching response as
 * an {@code MCP_JSONRPC_RESPONSE} event, and the pair must share one
 * {@code correlationId}. This is the contract of issue #53 — without the proxy actually
 * holding a recorder bean, the timeline only ever shows application logs.
 *
 * <p>
 * Lives in the shared {@code demo-app} test-jar so it runs once per stack (webmvc and
 * webflux) through each demo module's Failsafe {@code dependenciesToScan}. The events are
 * read back over the timeline HTTP endpoint - the same contract the inspector SPA
 * consumes - not from the in-process {@code TimelineService} bean, because reading the
 * bean directly would hide a stack whose HTTP surface does not expose the timeline at
 * all.
 *
 * <p>
 * The second test pins that correlations are isolated per proxy session: two sessions
 * that each open with JSON-RPC id {@code 1} must produce two independently correlated
 * pairs, not cross-wired ones.
 *
 * <p>
 * The third test pins the inverse: with the timeline disabled, no
 * {@link McpTrafficRecorder} bean exists, the timeline endpoint is not served, and the
 * proxy still relays traffic.
 */
@Epic("Inspector Proxy")
@Feature("Timeline traffic recording")
class ProxyTimelineTrafficIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	private static final Duration BUDGET = Duration.ofSeconds(30);

	private static final String TIMELINE_PATH = "/mcp-inspector/api/timeline";

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
	@DisplayName("timeline enabled: proxied tools/list surfaces a correlated pair over /api/timeline")
	@Story("Correlated traffic in the timeline")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo with spring.ai.mcp.inspector.timeline.enabled=true, relays one "
			+ "tools/list through the proxy and asserts GET /api/timeline returns a matching "
			+ "MCP_JSONRPC_REQUEST / MCP_JSONRPC_RESPONSE pair sharing one correlationId")
	void timelineEnabled_whenRelaying_httpTimelineShowsCorrelatedPair() throws Exception {
		// given
		this.app = ProxyAppHarness.start("STREAMABLE", false, null, "--spring.ai.mcp.inspector.timeline.enabled=true");
		final int port = ProxyAppHarness.port(this.app);
		final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// when — one initialize + one tools/list through the proxy
		final HttpResponse<String> initResponse = post(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null,
				initializeFrame());
		assertThat(initResponse.statusCode())
			.as("initialize through proxy on %s, body=%s", ProxyAppHarness.stack(), initResponse.body())
			.isEqualTo(200);
		final String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse("");
		assertThat(sessionId).as("mcp-session-id on %s", ProxyAppHarness.stack()).isNotBlank();

		ObjectNode toolsList = MAPPER.createObjectNode();
		toolsList.put("jsonrpc", "2.0");
		toolsList.put("method", "tools/list");
		toolsList.put("id", 2);
		final HttpResponse<String> toolsResponse = post(proxyBase + "/mcp", sessionId, toolsList);
		assertThat(toolsResponse.statusCode())
			.as("tools/list through proxy on %s, body=%s", ProxyAppHarness.stack(), toolsResponse.body())
			.isEqualTo(200);

		// then - the HTTP timeline endpoint serves the correlated pair
		final List<JsonNode> events = timelineEvents(port);
		final JsonNode toolsRequest = findEvent(events, "MCP_JSONRPC_REQUEST", sessionId, 2);
		assertThat(toolsRequest).as("tools/list REQUEST on /api/timeline over %s", ProxyAppHarness.stack()).isNotNull();
		final JsonNode toolsResponseEvent = findEvent(events, "MCP_JSONRPC_RESPONSE", sessionId, 2);
		assertThat(toolsResponseEvent).as("tools/list RESPONSE on /api/timeline over %s", ProxyAppHarness.stack())
			.isNotNull();
		assertThat(toolsResponseEvent.get("correlationId").asString())
			.as("tools/list REQUEST and RESPONSE share correlationId on %s", ProxyAppHarness.stack())
			.isEqualTo(toolsRequest.get("correlationId").asString());
	}

	@Test
	@DisplayName("timeline enabled: duplicate JSON-RPC id across sessions stays session-isolated")
	@Story("Session-scoped correlation")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Opens two proxy sessions that both initialize with JSON-RPC id 1 and "
			+ "asserts GET /api/timeline shows two independently correlated request/response "
			+ "pairs - each session's pair shares a correlationId, the sessions do not")
	void duplicateRpcIdAcrossSessionsIsCorrelatedPerSession() throws Exception {
		// given - two independent proxy sessions
		this.app = ProxyAppHarness.start("STREAMABLE", false, null, "--spring.ai.mcp.inspector.timeline.enabled=true");
		final int port = ProxyAppHarness.port(this.app);
		final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";
		final String connectUrl = proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8);

		// when - both sessions initialize with the same JSON-RPC id
		final HttpResponse<String> initA = post(connectUrl, null, initializeFrame());
		final HttpResponse<String> initB = post(connectUrl, null, initializeFrame());
		assertThat(initA.statusCode()).as("session A initialize on %s", ProxyAppHarness.stack()).isEqualTo(200);
		assertThat(initB.statusCode()).as("session B initialize on %s", ProxyAppHarness.stack()).isEqualTo(200);
		final String sessionA = initA.headers().firstValue("mcp-session-id").orElse("");
		final String sessionB = initB.headers().firstValue("mcp-session-id").orElse("");
		assertThat(sessionA).as("distinct sessions on %s", ProxyAppHarness.stack()).isNotBlank();
		assertThat(sessionB).as("distinct sessions on %s", ProxyAppHarness.stack()).isNotBlank();
		assertThat(sessionA).isNotEqualTo(sessionB);

		// then - each session has its own correlated pair for id=1, and the two
		// correlations differ (a shared map keyed by id alone would cross-wire these)
		final List<JsonNode> events = timelineEvents(port);
		final JsonNode reqA = findEvent(events, "MCP_JSONRPC_REQUEST", sessionA, 1);
		final JsonNode resA = findEvent(events, "MCP_JSONRPC_RESPONSE", sessionA, 1);
		final JsonNode reqB = findEvent(events, "MCP_JSONRPC_REQUEST", sessionB, 1);
		final JsonNode resB = findEvent(events, "MCP_JSONRPC_RESPONSE", sessionB, 1);
		assertThat(reqA).as("session A REQUEST on %s", ProxyAppHarness.stack()).isNotNull();
		assertThat(resA).as("session A RESPONSE on %s", ProxyAppHarness.stack()).isNotNull();
		assertThat(reqB).as("session B REQUEST on %s", ProxyAppHarness.stack()).isNotNull();
		assertThat(resB).as("session B RESPONSE on %s", ProxyAppHarness.stack()).isNotNull();
		assertThat(resA.get("correlationId").asString()).as("session A pair on %s", ProxyAppHarness.stack())
			.isEqualTo(reqA.get("correlationId").asString());
		assertThat(resB.get("correlationId").asString()).as("session B pair on %s", ProxyAppHarness.stack())
			.isEqualTo(reqB.get("correlationId").asString());
		assertThat(reqA.get("correlationId").asString())
			.as("correlations differ between sessions on %s", ProxyAppHarness.stack())
			.isNotEqualTo(reqB.get("correlationId").asString());
	}

	@Test
	@DisplayName("timeline disabled: no recorder bean, endpoint not served, proxy still relays")
	@Story("Proxy works without the timeline")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo with spring.ai.mcp.inspector.timeline.enabled=false, asserts no "
			+ "McpTrafficRecorder bean exists, /api/timeline is not served, and initialize "
			+ "through the proxy still returns 200")
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

		// when - the timeline endpoint must not be served with the subsystem off
		final HttpResponse<String> timelineOff = HTTP
			.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + TIMELINE_PATH))
				.timeout(BUDGET)
				.GET()
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(timelineOff.statusCode())
			.as("GET /api/timeline with timeline disabled on %s", ProxyAppHarness.stack())
			.isEqualTo(404);

		// and - proxy functional without the timeline
		HttpResponse<String> initResponse = post(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null,
				initializeFrame());

		// then
		assertThat(initResponse.statusCode())
			.as("initialize must succeed with timeline disabled on %s, body=%s", ProxyAppHarness.stack(),
					initResponse.body())
			.isEqualTo(200);
	}

	private static ObjectNode initializeFrame() {
		ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		init.set("params",
				MAPPER.createObjectNode()
					.put("protocolVersion", "2025-03-26")
					.set("capabilities", MAPPER.createObjectNode())
					.set("clientInfo", MAPPER.createObjectNode().put("name", "timeline-it").put("version", "0")));
		return init;
	}

	/**
	 * Fetches the timeline over its HTTP contract:
	 * {@code GET /mcp-inspector/api/timeline?limit=500}.
	 */
	private static List<JsonNode> timelineEvents(final int port) throws Exception {
		final HttpResponse<String> response = HTTP.send(HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + port + TIMELINE_PATH + "?limit="
					+ URLEncoder.encode("500", StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Accept", "application/json")
			.GET()
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode())
			.as("GET %s on %s, body=%s", TIMELINE_PATH, ProxyAppHarness.stack(), response.body())
			.isEqualTo(200);
		final JsonNode array = MAPPER.readTree(response.body());
		assertThat(array.isArray()).as("timeline response is a JSON array on %s", ProxyAppHarness.stack()).isTrue();
		final List<JsonNode> events = new ArrayList<>();
		array.forEach(events::add);
		return events;
	}

	/**
	 * Finds the first timeline event of {@code type} in session {@code sessionId} whose
	 * JSON-RPC payload carries id {@code rpcId}, or {@code null}.
	 */
	private static JsonNode findEvent(final List<JsonNode> events, final String type, final String sessionId,
			final int rpcId) {
		for (final JsonNode event : events) {
			if (!type.equals(event.path("type").asString())) {
				continue;
			}
			if (!sessionId.equals(event.path("sessionId").asString(""))) {
				continue;
			}
			final JsonNode id = event.path("payload").path("id");
			if (!id.isMissingNode() && id.asInt(-1) == rpcId) {
				return event;
			}
		}
		return null;
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
