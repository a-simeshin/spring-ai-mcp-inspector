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
 * Hits {@code GET /mcp-inspector-api/health} on both the servlet (webmvc) and reactive
 * (webflux) stacks.
 *
 * <p>
 * The health endpoint is explicitly allow-listed by {@code ProxyAuthFilter} /
 * {@code ProxyAuthWebFilter} so it must respond 200 with the JSON body
 * {@code {"status":"ok"}} even when the proxy auth filter is otherwise active.
 *
 * <p>
 * Note on path: the proxy actually lives under {@code /mcp-inspector-api/*} (see
 * {@code ProxyConstants#BASE}), not {@code /mcp-inspector/*}. The task brief used the
 * latter as shorthand; we test the live path.
 */
@Epic("Inspector Proxy")
@Feature("Health endpoint")
class ProxyHealthEndpointIT {

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
	@DisplayName("GET /health returns 200 even with auth enabled (allow-listed)")
	@Story("Health allow-list")
	@Severity(SeverityLevel.CRITICAL)
	@Description("With the proxy auth filter active, the allow-listed /health route must still respond "
			+ "200 with {\"status\":\"ok\"} and no auth header")
	void health_whenAuthEnabled_returnsOkOnBothStacks(ProxyAppHarness.Stack stack) throws Exception {
		// given
		// Auth ON to prove the health route is in fact unprotected (matches upstream).
		app = ProxyAppHarness.start(stack, "STREAMABLE", true, "test-token-health");
		int port = ProxyAppHarness.port(app);

		// when
		HttpRequest request = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/health"))
			.timeout(Duration.ofSeconds(10))
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

		// then
		assertThat(response.statusCode()).as("health responds 200 without auth header").isEqualTo(200);
		JsonNode body = MAPPER.readTree(response.body());
		assertThat(body.path("status").asText()).isEqualTo("ok");
	}

}
