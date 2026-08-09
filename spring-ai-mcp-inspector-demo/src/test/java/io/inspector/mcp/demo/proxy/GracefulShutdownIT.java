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

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ConfigurableApplicationContext;

import io.inspector.mcp.core.proxy.ProxySessionRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot's graceful shutdown blocks context close until every in-flight request completes
 * or {@code spring.lifecycle.timeout-per-shutdown-phase} expires. The inspector holds two
 * kinds of long-lived request open, and neither used to be released on shutdown:
 *
 * <ol>
 * <li>a browser-facing proxy SSE stream ({@link ProxySessionRegistry}), and</li>
 * <li>an inspector UI session whose loopback MCP client keeps an inbound request against
 * this very JVM alive.</li>
 * </ol>
 *
 * <p>
 * Each test opens one of them and then times {@code context.close()}. The apps are booted
 * with an explicit 15s shutdown phase (short enough to keep a failure quick, long enough
 * to be unmistakable) and the close must finish inside 10s. The gap is deliberately wide:
 * a healthy close is milliseconds, but Tomcat's own worker-executor drain on stop is
 * capped at 5s and does occasionally cost that much — which is a different wait from the
 * one under test. Anything at or above the shutdown phase means the graceful timeout was
 * paid, i.e. the streams were never released.
 *
 * <p>
 * Four scenarios, each on both stacks. Two of them exist because the first two were
 * passing for a reason nobody had checked: spring-ai's transport provider releases the
 * server-side session only during bean destruction, long after the graceful wait, and the
 * only thing that saved us was that {@code McpSyncClient.closeGracefully()} happens to
 * issue a loopback {@code DELETE /mcp} which makes the server tear its own session down.
 * That is a client-side round trip to a server already in the middle of shutting down —
 * far too load-bearing to leave untested. The last two scenarios take it away:
 *
 * <ul>
 * <li>{@code disallow-delete=true} — a supported spring-ai setting; the server answers
 * 405 and the session survives the client's close.</li>
 * <li>{@code protocol=SSE} — no {@code DELETE} exists in that protocol at all.</li>
 * </ul>
 *
 * <p>
 * Both stall for the full graceful phase without {@code McpServerTransportDrain} and pass
 * in milliseconds with it, on both stacks. Note this is not a webflux-only hole: webmvc
 * fails these two just as hard.
 */
@Epic("Inspector Proxy")
@Feature("Graceful shutdown")
class GracefulShutdownIT {

	/** Pinned to HTTP/1.1 — see {@link ProxyAppHarness#httpClient(Duration)}. */
	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/**
	 * Command-line args beat the failsafe system properties, so these hold regardless of
	 * what the surrounding build configures.
	 */
	private static final String[] GRACEFUL = { "--server.shutdown=graceful",
			"--spring.lifecycle.timeout-per-shutdown-phase=15s" };

