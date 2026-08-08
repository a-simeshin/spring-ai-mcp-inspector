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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.inspector.mcp.webmvc.InspectorServerPortHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT proving the inspector's own loopback client dials the SSE server where it actually
 * listens when the application is mounted under a context path — the SSE endpoint is
 * {@code /app/sse}, not the container root {@code /sse}.
 */
@Epic("WebMvc Inspector")
@Feature("Context path support")
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "server.servlet.context-path=/app", "spring.ai.mcp.server.protocol=SSE",
				// Spring AI advertises the message endpoint as base-url +
				// sse-message-endpoint;
				// under a context path the operator must point base-url at that prefix
				"spring.ai.mcp.server.base-url=/app", "spring.ai.mcp.server.name=mcp-inspector-itest-ctxpath-sse",
				"spring.ai.mcp.server.version=0.1.0", "spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-ctxpath-sse" })
class WebMvcContextPathSseIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private InspectorServerPortHolder portHolder;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("the loopback SSE client connects under the context path")
	@Story("Loopback client")
	@Severity(SeverityLevel.CRITICAL)
	@Description("POST ${contextPath}${path}/api/connect opens a session against the context-path-mounted SSE endpoint")
	void connect_withSseServerUnderContextPath_opensSession() throws Exception {
		// given — the port holder must point at the random embedded-tomcat port,
		// otherwise the loopback factory falls back to the default 8080
		setPort(this.portHolder, this.port);
		final HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		// when
		final ResponseEntity<String> response = this.restTemplate.exchange(url("/app/mcp-inspector/api/connect"),
				HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);

		// then
		assertThat(response.getStatusCode()).as("connect: %s", response.getBody()).isEqualTo(HttpStatus.OK);
		final JsonNode body = this.objectMapper.readTree(response.getBody());
		assertThat(body.path("sessionId").asText(null)).as("sessionId from /connect").isNotBlank();
	}

	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

	private static void setPort(final InspectorServerPortHolder holder, final int serverPort) throws Exception {
		final java.lang.reflect.Method setter = InspectorServerPortHolder.class.getDeclaredMethod("setPort", int.class);
		setter.setAccessible(true);
		setter.invoke(holder, serverPort);
	}

}
