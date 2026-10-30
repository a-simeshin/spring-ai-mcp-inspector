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
import org.springframework.context.ConfigurableApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring test for the exception-leak detector (issue #251): the demo
 * {@code leakyInsert} tool first logs the failure like an infrastructure layer would and
 * then rethrows, so the tool result carries the same E11000 text the application log
 * already contains. The timeline HTTP endpoint must annotate that tool result with a
 * {@code _leak} marker of type {@code EXACT} and {@code verbatim=true}, referencing the
 * log event id.
 *
 * <p>
 * Lives in the shared {@code demo-app} test-jar so it runs once per stack (webmvc and
 * webflux) through each demo module's Failsafe {@code dependenciesToScan}. Assertions go
 * over {@code GET ${path}/api/timeline} - the same contract the inspector SPA consumes -
 * never over the in-process beans, because that is the only path that proves the
 * enrichment is actually served to the browser.
 *
 * <p>
 * The second test pins the kill switch: with
 * {@code spring.ai.mcp.inspector.timeline.leak-detector.enabled=false} the timeline is
 * still served, but tool results carry no {@code _leak} annotation.
 */
@Epic("Inspector Proxy")
@Feature("Exception leak detection")
class LeakDetectorTimelineIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	private static final Duration BUDGET = Duration.ofSeconds(30);

	private static final String TIMELINE_PATH = "/mcp-inspector/api/timeline";

	private static final String E11000 = "E11000 duplicate key error collection: db.app index: tenant_name_idx dup key: { name: \"acme\" }";

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
	@DisplayName("leak detector: failing leakyInsert is annotated _leak EXACT verbatim over /api/timeline")
	@Story("Leak annotated on the timeline")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo with the timeline enabled, calls the leakyInsert demo tool " + "(name=acme) "
			+ "through the proxy and asserts GET /api/timeline returns the error tool result carrying a "
			+ "_leak annotation with matchType=EXACT, verbatim=true and a non-blank logEventId "
			+ "referencing the logged DuplicateKey-looking failure")
	void leakyInsertToolResult_isAnnotatedExactVerbatimOverHttpTimeline() throws Exception {
		// given
		this.app = ProxyAppHarness.start("STREAMABLE", false, null, "--spring.ai.mcp.inspector.timeline.enabled=true");
		final int port = ProxyAppHarness.port(this.app);
		final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// when: initialize + failing tools/call through the proxy
		final HttpResponse<String> initResponse = post(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null,
				initializeFrame());
		assertThat(initResponse.statusCode())
			.as("initialize through proxy on %s, body=%s", ProxyAppHarness.stack(), initResponse.body())
			.isEqualTo(200);
		final String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse("");
		assertThat(sessionId).as("mcp-session-id on %s", ProxyAppHarness.stack()).isNotBlank();

		final HttpResponse<String> callResponse = post(proxyBase + "/mcp", sessionId, leakyInsertFrame());
		assertThat(callResponse.statusCode())
			.as("tools/call through proxy on %s, body=%s", ProxyAppHarness.stack(), callResponse.body())
			.isEqualTo(200);
		final JsonNode result = MAPPER.readTree(callResponse.body()).path("result");
		assertThat(result.path("isError").asBoolean(false))
			.as("leakyInsert returns an error tool result on %s", ProxyAppHarness.stack())
			.isTrue();

		// then: the timeline serves the annotated pair (both recorder and log appender
		// are async, so poll the HTTP endpoint until the annotation lands or budget ends)
		final JsonNode annotated = awaitAnnotatedResponse(port, sessionId);
		assertThat(annotated).as("annotated tools/call RESPONSE on /api/timeline over %s", ProxyAppHarness.stack())
			.isNotNull();
		final JsonNode leak = annotated.path("payload").path("_leak");
		assertThat(leak.path("matchType").asString("")).as("_leak.matchType on %s", ProxyAppHarness.stack())
			.isEqualTo("EXACT");
		assertThat(leak.path("verbatim").asBoolean(false)).as("_leak.verbatim on %s", ProxyAppHarness.stack()).isTrue();
		final String logEventId = leak.path("logEventId").asString("");
		assertThat(logEventId).as("_leak.logEventId on %s", ProxyAppHarness.stack()).isNotBlank();
		assertThat(leak.path("logThrowableClass").asString("")).contains("RuntimeException");

		// and: the referenced log event exists and carries the E11000 text
		final List<JsonNode> events = timelineEvents(port);
		final JsonNode logEvent = findByEventId(events, logEventId);
		assertThat(logEvent).as("referenced APP_LOG event exists on %s", ProxyAppHarness.stack()).isNotNull();
		assertThat(logEvent.path("payload").path("throwable").asString(""))
			.as("referenced log event carries the E11000 failure on %s", ProxyAppHarness.stack())
			.contains(E11000);
	}

	@Test
	@DisplayName("leak detector disabled: timeline served, tool results carry no _leak")
	@Story("Kill switch")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo with spring.ai.mcp.inspector.timeline.leak-detector.enabled=false, "
			+ "calls leakyInsert and asserts the timeline endpoint still answers 200 with the "
			+ "error tool result present, but without any _leak annotation")
	void detectorDisabled_timelineServedWithoutLeakAnnotation() throws Exception {
		// given: the detector is disabled while the timeline stays on
		this.app = ProxyAppHarness.start("STREAMABLE", false, null, "--spring.ai.mcp.inspector.timeline.enabled=true",
				"--spring.ai.mcp.inspector.timeline.leak-detector.enabled=false");
		final int port = ProxyAppHarness.port(this.app);
		final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// when: initialize + failing tools/call through the proxy
		final HttpResponse<String> initResponse = post(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null,
				initializeFrame());
		final String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse("");
		assertThat(sessionId).as("mcp-session-id on %s", ProxyAppHarness.stack()).isNotBlank();
		final HttpResponse<String> callResponse = post(proxyBase + "/mcp", sessionId, leakyInsertFrame());
		assertThat(callResponse.statusCode())
			.as("tools/call through proxy on %s, body=%s", ProxyAppHarness.stack(), callResponse.body())
			.isEqualTo(200);

		// then: the timeline serves the error tool result with no _leak annotation
		final List<JsonNode> events = awaitEvents(port, sessionId, 2);
		final JsonNode response = findEvent(events, "MCP_JSONRPC_RESPONSE", sessionId, 2);
		assertThat(response).as("tools/call RESPONSE on /api/timeline over %s", ProxyAppHarness.stack()).isNotNull();
		assertThat(response.path("payload").path("result").path("isError").asBoolean(false))
			.as("error tool result still recorded on %s", ProxyAppHarness.stack())
			.isTrue();
		for (final JsonNode event : events) {
			assertThat(event.path("payload").has("_leak"))
				.as("no _leak annotation anywhere while the detector is disabled on %s", ProxyAppHarness.stack())
				.isFalse();
		}
	}

	private static ObjectNode initializeFrame() {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		init.set("params",
				MAPPER.createObjectNode()
					.put("protocolVersion", "2025-03-26")
					.set("capabilities", MAPPER.createObjectNode())
					.set("clientInfo", MAPPER.createObjectNode().put("name", "leak-it").put("version", "0")));
		return init;
	}

	private static ObjectNode leakyInsertFrame() {
		final ObjectNode call = MAPPER.createObjectNode();
		call.put("jsonrpc", "2.0");
		call.put("method", "tools/call");
		call.put("id", 2);
		call.set("params",
				MAPPER.createObjectNode()
					.put("name", "leakyInsert")
					.set("arguments", MAPPER.createObjectNode().put("name", "acme")));
		return call;
	}

	/**
	 * Polls {@code GET /api/timeline} until the tools/call response of this session
	 * carries a {@code _leak} annotation, then returns that event (or {@code null} when
	 * the budget ends). Both the traffic recorder and the log appender are async, so a
	 * single read would race the annotation.
	 */
	private static JsonNode awaitAnnotatedResponse(final int port, final String sessionId) throws Exception {
		final long deadline = System.nanoTime() + BUDGET.toNanos();
		JsonNode found = null;
		while (System.nanoTime() < deadline) {
			final JsonNode response = findEvent(timelineEvents(port), "MCP_JSONRPC_RESPONSE", sessionId, 2);
			if (response != null && !response.path("payload").path("_leak").isMissingNode()) {
				found = response;
				break;
			}
			Thread.sleep(250);
		}
		return found;
	}

	/**
	 * Polls {@code GET /api/timeline} until the response event of {@code rpcId} for this
	 * session shows up (recorder appender is async), then returns the full event list.
	 */
	private static List<JsonNode> awaitEvents(final int port, final String sessionId, final int rpcId)
			throws Exception {
		final long deadline = System.nanoTime() + BUDGET.toNanos();
		List<JsonNode> events = List.of();
		while (System.nanoTime() < deadline) {
			events = timelineEvents(port);
			if (findEvent(events, "MCP_JSONRPC_RESPONSE", sessionId, rpcId) != null) {
				break;
			}
			Thread.sleep(250);
		}
		return events;
	}

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

	private static JsonNode findByEventId(final List<JsonNode> events, final String eventId) {
		for (final JsonNode event : events) {
			if (eventId.equals(event.path("id").asString(""))) {
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