	/** Above Tomcat's 5s executor drain, well below the 15s graceful budget. */
	private static final long CLOSE_BUDGET_MS = 10_000L;

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
	@DisplayName("an open proxy SSE stream does not stall context close")
	@Story("Proxy session teardown")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Opens a proxy SSE session against the host's own /mcp endpoint, then closes the context "
			+ "and asserts the close finished well inside the graceful-shutdown budget and the session "
			+ "registry was drained")
	void openProxySession_whenContextCloses_doesNotWaitForGracefulTimeout(ProxyAppHarness.Stack stack)
			throws Exception {
		// given
		app = ProxyAppHarness.start(stack, "STREAMABLE", false, null, GRACEFUL);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String sseUrl = "http://127.0.0.1:" + port + "/mcp-inspector-api/sse?transportType=streamable-http&url="
				+ URLEncoder.encode(targetUrl, StandardCharsets.UTF_8);

		AtomicBoolean prologueSeen = new AtomicBoolean(false);
		AtomicBoolean streamEnded = new AtomicBoolean(false);
		Thread reader = new Thread(() -> {
			try {
				HttpResponse<java.io.InputStream> response = HTTP.send(HttpRequest.newBuilder(URI.create(sseUrl))
					.timeout(Duration.ofSeconds(30))
					.header("Accept", "text/event-stream")
					.GET()
					.build(), HttpResponse.BodyHandlers.ofInputStream());
				try (var in = new java.io.BufferedReader(
						new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = in.readLine()) != null) {
						if (line.startsWith("event:") && line.contains("endpoint")) {
							prologueSeen.set(true);
						}
					}
				}
				streamEnded.set(true);
			}
			catch (Throwable ignored) {
				/* an abrupt drop is a failure mode we assert against below */
			}
		}, "graceful-shutdown-sse-reader-" + stack);
		reader.setDaemon(true);
		reader.start();

		Awaitility.await("proxy SSE prologue")
			.atMost(Duration.ofSeconds(15))
			.pollInterval(Duration.ofMillis(50))
			.untilTrue(prologueSeen);

		ProxySessionRegistry registry = app.getBean(ProxySessionRegistry.class);
		assertThat(registry.size()).as("one live proxy session before close").isOne();

		// when
		ConfigurableApplicationContext closing = app;
		app = null;
		long startedAt = System.nanoTime();
		closing.close();
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		// then
		assertThat(elapsedMs).as("context close on %s took %d ms", stack, elapsedMs).isLessThan(CLOSE_BUDGET_MS);
		assertThat(registry.size()).as("proxy sessions after close").isZero();
		reader.join(Duration.ofSeconds(5).toMillis());
		assertThat(streamEnded).as("browser-facing SSE stream ended cleanly rather than being cut").isTrue();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	@DisplayName("an open inspector UI session does not stall context close")
	@Story("Inspector session teardown")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Connects an inspector UI session — whose loopback MCP client holds an inbound request "
			+ "against this very JVM — then closes the context and asserts the close finished well inside "
			+ "the graceful-shutdown budget")
	void openInspectorSession_whenContextCloses_doesNotWaitForGracefulTimeout(ProxyAppHarness.Stack stack)
			throws Exception {
		// given
		app = ProxyAppHarness.start(stack, "STREAMABLE", false, null, GRACEFUL);
		connectInspectorSession(ProxyAppHarness.port(app));

		// when / then
		assertCloseIsPrompt(stack);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	@DisplayName("an inspector session survives context close promptly even when DELETE is disallowed")
	@Story("Inspector session teardown")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Same as the previous case but with spring.ai.mcp.server.streamable-http.disallow-delete=true, "
			+ "which removes the loopback DELETE /mcp that used to make the server release its own session. "
			+ "Without McpServerTransportDrain this stalls for the whole graceful phase on both stacks")
	void openInspectorSession_whenDeleteDisallowed_doesNotWaitForGracefulTimeout(ProxyAppHarness.Stack stack)
			throws Exception {
		// given
		app = ProxyAppHarness.start(stack, "STREAMABLE", false, null,
				withGraceful("--spring.ai.mcp.server.streamable-http.disallow-delete=true"));
		connectInspectorSession(ProxyAppHarness.port(app));

		// when / then
		assertCloseIsPrompt(stack);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	@DisplayName("an inspector session on the SSE protocol does not stall context close")
	@Story("Inspector session teardown")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Same as the previous case on spring.ai.mcp.server.protocol=SSE, where the MCP protocol has "
			+ "no DELETE at all, so nothing but McpServerTransportDrain can release the server-side session")
	void openInspectorSession_onSseProtocol_doesNotWaitForGracefulTimeout(ProxyAppHarness.Stack stack)
			throws Exception {
		// given
		app = ProxyAppHarness.start(stack, "SSE", false, null, GRACEFUL);
		connectInspectorSession(ProxyAppHarness.port(app));

		// when / then
		assertCloseIsPrompt(stack);
	}

	/** Opens one inspector UI session and asserts the server accepted it. */
	private static void connectInspectorSession(int port) throws Exception {
		HttpResponse<String> connect = HTTP
			.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector/api/connect"))
				.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(connect.statusCode()).as("connect response: %s", connect.body()).isEqualTo(200);
		assertThat(connect.body()).contains("sessionId");
	}

	/**
	 * Closes {@link #app} and asserts the close landed inside {@link #CLOSE_BUDGET_MS}.
	 */
	private void assertCloseIsPrompt(ProxyAppHarness.Stack stack) {
		ConfigurableApplicationContext closing = app;
		app = null;
		long startedAt = System.nanoTime();
		closing.close();
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(elapsedMs).as("context close on %s took %d ms", stack, elapsedMs).isLessThan(CLOSE_BUDGET_MS);
	}

	/** {@link #GRACEFUL} plus extra args, both beating the build's system properties. */
	private static String[] withGraceful(String... extra) {
		String[] args = new String[GRACEFUL.length + extra.length];
		System.arraycopy(GRACEFUL, 0, args, 0, GRACEFUL.length);
		System.arraycopy(extra, 0, args, GRACEFUL.length, extra.length);
		return args;
	}

}
