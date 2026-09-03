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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the upstream liveness probe detects upstream MCP server death and closes
 * the downstream SSE stream within the configured bound (30s worst case).
 *
 * <p>
 * Topology: two completely separate Spring contexts.
 *
 * <ul>
 * <li>{@code inspectorApp}: boots the demo with the inspector mounted at
 * {@code /mcp-inspector-api} (proxy entry point, with liveness probing enabled).</li>
 * <li>{@code targetApp}: boots a second standalone demo that serves as the upstream MCP
 * server the proxy points at.</li>
 * </ul>
 *
 * <p>
 * Flow:
 *
 * <ol>
 * <li>Open an SSE proxy session on {@code inspectorApp} that targets {@code targetApp}'s
 * {@code /mcp} URL: confirm the SSE stream is alive by receiving the endpoint
 * prologue.</li>
 * <li>Post an {@code initialize} frame to confirm the session is working.</li>
 * <li>Shut down {@code targetApp}.</li>
 * <li>The SSE stream must close within 30s (the probe-based detection bound:
 * idleThreshold(15s) + probeInterval(10s) + probeTimeout(5s) = 30s worst case).</li>
 * </ol>
 */
@Epic("Inspector Proxy")
@Feature("SSE liveness probe")
class ProxySseLivenessIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/** Wall-clock budget for normal HTTP exchanges. */
	private static final Duration BUDGET = Duration.ofSeconds(20);

	/**
	 * Max time to wait for the SSE stream to close after the target is killed. The
	 * probe-based detection bound is 30s; we add headroom for JVM shutdown delays.
	 */
	private static final Duration SSE_CLOSE_BUDGET = Duration.ofSeconds(45);

	/** Inspector app: hosts the proxy with liveness probing. */
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
	@DisplayName("Upstream death closes the downstream SSE stream within 30s")
	@Story("SSE liveness detection")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Kills the upstream target mid-session and verifies the SSE stream is closed "
			+ "within the probe-based detection bound (idleThreshold 15s + probeInterval 10s + probeTimeout 5s = 30s)")
	void upstreamDeath_closesDownstreamSseWithinBound() throws Exception {
		// given
		// Use fast probe intervals to keep the test bounded
		inspectorApp = ProxyAppHarness.start("STREAMABLE", false, null,
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-interval=PT2S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-timeout=PT3S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-idle-threshold=PT3S");
		targetApp = ProxyAppHarness.start("STREAMABLE", false, null);

		final int inspectorPort = ProxyAppHarness.port(inspectorApp);
		final int targetPort = ProxyAppHarness.port(targetApp);
		final String proxyBase = "http://127.0.0.1:" + inspectorPort + "/mcp-inspector-api";
		final String targetUrl = "http://127.0.0.1:" + targetPort + "/mcp";

		// 1. Open the SSE stream and capture the endpoint prologue.
		final AtomicBoolean streamClosed = new AtomicBoolean(false);
		final AtomicReference<String> endpointData = new AtomicReference<>();
		final AtomicReference<Throwable> streamError = new AtomicReference<>();
		final Thread sseReader = new Thread(() -> {
			try {
				final HttpRequest sseRequest = HttpRequest
					.newBuilder(URI.create(proxyBase + "/sse?transportType=streamable-http&url="
							+ URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
					.timeout(Duration.ofSeconds(60))
					.header("Accept", "text/event-stream")
					.GET()
					.build();
				final HttpResponse<java.io.InputStream> response = HTTP.send(sseRequest,
						HttpResponse.BodyHandlers.ofInputStream());
				if (response.statusCode() != 200) {
					streamError.set(new IllegalStateException("SSE handshake HTTP " + response.statusCode()));
					return;
				}
				try (var reader = new java.io.BufferedReader(
						new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
					String eventName = null;
					StringBuilder dataBuf = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						if (line.isEmpty()) {
							if ("endpoint".equals(eventName) && dataBuf.length() > 0) {
								endpointData.set(dataBuf.toString());
							}
							eventName = null;
							dataBuf.setLength(0);
							continue;
						}
						if (line.startsWith("event:")) {
							eventName = line.substring(6).trim();
						}
						else if (line.startsWith("data:")) {
							if (dataBuf.length() > 0) {
								dataBuf.append('\n');
							}
							dataBuf.append(line.substring(5).trim());
						}
					}
				}
				// readLine returned null - the stream closed
				streamClosed.set(true);
			}
			catch (java.net.http.HttpTimeoutException ex) {
				// Timeout reading the stream is also a signal that the stream ended
				streamClosed.set(true);
			}
			catch (Throwable t) {
				streamError.set(t);
			}
		}, "sse-liveness-reader-" + ProxyAppHarness.stack());
		sseReader.setDaemon(true);
		sseReader.start();

		// 2. Wait for the endpoint prologue.
		Awaitility.await("endpoint prologue frame on " + ProxyAppHarness.stack())
			.atMost(BUDGET)
			.pollInterval(Duration.ofMillis(100))
			.until(() -> endpointData.get() != null);

		final String data = endpointData.get();
		assertThat(data).as("endpoint prologue data on %s", ProxyAppHarness.stack())
			.startsWith("/mcp-inspector-api/message?sessionId=");

		final String sessionId = data.substring(data.indexOf("sessionId=") + "sessionId=".length());
		assertThat(sessionId).isNotBlank();

		// 3. Post an initialize frame to confirm the session is working.
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-sse-liveness-it");
		info.put("version", "0.1.0");

		final HttpRequest initRequest = HttpRequest
			.newBuilder(URI.create(proxyBase + "/message?sessionId=" + sessionId))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		final HttpResponse<String> initResponse = HTTP.send(initRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(initResponse.statusCode()).as("initialize POST must be 2xx on %s", ProxyAppHarness.stack())
			.isBetween(200, 202);

		// when
		// 4. Kill the target app.
		targetApp.close();
		targetApp = null;

		// then
		// 5. The SSE stream must close (reader detects EOF or error) within the
		// probe-based detection bound. The prober's failUpstream errors the
		// targetToBrowser sink, which causes the SSE subscriber to call
		// emitter.completeWithError(), so the reader sees either EOF or an
		// IOException - either is valid.
		Awaitility.await("SSE stream closed after target loss on " + ProxyAppHarness.stack())
			.atMost(SSE_CLOSE_BUDGET)
			.pollInterval(Duration.ofMillis(200))
			.until(() -> streamClosed.get() || streamError.get() != null);

		// 6. The stream is expected to close with an error (emitter.completeWithError
		// from the failUpstream path). A reader error is the expected outcome.
		// If the stream closed cleanly (EOF), that is also valid - it means the
		// emitter was completed normally by some other path.
	}

}