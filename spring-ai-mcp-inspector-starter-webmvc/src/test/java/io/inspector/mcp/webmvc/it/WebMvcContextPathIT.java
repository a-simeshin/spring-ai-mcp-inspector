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
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT proving the servlet inspector is usable when the application is mounted under a
 * context path: the served {@code index.html} points at asset URLs that actually resolve,
 * and the root redirect lands inside the context path instead of at the container root.
 */
@Epic("WebMvc Inspector")
@Feature("Context path support")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "server.servlet.context-path=/app", "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-ctxpath", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false", "spring.application.name=mcp-inspector-itest-ctxpath" })
class WebMvcContextPathIT {

	/**
	 * Extracts the first hashed bundle asset URL from the served HTML. The hash changes
	 * on every UI build, so it is read back from the response instead of hardcoded.
	 */
	private static final Pattern ASSET_PATTERN = Pattern.compile("(?:src|href)=\"(/[^\"]*/assets/[^\"]+)\"");

	@Autowired
	private TestRestTemplate restTemplate;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("a hashed asset referenced by index.html is served under the context path")
	@Story("Asset URLs")
	@Severity(SeverityLevel.CRITICAL)
	@Description("index.html rewrites bundle asset URLs to carry the context path, and those URLs return 200 with a body")
	void indexHtml_assetUrl_isFetchable() {
		// given
		final ResponseEntity<String> index = this.restTemplate.getForEntity(url("/app/mcp-inspector/index.html"),
				String.class);
		assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);

		// when
		final Matcher matcher = ASSET_PATTERN.matcher(index.getBody());
		assertThat(matcher.find()).as("index.html must reference at least one hashed bundle asset").isTrue();
		final String assetPath = matcher.group(1);
		final ResponseEntity<String> asset = this.restTemplate.getForEntity(url(assetPath), String.class);

		// then
		assertThat(assetPath).as("asset URLs must carry the context path").startsWith("/app/mcp-inspector/assets/");
		assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(asset.getBody()).as("the asset must not be served empty").isNotEmpty();
	}

	@Test
	@DisplayName("the inspector root redirects inside the context path")
	@Story("Redirect")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET ${contextPath}${path} answers 302 with a Location that includes the context path")
	void inspectorRoot_redirectsWithinContextPath() {
		// given — redirect following would swallow the Location header and make the
		// assertion vacuous
		final TestRestTemplate noRedirects = this.restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);

		// when
		final ResponseEntity<Void> response = noRedirects.getForEntity(url("/app/mcp-inspector"), Void.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).hasToString("/app/mcp-inspector/index.html");
	}

	@Test
	@DisplayName("the bootstrap proxy address and detected URL carry the context path")
	@Story("Bootstrap payload")
	@Severity(SeverityLevel.CRITICAL)
	@Description("GET ${path}/config advertises a proxy address and detected URL the browser can actually reach")
	void configEndpoint_advertisesPrefixedAddresses() {
		// when
		final ResponseEntity<String> response = this.restTemplate.getForEntity(url("/app/mcp-inspector/config"),
				String.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"proxyAddress\":\"/app/mcp-inspector-api\"")
			.contains("\"detectedUrl\":\"/app/mcp\"");
	}

	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

}
