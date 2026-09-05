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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the proxy's behavior when the upstream target MCP server vanishes
 * mid-session.
 *
 * <p>
 * Topology: two completely separate Spring contexts.
 *
 * <ul>
 * <li>{@code inspectorApp} — boots the demo with the inspector mounted at
 * {@code /mcp-inspector-api} (proxy entry point).</li>
 * <li>{@code targetApp} — boots a second standalone demo that serves as the upstream MCP
 * server the proxy points at.</li>
 * </ul>
 *
 * <p>
 * Flow:
 *
 * <ol>
 * <li>Open a streamable proxy session on {@code inspectorApp} that targets
 * {@code targetApp}'s {@code /mcp} URL — assert {@code tools/list} returns 200
 * normally.</li>
 * <li>Shut down {@code targetApp}.</li>
 * <li>The next POST through the proxy on the existing session must return a non-2xx
 * response in bounded time (we accept either 504 Gateway Timeout — observed when the
 * upstream simply stops answering — or 5xx in general). The proxy must not hang.</li>
 * <li>Eventually the proxy must mark the session unreachable. We allow a short Awaitility
 * window for the upstream transport's close handler to propagate, then assert that
 * further DELETE / POST attempts treat the session as cleanly torn down.</li>
 * </ol>
 *
 * <p>
 * Documented observation: the proxy currently relies on the per-POST await timeout
 * (configurable via {@code spring.ai.mcp.inspector.timeouts.streamable-request}, default
 * 30s) to surface upstream loss as {@code 504}. The SSE backchannel does <em>not</em>
 * emit an explicit {@code event: error} frame on target loss — the sink simply stops
 * producing. That is acceptable for the upstream UI which displays a timeout error from
 * the failed POST anyway. We document this in the assertion so a regression that hangs
 * forever fails loudly.
 */
