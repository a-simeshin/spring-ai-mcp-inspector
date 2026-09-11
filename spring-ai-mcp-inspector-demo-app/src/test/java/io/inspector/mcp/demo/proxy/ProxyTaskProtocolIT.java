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
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the SEP-1686 task protocol methods ({@code tasks/get} and
 * {@code tasks/cancel}) through the streamable-HTTP proxy, on both webmvc and webflux
 * stacks.
 *
 * <p>
 * The proxy lives at {@code /mcp-inspector-api/mcp}. When the demo app boots the
 * {@code TaskRegistry} (which implements {@code TaskService}), the proxy controllers
 * intercept {@code tasks/get} and {@code tasks/cancel} and answer them locally instead of
 * relaying to the upstream MCP server. The tests verify:
 *
 * <ul>
 * <li>{@code tasks/get} on a live task returns the current handle ({@code working}, then
 * {@code cancelled} after cancel).</li>
 * <li>{@code tasks/cancel} on a running task returns {@code cancelled} and stops the
 * background work.</li>
 * <li>{@code tasks/get} on an unknown id returns a JSON-RPC {@code -32602} error.</li>
 * <li>{@code tasks/cancel} on a terminal task returns a JSON-RPC {@code -32602}
 * error.</li>
 * </ul>
 */
