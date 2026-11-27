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
package io.inspector.mcp.demo.proxy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the {@code multiRoundTripEcho} demo tool end to end through the streamable-HTTP
 * proxy on both web stacks: two {@code input_required} rounds, then the final text echo,
 * plus the {@code -32602} rejections. The tool is the deterministic SEP-2322 multi
 * round-trip fixture; every assertion here doubles as the contract an E2E suite can rely
 * on (byte-stable state tokens, fixed schema shapes, fixed error codes).
 *
 * <p>
 * Request frames (with a JSON-RPC id) are relayed by the proxy with the upstream reply
 * body inline, so no SSE capture is needed: each {@code tools/call} POST answers 200 with
 * the JSON-RPC response for that id.
 */
@Epic("Demo MCP server")
@Feature("SEP-2322 multi round-trip echo")
class MultiRoundTripEchoIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

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

	@Test
	@DisplayName("two input_required rounds then a final echo, end to end")
	@Story("Happy path: 3 tools/call requests")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Round 1 returns input_required with 3 typed inputRequests plus a state token; the re-send "
			+ "validates the answers and returns one more input_required round; the last re-send returns the "
			+ "text echo. Fresh calls mint byte-identical round-1 tokens (determinism).")
	void twoInputRequiredRounds_thenFinalEcho() throws Exception {
		// given
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyMcp = "http://127.0.0.1:" + port + "/mcp-inspector-api/mcp";
		String sessionId = openSession(proxyMcp, targetUrl);

		// when: round 1, fresh call
		JsonNode first = callTool(proxyMcp, sessionId, 101, null, null);
		// then: input_required with 3 typed requests and an opaque token
		assertThat(first.has("error")).as("round 1 must not fail, body=%s", first).isFalse();
		JsonNode result = first.path("result");
		assertThat(result.path("resultType").asString()).isEqualTo("input_required");
		JsonNode inputRequests = result.path("inputRequests");
		assertThat(inputRequests.size()).as("round 1 asks exactly 3 requests").isEqualTo(3);
		assertThat(inputRequests.has("favoriteCity")).isTrue();
		assertThat(inputRequests.has("coffeeSize")).isTrue();
		assertThat(inputRequests.has("refillCount")).isTrue();
		for (String key : new String[] { "favoriteCity", "coffeeSize", "refillCount" }) {
			assertThat(inputRequests.path(key).path("method").asString()).isEqualTo("elicitation/create");
			assertThat(inputRequests.path(key).path("params").path("mode").asString()).isEqualTo("form");
			assertThat(inputRequests.path(key).path("params").has("requestedSchema")).isTrue();
		}
		assertThat(inputRequests.path("favoriteCity")
			.path("params")
			.path("requestedSchema")
			.path("properties")
			.path("city")
			.path("type")
			.asString()).isEqualTo("string");
		assertThat(inputRequests.path("coffeeSize")
			.path("params")
			.path("requestedSchema")
			.path("properties")
			.path("size")
			.path("type")
			.asString()).isEqualTo("string");
		assertThat(inputRequests.path("coffeeSize")
			.path("params")
			.path("requestedSchema")
			.path("properties")
			.path("size")
			.path("enum")
			.toString()).isEqualTo("[\"small\",\"medium\",\"large\"]");
		assertThat(inputRequests.path("refillCount")
			.path("params")
			.path("requestedSchema")
			.path("properties")
			.path("refills")
			.path("type")
			.asString()).isEqualTo("integer");
		String stateOne = result.path("requestState").asString();
		assertThat(stateOne).startsWith("mrt-echo.r1.");
		// The SDK's CallToolResult builder materializes an empty list, not null, so the
		// wire shape is "content": [] rather than an absent field.
		assertThat(result.path("content").isArray()).as("input_required carries empty content").isTrue();
		assertThat(result.path("content").size()).as("input_required carries no content items").isZero();

		// determinism: a second fresh call mints the byte-identical token
		JsonNode firstAgain = callTool(proxyMcp, sessionId, 102, null, null);
		assertThat(firstAgain.path("result").path("requestState").asString()).as("round-1 state token is byte-stable")
			.isEqualTo(stateOne);

		// when: round 2, re-send with all three answers plus the echoed token
		ObjectNode answers = MAPPER.createObjectNode();
		answers.set("favoriteCity", stringAccept("city", "Berlin"));
		answers.set("coffeeSize", stringAccept("size", "large"));
		answers.set("refillCount", intAccept("refills", 2));
		JsonNode second = callTool(proxyMcp, sessionId, 103, answers, stateOne);
		// then: one more input_required round with a single boolean request
		assertThat(second.has("error")).as("round 2 must not fail, body=%s", second).isFalse();
		JsonNode resultTwo = second.path("result");
		assertThat(resultTwo.path("resultType").asString()).isEqualTo("input_required");
		JsonNode roundTwoRequests = resultTwo.path("inputRequests");
		assertThat(roundTwoRequests.size()).as("round 2 asks exactly 1 request").isEqualTo(1);
		assertThat(roundTwoRequests.path("confirmation")
			.path("params")
			.path("requestedSchema")
			.path("properties")
			.path("confirmed")
			.path("type")
			.asString()).isEqualTo("boolean");
		String stateTwo = resultTwo.path("requestState").asString();
		assertThat(stateTwo).startsWith("mrt-echo.r2.").isNotEqualTo(stateOne);

		// when: round 3, confirm and finish
		ObjectNode confirmation = MAPPER.createObjectNode();
		confirmation.set("confirmation", booleanAccept("confirmed", true));
		JsonNode third = callTool(proxyMcp, sessionId, 104, confirmation, stateTwo);
		// then: plain text echo, no resultType
		assertThat(third.has("error")).as("round 3 must not fail, body=%s", third).isFalse();
		JsonNode finalResult = third.path("result");
		assertThat(finalResult.path("resultType").isMissingNode()).isTrue();
		assertThat(finalResult.path("content").get(0).path("type").asString()).isEqualTo("text");
		assertThat(finalResult.path("content").get(0).path("text").asString())
			.isEqualTo("multiRoundTripEcho: round 1 - city=Berlin, size=large, refills=2; round 2 - confirmed=true");
	}

	@Test
	@DisplayName("invalid re-sends fail with JSON-RPC error -32602")
	@Story("Rejections: mismatched, missing, malformed")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Unknown requestState, inputResponses without requestState, requestState without "
			+ "inputResponses and a wrong enum value all return JSON-RPC error -32602 with a message "
			+ "naming the violation.")
	void invalidReSends_failWithMinus32602() throws Exception {
		// given
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyMcp = "http://127.0.0.1:" + port + "/mcp-inspector-api/mcp";
		String sessionId = openSession(proxyMcp, targetUrl);
		ObjectNode answers = MAPPER.createObjectNode();
		answers.set("favoriteCity", stringAccept("city", "Berlin"));
		answers.set("coffeeSize", stringAccept("size", "large"));
		answers.set("refillCount", intAccept("refills", 2));

		// unknown requestState
		JsonNode bogusState = callTool(proxyMcp, sessionId, 201, answers, "mrt-echo.r1.not-a-real-token");
		assertThat(bogusState.path("error").path("code").asLong()).isEqualTo(-32602);
		assertThat(bogusState.path("error").path("message").asString()).contains("requestState");

		// inputResponses without the echoed requestState
		JsonNode withoutState = callTool(proxyMcp, sessionId, 202, answers, null);
		assertThat(withoutState.path("error").path("code").asLong()).isEqualTo(-32602);
		assertThat(withoutState.path("error").path("message").asString()).contains("requestState");

		// requestState without inputResponses
		JsonNode first = callTool(proxyMcp, sessionId, 203, null, null);
		String stateOne = first.path("result").path("requestState").asString();
		JsonNode withoutResponses = callTool(proxyMcp, sessionId, 204, null, stateOne);
		assertThat(withoutResponses.path("error").path("code").asLong()).isEqualTo(-32602);
		assertThat(withoutResponses.path("error").path("message").asString()).contains("inputResponses");

		// wrong enum value
		ObjectNode venti = MAPPER.createObjectNode();
		venti.set("favoriteCity", stringAccept("city", "Berlin"));
		venti.set("coffeeSize", stringAccept("size", "venti"));
		venti.set("refillCount", intAccept("refills", 2));
		JsonNode badEnum = callTool(proxyMcp, sessionId, 205, venti, stateOne);
		assertThat(badEnum.path("error").path("code").asLong()).isEqualTo(-32602);
		assertThat(badEnum.path("error").path("message").asString()).contains("coffeeSize");
	}

	/**
	 * Opens a proxy session: {@code initialize} (200 + {@code mcp-session-id}) and the
	 * {@code notifications/initialized} notification (202), mirroring the flow IT.
	 */
	private String openSession(String proxyMcp, String targetUrl) throws Exception {
		ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		ObjectNode info = params.putObject("clientInfo");
		info.put("name", "multi-round-trip-it");
		info.put("version", "0.1.0");

		HttpRequest initRequest = HttpRequest
			.newBuilder(URI.create(proxyMcp + "?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		HttpResponse<String> initResponse = HTTP.send(initRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(initResponse.statusCode())
			.as("initialize HTTP status on %s, body=%s", ProxyAppHarness.stack(), initResponse.body())
			.isEqualTo(200);
		String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse(null);
		assertThat(sessionId).as("mcp-session-id round-trip on %s", ProxyAppHarness.stack()).isNotBlank();

		ObjectNode initializedNotification = MAPPER.createObjectNode();
		initializedNotification.put("jsonrpc", "2.0");
		initializedNotification.put("method", "notifications/initialized");
		HttpRequest initializedRequest = HttpRequest.newBuilder(URI.create(proxyMcp))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("mcp-session-id", sessionId)
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(initializedNotification)))
			.build();
		HttpResponse<String> initializedResponse = HTTP.send(initializedRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(initializedResponse.statusCode())
			.as("notifications/initialized must be 202 on %s, body=%s", ProxyAppHarness.stack(),
					initializedResponse.body())
			.isEqualTo(202);
		return sessionId;
	}

	/**
	 * Sends one {@code tools/call} for {@code multiRoundTripEcho}. Request frames are
	 * answered inline with the upstream JSON-RPC reply, so the body carries either the
	 * result or the JSON-RPC error object for the given id.
	 */
	private JsonNode callTool(String proxyMcp, String sessionId, int id, JsonNode inputResponses, String requestState)
			throws Exception {
		ObjectNode rpc = MAPPER.createObjectNode();
		rpc.put("jsonrpc", "2.0");
		rpc.put("id", id);
		rpc.put("method", "tools/call");
		ObjectNode params = rpc.putObject("params");
		params.put("name", "multiRoundTripEcho");
		params.putObject("arguments");
		if (inputResponses != null) {
			params.set("inputResponses", inputResponses);
		}
		if (requestState != null) {
			params.put("requestState", requestState);
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(proxyMcp))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("mcp-session-id", sessionId)
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(rpc)))
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode())
			.as("tools/call %d relay on %s, body=%s", id, ProxyAppHarness.stack(), response.body())
			.isEqualTo(200);
		return MAPPER.readTree(response.body());
	}

	private static ObjectNode stringAccept(String field, String value) {
		ObjectNode content = MAPPER.createObjectNode();
		content.put(field, value);
		return accept(content);
	}

	private static ObjectNode intAccept(String field, int value) {
		ObjectNode content = MAPPER.createObjectNode();
		content.put(field, value);
		return accept(content);
	}

	private static ObjectNode booleanAccept(String field, boolean value) {
		ObjectNode content = MAPPER.createObjectNode();
		content.put(field, value);
		return accept(content);
	}

	private static ObjectNode accept(ObjectNode content) {
		ObjectNode entry = MAPPER.createObjectNode();
		entry.put("action", "accept");
		entry.set("content", content);
		return entry;
	}

}
