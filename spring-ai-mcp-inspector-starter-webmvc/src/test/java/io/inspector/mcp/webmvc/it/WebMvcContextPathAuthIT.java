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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT proving the WebMVC auth filter is context-path aware (owner review PR #199, round 2,
 * BLOCK 3): under {@code server.servlet.context-path=/app} with auth enabled, the
 * intentionally open endpoints ({@code /health} for the LB probe and {@code /sandbox} for
 * the sandbox proxy page, both loaded in credential-less contexts) must answer 200
 * without a token, while protected endpoints still 401. The filter used to compare the
 * raw request URI (which includes the context path) against the unprefixed configured
 * paths, turning both open endpoints into 401; an LB in front of such an instance marks
 * it dead. The WebFlux filter compares pathWithinApplication() and was never affected.
 */
@Epic("WebMvc Inspector")
@Feature("Auth under a context path")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "server.servlet.context-path=/app", "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-ctxauth", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=true", "spring.ai.mcp.inspector.auth-token=itest-context-token",
				"spring.application.name=mcp-inspector-itest-ctxauth" })
class WebMvcContextPathAuthIT {

	private static final String TOKEN = "itest-context-token";

	@Autowired
	private TestRestTemplate restTemplate;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("the open health probe answers 200 under a context path with auth enabled")
	@Story("Health probe")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET ${contextPath}/mcp-inspector-api/health needs no token; a 401 here makes an LB mark the instance dead")
	void healthProbe_openUnderContextPath() {
		// when
		final ResponseEntity<String> response = this.restTemplate.getForEntity(url("/app/mcp-inspector-api/health"),
				String.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("the open sandbox proxy page answers 200 under a context path with auth enabled")
	@Story("Sandbox proxy page")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET ${contextPath}/mcp-inspector-api/sandbox needs no token: the iframe document carries no auth headers")
	void sandboxPage_openUnderContextPath() {
		// when
		final ResponseEntity<String> response = this.restTemplate.getForEntity(url("/app/mcp-inspector-api/sandbox"),
				String.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).as("the sandbox proxy page must actually be served").contains("sandbox");
	}

	@Test
	@DisplayName("protected endpoints still require the token under a context path")
	@Story("Token enforcement")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET ${contextPath}/mcp-inspector-api/config answers 401 without the proxy auth token and 200 with it")
	void protectedEndpoints_requireTokenUnderContextPath() {
		// without a token: 401
		final ResponseEntity<String> anonymous = this.restTemplate.getForEntity(url("/app/mcp-inspector-api/config"),
				String.class);
		assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		// with the token: 200
		final HttpHeaders headers = new HttpHeaders();
		headers.set("X-MCP-Proxy-Auth", TOKEN);
		final ResponseEntity<String> authenticated = this.restTemplate.exchange(url("/app/mcp-inspector-api/config"),
				HttpMethod.GET, new HttpEntity<>(headers), String.class);
		assertThat(authenticated.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

}
