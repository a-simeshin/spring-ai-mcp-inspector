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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ConfigurableApplicationContext;

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

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/**
	 * Per-request wall-clock budget for a single streamable {@code initialize}.
	 *
	 * <p>
	 * Deliberately generous: this IT is a <em>registry leak guard</em>, not a latency
	 * SLA. On a contended two-core CI runner (a full Spring Boot app plus JaCoCo
	 * instrumentation) a single one of the 100 sequential proxied handshakes can stall
	 * for tens of seconds before the scheduler/GC clears. The happy path is tens of
	 * milliseconds, so a wide per-request budget never slows a green run — it only stops
	 * a transient stall from failing the leak assertion.
	 *
	 * <p>
	 * The cost of that width is bounded by the method-level {@code @Timeout}: 100 cycles
	 * x (60 s + 20 s) would otherwise let a wedged proxy burn over two hours per stack.
	 */
	private static final Duration BUDGET = Duration.ofSeconds(60);

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

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	// Whole-test cap. The per-request BUDGET is intentionally wide, so without this a
	// wedged proxy would fail only after 100 x 80 s per stack. Green runs finish in
	// well under a minute; 10 min is pure headroom.
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("100 open/close cycles drain the registry back to its initial size")
	@Story("Registry leak guard")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Opens and closes 100 streamable sessions sequentially and asserts the "
			+ "ProxySessionRegistry drains back to its pre-loop size with 100 distinct session ids")
	void hundredSessionOpenCloseCycles_whenAllDeleted_doNotLeak(ProxyAppHarness.Stack stack) throws Exception {
		// given
		app = ProxyAppHarness.start(stack, "STREAMABLE", false, null);
		final int port = ProxyAppHarness.port(app);
		final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		final ProxySessionRegistry registry = app.getBean(ProxySessionRegistry.class);
		final int initialSize = registry.size();

		final Set<String> seenSessionIds = new HashSet<>(CYCLES);

		// when
		for (int i = 0; i < CYCLES; i++) {
			final String sessionId = openSession(proxyBase, targetUrl);
			assertThat(sessionId).as("cycle %d on %s: session id must be present", i, stack).isNotBlank();
			assertThat(seenSessionIds.add(sessionId))
				.as("cycle %d on %s: session id %s must be unique", i, stack, sessionId)
				.isTrue();

			final HttpResponse<String> deleteResponse = deleteSession(proxyBase, sessionId);
			assertThat(deleteResponse.statusCode())
				.as("cycle %d on %s: DELETE status, body=%s", i, stack, deleteResponse.body())
				.isIn(200, 204);
		}

		// then
		assertThat(seenSessionIds).as("100 cycles must produce 100 distinct session ids on %s", stack).hasSize(CYCLES);

		assertThat(registry.size())
			.as("registry must drain back to its pre-loop size on %s " + "(initial=%d, after %d open+close cycles)",
					stack, initialSize, CYCLES)
			.isEqualTo(initialSize);

		// Sanity: a DELETE on a never-issued id must be 404 — proves the
		// registry actually consults its map and does not silently 200.
		final HttpResponse<String> unknownDelete = deleteSession(proxyBase, "definitely-not-a-real-session");
		assertThat(unknownDelete.statusCode()).as("DELETE on unknown session id must be 404 on %s", stack)
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
		final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("initialize must be 200, body=%s", response.body()).isEqualTo(200);
		return response.headers().firstValue("mcp-session-id").orElseThrow();
	}

	/** DELETEs a session by id. */
	private static HttpResponse<String> deleteSession(String proxyBase, String sessionId) throws Exception {
		final HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
			.timeout(Duration.ofSeconds(20))
			.header("mcp-session-id", sessionId)
			.DELETE()
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
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
