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

import java.util.List;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inspector REST API timeline route with authentication left at its default
 * (enabled), pinning issue #316: the UI timeline poll must be accepted when it presents
 * the valid inspector token, and must stay denied without one.
 */
@Epic("MCP Inspector WebFlux")
@Feature("Timeline API authentication")
@AutoConfigureWebTestClient
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-timeline-auth",
				"spring.ai.mcp.inspector.timeline.enabled=true", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-token=timeline-it-token",
				"spring.application.name=mcp-inspector-itest-flux-timeline-auth" })
class TimelineAuthIT {

	private static final ParameterizedTypeReference<List<Map<String, Object>>> EVENT_LIST = new ParameterizedTypeReference<>() {
	};

	@Autowired
	private WebTestClient webTestClient;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("a valid inspector token answers 200 on the timeline route")
	@Story("Authentication")
	@Severity(SeverityLevel.BLOCKER)
	@Description("GET ${path}/api/timeline with a valid X-MCP-Inspector-Auth header answers 200 with an event array")
	void timeline_withValidToken_isOk() {
		// when & then
		this.webTestClient.get()
			.uri(url("/mcp-inspector/api/timeline"))
			.header("X-MCP-Inspector-Auth", "timeline-it-token")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(EVENT_LIST)
			.value((events) -> assertThat(events).isNotNull());
	}

	@Test
	@DisplayName("a missing token answers 401 on the timeline route")
	@Story("Authentication")
	@Severity(SeverityLevel.BLOCKER)
	@Description("GET ${path}/api/timeline without a token answers 401, as on any other guarded /api route")
	void timeline_withoutToken_isUnauthorized() {
		// when & then
		this.webTestClient.get().uri(url("/mcp-inspector/api/timeline")).exchange().expectStatus().isUnauthorized();
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
