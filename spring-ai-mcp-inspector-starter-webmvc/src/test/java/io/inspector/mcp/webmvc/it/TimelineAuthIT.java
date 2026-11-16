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
import org.springframework.boot.http.client.HttpRedirects;
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
 * The inspector REST API timeline route with authentication left at its default
 * (enabled), pinning issue #316: the UI timeline poll must be accepted when it presents
 * the valid inspector token, and must stay denied without one.
 */
@Epic("WebMvc Inspector")
@Feature("Timeline API authentication")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-timeline-auth", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.timeline.enabled=true", "spring.ai.mcp.inspector.auth-token=timeline-it-token",
				"spring.application.name=mcp-inspector-itest-timeline-auth" })
class TimelineAuthIT {

	private static final String TIMELINE_PATH = "/mcp-inspector/api/timeline";

	@Autowired
	private TestRestTemplate restTemplate;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("a valid inspector token answers 200 on the timeline route")
	@Story("Authentication")
	@Severity(SeverityLevel.BLOCKER)
	@Description("GET ${path}/api/timeline with a valid X-MCP-Inspector-Auth header answers 200 with an event array")
	void timeline_withValidToken_isOk() {
		// given
		final HttpHeaders headers = new HttpHeaders();
		headers.set("X-MCP-Inspector-Auth", "timeline-it-token");

		// when
		final ResponseEntity<String> response = this.restTemplate.exchange(url(TIMELINE_PATH), HttpMethod.GET,
				new HttpEntity<>(headers), String.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).as("the timeline must answer a JSON array").startsWith("[");
	}

	@Test
	@DisplayName("a missing token answers 401 on the timeline route")
	@Story("Authentication")
	@Severity(SeverityLevel.BLOCKER)
	@Description("GET ${path}/api/timeline without a token answers 401, as on any other guarded /api route")
	void timeline_withoutToken_isUnauthorized() {
		// given - redirect following would mask the guard
		final TestRestTemplate noRedirects = this.restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);

		// when
		final ResponseEntity<String> response = noRedirects.getForEntity(url(TIMELINE_PATH), String.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	/**
	 * Builds an absolute URL against the running server.
	 * @param path the request path
	 * @return the absolute URL
	 */
	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

}
