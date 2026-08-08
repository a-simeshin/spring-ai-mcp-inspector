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

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.http.CacheControl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

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
@Epic("MCP Inspector WebFlux")
@Feature("WebFlux auto-configuration (integration)")
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
		"spring.ai.mcp.server.name=mcp-inspector-itest-flux", "spring.ai.mcp.server.version=0.1.0",
		"spring.ai.mcp.inspector.auth-enabled=false", "spring.application.name=mcp-inspector-itest-flux" })
class WebFluxAutoConfigurationIT {

	/**
	 * Extracts the first hashed bundle asset URL from the served HTML. The hash changes
	 * on every UI build, so it is read back from the response instead of hardcoded.
	 */
	private static final Pattern ASSET_PATTERN = Pattern.compile("(?:src|href)=\"(/[^\"]*/assets/[^\"]+)\"");

	@Autowired
	private WebTestClient webTestClient;

	@Test
	@Story("Config endpoint")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET /api/config over the reactive stack returns the detected SSE transport, WEBFLUX stack and an auth token")
	void configEndpointReturnsDetectedTransportReactive() {
		// given — a reactive MCP server booted with the SSE protocol (see
		// @TestPropertySource)
		// when & then
		this.webTestClient.get()
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

	@Test
	@DisplayName("hashed bundle assets are served long-lived, not no-store")
	@Story("Static asset caching")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A hashed asset referenced by index.html returns 200 with a body and a long max-age, so the multi-megabyte bundle survives a reload")
	void hashedAsset_isServedWithLongMaxAge() {
		// given
		final String index = this.webTestClient.get()
			.uri("/mcp-inspector/index.html")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		assertThat(index).isNotNull();
		final Matcher matcher = ASSET_PATTERN.matcher(index);
		assertThat(matcher.find()).as("index.html must reference at least one hashed bundle asset").isTrue();

		// when & then — the hashed JS chunk is larger than the default 256 KB
		// in-memory codec limit.
		this.webTestClient.mutate()
			.codecs((codecs) -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
			.build()
			.get()
			.uri(matcher.group(1))
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
			.expectBody(String.class)
			.value((body) -> assertThat(body).as("the asset must not be served empty").isNotEmpty());
	}

}
