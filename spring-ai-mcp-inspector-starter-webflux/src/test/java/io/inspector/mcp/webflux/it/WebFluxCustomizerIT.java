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

import io.inspector.mcp.core.bootstrap.InspectorBootstrapCustomizer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Reactive parallel of {@code WebMvcCustomizerIT}.
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)} — Customizer
 * integration, WebFlux):
 * <ul>
 * <li>{@code customizer_affectsConfigEndpoint}</li>
 * <li>{@code customizer_orderedByAtOrder}</li>
 * </ul>
 */
@SpringBootTest(classes = { TestMcpServerApp.class, WebFluxCustomizerIT.TestCustomizers.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-cust", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false", "spring.application.name=mcp-inspector-itest-flux-cust" })
class WebFluxCustomizerIT {

	static final String ORDER_LOSER = "http://order-1.example/mcp";
	static final String ORDER_WINNER = "http://order-2.example/mcp";

	@Autowired
	private WebTestClient webTestClient;

	@Test
	void customizer_affectsConfigEndpoint() {
		webTestClient.get()
			.uri("/mcp-inspector/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.defaultUrl")
			.exists();
	}

	@Test
	void customizer_orderedByAtOrder() {
		webTestClient.get()
			.uri("/mcp-inspector/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.defaultUrl")
			.isEqualTo(ORDER_WINNER);
	}

	@TestConfiguration
	static class TestCustomizers {

		@Bean
		@Order(1)
		InspectorBootstrapCustomizer firstCustomizer() {
			return bootstrap -> bootstrap.setDefaultUrl(ORDER_LOSER);
		}

		@Bean
		@Order(2)
		InspectorBootstrapCustomizer secondCustomizer() {
			return bootstrap -> bootstrap.setDefaultUrl(ORDER_WINNER);
		}

	}

}
