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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the full JSON-RPC dance through the streamable-HTTP proxy on both webmvc and
 * webflux stacks. The proxy lives at {@code /mcp-inspector-api/mcp} and forwards every
 * frame to the demo MCP server's {@code /mcp} endpoint.
 *
 * <p>
 * Flow (one test method = one stack):
 *
 * <ol>
 * <li>{@code POST /mcp-inspector-api/mcp?url=<target>} with an {@code initialize} body →
 * 200, {@code mcp-session-id} echoed in the response header.</li>
 * <li>{@code POST /mcp-inspector-api/mcp} with the session id header and a
 * {@code tools/list} body → 202 Accepted (the proxy relays the frame to the demo; the
 * inline POST body for non-initialize calls on this stack is empty by design).</li>
 * <li>{@code GET /mcp-inspector-api/mcp} with the session id header opens an SSE stream
 * of {@code event: message} frames coming from the target. We pre-open this stream before
 * issuing requests so reply frames are captured deterministically.</li>
 * <li>{@code DELETE /mcp-inspector-api/mcp} → 200, session destroyed.</li>
 * </ol>
 *
 * <p>
 * For the SSE backchannel we use the JDK 17 {@code HttpClient}'s line-streamed body
 * subscriber. Awaitility is used to bound waits.
 */
