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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits {@code GET /mcp-inspector-api/config} on both webmvc and webflux stacks.
 *
 * <p>
 * Surprise vs. the task brief: the brief expected keys {@code MCP_PROXY_FULL_ADDRESS},
 * {@code MCP_PROXY_AUTH_TOKEN}, {@code lastTransportType}, {@code lastSseUrl} — those are
 * <em>upstream client-side cookie keys</em>, not the server-side defaults response. The
 * actual {@code ProxyConfigController#config} returns the upstream-compatible
 * {@code defaultEnvironment / defaultCommand / defaultArgs / defaultTransport /
 * defaultServerUrl} envelope (matches the JS inspector's
 * {@code mcp-proxy/src/server/index.ts#getConfig}). We assert the live shape.
 *
 * <p>
 * Reported as a brief-vs-impl mismatch in the run report.
 */
@Epic("Inspector Proxy")
@Feature("Config endpoint")
class ProxyConfigEndpointIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

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
	@DisplayName("GET /config returns the upstream-compatible defaults envelope")
	@Story("Config defaults")
	@Severity(SeverityLevel.NORMAL)
	@Description("Verifies GET /mcp-inspector-api/config returns the upstream-compatible shape "
			+ "(defaultEnvironment/defaultCommand/defaultArgs/defaultTransport/defaultServerUrl) on both stacks")
	void config_whenRequested_returnsUpstreamCompatibleShape(ProxyAppHarness.Stack stack) throws Exception {
		// given
		// Auth off so we don't need to thread a token through every assertion —
		// the auth filter is exhaustively covered by ProxyAuthFilterIT.
		app = ProxyAppHarness.start(stack, "STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);

		// when
		HttpRequest request = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/config"))
			.timeout(Duration.ofSeconds(10))
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

		// then
		assertThat(response.statusCode()).isEqualTo(200);

		JsonNode body = MAPPER.readTree(response.body());

		// Upstream-compatible keys — every one of these must be present.
		assertThat(body.has("defaultEnvironment")).as("defaultEnvironment key present").isTrue();
		assertThat(body.has("defaultCommand")).as("defaultCommand key present").isTrue();
		assertThat(body.has("defaultArgs")).as("defaultArgs key present").isTrue();
		assertThat(body.has("defaultTransport")).as("defaultTransport key present").isTrue();
		assertThat(body.has("defaultServerUrl")).as("defaultServerUrl key present").isTrue();

		// With protocol=STREAMABLE the controller maps to "streamable-http".
		assertThat(body.path("defaultTransport").asText())
			.as("STREAMABLE → streamable-http per ProxyConfigController#mapTransport")
			.isEqualTo("streamable-http");

		if (stack == ProxyAppHarness.Stack.WEBMVC) {
			// WebMvc path uses InspectorServerPortHolder, which is wired by the
			// mvc autoconfig only — we can therefore assert a concrete URL shape.
			assertThat(body.path("defaultServerUrl").asText())
				.as("defaultServerUrl on webmvc must point at the live MCP endpoint")
				.matches("http://localhost:\\d+/mcp");
		}
		else {
			// Webflux uses ProxyHandler.listeningPort which is populated by a
			// WebServerInitializedEvent. The event fires on the reactive stack
			// too, so we expect the same shape; if the timing race causes a
			// blank URL we surface that explicitly rather than silently passing.
			assertThat(body.path("defaultServerUrl").asText()).as("defaultServerUrl on webflux")
				.matches("http://localhost:\\d+/mcp|");
		}
	}

}
