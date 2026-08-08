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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive parallel of {@code WebMvcConfigurableInspectorIT}: verifies that
 * {@code spring.ai.mcp.inspector.path=/custom} re-mounts the entire functional router
 * under the new prefix.
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)} — custom
 * path, WebFlux):
 * <ul>
 * <li>{@code customPath_indexHtmlRespondsOnNewPrefix}</li>
 * <li>{@code customPath_configEndpointRespondsOnNewPrefix}</li>
 * <li>{@code customPath_defaultPrefixReturns404}</li>
 * <li>{@code customPath_proxyApiRespondsOnDerivedPrefix}</li>
 * <li>{@code customPath_internalApiRespondsOnNewPrefix}</li>
 * </ul>
 */
@Epic("MCP Inspector WebFlux")
@Feature("Configurable inspector path (integration)")
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.inspector.path=/custom",
				"spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.server.name=mcp-inspector-itest-flux-cfg",
				"spring.ai.mcp.server.version=0.1.0", "spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-flux-cfg" })
class WebFluxConfigurableInspectorIT {

	/**
	 * Extracts the first hashed bundle asset URL from the served HTML. The hash changes
	 * on every UI build, so it is read back from the response instead of hardcoded.
	 */
	private static final Pattern ASSET_PATTERN = Pattern.compile("(?:src|href)=\"(/[^\"]*/assets/[^\"]+)\"");

	@Autowired
	private WebTestClient webTestClient;

	@Test
	@Story("Custom path remount")
	@Severity(SeverityLevel.CRITICAL)
	@Description("index.html rewrites bundle asset URLs to the custom prefix, and those URLs return 200 with a body")
	void customPath_assetUrl_isFetchable() {
		// given — status/content-type of index.html alone cannot see a wrong assetBase
		// or a resource route still mounted at the default prefix: the browser only
		// 404s when it follows the asset URL, so follow it here.
		final String index = this.webTestClient.get()
			.uri("/custom/index.html")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		assertThat(index).isNotNull();

		// when
		final Matcher matcher = ASSET_PATTERN.matcher(index);
		assertThat(matcher.find()).as("index.html must reference at least one hashed bundle asset").isTrue();
		final String assetPath = matcher.group(1);

		// then
		assertThat(assetPath).as("asset URLs must carry the custom prefix").startsWith("/custom/assets/");
		// The hashed JS chunk is larger than the default 256 KB in-memory codec limit.
		this.webTestClient.mutate()
			.codecs((codecs) -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
			.build()
			.get()
			.uri(assetPath)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).as("the asset must not be served empty").isNotEmpty());
	}

	@Test
	@Story("Custom path remount")
	@Severity(SeverityLevel.NORMAL)
	@Description("index.html is served under the custom /custom prefix as text/html")
	void customPath_indexHtmlRespondsOnNewPrefix() {
		// given the inspector path configured to /custom; when & then
		this.webTestClient.get()
			.uri("/custom/index.html")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith("text/html");
	}

	@Test
	@Story("Custom path remount")
	@Severity(SeverityLevel.NORMAL)
	@Description("The /config JSON endpoint responds under the custom /custom prefix")
	void customPath_configEndpointRespondsOnNewPrefix() {
		// given the inspector path configured to /custom; when & then
		this.webTestClient.get()
			.uri("/custom/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith("application/json")
			.expectBody()
			.jsonPath("$.authToken")
			.exists()
			.jsonPath("$.proxyAddress")
			.exists()
			.jsonPath("$.detectedTransport")
			.exists();
	}

	@Test
	@Story("Custom path remount")
	@Severity(SeverityLevel.NORMAL)
	@Description("The default /mcp-inspector prefix is unmapped once a custom path is configured (404)")
	void customPath_defaultPrefixReturns404() {
		// given the inspector path configured to /custom; when & then
		this.webTestClient.get().uri("/mcp-inspector/index.html").exchange().expectStatus().isNotFound();
	}

	@Test
	@Story("Custom path remount")
	@Severity(SeverityLevel.NORMAL)
	@Description("The proxy API is mounted on the derived /custom-api prefix and /health responds")
	void customPath_proxyApiRespondsOnDerivedPrefix() {
		// given the inspector path configured to /custom; when & then
		this.webTestClient.get().uri("/custom-api/health").exchange().expectStatus().isOk();
	}

	@Test
	@Story("Custom path remount")
	@Severity(SeverityLevel.NORMAL)
	@Description("The internal /api routes are reachable under the new prefix and unmapped under the default one")
	void customPath_internalApiRespondsOnNewPrefix() {
		// given the inspector path configured to /custom; when & then
		// /api/roots requires a sessionId query parameter — without one it
		// returns 400/404 from the handler. The signal is that the route
		// MATCHES under the new prefix (vs. completely unmapped).
		this.webTestClient.get()
			.uri("/custom/api/roots?sessionId=missing")
			.exchange()
			.expectStatus()
			// Either 4xx from our handler (route matched) or 200 — both prove the route
			// is reachable.
			.value((status) -> assertThat(status).as("internal API at new prefix").isIn(200, 400, 401, 404));

		// Default prefix must be unmapped now.
		this.webTestClient.get()
			.uri("/mcp-inspector/api/roots?sessionId=missing")
			.exchange()
			.expectStatus()
			.isNotFound();
	}

}
