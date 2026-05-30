/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.it;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.bootstrap.InspectorBootstrapCustomizer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT verifying the inline
 * {@code <script>window.__MCP_INSPECTOR_BOOTSTRAP = ...;</script>} block injected into
 * {@code /mcp-inspector/index.html}.
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)} — /index.html
 * inline bootstrap):
 * <ul>
 * <li>{@code indexHtml_containsBootstrapWindowGlobal}</li>
 * <li>{@code indexHtml_bootstrapJsonMatchesConfigEndpoint}</li>
 * <li>{@code indexHtml_escapesClosingScriptTag}</li>
 * </ul>
 */
@SpringBootTest(classes = { TestMcpServerApp.class, WebMvcBootstrapInlineIT.TestCustomizers.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.server.name=mcp-inspector-itest-inline",
				"spring.ai.mcp.server.version=0.1.0", "spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-inline" })
class WebMvcBootstrapInlineIT {

	/**
	 * Regex extracting the JSON literal from the
	 * {@code <script>window.__MCP_INSPECTOR_BOOTSTRAP = {...};</script>} block. Captures
	 * everything between {@code =} and the terminating {@code ;</script>} so the body can
	 * be re-parsed by Jackson.
	 */
	private static final Pattern BOOTSTRAP_PATTERN = Pattern
		.compile("window\\.__MCP_INSPECTOR_BOOTSTRAP\\s*=\\s*(\\{.*?\\});</script>", Pattern.DOTALL);

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	@Test
	void indexHtml_containsBootstrapWindowGlobal() {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/mcp-inspector/index.html"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).as("index.html must contain the inline bootstrap window global")
			.contains("window.__MCP_INSPECTOR_BOOTSTRAP =");
	}

	@Test
	void indexHtml_bootstrapJsonMatchesConfigEndpoint() throws Exception {
		ResponseEntity<String> indexResponse = restTemplate.getForEntity(url("/mcp-inspector/index.html"),
				String.class);
		ResponseEntity<String> configResponse = restTemplate.getForEntity(url("/mcp-inspector/config"), String.class);
		assertThat(indexResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(configResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

		Matcher m = BOOTSTRAP_PATTERN.matcher(indexResponse.getBody());
		assertThat(m.find()).as("inline bootstrap JSON must be present").isTrue();
		// Reverse the </ -> <\/ escape applied by BootstrapHtmlRenderer so the
		// recovered JSON parses cleanly.
		String inlineJson = m.group(1).replace("<\\/", "</");

		JsonNode inline = objectMapper.readTree(inlineJson);
		JsonNode config = objectMapper.readTree(configResponse.getBody());
		assertThat(inline).as("inline bootstrap JSON must deep-equal /config response").isEqualTo(config);
	}

	@Test
	void indexHtml_escapesClosingScriptTag() {
		ResponseEntity<String> response = restTemplate.getForEntity(url("/mcp-inspector/index.html"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String body = response.getBody();
		assertThat(body).isNotNull();

		// The customizer below puts "</script>" into bootstrap.extra. The renderer
		// must escape "</" sequences so the surrounding <script> element isn't
		// prematurely closed. After escaping, no spurious "</script>" can appear
		// BEFORE the renderer's own closing tag.
		Matcher m = BOOTSTRAP_PATTERN.matcher(body);
		assertThat(m.find()).as("inline bootstrap block must still render").isTrue();
		String literal = m.group(1);

		assertThat(literal).as("raw </script> must NOT appear inside the JSON literal").doesNotContain("</script>");
		assertThat(literal).as("the escape sequence <\\/ must be present (proof escaping ran)").contains("<\\/script>");
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	@TestConfiguration
	static class TestCustomizers {

		@Bean
		InspectorBootstrapCustomizer payloadInjectingCustomizer() {
			return bootstrap -> bootstrap.getExtra().put("dangerous", "</script><script>alert(1)</script>");
		}

	}

}
