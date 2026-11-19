/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.ui;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.inspector.mcp.demo.DemoApplication;
import io.inspector.mcp.demo.client.DemoClientHandlers;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Failure-path E2E for the demo app's own outbound MCP client (issue #120), run once per
 * web stack (this class ships in the demo-app test-jar and both stack modules pick it up
 * through Failsafe's {@code dependenciesToScan}). No browser involved.
 *
 * <p>
 * The PR body and the {@link io.inspector.mcp.demo.client.DemoLoopbackClientInitializer}
 * javadoc promise that a failed loopback initialization "never fails the application
 * startup": the catch in the ready-event listener is the only thing standing between a
 * refused loopback connect and a dead demo app (an exception escaping the listener aborts
 * {@code SpringApplication.run} through the ready-event multicaster). This test pins that
 * promise: it boots the demo app with the {@code demo-loopback} client pointed at an
 * unreachable endpoint and asserts the context is up, the
 * {@code /mcp-inspector/api/client-connections} REST snapshot answers, and the registry
 * keeps the {@code FAILED} state with a recorded error.
 */
@Epic("MCP Inspector UI")
@Feature("Outbound client connections (issue #120)")
class DemoLoopbackClientStartupFailureIT {

	/** Wait budget for the asynchronous wait in this class. */
	private static final Duration BUDGET = Duration.ofSeconds(30);

	/** Delay between two REST polls inside the await loop. */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

	/**
	 * Pinned to HTTP/1.1: {@link HttpClient} negotiates HTTP/2 by default and remembers
	 * the outcome per host:port, while the harness boots on recycled ports (see
	 * {@code ProxyAppHarness#httpClient} for the full rationale).
	 */
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(5))
		.build();

	private static final JsonMapper JSON = JsonMapper.builder().build();

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

	@Test
	@DisplayName("initializationFailureKeepsStartupAlive: a failed loopback initialize never aborts the boot")
	@Story("Client connections")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo app with the demo-loopback client pointed at an unreachable endpoint and "
			+ "asserts the application context survives, the client-connections REST snapshot answers, and "
			+ "the registry keeps the demo-loopback connection in the FAILED state with a recorded error.")
	void initializationFailureKeepsStartupAlive() throws Exception {
		// when: the demo app boots with the loopback client aimed at an endpoint
		// nobody listens on: the ready-event listener must swallow the initialize
		// failure (a rethrow here aborts SpringApplication.run) and leave the
		// failure to the inspector's connection registry
		int port = freePort();
		this.app = new SpringApplicationBuilder(DemoApplication.class).run("--server.port=" + port,
				"--spring.ai.mcp.server.protocol=STREAMABLE", "--spring.ai.mcp.inspector.auth-enabled=false",
				"--spring.ai.mcp.client.streamable-http.connections.demo-loopback.url=http://127.0.0.1:1");

		// then: the context is up: run() returning without an exception already
		// proves the ready-event listener swallowed the failure
		assertThat(this.app.isActive()).as("application context after the failed loopback initialize").isTrue();

		// and the REST snapshot reports the demo-loopback failure state
		JsonNode failed = awaitConnectionState(port, "FAILED");
		assertThat(failed.path("lastError").asText(null)).as("lastError of the failed demo-loopback connection")
			.isNotBlank();
	}

	/**
	 * Picks a free port before boot: the REST endpoint needs an explicit port, and
	 * {@code RANDOM_PORT} (0) would also end up inside the default loopback client URL
	 * from {@code application.yml} (overridden here, but kept consistent with
	 * {@code DemoLoopbackClientUiIT}).
	 */
	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new IllegalStateException("no free port available", ex);
		}
	}

	/**
	 * Polls the client-connections REST snapshot until the demo-loopback connection
	 * reports the wanted state. The loop starts from the empty state (no response, or the
	 * connection absent, or not yet in the wanted state) and fails with the last snapshot
	 * on timeout.
	 */
	private static JsonNode awaitConnectionState(int port, String wantedState) throws Exception {
		String url = "http://127.0.0.1:" + port + "/mcp-inspector/api/client-connections";
		long deadline = System.nanoTime() + BUDGET.toNanos();
		String lastSnapshot = "<no response yet>";
		while (System.nanoTime() < deadline) {
			JsonNode snapshot = getJson(url);
			if (snapshot != null) {
				lastSnapshot = snapshot.toString();
				for (JsonNode connection : snapshot) {
					if (DemoClientHandlers.DEMO_CLIENT_NAME.equals(connection.path("name").asText())
							&& wantedState.equals(connection.path("state").asText())) {
						return connection;
					}
				}
			}
			Thread.sleep(POLL_INTERVAL.toMillis());
		}
		fail("the " + DemoClientHandlers.DEMO_CLIENT_NAME + " connection did not reach " + wantedState + " within "
				+ BUDGET + "; last snapshot: " + lastSnapshot);
		return null;
	}

	private static JsonNode getJson(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return null;
			}
			return JSON.readTree(response.body());
		}
		catch (Exception ex) {
			// The server may not be listening yet during the early poll cycles.
			return null;
		}
	}

}
