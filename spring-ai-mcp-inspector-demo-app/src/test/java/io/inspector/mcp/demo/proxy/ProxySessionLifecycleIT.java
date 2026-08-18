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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.proxy.ProxySessionRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Open-then-close 100 streamable proxy sessions and verify that
 * {@link ProxySessionRegistry} has no leaked entries afterwards.
 *
 * <p>
 * Why this matters: every open session pins a {@code ProxySession} plus the upstream
 * {@code McpClientTransport} and two Reactor sinks. A leak here means the inspector
 * accumulates dead state across long-lived deployments — exactly the kind of slow burn
 * that production never catches in time.
 *
 * <p>
 * Implementation notes:
 *
 * <ul>
 * <li>We grab the live {@link ProxySessionRegistry} bean directly from the running
 * {@link ConfigurableApplicationContext} — the registry exposes a
 * {@code public int size()} method already (added by the original author for "tests /
 * metrics"), so no test-only accessor was needed.</li>
 * <li>Sessions are opened sequentially: each {@code initialize} POST gets a fresh
 * {@code mcp-session-id}; we DELETE it immediately. After the loop we assert
 * {@code registry.size() == 0}.</li>
 * <li>We also collect every issued session id and assert all 100 are distinct — proves
 * the id generator does not collide and is not reused across the same registry
 * instance.</li>
 * </ul>
 */
@Epic("Inspector Proxy")
@Feature("Session lifecycle")
class ProxySessionLifecycleIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(10));

	/**
	 * Per-request wall-clock budget for a single streamable {@code initialize}.
	 *
	 * <p>
	 * Generous, because this IT is a <em>registry leak guard</em> and not a latency SLA:
	 * the happy path is tens of milliseconds, so the budget only ever bites on a stall.
	 * It no longer has to absorb the pooled-connection stall described on
	 * {@link #send(HttpRequest)} — that one is retried rather than waited out — so it is
	 * back to a length that keeps a genuine hang from costing a whole CI minute.
	 */
	private static final Duration BUDGET = Duration.ofSeconds(20);

	/**
	 * Requests that {@link #send(HttpRequest)} had to replay. Each one may have left a
	 * session behind on the server; see the drain assertion.
	 */
	private static final AtomicInteger REPLAYED = new AtomicInteger();

	/** Number of session create+close cycles per test run. */
	private static final int CYCLES = 100;

	/** MCP protocol version reported by demo. */
	private static final String PROTOCOL_VERSION = "2025-11-25";

	/** Demo app under test. */
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
	@DisplayName("100 open/close cycles drain the registry back to its initial size")
	@Story("Registry leak guard")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Opens and closes 100 streamable sessions sequentially and asserts the "
			+ "ProxySessionRegistry drains back to its pre-loop size with 100 distinct session ids")
	void hundredSessionOpenCloseCycles_whenAllDeleted_doNotLeak() throws Exception {
		// given
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		final int port = ProxyAppHarness.port(app);
		final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		final ProxySessionRegistry registry = app.getBean(ProxySessionRegistry.class);
		final int initialSize = registry.size();

		final Set<String> seenSessionIds = new HashSet<>(CYCLES);
		REPLAYED.set(0);

		// when
		for (int i = 0; i < CYCLES; i++) {
			final String sessionId = openSession(proxyBase, targetUrl);
			assertThat(sessionId).as("cycle %d on %s: session id must be present", i, ProxyAppHarness.stack())
				.isNotBlank();
			assertThat(seenSessionIds.add(sessionId))
				.as("cycle %d on %s: session id %s must be unique", i, ProxyAppHarness.stack(), sessionId)
				.isTrue();

			final HttpResponse<String> deleteResponse = deleteSession(proxyBase, sessionId);
			assertThat(deleteResponse.statusCode())
				.as("cycle %d on %s: DELETE status, body=%s", i, ProxyAppHarness.stack(), deleteResponse.body())
				.isIn(200, 204);
		}

		// then
		assertThat(seenSessionIds).as("100 cycles must produce 100 distinct session ids on %s", ProxyAppHarness.stack())
			.hasSize(CYCLES);

		// A replayed initialize is allowed to cost one undrained session: a request that
		// timed out is not necessarily lost, and when it reaches the server late it opens
		// a session whose id no client ever learned and so nobody DELETEs. The reaper
		// collects that one on its own schedule, long after this loop is done. With no
		// replay — the normal run — this is the strict "drains back to zero" assertion.
		assertThat(registry.size())
			.as("registry must drain back to its pre-loop size on %s "
					+ "(initial=%d, after %d open+close cycles, %d replayed)", ProxyAppHarness.stack(), initialSize,
					CYCLES, REPLAYED.get())
			.isBetween(initialSize, initialSize + REPLAYED.get());

		// Sanity: a DELETE on a never-issued id must be 404 — proves the
		// registry actually consults its map and does not silently 200.
		final HttpResponse<String> unknownDelete = deleteSession(proxyBase, "definitely-not-a-real-session");
		assertThat(unknownDelete.statusCode())
			.as("DELETE on unknown session id must be 404 on %s", ProxyAppHarness.stack())
			.isEqualTo(404);
	}

	/**
	 * Opens a streamable session by sending the initialize POST. Returns its
	 * {@code mcp-session-id}.
	 */
	private static String openSession(String proxyBase, String targetUrl) throws Exception {
		final ObjectNode init = buildInit();
		final HttpRequest request = HttpRequest
			.newBuilder(URI.create(proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		final HttpResponse<String> response = send(request);
		assertThat(response.statusCode()).as("initialize must be 200, body=%s", response.body()).isEqualTo(200);
		return response.headers().firstValue("mcp-session-id").orElseThrow();
	}

	/** DELETEs a session by id. */
	private static HttpResponse<String> deleteSession(String proxyBase, String sessionId) throws Exception {
		final HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(BUDGET)
			.header("mcp-session-id", sessionId)
			.DELETE()
			.build();
		return send(request);
	}

	/**
	 * Sends one request on the shared client and, if it times out, replays it once on a
	 * client of its own.
	 *
	 * <p>
	 * The retry is not a green-washing retry. {@link HttpClient} pools connections per
	 * origin, and a request can be handed a pooled connection that is not ready to carry
	 * it, where it then queues instead of going out. Because neither {@code POST} nor
	 * {@code DELETE} is idempotent, the JDK will not re-drive it onto another connection
	 * ({@code jdk.httpclient.enableAllMethodRetry} is off by default) and simply waits
	 * out the request timeout. This loop drives 200 sequential requests through one
	 * client, so it hits that window regularly on CI and effectively never on a
	 * workstation.
	 *
	 * <p>
	 * Measured, on the reactive stack on GitHub's runners: at the moment the shared
	 * client was 60s into a stall, a brand-new client answered the identical
	 * {@code initialize} in 8ms, 15ms and 17ms across three runs, with the server's event
	 * loops idle and no request ever reaching a handler. So the server is not slow and
	 * the connection is not dead — the request is stuck behind a busy one and goes out
	 * late, by up to a minute, which is why a session can still appear for a request the
	 * client has already given up on. Stalls landed on cycles 3, 17 and 81: a race, not a
	 * threshold.
	 *
	 * <p>
	 * A genuine hang still fails the test, because both attempts then time out. The retry
	 * client is deliberately not cached: on Java 17 an {@link HttpClient} cannot be
	 * closed and costs a selector thread until it is collected, which is affordable for
	 * the rare retry and would not be for one per cycle.
	 * @param request the request to send
	 * @return the response, from whichever attempt succeeded
	 * @throws Exception if both attempts fail
	 */
	private static HttpResponse<String> send(final HttpRequest request) throws Exception {
		try {
			return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (final HttpTimeoutException ex) {
			REPLAYED.incrementAndGet();
			final HttpClient fresh = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			return fresh.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

	/** Builds a JSON-RPC {@code initialize} frame with a fixed id of 1. */
	private static ObjectNode buildInit() {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", PROTOCOL_VERSION);
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-session-lifecycle-it");
		info.put("version", "0.1.0");
		return init;
	}

}
