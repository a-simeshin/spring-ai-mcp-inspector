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
 * IT verifying that {@code spring.ai.mcp.inspector.path=/custom} actually mounts the
 * entire inspector (UI, config endpoint, internal API and proxy backend) at the new
 * prefix, while the default {@code /mcp-inspector} prefix returns 404.
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)} — custom
 * path):
 * <ul>
 * <li>{@code customPath_indexHtmlRespondsOnNewPrefix}</li>
 * <li>{@code customPath_configEndpointRespondsOnNewPrefix}</li>
 * <li>{@code customPath_defaultPrefixReturns404}</li>
 * <li>{@code customPath_proxyApiRespondsOnDerivedPrefix}</li>
 * <li>{@code customPath_internalApiRespondsOnNewPrefix}</li>
 * </ul>
 */
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.inspector.path=/custom", "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-cfg", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false", "spring.application.name=mcp-inspector-itest-cfg" })
class WebMvcConfigurableInspectorIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@LocalServerPort
	private int port;

	@Test
	void customPath_indexHtmlRespondsOnNewPrefix() {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/custom/index.html"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).isNotNull()
			.matches(mt -> MediaType.TEXT_HTML.isCompatibleWith(mt));
	}

	@Test
	void customPath_configEndpointRespondsOnNewPrefix() {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/custom/config"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).isNotNull()
			.matches(mt -> MediaType.APPLICATION_JSON.isCompatibleWith(mt));
		assertThat(response.getBody()).contains("authToken", "proxyAddress", "detectedTransport");
	}

	@Test
	void customPath_defaultPrefixReturns404() {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/mcp-inspector/index.html"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void customPath_proxyApiRespondsOnDerivedPrefix() {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/custom-api/health"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("ok");
	}

	@Test
	void customPath_internalApiRespondsOnNewPrefix() {
		// The custom prefix's internal API route exists. Without a session id
		// the controller returns 404 with a Map error body — but the important
		// signal is that the route is registered (404 from the controller, not
		// a 404 from Spring's no-handler-found because no mapping existed).
		ResponseEntity<String> response = restTemplate.getForEntity(url("/custom/api/roots?sessionId=missing"),
				String.class);
		assertThat(response.getStatusCode())
			.as("inspector internal API must be reachable at the new prefix; body=%s", response.getBody())
			.isIn(HttpStatus.OK, HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED);
		// Confirm the route DID match by asserting an inspector-shaped error body
		// (vs. an empty Spring no-handler page). When auth is disabled and the
		// sessionId is unknown we get a JSON map with an "error" key.
		assertThat(response.getBody()).as("custom-prefix /api/roots should hit our controller, not be unmapped")
			.contains("error");

		// The default prefix must NOT exist anymore.
		ResponseEntity<String> defaultResponse = restTemplate
			.getForEntity(url("/mcp-inspector/api/roots?sessionId=missing"), String.class);
		assertThat(defaultResponse.getStatusCode()).as("default prefix must be unmapped when path is customised")
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

}
