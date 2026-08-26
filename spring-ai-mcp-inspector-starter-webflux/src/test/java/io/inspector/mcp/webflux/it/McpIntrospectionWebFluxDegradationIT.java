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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-DEGRADE v13 integration IT (WebFlux): with the Spring AI MCP server
 * auto-configuration disabled, a lazy {@code List<SyncToolSpecification>} bean that
 * throws is requested by the introspection endpoint, skipped per-bean (R-READ) and never
 * fails the report.
 */
@Epic("MCP Inspector WebFlux")
@Feature("Introspection degradation (integration)")
@AutoConfigureWebTestClient
@SpringBootTest(classes = { TestMcpServerApp.class, DegradationConfiguration.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.enabled=false",
				"spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-flux-intro-degrade" })
class McpIntrospectionWebFluxDegradationIT {

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private JsonMapper objectMapper;

	@Test
	@Story("Degradation")
	@Severity(SeverityLevel.CRITICAL)
	@Description("the failing spec list is requested, the context boots, echo/sum/currentTime stay reported and the endpoint answers 200 (R-DEGRADE v13)")
	void failingBean_degradesNotFailsReport() {
		// given
		DegradationConfiguration.REQUESTED.set(false);
		// when
		final JsonNode body = getReport();
		// then
		assertThat(DegradationConfiguration.REQUESTED.get()).isTrue();
		final List<String> toolNames = new ArrayList<>();
		for (final JsonNode tool : body.path("tools")) {
			toolNames.add(tool.path("name").asText());
		}
		assertThat(toolNames).contains("echo", "sum", "currentTime");
	}

	private JsonNode getReport() {
		final AtomicReference<byte[]> bodyRef = new AtomicReference<>();
		this.webTestClient.get()
			.uri("/mcp-inspector/api/introspection")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.consumeWith((result) -> bodyRef.set(result.getResponseBody()));
		assertThat(bodyRef.get()).isNotNull();
		try {
			return this.objectMapper.readTree(bodyRef.get());
		}
		catch (final Exception ex) {
			throw new AssertionError("introspection response is not valid JSON", ex);
		}
	}

}
