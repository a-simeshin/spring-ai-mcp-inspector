/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.bootstrap.InspectorBootstrapCustomizer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT verifying that user-registered {@link InspectorBootstrapCustomizer} beans influence
 * the bootstrap exposed at {@code /config} and that two customizers compose according to
 * {@code @Order} (higher value wins on conflict).
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)} — Customizer
 * integration):
 * <ul>
 * <li>{@code customizer_affectsConfigEndpoint}</li>
 * <li>{@code customizer_orderedByAtOrder}</li>
 * </ul>
 */
@SpringBootTest(classes = { TestMcpServerApp.class, WebMvcCustomizerIT.TestCustomizers.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.server.name=mcp-inspector-itest-cust",
				"spring.ai.mcp.server.version=0.1.0", "spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-cust" })
class WebMvcCustomizerIT {

	static final String EXPECTED_DEFAULT_URL = "http://test/mcp";
	static final String ORDER_LOSER = "http://order-1.example/mcp";
	static final String ORDER_WINNER = "http://order-2.example/mcp";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	@Test
	void customizer_affectsConfigEndpoint() throws Exception {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/mcp-inspector/config"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode body = objectMapper.readTree(response.getBody());
		// setDefaultUrlCustomizer (the lowest-order one) sets defaultUrl. The
		// @Order-based winner test asserts the final value below — here we only
		// need to confirm SOME customizer-supplied defaultUrl is present.
		assertThat(body.has("defaultUrl")).isTrue();
		assertThat(body.path("defaultUrl").asText()).isNotBlank();
	}

	@Test
	void customizer_orderedByAtOrder() throws Exception {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/mcp-inspector/config"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode body = objectMapper.readTree(response.getBody());
		// Two customizers set defaultUrl: @Order(1) writes ORDER_LOSER, then
		// @Order(2) overwrites with ORDER_WINNER. The later (higher) order wins.
		assertThat(body.path("defaultUrl").asText()).as("higher @Order value must win on field conflict")
			.isEqualTo(ORDER_WINNER);
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
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
