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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT verifying the {@code GET ${path}/config} JSON endpoint at the default prefix.
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)} — /config
 * JSON endpoint):
 * <ul>
 * <li>{@code configEndpoint_returnsBootstrapJsonWithRequiredFields}</li>
 * <li>{@code configEndpoint_returnsValidJson}</li>
 * <li>{@code configEndpoint_setsNoCacheHeaders}</li>
 * </ul>
 */
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.server.name=mcp-inspector-itest-cfgep",
				"spring.ai.mcp.server.version=0.1.0", "spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-cfgep" })
class WebMvcBootstrapEndpointIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	@Test
	void configEndpoint_returnsBootstrapJsonWithRequiredFields() throws Exception {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/mcp-inspector/config"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).isNotNull()
			.matches(mt -> MediaType.APPLICATION_JSON.isCompatibleWith(mt));

		JsonNode body = objectMapper.readTree(response.getBody());
		assertThat(body.has("authToken")).isTrue();
		assertThat(body.has("proxyAddress")).isTrue();
		assertThat(body.has("detectedTransport")).isTrue();
		assertThat(body.path("proxyAddress").asText()).isEqualTo("/mcp-inspector-api");
	}

	@Test
	void configEndpoint_returnsValidJson() throws Exception {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/mcp-inspector/config"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		// Parse cleanly — anything that doesn't is invalid JSON.
		JsonNode root = objectMapper.readTree(response.getBody());
		assertThat(root.isObject()).isTrue();
	}

	@Test
	void configEndpoint_setsNoCacheHeaders() {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/mcp-inspector/config"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String cacheControl = response.getHeaders().getFirst("Cache-Control");
		assertThat(cacheControl).as("Cache-Control header on /config").isNotNull().contains("no-cache");
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

}