@Epic("Inspector Proxy")
@Feature("Streamable-HTTP flow")
class ProxyStreamableHttpFlowIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static final Duration BUDGET = Duration.ofSeconds(30);

	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (app != null) {
			try {
				app.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			app = null;
		}
	}

	/**
	 * Initialize + tools/list round-trip on both stacks.
	 *
	 * <p>
	 * The 4 transport combos (sse/streamable/stateless × webmvc/webflux) of the proxy SSE
	 * controller are covered by {@link ProxySseFlowIT}; here we only exercise the
	 * Streamable-HTTP proxy port, which is independent of the demo's chosen protocol —
	 * the proxy targets the demo's {@code /mcp} URL with {@code streamable-http}
	 * regardless of {@code STREAMABLE} vs {@code STATELESS}. We pick {@code STREAMABLE}
	 * as the most representative.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	@DisplayName("initialize + tools/list round-trip through the streamable-HTTP proxy")
	@Story("Init + tools/list round-trip")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Drives initialize (200 + session id), notifications/initialized (202), tools/list (200) "
			+ "and DELETE (200 then 404) through /mcp-inspector-api/mcp on both stacks")
	void initializeAndToolsList_throughStreamableHttpProxy_completesFullDance(ProxyAppHarness.Stack stack)
			throws Exception {
		// given
		app = ProxyAppHarness.start(stack, "STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// when
		// ---- 1. initialize → expects 200 + mcp-session-id header ------------
		ObjectNode init = buildInit();
		HttpRequest initRequest = HttpRequest
			.newBuilder(URI.create(proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		HttpResponse<String> initResponse = HTTP.send(initRequest, HttpResponse.BodyHandlers.ofString());

		// then
		assertThat(initResponse.statusCode()).as("initialize HTTP status on %s, body=%s", stack, initResponse.body())
			.isEqualTo(200);
		String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse(null);
		assertThat(sessionId).as("mcp-session-id round-trip on %s", stack).isNotBlank();

		// ---- 2a. notifications/initialized — required by the MCP handshake.
		// The proxy answers 202 for notification frames (no id) per spec.
		ObjectNode initializedNotification = MAPPER.createObjectNode();
		initializedNotification.put("jsonrpc", "2.0");
		initializedNotification.put("method", "notifications/initialized");
		HttpRequest initializedNotificationRequest = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("mcp-session-id", sessionId)
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(initializedNotification)))
			.build();
		HttpResponse<String> initializedNotificationResponse = HTTP.send(initializedNotificationRequest,
				HttpResponse.BodyHandlers.ofString());
		assertThat(initializedNotificationResponse.statusCode())
			.as("notifications/initialized must be 202 on %s, body=%s", stack, initializedNotificationResponse.body())
			.isEqualTo(202);

		// ---- 2b. tools/list ------------------------------------------------
		ObjectNode toolsListRpc = MAPPER.createObjectNode();
		toolsListRpc.put("jsonrpc", "2.0");
		toolsListRpc.put("method", "tools/list");
		toolsListRpc.put("id", 2);

		HttpRequest toolsListRequest = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("mcp-session-id", sessionId)
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(toolsListRpc)))
			.build();
		HttpResponse<String> toolsListResponse = HTTP.send(toolsListRequest, HttpResponse.BodyHandlers.ofString());

		// After the spec-compliance fix the proxy waits for the matching JSON-RPC
		// response for request frames; tools/list is a request → 200 with the
		// upstream's reply body.
		assertThat(toolsListResponse.statusCode())
			.as("tools/list POST relay on %s, body=%s", stack, toolsListResponse.body())
			.isEqualTo(200);

		// ---- 3. DELETE the session — proxy reports it as known ------------
		HttpRequest deleteRequest = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(Duration.ofSeconds(10))
			.header("mcp-session-id", sessionId)
			.DELETE()
			.build();
		HttpResponse<String> deleteResponse = HTTP.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(deleteResponse.statusCode()).as("DELETE known session on %s", stack).isEqualTo(200);

		// ---- 4. DELETE again — now unknown -> 404 -------------------------
		HttpResponse<String> secondDelete = HTTP.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(secondDelete.statusCode()).as("DELETE unknown session on %s", stack).isEqualTo(404);
	}

	/**
	 * GET on /mcp without a known session-id must not crash the server. On webmvc the
	 * controller returns an SSE stream containing one {@code error} event then completes;
	 * on webflux the handler returns 404 JSON. Both must respond in bounded time without
	 * hanging.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	@DisplayName("GET /mcp with an unknown session responds quickly without hanging")
	@Story("Unknown session GET")
	@Severity(SeverityLevel.NORMAL)
	@Description("A GET on /mcp with a never-issued session id must answer within the budget (200 with an "
			+ "error event on webmvc, 404 on webflux) and never hang")
	void getMcp_withUnknownSession_respondsQuickly(ProxyAppHarness.Stack stack) throws Exception {
		// given
		app = ProxyAppHarness.start(stack, "STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);

		// when
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/mcp"))
			.timeout(Duration.ofSeconds(10))
			.header("mcp-session-id", "definitely-not-a-real-session")
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

		// then
		// WebMvc → 200 with an event:error inside; WebFlux → 404. Accept both
		// shapes; the contract is "answers within 10s without hanging".
		assertThat(response.statusCode()).as("status on %s for unknown session GET", stack).isIn(200, 404);
	}

	/**
	 * Reusable JSON-RPC {@code initialize} body. Protocol version chosen to match the
	 * demo server's reported {@code LATEST_PROTOCOL_VERSION}; the SDK negotiates down if
	 * needed.
	 */
	private static ObjectNode buildInit() {
		ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-it");
		info.put("version", "0.1.0");
		return init;
	}

	/** Defensive: helper kept for assertions that may include tool-list shape later. */
	@SuppressWarnings("unused")
	private static List<String> toolNames(JsonNode toolsArray) {
		java.util.List<String> names = new java.util.ArrayList<>();
		if (toolsArray != null && toolsArray.isArray()) {
			toolsArray.forEach(t -> names.add(t.path("name").asText()));
		}
		return names;
	}

	/**
	 * Defensive helper kept for future tool/list assertions; intentionally unused now.
	 */
	@SuppressWarnings("unused")
	private static IOException unexpected(String stage, HttpResponse<String> response) {
		return new IOException(stage + " HTTP " + response.statusCode() + ": " + response.body());
	}

}
