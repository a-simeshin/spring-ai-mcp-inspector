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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive parallel of {@code WebMvcContextPathIT}: the inspector must serve resolvable
 * asset URLs and a base-path-aware redirect when the application runs under
 * {@code spring.webflux.base-path}.
 */
@Epic("MCP Inspector WebFlux")
@Feature("Base path support")
@AutoConfigureWebTestClient
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.webflux.base-path=/app",
				"spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-basepath", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-flux-basepath" })
class WebFluxContextPathIT {

	/**
	 * Extracts the first hashed bundle asset URL from the served HTML. The hash changes
	 * on every UI build, so it is read back from the response instead of hardcoded.
	 */
	private static final Pattern ASSET_PATTERN = Pattern.compile("(?:src|href)=\"(/[^\"]*/assets/[^\"]+)\"");

	@Autowired
	private WebTestClient webTestClient;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("a hashed asset referenced by index.html is served under the base path")
	@Story("Asset URLs")
	@Severity(SeverityLevel.CRITICAL)
	@Description("index.html rewrites bundle asset URLs to carry the base path, and those URLs return 200 with a body")
	void indexHtml_assetUrl_isFetchable() {
		// given
		final String index = this.webTestClient.get()
			.uri(url("/app/mcp-inspector/index.html"))
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
		assertThat(assetPath).as("asset URLs must carry the base path").startsWith("/app/mcp-inspector/assets/");
		// The hashed JS chunk is larger than the default 256 KB in-memory codec limit.
		this.webTestClient.mutate()
			.codecs((codecs) -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
			.build()
			.get()
			.uri(url(assetPath))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).as("the asset must not be served empty").isNotEmpty());
	}

	@Test
	@DisplayName("the inspector root redirects inside the base path")
	@Story("Redirect")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET ${basePath}${path} answers a redirect whose Location includes the base path")
	void inspectorRoot_redirectsWithinBasePath() {
		// when & then — WebTestClient does not follow redirects, so Location is visible
		this.webTestClient.get()
			.uri(url("/app/mcp-inspector"))
			.exchange()
			.expectStatus()
			.isTemporaryRedirect()
			.expectHeader()
			.location("/app/mcp-inspector/index.html");
	}

	@Test
	@DisplayName("the bootstrap proxy address and detected URL carry the base path")
	@Story("Bootstrap payload")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET ${path}/config advertises a proxy address and detected URL the browser can actually reach")
	void configEndpoint_advertisesPrefixedAddresses() {
		// when & then
		this.webTestClient.get()
			.uri(url("/app/mcp-inspector/config"))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("\"proxyAddress\":\"/app/mcp-inspector-api\"")
				.contains("\"detectedUrl\":\"/app/mcp\""));
	}

	/**
	 * Builds an absolute URL. The auto-configured {@link WebTestClient} already prepends
	 * {@code spring.webflux.base-path} to relative URIs, which would double the prefix
	 * and hide whether the served links are correct.
	 * @param path the absolute request path, base path included
	 * @return the absolute URL against the running server
	 */
	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

}
