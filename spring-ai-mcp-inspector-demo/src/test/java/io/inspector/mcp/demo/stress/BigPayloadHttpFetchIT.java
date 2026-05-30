/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.stress;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress: the {@code POST /mcp-inspector-api/fetch} outbound proxy must hand back a 1 MiB
 * body intact when targeted at a self-hosted endpoint.
 *
 * <p>
 * Note on path: the task brief refers to {@code /mcp-inspector/fetch}; the actual
 * controller route is {@code /mcp-inspector-api/fetch} (see {@code ProxyConstants#BASE}).
 * We use the live path here so the test reflects production behaviour.
 *
 * <p>
 * Bootstrapping uses {@link ProxyAppHarness} — the same pattern as the neighbouring
 * {@code ProxyFetchEndpointIT}. A bare {@code @SpringBootTest} does not wire the proxy
 * controllers reliably in this multi-stack project.
 *
 * <p>
 * The 1 MiB target body is served by a tiny JDK {@link HttpServer} running on a dedicated
 * port in the same JVM — this keeps the demo's production capabilities untouched (task
 * constraint) while still giving us a true 1 MiB external resource for the proxy to
 * round-trip.
 */
class BigPayloadHttpFetchIT {

	private static final int ONE_MIB = 1024 * 1024;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static final Duration FETCH_BUDGET = Duration.ofSeconds(15);

	private ConfigurableApplicationContext app;

	private HttpServer bigPayloadHost;

	@AfterEach
	void stopApp() {
		if (bigPayloadHost != null) {
			try {
				bigPayloadHost.stop(0);
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			bigPayloadHost = null;
		}
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
	void fetchProxyReturnsOneMibBodyIntact() throws Exception {
		app = StressTestSupport.startWebmvc("STREAMABLE");
		int port = StressTestSupport.port(app);

		// Sanity: confirm the proxy controllers are wired before we exercise them.
		HttpResponse<String> health = HTTP
			.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/health"))
				.timeout(Duration.ofSeconds(5))
				.GET()
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(health.statusCode()).as("proxy autoconfig active (/health responsive)").isEqualTo(200);

		// Spin up a dedicated 1 MiB host on a random port.
		bigPayloadHost = startOneMibHost();
		int hostPort = bigPayloadHost.getAddress().getPort();
		String selfTargetUrl = "http://127.0.0.1:" + hostPort + "/big-payload";

		String requestBody = "{\"url\":\"" + selfTargetUrl + "\",\"init\":{\"method\":\"GET\"}}";
		HttpRequest fetchRequest = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/fetch"))
			.timeout(FETCH_BUDGET)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(requestBody))
			.build();

		Instant t0 = Instant.now();
		HttpResponse<String> response = HTTP.send(fetchRequest, HttpResponse.BodyHandlers.ofString());
		Duration elapsed = Duration.between(t0, Instant.now());

		assertThat(response.statusCode()).as("fetch proxy responded 2xx (head=%s)", abbreviate(response.body()))
			.isBetween(200, 299);
		assertThat(elapsed).as("fetch proxy budget").isLessThanOrEqualTo(FETCH_BUDGET);

		JsonNode envelope = MAPPER.readTree(response.body());
		assertThat(envelope.path("ok").asBoolean()).isTrue();
		assertThat(envelope.path("status").asInt()).isEqualTo(200);

		String relayedBody = envelope.path("body").asText();
		assertThat(relayedBody.length()).as("1 MiB body returned by the proxy must not be truncated")
			.isEqualTo(ONE_MIB);
	}

	/** JDK {@link HttpServer} that serves exactly 1 MiB on {@code /big-payload}. */
	private static HttpServer startOneMibHost() throws IOException {
		byte[] payload = buildOneMib().getBytes(StandardCharsets.UTF_8);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/big-payload", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(payload);
			}
		});
		server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
		server.start();
		return server;
	}

	private static String buildOneMib() {
		StringBuilder sb = new StringBuilder(ONE_MIB);
		String chunk = "Stress fetch payload chunk. ";
		while (sb.length() < ONE_MIB) {
			sb.append(chunk);
		}
		sb.setLength(ONE_MIB);
		return sb.toString();
	}

	private static String abbreviate(String s) {
		if (s == null) {
			return "<null>";
		}
		return s.length() <= 200 ? s : s.substring(0, 200) + "...";
	}

}
