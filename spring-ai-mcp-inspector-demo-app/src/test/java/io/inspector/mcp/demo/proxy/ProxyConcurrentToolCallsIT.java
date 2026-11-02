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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.json.JsonMapper;
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
 * Stress: concurrent {@code tools/call} requests through the inspector proxy itself
 * ({@code POST /mcp-inspector-api/mcp}) on ONE proxy session must complete in parallel.
 *
 * <p>
 * Unlike {@code ConcurrentToolCallsIT} (which connects straight to the demo server's
 * {@code /mcp} endpoint), this IT drives the full browser path: JSON-RPC frames enter
 * through the proxy HTTP endpoint, are relayed by {@code McpProxy} to the target, and the
 * per-request awaiter matches the response by JSON-RPC id. It therefore proves the claim
 * the concurrency-probe spec (issue #237) rests on: the proxy session does not serialize
 * concurrent calls.
 *
 * <p>
 * Ceiling: 10 concurrent {@code slowEcho} calls (server sleeps 2s per call) must finish
 * in {@code ≤ 8s}. The serial baseline would be 20s; the slack absorbs session setup and
 * fixed relay overhead while still failing loudly on a serialized session.
 */
@Epic("Inspector Proxy")
@Feature("Concurrent tool calls through the proxy")
class ProxyConcurrentToolCallsIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/** Per-POST HTTP ceiling: above the proxy's 30s streamable-request timeout. */
	private static final Duration POST_BUDGET = Duration.ofSeconds(35);

	/**
	 * Parallel ceiling for 10 × 2s {@code slowEcho} calls through the proxy. Sequential
	 * baseline would be 20s. Measured: webmvc 2.2s (4-core runner) / 2.0s (CI); webflux
	 * 8.2s (4-core runner) / 10.1s (2-core CI), of which ~6.3s is the demo server itself
	 * executing the same 10 direct /mcp calls without the proxy, i.e. the webflux tool
	 * pipeline is not fully parallel before the proxy is even involved. The 14s ceiling
	 * keeps ~39% headroom above the slowest measured run while staying 30% under the 20s
	 * serial baseline, so a serializing proxy still fails loudly.
	 */
	private static final Duration TEN_SLOW_ECHO_BUDGET = Duration.ofSeconds(14);

	private static final int CALLS = 10;

	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (this.app != null) {
			try {
				this.app.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			this.app = null;
		}
	}

	/**
	 * Opens one proxy session (initialize + notifications/initialized), then fires 10
	 * concurrent {@code slowEcho} {@code tools/call} POSTs on that single session and
	 * asserts the total wall time stays far below the 20s sequential baseline.
	 */
	@Test
	@DisplayName("10 concurrent slowEcho calls through /mcp-inspector-api/mcp on one session run in parallel")
	@Story("Parallel execution through the proxy")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Opens one proxy session, sends 10 concurrent 2s slowEcho tools/call POSTs through "
			+ "/mcp-inspector-api/mcp and asserts wall time <= 14s, well below the 20s sequential baseline, "
			+ "on both stacks")
	void tenConcurrentSlowEchoes_throughProxy_onSameSession_runInParallel() throws Exception {
		// given
		this.app = ProxyAppHarness.start("STREAMABLE", false, null);
		int port = ProxyAppHarness.port(this.app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyMcp = "http://127.0.0.1:" + port + "/mcp-inspector-api/mcp";
		String sessionId = openProxySession(proxyMcp, targetUrl);

		ExecutorService pool = Executors.newFixedThreadPool(CALLS);
		try {
			List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>(CALLS);

			// when
			Instant t0 = Instant.now();
			final Instant[] sentAt = new Instant[CALLS];
			final Instant[] doneAt = new Instant[CALLS];
			for (int i = 0; i < CALLS; i++) {
				final int idx = i;
				futures.add(CompletableFuture.supplyAsync(() -> {
					sentAt[idx] = Instant.now();
					try {
						return callSlowEcho(proxyMcp, sessionId, idx);
					}
					finally {
						doneAt[idx] = Instant.now();
					}
				}, pool));
			}
			for (int i = 0; i < CALLS; i++) {
				// Bounded wait: a serialized session would still answer (20s <
				// POST_BUDGET),
				// so the wall-time assertion below is what fails, with a clear message.
				futures.get(i).get(POST_BUDGET.plusSeconds(5).toSeconds(), TimeUnit.SECONDS);
			}
			Duration elapsed = Duration.between(t0, Instant.now());
			// Per-call latencies land in the failsafe report's <system-err>: evidence
			// for "parallel, not serialized" beyond the single wall-time number.
			for (int i = 0; i < CALLS; i++) {
				System.err.printf("proxy-concurrency %s slowEcho[%d] latency %d ms%n", ProxyAppHarness.stack(), i,
						Duration.between(sentAt[i], doneAt[i]).toMillis());
			}
			System.err.printf("proxy-concurrency %s total wall %d ms%n", ProxyAppHarness.stack(), elapsed.toMillis());

			// then
			for (int i = 0; i < CALLS; i++) {
				HttpResponse<String> response = futures.get(i).join();
				assertThat(response.statusCode())
					.as("slowEcho[%d] HTTP status on %s, body=%s", i, ProxyAppHarness.stack(), response.body())
					.isEqualTo(200);
				String text = MAPPER.readTree(response.body())
					.path("result")
					.path("content")
					.path(0)
					.path("text")
					.asText();
				assertThat(text).as("slowEcho[%d] echoed its text on %s", i, ProxyAppHarness.stack())
					.isEqualTo("echo-" + i);
			}
			assertThat(elapsed)
				.as("10 x slowEcho through the proxy must finish in <14s on %s - sequential 20s baseline "
						+ "would prove the proxy serializes one session", ProxyAppHarness.stack())
				.isLessThanOrEqualTo(TEN_SLOW_ECHO_BUDGET);
		}
		finally {
			pool.shutdownNow();
			pool.awaitTermination(2, TimeUnit.SECONDS);
		}
	}

	/**
	 * Initializes a proxy session: POST {@code ?url=<target>} with an {@code initialize}
	 * frame, then {@code notifications/initialized} (202).
	 * @param proxyMcp absolute proxy MCP endpoint URL
	 * @param targetUrl absolute demo MCP server URL to proxy to
	 * @return the {@code mcp-session-id} issued by the proxy
	 */
	private static String openProxySession(final String proxyMcp, final String targetUrl) throws Exception {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-concurrency-it");
		info.put("version", "0.1.0");

		final HttpRequest initRequest = HttpRequest
			.newBuilder(URI.create(proxyMcp + "?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
			.timeout(POST_BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		final HttpResponse<String> initResponse = HTTP.send(initRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(initResponse.statusCode())
			.as("initialize through proxy on %s, body=%s", ProxyAppHarness.stack(), initResponse.body())
			.isEqualTo(200);
		final String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse(null);
		assertThat(sessionId).as("mcp-session-id round-trip on %s", ProxyAppHarness.stack()).isNotBlank();

		final ObjectNode initializedNotification = MAPPER.createObjectNode();
		initializedNotification.put("jsonrpc", "2.0");
		initializedNotification.put("method", "notifications/initialized");
		final HttpRequest initializedRequest = HttpRequest.newBuilder(URI.create(proxyMcp))
			.timeout(POST_BUDGET)
			.header("Content-Type", "application/json")
			.header("mcp-session-id", sessionId)
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(initializedNotification)))
			.build();
		final HttpResponse<String> initializedResponse = HTTP.send(initializedRequest,
				HttpResponse.BodyHandlers.ofString());
		assertThat(initializedResponse.statusCode())
			.as("notifications/initialized must be 202 on %s, body=%s", ProxyAppHarness.stack(),
					initializedResponse.body())
			.isEqualTo(202);
		return sessionId;
	}

	/**
	 * Sends one {@code tools/call slowEcho} frame through the proxy on the given session.
	 * @param proxyMcp absolute proxy MCP endpoint URL
	 * @param sessionId the live proxy session id
	 * @param idx call index, used for the JSON-RPC id and the echoed text
	 * @return the completed HTTP response
	 */
	private static HttpResponse<String> callSlowEcho(final String proxyMcp, final String sessionId, final int idx) {
		try {
			final ObjectNode call = MAPPER.createObjectNode();
			call.put("jsonrpc", "2.0");
			call.put("method", "tools/call");
			call.put("id", 1000 + idx);
			final ObjectNode callParams = call.putObject("params");
			callParams.put("name", "slowEcho");
			callParams.putObject("arguments").put("text", "echo-" + idx);

			final HttpRequest request = HttpRequest.newBuilder(URI.create(proxyMcp))
				.timeout(POST_BUDGET)
				.header("Content-Type", "application/json")
				.header("mcp-session-id", sessionId)
				.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(call)))
				.build();
			return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (final Exception ex) {
			// CompletableFuture.supplyAsync swallows checked exceptions into a completed
			// exceptionally-future; rethrowing keeps the failure message in the test
			// report.
			throw new RuntimeException("slowEcho[" + idx + "] POST failed: " + ex, ex);
		}
	}

}
