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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
 * SEP-2640 Skills extension pass-through contract on the streamable-HTTP proxy
 * ({@code /mcp-inspector-api/mcp}), on both stacks.
 *
 * <p>
 * The proxy is a method-agnostic JSON-RPC frame relay, so the three extension methods
 * ({@code skills/list}, {@code skills/get}, {@code resources/directory/read}) need no
 * proxy-side handling. This IT locks that behaviour in against a raw mock MCP server (JDK
 * {@link HttpServer}, not the demo app) that records every received frame:
 *
 * <ul>
 * <li>the mock's {@code initialize} result advertising
 * {@code capabilities.extensions["io.modelcontextprotocol/skills"]} reaches the browser
 * unchanged (the proxy never mutates payloads, so the browser SDK sees the advertisement
 * and the UI can gate the Skills tab);</li>
 * <li>{@code skills/list} and {@code skills/get} params arrive at the mock semantically
 * identical to what the browser sent, and the mock's results come back unchanged;</li>
 * <li>a server-side JSON-RPC error ({@code -32601} for {@code resources/directory/read}
 * when not declared) surfaces to the browser verbatim;</li>
 * <li>for a server that does NOT advertise the extension, the proxy still passes requests
 * through and surfaces the server's own {@code -32601}: the documented choice is
 * pass-through, not inspector-side short-circuiting.</li>
 * </ul>
 */
