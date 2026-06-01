/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.inspector.mcp.webmvc.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
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
@Epic("WebMvc Inspector")
@Feature("Bootstrap config endpoint")
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
	@DisplayName("GET /config returns the bootstrap JSON with required fields")
	@Story("Bootstrap JSON")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The /config endpoint exposes authToken, proxyAddress and detectedTransport")
	void configEndpoint_returnsBootstrapJsonWithRequiredFields() throws Exception {
		// when
		final ResponseEntity<String> response = this.restTemplate.getForEntity(url("/mcp-inspector/config"),
				String.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).isNotNull()
			.matches((mt) -> MediaType.APPLICATION_JSON.isCompatibleWith(mt));

		final JsonNode body = this.objectMapper.readTree(response.getBody());
		assertThat(body.has("authToken")).isTrue();
		assertThat(body.has("proxyAddress")).isTrue();
		assertThat(body.has("detectedTransport")).isTrue();
		assertThat(body.path("proxyAddress").asText()).isEqualTo("/mcp-inspector-api");
	}

	@Test
	@DisplayName("GET /config returns parseable JSON")
	@Story("Bootstrap JSON")
	@Severity(SeverityLevel.NORMAL)
	@Description("The /config endpoint returns a JSON object that parses cleanly")
	void configEndpoint_returnsValidJson() throws Exception {
		// when
		final ResponseEntity<String> response = this.restTemplate.getForEntity(url("/mcp-inspector/config"),
				String.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		// Parse cleanly — anything that doesn't is invalid JSON.
		final JsonNode root = this.objectMapper.readTree(response.getBody());
		assertThat(root.isObject()).isTrue();
	}

	@Test
	@DisplayName("GET /config sets no-cache headers")
	@Story("Bootstrap JSON")
	@Severity(SeverityLevel.NORMAL)
	@Description("The /config endpoint sets a no-cache Cache-Control header so the SPA always re-reads it")
	void configEndpoint_setsNoCacheHeaders() {
		// when
		final ResponseEntity<String> response = this.restTemplate.getForEntity(url("/mcp-inspector/config"),
				String.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		final String cacheControl = response.getHeaders().getFirst("Cache-Control");
		assertThat(cacheControl).as("Cache-Control header on /config").isNotNull().contains("no-cache");
	}

	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

}
