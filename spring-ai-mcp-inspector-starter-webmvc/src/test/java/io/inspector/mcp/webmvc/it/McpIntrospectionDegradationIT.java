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

import java.util.ArrayList;
import java.util.List;

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
 * R-DEGRADE v13 integration IT (WebMvc): with the Spring AI MCP server auto-configuration
 * disabled, a lazy {@code List<SyncToolSpecification>} bean that throws is requested by
 * the introspection endpoint, skipped per-bean (R-READ) and never fails the report.
 */
@Epic("WebMvc Inspector")
@Feature("Introspection degradation (integration)")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = { TestMcpServerApp.class, DegradationConfiguration.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.enabled=false", "spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-intro-degrade" })
class McpIntrospectionDegradationIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JsonMapper objectMapper;

	@LocalServerPort
	private int port;

	@Test
	@Story("Degradation")
	@Severity(SeverityLevel.CRITICAL)
	@Description("the failing spec list is requested, the context boots, echo/sum/currentTime stay reported and the endpoint answers 200 (R-DEGRADE v13)")
	void failingBean_degradesNotFailsReport() throws Exception {
		// given
		DegradationConfiguration.REQUESTED.set(false);
		// when
		final ResponseEntity<String> response = this.restTemplate.getForEntity(url("/mcp-inspector/api/introspection"),
				String.class);
		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getStatusCode().is5xxServerError()).isFalse();
		assertThat(DegradationConfiguration.REQUESTED.get()).isTrue();
		final JsonNode body = this.objectMapper.readTree(response.getBody());
		final List<String> toolNames = new ArrayList<>();
		for (final JsonNode tool : body.path("tools")) {
			toolNames.add(tool.path("name").asText());
		}
		assertThat(toolNames).contains("echo", "sum", "currentTime");
	}

	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

}
