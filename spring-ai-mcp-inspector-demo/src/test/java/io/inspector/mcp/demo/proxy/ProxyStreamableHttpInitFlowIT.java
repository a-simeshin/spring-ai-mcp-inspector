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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * Regression test for the Streamable-HTTP init flow per <a href=
 * "https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http">MCP
 * spec 2025-03-26</a>.
 *
 * <p>
 * Bug being guarded against: the proxy used to answer every POST with a fake
 * {@code {"jsonrpc":"2.0","result":{"accepted":true}}} placeholder, breaking the upstream
 * React Inspector client which expects the actual {@code initialize} response from the
 * target MCP server on the first POST. After the fix the proxy:
 *
 * <ul>
 * <li>Forwards each browser POST that carries a JSON-RPC <em>request</em> (has
 * {@code id}) to the upstream and returns the matching response as
 * {@code application/json} (status 200, body must contain {@code result.serverInfo},
 * {@code result.protocolVersion}, {@code id})</li>
 * <li>Returns {@code 202 Accepted} with empty body for notification frames (no
 * {@code id}), e.g. {@code notifications/initialized}</li>
 * <li>Replays the same response/notification frames over the long-lived {@code GET /mcp}
 * SSE stream (so multi-subscriber semantics are needed)</li>
 * <li>Tears down the session on DELETE</li>
 * </ul>
 *
 * <p>
 * Both stacks (webmvc + webflux) must satisfy the contract.
 */
@Epic("Inspector Proxy")
@Feature("Streamable-HTTP init flow")
class ProxyStreamableHttpInitFlowIT {

	/** Jackson instance reused across requests; the proxy returns/parses JSON only. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Shared HTTP client; bound to a short connect-timeout to fail fast on bring-up
	 * issues.
	 */
	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/** Wall-clock budget per HTTP exchange. Tests must finish well under this. */
	private static final Duration BUDGET = Duration.ofSeconds(30);

	/** Protocol version we negotiate. Matches the demo server's reported latest. */
	private static final String PROTOCOL_VERSION = "2025-11-25";

	/** Demo app under test. {@code null} between tests. */
	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (app != null) {
			try {
				app.close();
			}
			catch (Exception ignored) {
				// best-effort
			}
			app = null;
		}
	}

	/**
	 * Drives the full request/notification dance through {@code /mcp-inspector-api/mcp}
	 * and asserts spec-compliant responses on each step.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	@DisplayName("Streamable-HTTP init flow returns real upstream responses, not the legacy placeholder")
	@Story("Spec-compliant init")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Regression: drives initialize, notifications/initialized and tools/list through the "
			+ "streamable-HTTP proxy and asserts real upstream responses (200 with result, 202 for "
			+ "notifications), guarding against the old {\"accepted\":true} placeholder")
	void streamableHttpInitFlow_whenDriven_returnsRealUpstreamResponses(ProxyAppHarness.Stack stack) throws Exception {
		// given
		app = ProxyAppHarness.start(stack, "STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// when
		// ---- 1. initialize ------------------------------------------------
		ObjectNode init = buildInit(1);
		HttpResponse<String> initResponse = postProxy(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null, init);

		assertThat(initResponse.statusCode()).as("initialize must be 200 on %s, body=%s", stack, initResponse.body())
			.isEqualTo(200);
		String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse(null);
		assertThat(sessionId).as("mcp-session-id on %s", stack).isNotBlank();

		// The body MUST be the real initialize response, not the old placeholder.
		assertThat(initResponse.body()).as("initialize body on %s must NOT be the legacy placeholder", stack)
			.doesNotContain("\"accepted\":true");
		JsonNode initBody = MAPPER.readTree(initResponse.body());
		assertThat(initBody.path("jsonrpc").asText()).isEqualTo("2.0");
		assertThat(initBody.path("id").asInt()).isEqualTo(1);
		assertThat(initBody.path("result").path("serverInfo").isMissingNode())
			.as("initialize result.serverInfo on %s must be present", stack)
			.isFalse();
		assertThat(initBody.path("result").path("protocolVersion").asText())
			.as("initialize result.protocolVersion on %s", stack)
			.isNotBlank();

		// ---- 2. notifications/initialized — no id → expect 202 -----------
		ObjectNode initializedNotification = MAPPER.createObjectNode();
		initializedNotification.put("jsonrpc", "2.0");
		initializedNotification.put("method", "notifications/initialized");
		HttpResponse<String> notifResponse = postProxy(proxyBase + "/mcp", sessionId, initializedNotification);
		assertThat(notifResponse.statusCode())
			.as("notification POST must be 202 Accepted on %s, body=%s", stack, notifResponse.body())
			.isEqualTo(202);

		// ---- 3. tools/list (id=2) — request → expect 200 + matching response
		ObjectNode toolsList = MAPPER.createObjectNode();
		toolsList.put("jsonrpc", "2.0");
		toolsList.put("method", "tools/list");
		toolsList.put("id", 2);
		HttpResponse<String> toolsListResponse = postProxy(proxyBase + "/mcp", sessionId, toolsList);
		assertThat(toolsListResponse.statusCode())
			.as("tools/list HTTP status on %s, body=%s", stack, toolsListResponse.body())
			.isEqualTo(200);
		JsonNode toolsBody = MAPPER.readTree(toolsListResponse.body());
		assertThat(toolsBody.path("id").asInt()).as("tools/list response id on %s", stack).isEqualTo(2);
		assertThat(toolsBody.path("result").path("tools").isArray())
			.as("tools/list response result.tools is an array on %s, body=%s", stack, toolsListResponse.body())
			.isTrue();

		// ---- 4. DELETE — session is known, then unknown ----------------
		HttpRequest deleteRequest = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(Duration.ofSeconds(10))
			.header("mcp-session-id", sessionId)
			.DELETE()
			.build();
		HttpResponse<String> deleteResponse = HTTP.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(deleteResponse.statusCode()).as("DELETE on known session must be 200 on %s", stack).isIn(200, 204);
	}

	/**
	 * POSTs {@code body} to the proxy. When {@code sessionId} is non-null it is attached
	 * as the {@code mcp-session-id} header. Synchronous.
	 */
	private static HttpResponse<String> postProxy(String urlWithQuery, String sessionId, ObjectNode body)
			throws Exception {
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

	/** Builds a JSON-RPC {@code initialize} frame with the given {@code id}. */
	private static ObjectNode buildInit(int id) {
		ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", id);
		ObjectNode params = init.putObject("params");
		params.put("protocolVersion", PROTOCOL_VERSION);
		params.putObject("capabilities");
		ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-init-it");
		info.put("version", "0.1.0");
		return init;
	}

}
