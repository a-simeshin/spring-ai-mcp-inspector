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

package io.inspector.mcp.webmvc.plain;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empty-context integration IT (WebMvc): a context whose component scan covers a package
 * without MCP annotations must answer HTTP 200 with empty {@code tools}/{@code resources}
 * and exactly one {@code NO_MCP_ELEMENTS} warning (R8-PLAIN v10).
 */
@Epic("WebMvc Inspector")
@Feature("Introspection empty context (integration)")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = PlainMcpApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.server.name=mcp-inspector-itest-intro-plain",
				"spring.ai.mcp.server.version=0.1.0", "spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-intro-plain" })
class McpIntrospectionEmptyContextIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JsonMapper objectMapper;

	@LocalServerPort
	private int port;

	@Test
	@Story("Empty context")
	@Severity(SeverityLevel.CRITICAL)
	@Description("an MCP-free context reports tools=[] and resources=[] with NO_MCP_ELEMENTS and HTTP 200")
	void emptyContext_reportsNoMcpElements() throws Exception {
		// when
		final ResponseEntity<String> response = this.restTemplate.getForEntity(url("/mcp-inspector/api/introspection"),
				String.class);
		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getStatusCode().is5xxServerError()).isFalse();
		final JsonNode body = this.objectMapper.readTree(response.getBody());
		assertThat(body.path("tools").size()).isZero();
		assertThat(body.path("resources").size()).isZero();
		assertThat(body.path("warnings").size()).isEqualTo(1);
		final JsonNode warning = body.path("warnings").get(0);
		assertThat(warning.path("code").asText()).isEqualTo("NO_MCP_ELEMENTS");
		assertThat(warning.path("severity").asText()).isEqualTo("warning");
		assertThat(warning.path("element").asText()).isEmpty();
		assertThat(warning.path("path").asText()).isEqualTo("$");
	}

	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

}
