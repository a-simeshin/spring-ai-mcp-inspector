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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.inspector.mcp.core.bootstrap.InspectorBootstrapCustomizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive parallel of {@code WebMvcBootstrapInlineIT}.
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)} — /index.html
 * inline bootstrap, WebFlux):
 * <ul>
 * <li>{@code indexHtml_containsBootstrapWindowGlobal}</li>
 * <li>{@code indexHtml_bootstrapJsonMatchesConfigEndpoint}</li>
 * <li>{@code indexHtml_escapesClosingScriptTag}</li>
 * </ul>
 */
@Epic("MCP Inspector WebFlux")
@Feature("index.html inline bootstrap (integration)")
@SpringBootTest(classes = { TestMcpServerApp.class, WebFluxBootstrapInlineIT.TestCustomizers.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-inline", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-flux-inline" })
class WebFluxBootstrapInlineIT {

	private static final Pattern BOOTSTRAP_PATTERN = Pattern
		.compile("window\\.__MCP_INSPECTOR_BOOTSTRAP\\s*=\\s*(\\{.*?\\});</script>", Pattern.DOTALL);

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private JsonMapper objectMapper;

	@Test
	@Story("Inline bootstrap")
	@Severity(SeverityLevel.NORMAL)
	@Description("index.html contains the window.__MCP_INSPECTOR_BOOTSTRAP global injected by the renderer")
	void indexHtml_containsBootstrapWindowGlobal() {
		// given a booted reactive inspector; when & then
		this.webTestClient.get()
			.uri("/mcp-inspector/index.html")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("window.__MCP_INSPECTOR_BOOTSTRAP ="));
	}

	@Test
	@Story("Inline bootstrap")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The inline bootstrap JSON embedded in index.html is byte-for-byte equal to the /config endpoint payload")
	void indexHtml_bootstrapJsonMatchesConfigEndpoint() {
		// given a booted reactive inspector; when & then
		final String indexBody = this.webTestClient.get()
			.uri("/mcp-inspector/index.html")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		final String configBody = this.webTestClient.get()
			.uri("/mcp-inspector/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		assertThat(indexBody).isNotNull();
		assertThat(configBody).isNotNull();
		final Matcher m = BOOTSTRAP_PATTERN.matcher(indexBody);
		assertThat(m.find()).as("inline bootstrap JSON must be present").isTrue();
		final String inlineJson = m.group(1).replace("<\\/", "</");

		try {
			final JsonNode inline = this.objectMapper.readTree(inlineJson);
			final JsonNode config = this.objectMapper.readTree(configBody);
			assertThat(inline).isEqualTo(config);
		}
		catch (final Exception ex) {
			throw new AssertionError("failed to parse inline or config JSON: " + ex.getMessage(), ex);
		}
	}

	@Test
	@Story("Inline bootstrap")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A customizer-injected </script> payload is escaped to <\\/script> inside the inline JSON literal")
	void indexHtml_escapesClosingScriptTag() {
		// given a customizer injecting a dangerous </script> payload; when & then
		final String body = this.webTestClient.get()
			.uri("/mcp-inspector/index.html")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		final Matcher m = BOOTSTRAP_PATTERN.matcher(body);
		assertThat(m.find()).as("inline bootstrap block must still render").isTrue();
		final String literal = m.group(1);
		assertThat(literal).as("raw </script> must NOT appear inside the JSON literal").doesNotContain("</script>");
		assertThat(literal).as("the escape sequence <\\/ must be present (proof escaping ran)").contains("<\\/script>");
	}

	@TestConfiguration
	static class TestCustomizers {

		@Bean
		InspectorBootstrapCustomizer payloadInjectingCustomizer() {
			return (bootstrap) -> bootstrap.getExtra().put("dangerous", "</script><script>alert(1)</script>");
		}

	}

}
