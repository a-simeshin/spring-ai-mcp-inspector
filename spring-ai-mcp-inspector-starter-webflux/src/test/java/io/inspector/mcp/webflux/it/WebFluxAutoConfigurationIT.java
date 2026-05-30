/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux.it;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for the WebFlux inspector starter using a real reactive Spring AI MCP
 * server (SSE protocol) and {@link WebTestClient}.
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)}):
 *
 * <ul>
 * <li>{@code configEndpointReturnsDetectedTransportReactive}</li>
 * </ul>
 */
@Epic("MCP Inspector WebFlux")
@Feature("WebFlux auto-configuration (integration)")
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
		"spring.ai.mcp.server.name=mcp-inspector-itest-flux", "spring.ai.mcp.server.version=0.1.0",
		"spring.ai.mcp.inspector.auth-enabled=false", "spring.application.name=mcp-inspector-itest-flux" })
class WebFluxAutoConfigurationIT {

	@Autowired
	private WebTestClient webTestClient;

	@Test
	@Story("Config endpoint")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET /api/config over the reactive stack returns the detected SSE transport, WEBFLUX stack and an auth token")
	void configEndpointReturnsDetectedTransportReactive() {
		// given — a reactive MCP server booted with the SSE protocol (see
		// @TestPropertySource)
		// when & then
		webTestClient.get()
			.uri("/mcp-inspector/api/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.transport")
			.isEqualTo("SSE")
			.jsonPath("$.stack")
			.isEqualTo("WEBFLUX")
			.jsonPath("$.authToken")
			.isNotEmpty();
	}

}
