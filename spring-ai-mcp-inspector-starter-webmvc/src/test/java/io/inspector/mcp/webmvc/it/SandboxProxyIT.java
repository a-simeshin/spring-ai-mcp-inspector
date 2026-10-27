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

import java.util.LinkedHashMap;
import java.util.Map;

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
 * with the correct content type, an effective single-policy CSP, and no authentication
 * required (matching the {@code /health} open-access pattern).
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

	/**
	 * Parse a served policy into its effective directives, mirroring CSP3 parsing: the
	 * FIRST occurrence of a repeated directive wins, later ones are ignored.
	 */
	private static Map<String, String> effectiveDirectives(final String policy) {
		final Map<String, String> directives = new LinkedHashMap<>();
		for (final String token : policy.split(";")) {
			final String trimmed = token.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			final String[] nameAndValue = trimmed.split("\\s+", 2);
			directives.putIfAbsent(nameAndValue[0], (nameAndValue.length > 1) ? nameAndValue[1] : "");
		}
		return directives;
	}

	/** Occurrence count of every directive name in the served policy. */
	private static Map<String, Long> directiveCounts(final String policy) {
		final Map<String, Long> counts = new LinkedHashMap<>();
		for (final String token : policy.split(";")) {
			final String trimmed = token.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			counts.merge(trimmed.split("\\s+", 2)[0], 1L, Long::sum);
		}
		return counts;
	}

	private ResponseEntity<String> getSandbox(final String query, final Object... uriVars) {
		return this.restTemplate.getForEntity("http://localhost:" + this.port + "/mcp-inspector-api/sandbox" + query,
				String.class, uriVars);
	}

	@Test
	@DisplayName("GET /mcp-inspector-api/sandbox returns 200 with HTML and CSP headers")
	@Story("Sandbox proxy")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The sandbox proxy endpoint serves the outer iframe page with text/html, a restrictive CSP, and no-store caching")
	void sandboxEndpointReturnsHtmlWithCspHeaders() {
		final ResponseEntity<String> response = getSandbox("");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType().toString()).contains("text/html");
		final Map<String, String> effective = effectiveDirectives(
				response.getHeaders().getFirst("Content-Security-Policy"));
		assertThat(effective.get("default-src")).isEqualTo("'none'");
		// frame-ancestors 'self' stops any other origin from framing the sandbox
		// endpoint (owner review PR #199, BLOCK 1)
		assertThat(effective.get("frame-ancestors")).isEqualTo("'self'");
		assertThat(response.getHeaders().getCacheControl()).contains("no-store");
		assertThat(response.getBody()).contains("sandbox-proxy-ready");
	}

	@Test
	@DisplayName("App csp param yields one effective connect-src with the App domains, object-src 'none', no duplicated directives")
	@Story("Sandbox proxy")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The served CSP is ONE policy merged at the directive level: an App declaring api.example.com gets it in the single effective connect-src, object-src is 'none', and no directive appears twice (owner review PR #199, BLOCK 2)")
	void sandboxEndpointWithAppCspParam_effectiveDirectives() {
		// getSandbox builds the URL via URI template expansion: the raw JSON goes in as
		// the {csp} variable and is percent-encoded exactly once. Pre-encoding it here
		// would double-encode, fail JSON parsing server-side, and fall back to the
		// baseline.
		final ResponseEntity<String> response = getSandbox("?csp={csp}",
				"{\"connectDomains\":[\"https://api.example.com\"]}");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		final String policy = response.getHeaders().getFirst("Content-Security-Policy");
		// The App-declared domain IS the effective connect-src the browser enforces.
		final Map<String, String> effective = effectiveDirectives(policy);
		assertThat(effective.get("connect-src")).isEqualTo("https://api.example.com");
		// SEP-1865 draft, "Sandbox proxy", item 5: object-src 'none' everywhere.
		assertThat(effective.get("object-src")).isEqualTo("'none'");
		// A concatenated second policy would repeat directives the browser ignores.
		assertThat(directiveCounts(policy))
			.allSatisfy((name, count) -> assertThat(count).as("directive %s", name).isEqualTo(1L));
	}

}
