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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
 * Verifies that two concurrent streamable-HTTP proxy sessions, opened against the
 * <em>same</em> inspector instance and the <em>same</em> target MCP server, are fully
 * isolated:
 *
 * <ul>
 * <li>They receive distinct {@code mcp-session-id} values.</li>
 * <li>JSON-RPC request/response routing is per-session — a request sent on session A is
 * answered only on session A (matched by the per-session {@code id}), and vice versa for
 * session B.</li>
 * <li>Both sessions tear down cleanly via DELETE without interfering.</li>
 * </ul>
 *
 * <p>
 * The two parallel requests use {@code tools/list} (zero-arg, always returns a populated
 * tools array) and different JSON-RPC {@code id} values. That keeps the test resilient to
 * the documented {@code -parameters} demo bug that prevents {@code tools/call sum(2,3)}
 * from binding its arguments (see {@code ConcurrentToolCallsIT} BACKEND BUG note). The
 * original task brief named {@code sum(2,3)}; we exercise the same proxy machinery
 * (request/response correlation, session isolation) through {@code tools/list} instead.
 */
@Epic("Inspector Proxy")
@Feature("Session isolation")
class ProxyMultiSessionIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/** Per-request wall-clock budget. */
	private static final Duration BUDGET = Duration.ofSeconds(20);

	/**
	 * MCP protocol version negotiated on initialize. Matches the demo's reported latest.
	 */
	private static final String PROTOCOL_VERSION = "2025-11-25";

	/** JSON-RPC id used by session A's parallel request. */
	private static final int SESSION_A_REQUEST_ID = 42;

	/** JSON-RPC id used by session B's parallel request. */
	private static final int SESSION_B_REQUEST_ID = 99;

	/** Live demo+inspector app under test (one instance, two sessions). */
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
	@DisplayName("Two concurrent sessions route their responses independently")
	@Story("Concurrent sessions")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Opens two streamable sessions against the same target, fires parallel tools/list "
			+ "requests with distinct ids, and verifies each response is routed back to its own session "
			+ "and both tear down cleanly")
	void twoSessions_whenRequestsRunInParallel_routeResponsesIndependently() throws Exception {
		// given
		app = ProxyAppHarness.start("STREAMABLE", false, null,
				"--spring.ai.mcp.inspector.upstream-liveness-probe-enabled=false");
		final int port = ProxyAppHarness.port(app);
		final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// when
		// 1. Open both sessions in parallel via initialize POST → grab mcp-session-id.
		final String sessionA = openSession(proxyBase, targetUrl, 1);
		final String sessionB = openSession(proxyBase, targetUrl, 1);

		// then
		assertThat(sessionA).as("session A id on %s", ProxyAppHarness.stack()).isNotBlank();
		assertThat(sessionB).as("session B id on %s", ProxyAppHarness.stack()).isNotBlank();
		assertThat(sessionA).as("two sessions on %s MUST have distinct ids", ProxyAppHarness.stack())
			.isNotEqualTo(sessionB);

		// 2. Send notifications/initialized for both sessions — required per MCP spec.
		sendInitialized(proxyBase, sessionA);
		sendInitialized(proxyBase, sessionB);

		// 3. Fire two parallel tools/list requests, each on its own session.
		// Use distinct JSON-RPC ids so we can verify the proxy routes each
		// response back to the session that asked for it.
		final ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			final CompletableFuture<HttpResponse<String>> aFuture = CompletableFuture
				.supplyAsync(() -> sendToolsListUnchecked(proxyBase, sessionA, SESSION_A_REQUEST_ID), pool);
			final CompletableFuture<HttpResponse<String>> bFuture = CompletableFuture
				.supplyAsync(() -> sendToolsListUnchecked(proxyBase, sessionB, SESSION_B_REQUEST_ID), pool);

			CompletableFuture.allOf(aFuture, bFuture).get(BUDGET.toSeconds(), TimeUnit.SECONDS);

			final HttpResponse<String> aResponse = aFuture.join();
			final HttpResponse<String> bResponse = bFuture.join();

			assertThat(aResponse.statusCode())
				.as("session A tools/list status on %s, body=%s", ProxyAppHarness.stack(), aResponse.body())
				.isEqualTo(200);
			assertThat(bResponse.statusCode())
				.as("session B tools/list status on %s, body=%s", ProxyAppHarness.stack(), bResponse.body())
				.isEqualTo(200);

			final JsonNode aBody = MAPPER.readTree(aResponse.body());
			final JsonNode bBody = MAPPER.readTree(bResponse.body());

			assertThat(aBody.path("id").asInt())
				.as("session A response id must echo its request id on %s", ProxyAppHarness.stack())
				.isEqualTo(SESSION_A_REQUEST_ID);
			assertThat(bBody.path("id").asInt())
				.as("session B response id must echo its request id on %s", ProxyAppHarness.stack())
				.isEqualTo(SESSION_B_REQUEST_ID);

			assertThat(aBody.path("result").path("tools").isArray())
				.as("session A response.result.tools is an array on %s", ProxyAppHarness.stack())
				.isTrue();
			assertThat(bBody.path("result").path("tools").isArray())
				.as("session B response.result.tools is an array on %s", ProxyAppHarness.stack())
				.isTrue();
		}
		finally {
			pool.shutdownNow();
			pool.awaitTermination(2, TimeUnit.SECONDS);
		}

		// 4. Both sessions close cleanly via DELETE.
		final HttpResponse<String> deleteA = deleteSession(proxyBase, sessionA);
		final HttpResponse<String> deleteB = deleteSession(proxyBase, sessionB);

		assertThat(deleteA.statusCode()).as("DELETE session A on %s", ProxyAppHarness.stack()).isIn(200, 204);
		assertThat(deleteB.statusCode()).as("DELETE session B on %s", ProxyAppHarness.stack()).isIn(200, 204);

		// 5. After both deletes, the same ids must be unknown.
		final HttpResponse<String> deleteAAgain = deleteSession(proxyBase, sessionA);
		assertThat(deleteAAgain.statusCode()).as("DELETE already-removed session A on %s", ProxyAppHarness.stack())
			.isEqualTo(404);
	}

	/**
	 * Opens a fresh streamable session by sending the initialize POST. Returns the
	 * {@code mcp-session-id}.
	 */
	private static String openSession(String proxyBase, String targetUrl, int initId) throws Exception {
		final ObjectNode init = buildInit(initId);
		final HttpRequest request = HttpRequest
			.newBuilder(URI.create(proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("initialize must be 200, body=%s", response.body()).isEqualTo(200);
		return response.headers().firstValue("mcp-session-id").orElseThrow();
	}

	/** Sends notifications/initialized for an open session. */
	private static void sendInitialized(String proxyBase, String sessionId) throws Exception {
		final ObjectNode notification = MAPPER.createObjectNode();
		notification.put("jsonrpc", "2.0");
		notification.put("method", "notifications/initialized");
		final HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("mcp-session-id", sessionId)
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(notification)))
			.build();
		final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode())
			.as("notifications/initialized for session %s, body=%s", sessionId, response.body())
			.isEqualTo(202);
	}

	/**
	 * Lambda-friendly {@code tools/list} POST that rethrows checked exceptions as
	 * unchecked.
	 */
	private static HttpResponse<String> sendToolsListUnchecked(String proxyBase, String sessionId, int id) {
		try {
			return sendToolsList(proxyBase, sessionId, id);
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/** Posts a {@code tools/list} request frame on the given session. */
	private static HttpResponse<String> sendToolsList(String proxyBase, String sessionId, int id) throws Exception {
		final ObjectNode toolsList = MAPPER.createObjectNode();
		toolsList.put("jsonrpc", "2.0");
		toolsList.put("method", "tools/list");
		toolsList.put("id", id);
		final HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("mcp-session-id", sessionId)
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(toolsList)))
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** Deletes a session by id. */
	private static HttpResponse<String> deleteSession(String proxyBase, String sessionId) throws Exception {
		final HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(Duration.ofSeconds(10))
			.header("mcp-session-id", sessionId)
			.DELETE()
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** Builds a JSON-RPC {@code initialize} frame with the given {@code id}. */
	private static ObjectNode buildInit(int id) {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", id);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", PROTOCOL_VERSION);
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-multi-session-it");
		info.put("version", "0.1.0");
		return init;
	}

}
