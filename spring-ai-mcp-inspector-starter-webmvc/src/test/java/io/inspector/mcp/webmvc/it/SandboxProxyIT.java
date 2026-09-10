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

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the SEP-1865 sandbox proxy endpoint.
 *
 * <p>
 * Verifies that {@code GET /mcp-inspector-api/sandbox} serves the sandbox proxy HTML page
 * with the correct content type, restrictive CSP headers, and no authentication required
 * (matching the {@code /health} open-access pattern).
 */
@Epic("WebMvc Inspector")
@Feature("Sandbox proxy endpoint")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.server.name=mcp-inspector-itest",
		"spring.ai.mcp.server.version=0.1.0", "spring.ai.mcp.inspector.auth-enabled=false",
		"spring.application.name=mcp-inspector-itest" })
class SandboxProxyIT {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	@DisplayName("GET /mcp-inspector-api/sandbox returns 200 with HTML and CSP headers")
	@Story("Sandbox proxy")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The sandbox proxy endpoint serves the outer iframe page with text/html, a restrictive CSP, and no-store caching")
	void sandboxEndpointReturnsHtmlWithCspHeaders() {
		final ResponseEntity<String> response = this.restTemplate
			.getForEntity("http://localhost:" + this.port + "/mcp-inspector-api/sandbox", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType().toString()).contains("text/html");
		assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'");
		assertThat(response.getHeaders().getCacheControl()).contains("no-store");
		assertThat(response.getBody()).contains("sandbox-proxy-ready");
	}

}