@Epic("Inspector Proxy")
@Feature("SEP-2640 skills pass-through")
class ProxySkillsExtensionPassThroughIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	private static final Duration BUDGET = Duration.ofSeconds(30);

	private static final String SKILLS_EXT_ID = "io.modelcontextprotocol/skills";

	private ConfigurableApplicationContext app;

	private HttpServer mockServer;

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
		if (this.mockServer != null) {
			this.mockServer.stop(0);
			this.mockServer = null;
		}
	}

	@Test
	@DisplayName("skills/list, skills/get and directory-read errors pass through the proxy unchanged")
	@Story("SEP-2640 pass-through")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Against a mock MCP server: the skills extension advertisement survives initialize, "
			+ "skills/list, skills/get and resources/directory/read params reach the server semantically "
			+ "identical and results return unchanged")
	void skillsMethods_throughStreamableProxy_passThroughUnchanged() throws Exception {
		// given: mock MCP server advertising SEP-2640 with directoryRead=true, so
		// it answers skills/list, skills/get AND resources/directory/read.
		final MockMcpServer mock = new MockMcpServer(true);
		this.mockServer = mock.server;
		final String targetUrl = "http://127.0.0.1:" + mock.server.getAddress().getPort() + "/mcp";

		this.app = ProxyAppHarness.start("STREAMABLE", false, null);
		final String proxyBase = "http://127.0.0.1:" + ProxyAppHarness.port(this.app) + "/mcp-inspector-api";

		// ---- 1. initialize: extension advertisement must survive the relay ----
		final ObjectNode init = buildInit(1);
		final HttpResponse<String> initResponse = postProxy(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null, init);
		assertThat(initResponse.statusCode())
			.as("initialize on %s, body=%s", ProxyAppHarness.stack(), initResponse.body())
			.isEqualTo(200);
		final String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse(null);
		assertThat(sessionId).as("mcp-session-id on %s", ProxyAppHarness.stack()).isNotBlank();

		final JsonNode initBody = MAPPER.readTree(initResponse.body());
		final JsonNode extensions = initBody.path("result").path("capabilities").path("extensions");
		assertThat(extensions.path(SKILLS_EXT_ID).isObject())
			.as("capabilities.extensions[%s] must reach the browser unchanged, body=%s", SKILLS_EXT_ID,
					initResponse.body())
			.isTrue();
		assertThat(initBody.path("result").path("capabilities").path("extensions").path(SKILLS_EXT_ID))
			.as("extension settings node must equal the mock's advertisement")
			.isEqualTo(MAPPER.valueToTree(Map.of("directoryRead", true)));

		// ---- 2. notifications/initialized -> 202 -------------------------------
		final ObjectNode initialized = MAPPER.createObjectNode();
		initialized.put("jsonrpc", "2.0");
		initialized.put("method", "notifications/initialized");
		final HttpResponse<String> notifResponse = postProxy(proxyBase + "/mcp", sessionId, initialized);
		assertThat(notifResponse.statusCode()).as("notification on %s", ProxyAppHarness.stack()).isEqualTo(202);

		// ---- 3. skills/list: params unchanged at the server, result unchanged
		// at the browser. The params carry a cursor and a _meta block that a
		// mutating proxy would drop or reorder.
		final ObjectNode skillsList = MAPPER.createObjectNode();
		skillsList.put("jsonrpc", "2.0");
		skillsList.put("method", "skills/list");
		skillsList.put("id", 2);
		final ObjectNode listParams = skillsList.putObject("params");
		listParams.put("cursor", "page-2-cursor");
		listParams.putObject("_meta").put("probe", "keep-me");
		final HttpResponse<String> listResponse = postProxy(proxyBase + "/mcp", sessionId, skillsList);
		assertThat(listResponse.statusCode())
			.as("skills/list on %s, body=%s", ProxyAppHarness.stack(), listResponse.body())
			.isEqualTo(200);

		final JsonNode serverSawList = mock.receivedParams("skills/list");
		assertThat(serverSawList).as("mock server saw a skills/list request").isNotNull();
		assertThat(serverSawList).as("skills/list params pass through semantically identical")
			.isEqualTo(MAPPER.readTree(listParams.toString()));

		final JsonNode listBody = MAPPER.readTree(listResponse.body());
		assertThat(listBody.path("id").asInt()).isEqualTo(2);
		assertThat(listBody.path("result")).as("skills/list result returned verbatim")
			.isEqualTo(MAPPER.valueToTree(MockMcpServer.SKILLS_LIST_RESULT));

		// ---- 4. skills/get: same contract for the detail method ----------------
		final ObjectNode skillsGet = MAPPER.createObjectNode();
		skillsGet.put("jsonrpc", "2.0");
		skillsGet.put("method", "skills/get");
		skillsGet.put("id", 3);
		final ObjectNode getParams = skillsGet.putObject("params");
		getParams.put("uri", "skill://demo-billing/SKILL.md");
		final HttpResponse<String> getResponse = postProxy(proxyBase + "/mcp", sessionId, skillsGet);
		assertThat(getResponse.statusCode())
			.as("skills/get on %s, body=%s", ProxyAppHarness.stack(), getResponse.body())
			.isEqualTo(200);

		final JsonNode serverSawGet = mock.receivedParams("skills/get");
		assertThat(serverSawGet).as("skills/get params pass through semantically identical")
			.isEqualTo(MAPPER.readTree(getParams.toString()));

		final JsonNode getBody = MAPPER.readTree(getResponse.body());
		assertThat(getBody.path("id").asInt()).isEqualTo(3);
		assertThat(getBody.path("result")).as("skills/get result returned verbatim")
			.isEqualTo(MAPPER.valueToTree(MockMcpServer.SKILLS_GET_RESULT));

		// ---- 5. resources/directory/read: the mock declared directoryRead=true and
		// answers with a directory listing. Same verbatim contract.
		final ObjectNode dirRead = MAPPER.createObjectNode();
		dirRead.put("jsonrpc", "2.0");
		dirRead.put("method", "resources/directory/read");
		dirRead.put("id", 4);
		dirRead.putObject("params").put("uri", "skill://demo-billing");
		final HttpResponse<String> dirResponse = postProxy(proxyBase + "/mcp", sessionId, dirRead);
		assertThat(dirResponse.statusCode())
			.as("directory/read on %s, body=%s", ProxyAppHarness.stack(), dirResponse.body())
			.isEqualTo(200);

		final JsonNode dirBody = MAPPER.readTree(dirResponse.body());
		assertThat(dirBody.path("id").asInt()).isEqualTo(4);
		assertThat(dirBody.path("result")).as("directory/read result returned verbatim")
			.isEqualTo(MAPPER.valueToTree(MockMcpServer.DIRECTORY_READ_RESULT));

		// ---- 6. teardown --------------------------------------------------------
		final HttpRequest delete = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(Duration.ofSeconds(10))
			.header("mcp-session-id", sessionId)
			.DELETE()
			.build();
		assertThat(HTTP.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode())
			.as("DELETE on %s", ProxyAppHarness.stack())
			.isIn(200, 204);
	}

	@Test
	@DisplayName("a server that does not advertise SEP-2640 keeps working: pass-through, server -32601 surfaces")
	@Story("Non-SEP-2640 servers")
	@Severity(SeverityLevel.CRITICAL)
	@Description("For servers without the extension the proxy does not short-circuit: initialize relays "
			+ "without an extensions key and skills/list surfaces the server's own -32601 (documented "
			+ "pass-through choice), so non-SEP-2640 servers see no regression")
	void nonSkillsServer_throughStreamableProxy_passesThroughAndSurfacesServer404() throws Exception {
		// given: mock MCP server WITHOUT the skills extension
		final MockMcpServer mock = new MockMcpServer(false);
		this.mockServer = mock.server;
		final String targetUrl = "http://127.0.0.1:" + mock.server.getAddress().getPort() + "/mcp";

		this.app = ProxyAppHarness.start("STREAMABLE", false, null);
		final String proxyBase = "http://127.0.0.1:" + ProxyAppHarness.port(this.app) + "/mcp-inspector-api";

		// when: initialize
		final HttpResponse<String> initResponse = postProxy(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null, buildInit(1));
		assertThat(initResponse.statusCode()).as("initialize on %s", ProxyAppHarness.stack()).isEqualTo(200);
		final String sessionId = initResponse.headers().firstValue("mcp-session-id").orElseThrow();

		final JsonNode initBody = MAPPER.readTree(initResponse.body());
		assertThat(initBody.path("result").path("capabilities").path("extensions").isMissingNode()
				|| initBody.path("result").path("capabilities").path("extensions").isNull())
			.as("no extensions key for a non-advertising server, body=%s", initResponse.body())
			.isTrue();

		// when: skills/list against the non-advertising server: documented choice
		// is pass-through, so the server's own -32601 must surface unchanged.
		final ObjectNode skillsList = MAPPER.createObjectNode();
		skillsList.put("jsonrpc", "2.0");
		skillsList.put("method", "skills/list");
		skillsList.put("id", 2);
		skillsList.putObject("params");
		final HttpResponse<String> listResponse = postProxy(proxyBase + "/mcp", sessionId, skillsList);

		// then
		assertThat(listResponse.statusCode())
			.as("skills/list on %s, body=%s", ProxyAppHarness.stack(), listResponse.body())
			.isEqualTo(200);
		final JsonNode listBody = MAPPER.readTree(listResponse.body());
		assertThat(listBody.path("error").path("code").asInt()).isEqualTo(-32601);
		assertThat(listBody.path("error").path("message").asText()).isEqualTo("Method not found");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static HttpResponse<String> postProxy(String urlWithQuery, String sessionId, ObjectNode body)
			throws Exception {
		final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(urlWithQuery))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
		if (sessionId != null) {
			builder.header("mcp-session-id", sessionId);
		}
		return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static ObjectNode buildInit(int id) {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", id);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "skills-pass-through-it");
		info.put("version", "0.1.0");
		return init;
	}

	/**
	 * Minimal raw MCP streamable-HTTP server on a JDK {@link HttpServer}: answers
	 * {@code initialize} (optionally advertising SEP-2640), {@code skills/list},
	 * {@code skills/get}, records the params of every request it sees, and answers any
	 * other method with a JSON-RPC {@code -32601} error like a server without the
	 * extension would.
	 */
	private static final class MockMcpServer {

		static final Map<String, Object> SKILLS_LIST_RESULT = Map.of("skills",
				List.of(Map.of("uri", "skill://demo-billing/SKILL.md", "frontmatter",
						Map.of("name", "demo-billing", "description", "Billing workflow demo")),
						Map.of("uri", "skill://demo-refunds/SKILL.md", "frontmatter",
								Map.of("name", "demo-refunds", "description", "Refund workflow demo"))));

		static final Map<String, Object> SKILLS_GET_RESULT = Map.of("uri", "skill://demo-billing/SKILL.md",
				"frontmatter", Map.of("name", "demo-billing", "description", "Billing workflow demo"), "resources",
				List.of(Map.of("uri", "skill://demo-billing/references/GUIDE.md", "digest",
						"sha256:0000000000000000000000000000000000000000000000000000000000000000", "size", 42)));

		static final Map<String, Object> DIRECTORY_READ_RESULT = Map.of("children",
				List.of(Map.of("uri", "skill://demo-billing/SKILL.md", "mimeType", "text/markdown"),
						Map.of("uri", "skill://demo-billing/references", "mimeType", "inode/directory")));

		/** method -> params node of the LAST request seen for that method. */
		private final ConcurrentMap<String, JsonNode> seen = new ConcurrentHashMap<>();

		final HttpServer server;

		MockMcpServer(final boolean advertiseSkills) throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/mcp", (exchange) -> handle(exchange, advertiseSkills));
			this.server.setExecutor(null);
			this.server.start();
		}

		JsonNode receivedParams(final String method) {
			return this.seen.get(method);
		}

		private void handle(final HttpExchange exchange, final boolean advertiseSkills) throws IOException {
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				exchange.close();
				return;
			}
			final String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			final JsonNode frame = MAPPER.readTree(request);
			final String method = frame.path("method").asText("");
			if (frame.has("params")) {
				this.seen.put(method, frame.get("params"));
			}
			final JsonNode id = frame.get("id");
			if (id == null || id.isNull()) {
				// notification -> 202
				exchange.sendResponseHeaders(202, -1);
				exchange.close();
				return;
			}
			final ObjectNode response = MAPPER.createObjectNode();
			response.put("jsonrpc", "2.0");
			response.set("id", id);
			switch (method) {
				case "initialize" -> response.set("result", initializeResult(advertiseSkills));
				case "skills/list" -> {
					if (advertiseSkills) {
						response.set("result", MAPPER.valueToTree(SKILLS_LIST_RESULT));
					}
					else {
						setMethodNotFound(response, method);
					}
				}
				case "skills/get" -> {
					if (advertiseSkills) {
						response.set("result", MAPPER.valueToTree(SKILLS_GET_RESULT));
					}
					else {
						setMethodNotFound(response, method);
					}
				}
				case "resources/directory/read" -> {
					if (advertiseSkills) {
						response.set("result", MAPPER.valueToTree(DIRECTORY_READ_RESULT));
					}
					else {
						setMethodNotFound(response, method);
					}
				}
				default -> setMethodNotFound(response, method);
			}
			final byte[] payload = MAPPER.writeValueAsBytes(response);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.getResponseHeaders().add("Mcp-Session-Id", "mock-session-1");
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(payload);
			}
			exchange.close();
		}

		private static void setMethodNotFound(final ObjectNode response, final String method) {
			final ObjectNode error = response.putObject("error");
			error.put("code", -32601);
			error.put("message", "Method not found");
			error.putObject("data").put("method", method);
		}

		private static ObjectNode initializeResult(final boolean advertiseSkills) {
			final ObjectNode result = MAPPER.createObjectNode();
			result.put("protocolVersion", "2025-06-18");
			final ObjectNode capabilities = result.putObject("capabilities");
			capabilities.putObject("tools");
			if (advertiseSkills) {
				capabilities.putObject("extensions").putObject(SKILLS_EXT_ID).put("directoryRead", true);
			}
			final ObjectNode serverInfo = result.putObject("serverInfo");
			serverInfo.put("name", "mock-skills-server");
			serverInfo.put("version", "0.0.1");
			return result;
		}

	}

}
