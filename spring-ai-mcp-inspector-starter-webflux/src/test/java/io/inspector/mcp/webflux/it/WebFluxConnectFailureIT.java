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

import java.net.InetAddress;
import java.net.ServerSocket;

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
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the WebFlux proxy connect-failure path: pointing the inspector at
 * an unreachable MCP server must return a structured {@code MCP_CONNECT_FAILED} JSON
 * payload with the machine-readable reason instead of a flat error string.
 *
 * <p>
 * Mirrors
 * {@code WebMvcAutoConfigurationIT.postMcp_unreachableUpstream_returnsStructured502}. The
 * unreachable target is a freshly-allocated closed loopback port (same trick): the very
 * first connect attempt is refused, and no other process can claim the port while the
 * test runs.
 */
@Epic("MCP Inspector WebFlux")
@Feature("Proxy connect failure (integration)")
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-connect", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-flux-connect" })
class WebFluxConnectFailureIT {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@Story("Connect failure")
	@Severity(SeverityLevel.CRITICAL)
	@Description("POST /mcp with an unreachable upstream returns a structured 502 MCP_CONNECT_FAILED with reason connection_refused")
	@DisplayName("postMcp_unreachableUpstream_returnsStructured502")
	void postMcp_unreachableUpstream_returnsStructured502() throws Exception {
		// given: a loopback port that nothing listens on, so the very first
		// connect attempt is refused
		final int deadPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			deadPort = socket.getLocalPort();
		}
		final HttpHeaders jsonHeaders = new HttpHeaders();
		jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
		final HttpEntity<String> entity = new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}",
				jsonHeaders);
		final String uri = UriComponentsBuilder.fromUriString(url("/mcp-inspector-api/mcp"))
			.queryParam("url", "http://127.0.0.1:" + deadPort + "/mcp")
			.build()
			.toUriString();

		// when: the browser-facing POST reaches the proxy, the upstream refuses
		final ResponseEntity<String> response = this.restTemplate.exchange(uri, HttpMethod.POST, entity, String.class);

		// then: non-2xx, machine-readable reason, no stack traces in the body
		assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.BAD_GATEWAY);
		final JsonNode body = this.objectMapper.readTree(response.getBody());
		final JsonNode error = body.path("error");
		assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
		assertThat(error.path("reason").asText()).isEqualTo("connection_refused");
		assertThat(error.path("message").asText()).isNotBlank();
		assertThat(error.path("retryable").asBoolean()).isTrue();
	}

	private String url(final String path) {
		return "http://localhost:" + this.port + path;
	}

}
