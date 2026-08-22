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

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.webmvc.InspectorServerPortHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the WebMvc inspector starter using a real Spring AI MCP server
 * (SSE protocol) and a real loopback client through the inspector REST relay (driven by
 * {@link TestRestTemplate}).
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)}):
 *
 * <ul>
 * <li>{@code configEndpointReturnsDetectedTransport}</li>
 * <li>{@code jsonRpcRelayCallsToolsList}</li>
 * </ul>
 */
@Epic("WebMvc Inspector")
@Feature("Auto-configuration integration")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.server.name=mcp-inspector-itest",
		"spring.ai.mcp.server.version=0.1.0", "spring.ai.mcp.inspector.auth-enabled=false",
		"spring.application.name=mcp-inspector-itest" })
class WebMvcAutoConfigurationIT {

	/**
	 * Extracts the first hashed bundle asset URL from the served HTML. The hash changes
	 * on every UI build, so it is read back from the response instead of hardcoded.
	 */
	private static final Pattern ASSET_PATTERN = Pattern.compile("(?:src|href)=\"(/[^\"]*/assets/[^\"]+)\"");

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JsonMapper objectMapper;

	@Autowired
	private InspectorServerPortHolder portHolder;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("GET /config returns the detected SSE transport")
	@Story("Config endpoint")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The /config endpoint over a real random-port server reports the detected SSE transport and stack")
	void configEndpointReturnsDetectedTransport() throws Exception {
		// when
		final ResponseEntity<String> response = this.restTemplate.getForEntity(url("/mcp-inspector/api/config"),
				String.class);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		final JsonNode body = this.objectMapper.readTree(response.getBody());
		assertThat(body.path("transport").asText()).isEqualTo("SSE");
		assertThat(body.path("stack").asText()).isEqualTo("WEBMVC");
		assertThat(body.path("authToken").asText()).isNotBlank();
	}

	@Test
	@DisplayName("JSON-RPC tools/list relays through the proxy")
	@Story("JSON-RPC relay")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Opening a session via /connect then relaying tools/list returns the backing server's tool names")
	void jsonRpcRelayCallsToolsList() throws Exception {
		// Force the port holder to point at the random embedded-tomcat port,
		// otherwise the loopback factory falls back to the default 8080.
		setPort(this.portHolder, this.port);

		// 1) Open a session via /connect — inspector builds its own loopback MCP
		// client against the same embedded Tomcat.
		final HttpHeaders jsonHeaders = new HttpHeaders();
		jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
		final HttpEntity<String> connectEntity = new HttpEntity<>("{}", jsonHeaders);

		final ResponseEntity<String> connectResponse = this.restTemplate.exchange(url("/mcp-inspector/api/connect"),
				HttpMethod.POST, connectEntity, String.class);
		assertThat(connectResponse.getStatusCode()).as("connect: %s", connectResponse.getBody())
			.isEqualTo(HttpStatus.OK);
		final JsonNode connectBody = this.objectMapper.readTree(connectResponse.getBody());
		final String sessionId = connectBody.path("sessionId").asText(null);
		assertThat(sessionId).as("sessionId from /connect").isNotBlank();

		// 2) Relay JSON-RPC tools/list and validate the tool names.
		final Map<String, Object> relayPayload = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params",
				Map.of());
		final HttpEntity<String> relayEntity = new HttpEntity<>(this.objectMapper.writeValueAsString(relayPayload),
				jsonHeaders);

		final String jsonrpcUri = UriComponentsBuilder.fromUriString(url("/mcp-inspector/api/jsonrpc"))
			.queryParam("sessionId", sessionId)
			.build()
			.toUriString();

		final ResponseEntity<String> jsonRpcResponse = this.restTemplate.exchange(jsonrpcUri, HttpMethod.POST,
				relayEntity, String.class);

		assertThat(jsonRpcResponse.getStatusCode()).as("jsonrpc: %s", jsonRpcResponse.getBody())
			.isEqualTo(HttpStatus.OK);

		final JsonNode response = this.objectMapper.readTree(jsonRpcResponse.getBody());
		assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
		final JsonNode tools = response.path("result").path("tools");
		assertThat(tools.isArray()).as("result.tools should be an array, body=%s", response).isTrue();
		assertThat(tools.size()).isGreaterThanOrEqualTo(3);

		final List<String> names = new java.util.ArrayList<>();
		tools.forEach((t) -> names.add(t.path("name").asText()));
		assertThat(names).contains("echo", "sum", "currentTime");
	}

	@Test
	@DisplayName("hashed bundle assets are served long-lived, not no-store")
	@Story("Static asset caching")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A hashed asset referenced by index.html returns 200 with a body and a long max-age, so the multi-megabyte bundle survives a reload")
	void hashedAsset_isServedWithLongMaxAge() {
		// given
		final ResponseEntity<String> index = this.restTemplate.getForEntity(url("/mcp-inspector/index.html"),
				String.class);
		assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
		final Matcher matcher = ASSET_PATTERN.matcher(index.getBody());
		assertThat(matcher.find()).as("index.html must reference at least one hashed bundle asset").isTrue();

		// when
		final ResponseEntity<String> asset = this.restTemplate.getForEntity(url(matcher.group(1)), String.class);

		// then
		assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(asset.getBody()).as("the asset must not be served empty").isNotEmpty();
		assertThat(asset.getHeaders().getCacheControl()).as("hashed assets must be cacheable")
			.contains("max-age=604800");
	}

	@Test
	@DisplayName("POST /mcp with an unreachable upstream returns a structured 502")
	@Story("JSON-RPC relay")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Pointing the proxy at a closed port returns MCP_CONNECT_FAILED with reason connection_refused instead of a flat or timeout error")
	void postMcp_unreachableUpstream_returnsStructured502() throws Exception {
		// given — a loopback port that nothing listens on, so the very first
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

		// when — the browser-facing POST reaches the proxy, the upstream refuses
		final ResponseEntity<String> response = this.restTemplate.exchange(uri, HttpMethod.POST, entity, String.class);

		// then — non-2xx, machine-readable reason, no stack traces in the body
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

	private static void setPort(final InspectorServerPortHolder holder, final int port) throws Exception {
		final java.lang.reflect.Method m = InspectorServerPortHolder.class.getDeclaredMethod("setPort", int.class);
		m.setAccessible(true);
		m.invoke(holder, port);
	}

}
