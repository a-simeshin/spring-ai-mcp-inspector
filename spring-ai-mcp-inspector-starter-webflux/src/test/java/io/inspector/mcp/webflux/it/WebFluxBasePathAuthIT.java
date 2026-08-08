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

package io.inspector.mcp.webflux.it;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base-path deployment with authentication left at its default (enabled).
 *
 * <p>
 * {@code spring.main.web-application-type} is deliberately absent: Boot deduces the
 * reactive type from the classpath, so a real WebFlux application never sets it, and
 * pinning it would hide whether the inspector reads {@code spring.webflux.base-path}.
 */
@Epic("MCP Inspector WebFlux")
@Feature("Base path support")
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.webflux.base-path=/app", "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-basepath-auth",
				"spring.ai.mcp.server.version=0.1.0",
				"spring.application.name=mcp-inspector-itest-flux-basepath-auth" })
class WebFluxBasePathAuthIT {

	@Autowired
	private WebTestClient webTestClient;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("the proxy stays token-guarded under a base path")
	@Story("Authentication")
	@Severity(SeverityLevel.BLOCKER)
	@Description("GET ${basePath}${proxyPath}/config without a token answers 401, not the proxy configuration")
	void proxyApi_underBasePathWithoutToken_isUnauthorized() {
		// when & then
		this.webTestClient.get().uri(url("/app/mcp-inspector-api/config")).exchange().expectStatus().isUnauthorized();
	}

	@Test
	@DisplayName("the inspector REST API stays token-guarded under a base path")
	@Story("Authentication")
	@Severity(SeverityLevel.BLOCKER)
	@Description("POST ${basePath}${path}/api/connect without a token answers 401, so no MCP session can be opened")
	void inspectorApi_underBasePathWithoutToken_isUnauthorized() {
		// when & then
		this.webTestClient.post().uri(url("/app/mcp-inspector/api/connect")).exchange().expectStatus().isUnauthorized();
	}

	@Test
	@DisplayName("the detected MCP endpoint carries the base path without web-application-type set")
	@Story("Bootstrap payload")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET ${path}/config advertises the base-path-prefixed MCP endpoint on a stock WebFlux application")
	void configEndpoint_withoutWebApplicationTypeProperty_advertisesPrefixedEndpoint() {
		// when & then
		this.webTestClient.get()
			.uri(url("/app/mcp-inspector/config"))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("\"detectedUrl\":\"/app/mcp\"")
				.contains("\"proxyAddress\":\"/app/mcp-inspector-api\""));
	}

	/**
	 * Builds an absolute URL. The auto-configured {@link WebTestClient} already prepends
	 * {@code spring.webflux.base-path} to relative URIs, which would double the prefix.
	 * @param path the absolute request path, base path included
	 * @return the absolute URL against the running server
	 */
	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

}