@Epic("Inspector Proxy")
@Feature("Task protocol methods")
class ProxyTaskProtocolIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

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
	@DisplayName("tasks/get on a running task returns the live handle")
	@Story("tasks/get live transition")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Starts a long-running generateReport via tools/call, then polls tasks/get through the "
			+ "proxy. The handle must report status=working. After tasks/cancel, tasks/get must "
			+ "report status=cancelled.")
	void tasksGet_onRunningTask_returnsLiveHandle() throws Exception {
		// given
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// Initialize a session
		String sessionId = initializeSession(proxyBase, targetUrl);

		// Start a long-running task via tools/call (generateReport with 30s duration)
		String taskId = startReportTask(proxyBase, sessionId);

		// when: tasks/get on the running task
		JsonNode getBody = buildJsonRpcRequest("tasks/get", 10, taskId);
		HttpResponse<String> getResponse = postMcp(proxyBase, sessionId, getBody);

		// then: 200 with status=working
		assertThat(getResponse.statusCode()).as("tasks/get status").isEqualTo(200);
		JsonNode getResult = MAPPER.readTree(getResponse.body());
		assertThat(getResult.get("result").get("taskId").asText()).as("taskId in get response").isEqualTo(taskId);
		assertThat(getResult.get("result").get("status").asText()).as("status in get response").isEqualTo("working");
		assertThat(getResult.get("result").get("ttl").asLong()).as("ttl in get response").isPositive();
		assertThat(getResult.get("result").get("pollInterval").asLong()).as("pollInterval in get response")
			.isPositive();

		// when: tasks/cancel
		JsonNode cancelBody = buildJsonRpcRequest("tasks/cancel", 11, taskId);
		HttpResponse<String> cancelResponse = postMcp(proxyBase, sessionId, cancelBody);

		// then: 200 with status=cancelled
		assertThat(cancelResponse.statusCode()).as("tasks/cancel status").isEqualTo(200);
		JsonNode cancelResult = MAPPER.readTree(cancelResponse.body());
		assertThat(cancelResult.get("result").get("status").asText()).as("status in cancel response")
			.isEqualTo("cancelled");

		// when: tasks/get again after cancel
		HttpResponse<String> getAfterCancel = postMcp(proxyBase, sessionId,
				buildJsonRpcRequest("tasks/get", 12, taskId));

		// then: still cancelled (terminal states are final)
		JsonNode getAfterCancelResult = MAPPER.readTree(getAfterCancel.body());
		assertThat(getAfterCancelResult.get("result").get("status").asText())
			.as("status after cancel must remain cancelled")
			.isEqualTo("cancelled");
	}

	@Test
	@DisplayName("tasks/get on an unknown task returns -32602")
	@Story("Unknown task get")
	@Severity(SeverityLevel.NORMAL)
	@Description("A tasks/get with a non-existent taskId must return a JSON-RPC error with code -32602 "
			+ "(Invalid params), per SEP-1686")
	void tasksGet_unknownTaskId_returnsError() throws Exception {
		// given
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";
		String sessionId = initializeSession(proxyBase, targetUrl);

		// when
		JsonNode getBody = buildJsonRpcRequest("tasks/get", 20, "no-such-task-id");
		HttpResponse<String> response = postMcp(proxyBase, sessionId, getBody);

		// then
		assertThat(response.statusCode()).as("HTTP status").isEqualTo(200);
		JsonNode body = MAPPER.readTree(response.body());
		assertThat(body.has("error")).as("must have error field").isTrue();
		assertThat(body.get("error").get("code").asInt()).as("error code").isEqualTo(-32602);
		assertThat(body.get("error").get("message").asText()).as("error message").containsIgnoringCase("not found");
	}

	@Test
	@DisplayName("tasks/cancel on an already-terminal task returns -32602")
	@Story("Cancel terminal task")
	@Severity(SeverityLevel.NORMAL)
	@Description("A tasks/cancel on a task already in a terminal state must return a JSON-RPC error "
			+ "with code -32602, per SEP-1686")
	void tasksCancel_onTerminalTask_returnsError() throws Exception {
		// given
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";
		String sessionId = initializeSession(proxyBase, targetUrl);

		// Start and immediately cancel a task (double-cancel)
		String taskId = startReportTask(proxyBase, sessionId);
		JsonNode cancelBody = buildJsonRpcRequest("tasks/cancel", 30, taskId);
		HttpResponse<String> firstCancel = postMcp(proxyBase, sessionId, cancelBody);
		assertThat(firstCancel.statusCode()).as("first cancel HTTP status").isEqualTo(200);
		JsonNode firstResult = MAPPER.readTree(firstCancel.body());
		assertThat(firstResult.get("result").get("status").asText()).isEqualTo("cancelled");

		// when: cancel again
		HttpResponse<String> secondCancel = postMcp(proxyBase, sessionId,
				buildJsonRpcRequest("tasks/cancel", 31, taskId));

		// then: -32602
		assertThat(secondCancel.statusCode()).as("second cancel HTTP status").isEqualTo(200);
		JsonNode secondResult = MAPPER.readTree(secondCancel.body());
		assertThat(secondResult.has("error")).as("must have error field").isTrue();
		assertThat(secondResult.get("error").get("code").asInt()).as("error code").isEqualTo(-32602);
		assertThat(secondResult.get("error").get("message").asText()).as("error message")
			.containsIgnoringCase("terminal");
	}

	@Test
	@DisplayName("initialize response includes injected capabilities.tasks")
	@Story("Initialize capability injection")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The proxy must inject capabilities.tasks into the initialize response so the UI Tasks tab "
			+ "is enabled. The injected object must contain list, cancel, and requests.tools.call sub-objects.")
	void initialize_responseIncludesInjectedTasksCapability() throws Exception {
		// given
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// when: initialize a session
		ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		ObjectNode info = params.putObject("clientInfo");
		info.put("name", "task-protocol-it");
		info.put("version", "0.1.0");

		HttpRequest request = HttpRequest
			.newBuilder(URI.create(proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

		// then: 200 and the response must contain capabilities.tasks
		assertThat(response.statusCode()).as("initialize HTTP status").isEqualTo(200);
		JsonNode initResponse = MAPPER.readTree(response.body());
		JsonNode capabilities = initResponse.path("result").path("capabilities");
		assertThat(capabilities.isObject()).as("capabilities must be an object").isTrue();
		assertThat(capabilities.has("tasks")).as("capabilities must contain injected tasks").isTrue();
		JsonNode tasks = capabilities.get("tasks");
		assertThat(tasks.isObject()).as("tasks must be an object").isTrue();
		assertThat(tasks.has("list")).as("tasks must contain list").isTrue();
		assertThat(tasks.has("cancel")).as("tasks must contain cancel").isTrue();
		assertThat(tasks.path("requests").path("tools").path("call").isObject())
			.as("tasks.requests.tools.call must be an object")
			.isTrue();
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	/**
	 * Initialize a new proxy session and return the mcp-session-id.
	 */
	private static String initializeSession(String proxyBase, String targetUrl) throws Exception {
		ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		ObjectNode info = params.putObject("clientInfo");
		info.put("name", "task-protocol-it");
		info.put("version", "0.1.0");

		HttpRequest request = HttpRequest
			.newBuilder(URI.create(proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("initialize HTTP status").isEqualTo(200);
		return response.headers().firstValue("mcp-session-id").orElseThrow();
	}

	/**
	 * Start a long-running generateReport task via tools/call through the proxy, and
	 * return the taskId from the response.
	 */
	private static String startReportTask(String proxyBase, String sessionId) throws Exception {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("jsonrpc", "2.0");
		body.put("method", "tools/call");
		body.put("id", 100);
		ObjectNode params = body.putObject("params");
		params.put("name", "generateReport");
		ObjectNode args = params.putObject("arguments");
		args.put("durationSeconds", 30); // long enough that we can cancel before it
											// completes

		HttpResponse<String> response = postMcp(proxyBase, sessionId, body);
		assertThat(response.statusCode()).as("tools/call HTTP status").isEqualTo(200);
		JsonNode result = MAPPER.readTree(response.body());
		// The MCP server wraps the tool result: result.content[0].text contains the JSON
		// string returned by the @McpTool method.
		JsonNode content = result.get("result").get("content");
		if (content != null && content.isArray() && content.size() > 0) {
			JsonNode textNode = content.get(0).get("text");
			if (textNode != null) {
				JsonNode toolResult = MAPPER.readTree(textNode.asText());
				return toolResult.get("taskId").asText();
			}
		}
		// Fallback: maybe the result is directly the tool return value
		JsonNode taskNode = result.get("result");
		if (taskNode != null && taskNode.has("taskId")) {
			return taskNode.get("taskId").asText();
		}
		throw new IllegalStateException("Cannot extract taskId from tools/call response: " + result);
	}

	/**
	 * POST a JSON-RPC request through the streamable-HTTP proxy.
	 */
	private static HttpResponse<String> postMcp(String proxyBase, String sessionId, JsonNode body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("mcp-session-id", sessionId)
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Build a JSON-RPC request for a task method with a taskId parameter.
	 */
	private static ObjectNode buildJsonRpcRequest(String method, int id, String taskId) {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("jsonrpc", "2.0");
		body.put("method", method);
		body.put("id", id);
		ObjectNode params = body.putObject("params");
		params.put("taskId", taskId);
		return body;
	}

}