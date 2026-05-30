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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the proxy bearer-token guard on both webmvc and webflux.
 *
 * <p>
 * Scenarios per stack (3 each = 6 total):
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

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	void protectedEndpointRejectsMissingToken(ProxyAppHarness.Stack stack) throws Exception {
		app = ProxyAppHarness.start(stack, "STREAMABLE", true, AUTH_TOKEN);
		int port = ProxyAppHarness.port(app);

		HttpResponse<String> response = get(port, "/config", null);
		assertThat(response.statusCode()).as("missing X-MCP-Proxy-Auth on %s", stack).isEqualTo(401);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	void protectedEndpointRejectsWrongToken(ProxyAppHarness.Stack stack) throws Exception {
		app = ProxyAppHarness.start(stack, "STREAMABLE", true, AUTH_TOKEN);
		int port = ProxyAppHarness.port(app);

		HttpResponse<String> response = get(port, "/config", "Bearer wrong-token");
		assertThat(response.statusCode()).as("wrong token on %s", stack).isEqualTo(401);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	void protectedEndpointAcceptsCorrectBearerToken(ProxyAppHarness.Stack stack) throws Exception {
		app = ProxyAppHarness.start(stack, "STREAMABLE", true, AUTH_TOKEN);
		int port = ProxyAppHarness.port(app);

		HttpResponse<String> response = get(port, "/config", "Bearer " + AUTH_TOKEN);
		assertThat(response.statusCode()).as("correct bearer token on %s, body=%s", stack, response.body())
			.isEqualTo(200);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	void protectedEndpointAcceptsRawToken(ProxyAppHarness.Stack stack) throws Exception {
		// The filter accepts both "Bearer <token>" and a raw token (matches
		// upstream's tolerance). Exercise the raw form so future refactors
		// can't silently drop it.
		app = ProxyAppHarness.start(stack, "STREAMABLE", true, AUTH_TOKEN);
		int port = ProxyAppHarness.port(app);

		HttpResponse<String> response = get(port, "/config", AUTH_TOKEN);
		assertThat(response.statusCode()).as("raw token on %s", stack).isEqualTo(200);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	void queryParamFallbackAcceptedForEventSourceClients(ProxyAppHarness.Stack stack) throws Exception {
		// EventSource cannot set custom headers. Upstream UI falls back to the
		// ?MCP_PROXY_AUTH_TOKEN query parameter — verify both stacks honor it.
		app = ProxyAppHarness.start(stack, "STREAMABLE", true, AUTH_TOKEN);
		int port = ProxyAppHarness.port(app);

		HttpRequest request = HttpRequest
			.newBuilder(URI
				.create("http://127.0.0.1:" + port + "/mcp-inspector-api/config?MCP_PROXY_AUTH_TOKEN=" + AUTH_TOKEN))
			.timeout(Duration.ofSeconds(10))
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("query-param token on %s, body=%s", stack, response.body()).isEqualTo(200);
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
