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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the proxy bearer-token guard on both webmvc and webflux.
 *
 * <p>
 * Scenarios per ProxyAppHarness.stack() (3 each = 6 total):
 *
 * <ol>
 * <li>No token on a protected endpoint ({@code /config}) → 401</li>
 * <li>Wrong token → 401</li>
 * <li>Correct token via {@code X-MCP-Proxy-Auth: Bearer …} → 200</li>
 * </ol>
 *
 * <p>
 * {@code /health} is intentionally not tested here — it lives in
 * {@link ProxyHealthEndpointIT}, which also verifies the allow-list.
 */
@Epic("Inspector Proxy")
@Feature("Auth filter")
class ProxyAuthFilterIT {

	private static final String AUTH_TOKEN = "deadbeef-cafef00d-deadbeef-cafef00d";

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

	@Nested
	@DisplayName("Rejects invalid credentials")
	class RejectsInvalidToken {

		@Test
		@DisplayName("missing token → 401")
		@Story("Missing token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A request to the protected /config endpoint without the X-MCP-Proxy-Auth header is "
				+ "rejected with 401 on both stacks")
		void protectedEndpoint_withMissingToken_returns401() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			int port = ProxyAppHarness.port(app);

			// when
			HttpResponse<String> response = get(port, "/config", null);

			// then
			assertThat(response.statusCode()).as("missing X-MCP-Proxy-Auth on %s", ProxyAppHarness.stack())
				.isEqualTo(401);
		}

		@Test
		@DisplayName("wrong token → 401")
		@Story("Wrong token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A request carrying an incorrect bearer token is rejected with 401 on both stacks")
		void protectedEndpoint_withWrongToken_returns401() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			int port = ProxyAppHarness.port(app);

			// when
			HttpResponse<String> response = get(port, "/config", "Bearer wrong-token");

			// then
			assertThat(response.statusCode()).as("wrong token on %s", ProxyAppHarness.stack()).isEqualTo(401);
		}

	}

	@Nested
	@DisplayName("Accepts valid credentials")
	class AcceptsValidToken {

		@Test
		@DisplayName("correct bearer token → 200")
		@Story("Bearer token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A request carrying the correct token via X-MCP-Proxy-Auth: Bearer <token> is accepted "
				+ "with 200 on both stacks")
		void protectedEndpoint_withCorrectBearerToken_returns200() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			int port = ProxyAppHarness.port(app);

			// when
			HttpResponse<String> response = get(port, "/config", "Bearer " + AUTH_TOKEN);

			// then
			assertThat(response.statusCode())
				.as("correct bearer token on %s, body=%s", ProxyAppHarness.stack(), response.body())
				.isEqualTo(200);
		}

		@Test
		@DisplayName("raw token (no Bearer prefix) → 200")
		@Story("Raw token")
		@Severity(SeverityLevel.NORMAL)
		@Description("The filter tolerates a raw token without the Bearer prefix (matches upstream); exercise "
				+ "it so future refactors cannot silently drop the fallback")
		void protectedEndpoint_withRawToken_returns200() throws Exception {
			// given
			// The filter accepts both "Bearer <token>" and a raw token (matches
			// upstream's tolerance). Exercise the raw form so future refactors
			// can't silently drop it.
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			int port = ProxyAppHarness.port(app);

			// when
			HttpResponse<String> response = get(port, "/config", AUTH_TOKEN);

			// then
			assertThat(response.statusCode()).as("raw token on %s", ProxyAppHarness.stack()).isEqualTo(200);
		}

		@Test
		@DisplayName("query-param fallback → 200")
		@Story("Query-param fallback")
		@Severity(SeverityLevel.NORMAL)
		@Description("EventSource clients cannot set headers, so the filter accepts the token via the "
				+ "?MCP_PROXY_AUTH_TOKEN query parameter; verify both stacks honor it")
		void queryParamFallback_forEventSourceClients_returns200() throws Exception {
			// given
			// EventSource cannot set custom headers. Upstream UI falls back to the
			// ?MCP_PROXY_AUTH_TOKEN query parameter — verify both stacks honor it.
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			int port = ProxyAppHarness.port(app);

			// when
			HttpRequest request = HttpRequest
				.newBuilder(URI.create(
						"http://127.0.0.1:" + port + "/mcp-inspector-api/config?MCP_PROXY_AUTH_TOKEN=" + AUTH_TOKEN))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

			// then
			assertThat(response.statusCode())
				.as("query-param token on %s, body=%s", ProxyAppHarness.stack(), response.body())
				.isEqualTo(200);
		}

	}

	private static HttpResponse<String> get(int port, String path, String authHeader) throws Exception {
		HttpRequest.Builder builder = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api" + path))
			.timeout(Duration.ofSeconds(10))
			.GET();
		if (authHeader != null) {
			builder.header("X-MCP-Proxy-Auth", authHeader);
		}
		return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