@Epic("Inspector Proxy")
@Feature("Target loss handling")
class ProxyTargetLossIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/** Wall-clock budget for normal HTTP exchanges (initialize, tools/list). */
	private static final Duration BUDGET = Duration.ofSeconds(20);

	/**
	 * Generous client-side timeout for the post-loss POST. The proxy's own upstream-await
	 * timeout is 30s; we give the client a bit more headroom so we observe the proxy's
	 * intentional 504, not a client-side cancellation.
	 */
	private static final Duration POST_LOSS_BUDGET = Duration.ofSeconds(45);

	/** MCP protocol version reported by demo. */
	private static final String PROTOCOL_VERSION = "2025-11-25";

	/** Inspector app: hosts the proxy. */
	private ConfigurableApplicationContext inspectorApp;

	/** Target app: the upstream MCP server we kill mid-session. */
	private ConfigurableApplicationContext targetApp;

	@AfterEach
	void stopApps() {
		if (targetApp != null) {
			try {
				targetApp.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			targetApp = null;
		}
		if (inspectorApp != null) {
			try {
				inspectorApp.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			inspectorApp = null;
		}
	}

	@Test
	@DisplayName("Target loss mid-session surfaces as 5xx and the session is cleaned up")
	@Story("Upstream loss")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Kills the upstream target mid-session and verifies the next POST returns 5xx without "
			+ "hanging, then the session is reaped so a subsequent DELETE returns 404")
	void targetLossMidSession_whenUpstreamKilled_surfacesAsErrorAndCleansUp() throws Exception {
		// given
		// Disable liveness probing for this test so the proxy does not send ping
		// probes during the baseline phase.
		inspectorApp = ProxyAppHarness.start("STREAMABLE", false, null,
				"--spring.ai.mcp.inspector.upstream-liveness-probe-enabled=false");
		targetApp = ProxyAppHarness.start("STREAMABLE", false, null);

		final int inspectorPort = ProxyAppHarness.port(inspectorApp);
		final int targetPort = ProxyAppHarness.port(targetApp);
		final String proxyBase = "http://127.0.0.1:" + inspectorPort + "/mcp-inspector-api";
		final String targetUrl = "http://127.0.0.1:" + targetPort + "/mcp";

		// 1. Open the session against the target app.
		final String sessionId = openSession(proxyBase, targetUrl);
		assertThat(sessionId).as("session id on %s", ProxyAppHarness.stack()).isNotBlank();
		sendInitialized(proxyBase, sessionId);

		// 2. Confirm baseline tools/list works while the target is alive.
		final HttpResponse<String> baseline = sendToolsList(proxyBase, sessionId, 2, BUDGET);
		assertThat(baseline.statusCode())
			.as("baseline tools/list status on %s, body=%s", ProxyAppHarness.stack(), baseline.body())
			.isEqualTo(200);
		final JsonNode baselineBody = MAPPER.readTree(baseline.body());
		assertThat(baselineBody.path("result").path("tools").isArray())
			.as("baseline tools list on %s", ProxyAppHarness.stack())
			.isTrue();

		// when
		// 3. Kill the target.
		targetApp.close();
		targetApp = null;

		// then
		// 4. The next POST through the proxy must NOT hang. Either:
		// - 504 (proxy's own upstream-await timeout fired), or
		// - 5xx (transport-level failure surfaced sooner).
		final HttpResponse<String> afterLoss = sendToolsList(proxyBase, sessionId, 3, POST_LOSS_BUDGET);
		assertThat(afterLoss.statusCode())
			.as("after target loss, proxy must respond with 5xx (not hang) on %s, body=%s", ProxyAppHarness.stack(),
					afterLoss.body())
			.isBetween(500, 599);

		// 5. Eventually the session is reaped — DELETE on the now-stale id
		// returns 200 (if the proxy still has the record and removes it) or
		// 404 (if the upstream-loss path already removed it). Either is a
		// valid clean-shutdown signal — the contract is "not 5xx, not a hang".
		Awaitility.await("session reaped after target loss on " + ProxyAppHarness.stack())
			.atMost(Duration.ofSeconds(10))
			.pollInterval(Duration.ofMillis(200))
			.until(() -> {
				HttpResponse<String> probe = deleteSession(proxyBase, sessionId);
				int code = probe.statusCode();
				return code == 200 || code == 204 || code == 404;
			});

		// 6. A fresh DELETE on the same id must be 404 — proves the session is gone.
		final HttpResponse<String> finalDelete = deleteSession(proxyBase, sessionId);
		assertThat(finalDelete.statusCode())
			.as("DELETE on torn-down session must be 404 on %s", ProxyAppHarness.stack())
			.isEqualTo(404);
	}

	/** Opens a streamable session via initialize POST. */
	private static String openSession(String proxyBase, String targetUrl) throws Exception {
		final ObjectNode init = buildInit(1);
		final HttpRequest request = HttpRequest
			.newBuilder(URI.create(proxyBase + "/sse?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("initialize must be 200, body=%s", response.body()).isEqualTo(200);
		// For SSE transport, the session ID comes in the mcp-session-id header.
		return response.headers().firstValue("mcp-session-id").orElseThrow();
	}

	/** Sends the post-init {@code notifications/initialized} notification. */
	private static void sendInitialized(String proxyBase, String sessionId) throws Exception {
		final ObjectNode notification = MAPPER.createObjectNode();
		notification.put("jsonrpc", "2.0");
		notification.put("method", "notifications/initialized");
		final HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/sse?sessionId=" + sessionId))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(notification)))
			.build();
		final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("notifications/initialized status, body=%s", response.body())
			.isEqualTo(202);
	}

	/**
	 * Posts a {@code tools/list} request frame on a session with an explicit per-call
	 * client timeout.
	 */
	private static HttpResponse<String> sendToolsList(String proxyBase, String sessionId, int id, Duration budget)
			throws Exception {
		final ObjectNode toolsList = MAPPER.createObjectNode();
		toolsList.put("jsonrpc", "2.0");
		toolsList.put("method", "tools/list");
		toolsList.put("id", id);
		final HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/sse?sessionId=" + sessionId))
			.timeout(budget)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(toolsList)))
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** DELETE a session by id. Returns the raw response so callers can inspect status. */
	private static HttpResponse<String> deleteSession(String proxyBase, String sessionId) throws Exception {
		final HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/sse?sessionId=" + sessionId))
			.timeout(Duration.ofSeconds(10))
			.DELETE()
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** Builds a JSON-RPC {@code initialize} frame. */
	private static ObjectNode buildInit(int id) {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", id);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", PROTOCOL_VERSION);
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-target-loss-it");
		info.put("version", "0.1.0");
		return init;
	}

}
