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
import java.util.Optional;

import io.inspector.mcp.demo.DemoApplication;

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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies CORS preflight handling on the proxy endpoints.
 *
 * <p>
 * The {@code addCorsMappings} block in both autoconfigs registers CORS rules only when
 * {@code spring.ai.mcp.inspector.allowed-origins} is non-empty. We therefore boot the
 * demo with an explicit allowed origin and then send an {@code OPTIONS} preflight with
 * {@code Origin:} + {@code Access-Control-Request-Method:} +
 * {@code Access-Control-Request-Headers:} to verify the response surfaces the expected
 * CORS headers.
 *
 * <p>
 * Two stacks × one assertion = 2 tests.
 */
@Epic("Inspector Proxy")
@Feature("CORS")
class ProxyCorsIT {

	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

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
	@DisplayName("OPTIONS preflight advertises the allowed origin and headers")
	@Story("CORS preflight")
	@Severity(SeverityLevel.NORMAL)
	@Description("Boots the demo with an allowed origin and verifies an OPTIONS preflight surfaces "
			+ "Access-Control-Allow-Origin and the proxy auth header in Access-Control-Allow-Headers")
	void corsPreflight_withAllowedOrigin_advertisesAllowedHeadersAndOrigin(ProxyAppHarness.Stack stack)
			throws Exception {
		// given
		app = startWithAllowedOrigin(stack);
		int port = ((WebServerApplicationContext) app).getWebServer().getPort();

		// when
		HttpRequest preflight = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/sse"))
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
			.header("Origin", ALLOWED_ORIGIN)
			.header("Access-Control-Request-Method", "GET")
			.header("Access-Control-Request-Headers", "X-MCP-Proxy-Auth, content-type")
			.timeout(Duration.ofSeconds(10))
			.build();
		HttpResponse<String> response = HTTP.send(preflight, HttpResponse.BodyHandlers.ofString());

		// then
		assertThat(response.statusCode()).as("preflight status on %s, body=%s", stack, response.body())
			.isBetween(200, 299);

		Optional<String> allowOrigin = response.headers().firstValue("Access-Control-Allow-Origin");
		assertThat(allowOrigin).as("Access-Control-Allow-Origin must be present on %s", stack).isPresent();
		assertThat(allowOrigin.get()).isEqualTo(ALLOWED_ORIGIN);

		Optional<String> allowHeaders = response.headers().firstValue("Access-Control-Allow-Headers");
		// Both stacks use allowedHeaders("*") which echoes the requested headers
		// verbatim.
		assertThat(allowHeaders).as("Access-Control-Allow-Headers on %s", stack).isPresent();
		assertThat(allowHeaders.get().toLowerCase())
			.as("Allowed headers must include the proxy auth header (case-insensitive)")
			.contains("x-mcp-proxy-auth");
	}

	/** Boots the demo with a single CORS-allowed origin so the cors filters wire up. */
	private static ConfigurableApplicationContext startWithAllowedOrigin(ProxyAppHarness.Stack stack) {
		boolean reactive = stack == ProxyAppHarness.Stack.WEBFLUX;
		WebApplicationType type = reactive ? WebApplicationType.REACTIVE : WebApplicationType.SERVLET;

		String exclude;
		if (reactive) {
			exclude = "org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebMvcAutoConfiguration,"
					+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration,"
					+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebMvcAutoConfiguration,"
					+ "io.inspector.mcp.webmvc.McpInspectorWebMvcAutoConfiguration";
		}
		else {
			exclude = "org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebFluxAutoConfiguration,"
					+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration,"
					+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebFluxAutoConfiguration,"
					+ "io.inspector.mcp.webflux.McpInspectorWebFluxAutoConfiguration";
		}

		return new SpringApplicationBuilder(DemoApplication.class).web(type)
			.run("--server.port=0", "--spring.main.web-application-type=" + (reactive ? "reactive" : "servlet"),
					"--spring.ai.mcp.server.protocol=STREAMABLE", "--spring.ai.mcp.inspector.auth-enabled=false",
					"--spring.ai.mcp.inspector.allowed-origins=" + ALLOWED_ORIGIN,
					"--spring.autoconfigure.exclude=" + exclude);
	}

}
