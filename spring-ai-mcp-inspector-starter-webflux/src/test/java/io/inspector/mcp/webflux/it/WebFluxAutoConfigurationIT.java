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
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
		"spring.ai.mcp.server.name=mcp-inspector-itest-flux", "spring.ai.mcp.server.version=0.1.0",
		"spring.ai.mcp.inspector.auth-enabled=false", "spring.application.name=mcp-inspector-itest-flux" })
class WebFluxAutoConfigurationIT {

	@Autowired
	private WebTestClient webTestClient;

	@Test
	void configEndpointReturnsDetectedTransportReactive() {
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
