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

package io.inspector.mcp.webflux.plain;

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
 * Empty-context integration IT (WebFlux): a context whose component scan covers a package
 * without MCP annotations must answer HTTP 200 with empty {@code tools}/{@code resources}
 * and exactly one {@code NO_MCP_ELEMENTS} warning (R8-PLAIN v10).
 */
@Epic("MCP Inspector WebFlux")
@Feature("Introspection empty context (integration)")
@AutoConfigureWebTestClient
@SpringBootTest(classes = PlainWebFluxApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-intro-plain", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-flux-intro-plain" })
class McpIntrospectionWebFluxEmptyContextIT {

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private JsonMapper objectMapper;

	@Test
	@Story("Empty context")
	@Severity(SeverityLevel.CRITICAL)
	@Description("an MCP-free context reports tools=[] and resources=[] with NO_MCP_ELEMENTS and HTTP 200")
	void emptyContext_reportsNoMcpElements() {
		// when
		final JsonNode body = getReport();
		// then
		assertThat(body.path("tools").size()).isZero();
		assertThat(body.path("resources").size()).isZero();
		assertThat(body.path("warnings").size()).isEqualTo(1);
		final JsonNode warning = body.path("warnings").get(0);
		assertThat(warning.path("code").asText()).isEqualTo("NO_MCP_ELEMENTS");
		assertThat(warning.path("severity").asText()).isEqualTo("warning");
		assertThat(warning.path("element").asText()).isEmpty();
		assertThat(warning.path("path").asText()).isEqualTo("$");
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
