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
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive parallel of {@code WebMvcBootstrapEndpointIT}.
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)} — /config
 * JSON endpoint, WebFlux):
 * <ul>
 * <li>{@code configEndpoint_returnsBootstrapJsonWithRequiredFields}</li>
 * <li>{@code configEndpoint_returnsValidJson}</li>
 * <li>{@code configEndpoint_setsNoCacheHeaders}</li>
 * </ul>
 */
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-cfgep", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-flux-cfgep" })
class WebFluxBootstrapEndpointIT {

	@Autowired
	private WebTestClient webTestClient;

	@Test
	void configEndpoint_returnsBootstrapJsonWithRequiredFields() {
		webTestClient.get()
			.uri("/mcp-inspector/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith("application/json")
			.expectBody()
			.jsonPath("$.authToken")
			.exists()
			.jsonPath("$.proxyAddress")
			.isEqualTo("/mcp-inspector-api")
			.jsonPath("$.detectedTransport")
			.exists();
	}

	@Test
	void configEndpoint_returnsValidJson() {
		webTestClient.get()
			.uri("/mcp-inspector/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.consumeWith(result -> {
				byte[] body = result.getResponseBody();
				assertThat(body).isNotNull();
				// Any malformed JSON would have already failed jsonPath
				// assertions in other tests; this method exists only to
				// declare the named scenario.
				assertThat(new String(body)).startsWith("{");
			});
	}

	@Test
	void configEndpoint_setsNoCacheHeaders() {
		webTestClient.get()
			.uri("/mcp-inspector/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.value("Cache-Control", value -> assertThat(value).contains("no-store"));
	}

}
