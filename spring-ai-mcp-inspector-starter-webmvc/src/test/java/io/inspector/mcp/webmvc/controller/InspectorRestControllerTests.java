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

package io.inspector.mcp.webmvc.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.client.InspectorClientHandlers;
import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.dto.ConfigDto;
import io.inspector.mcp.core.dto.JsonRpcRelay;
import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.dto.RootsDto;
import io.inspector.mcp.core.introspect.model.IntrospectionReport;
import io.inspector.mcp.core.introspect.model.SchemaWarning;
import io.inspector.mcp.core.introspect.model.WarningCode;
import io.inspector.mcp.core.oauth.InspectorOAuthClient;
import io.inspector.mcp.core.oauth.OAuthInitiateRequest;
import io.inspector.mcp.core.oauth.OAuthInitiateResponse;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;
import io.inspector.mcp.webmvc.sse.InspectorSseEmitterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link InspectorRestController}, the REST surface backing the inspector
 * SPA. Collaborators are mocked; assertions cover {@code ResponseEntity} status / body,
 * JSON-RPC dispatch mapping, error branches and delegation to the session map.
 */
@Epic("WebMvc Inspector")
@Feature("InspectorRestController")
class InspectorRestControllerTests {

	private McpInspectorProperties properties;

	private TransportDetector transportDetector;

	private LoopbackMcpClientFactory loopbackFactory;

	private InspectorAuthTokenProvider authTokenProvider;

	private InspectorSseEmitterRegistry emitterRegistry;

	private InspectorServerPortHolder portHolder;

	private JsonMapper objectMapper;

	private InspectorOAuthClient oauthClient;

	private InspectorRestController controller;

	@BeforeEach
	void setUp() {
		this.properties = new McpInspectorProperties();
		this.properties.setAuthToken("fixed-token-for-tests");
		this.transportDetector = mock(TransportDetector.class);
		this.loopbackFactory = mock(LoopbackMcpClientFactory.class);
		this.authTokenProvider = new InspectorAuthTokenProvider(this.properties);
		this.emitterRegistry = mock(InspectorSseEmitterRegistry.class);
		this.portHolder = mock(InspectorServerPortHolder.class);
		this.objectMapper = new JsonMapper();
		this.oauthClient = mock(InspectorOAuthClient.class);
		this.controller = new InspectorRestController(this.properties, this.transportDetector, this.loopbackFactory,
				this.authTokenProvider, this.emitterRegistry, this.portHolder, this.objectMapper, this.oauthClient,
				"demo-server");
	}

	/** Registers a session with a mocked client and returns the session id. */
	private String registerSession(final McpSyncClient client) {
		final String sessionId = "session-" + System.nanoTime();
		this.controller.sessions().put(sessionId, new SessionState(client));
		return sessionId;
	}

	@Nested
	@DisplayName("config()")
	class Config {

		@Test
		@Story("Config endpoint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("config() maps the detected transport, server name, version and auth token into ConfigDto")
		void config_withDetectedTransport_returnsMappedConfigDto() {
			// given
			given(InspectorRestControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBMVC"));

			// when
			final ConfigDto dto = InspectorRestControllerTests.this.controller.config();

			// then
			assertThat(dto.transport()).isEqualTo("SSE");
			assertThat(dto.endpoint()).isEqualTo("/sse");
			assertThat(dto.messageEndpoint()).isEqualTo("/mcp/message");
			assertThat(dto.stack()).isEqualTo("WEBMVC");
			assertThat(dto.serverName()).isEqualTo("demo-server");
			assertThat(dto.version()).isEqualTo("0.1.0");
			assertThat(dto.authToken()).isEqualTo("fixed-token-for-tests");
		}

	}

	@Nested
	@DisplayName("connect()")
	class Connect {

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("connect() dials the detected SSE endpoint, deployment prefix included, and returns the new session id")
		void connect_withSseTransport_initializesClientAndReturnsSessionId() {
			// given — the detected endpoints already carry the context path
			given(InspectorRestControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/app/sse", "/app/mcp/message", "WEBMVC"));
			given(InspectorRestControllerTests.this.portHolder.port()).willReturn(1234);
			final McpSyncClient client = mock(McpSyncClient.class);
			given(InspectorRestControllerTests.this.loopbackFactory.forSse(eq("127.0.0.1"), eq(1234), eq("/app/sse"),
					any()))
				.willReturn(client);

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.connect(null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).containsKey("sessionId");
			verify(client).initialize();
			assertThat(InspectorRestControllerTests.this.controller.sessions()).hasSize(1);
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() returns 400 when the transport has no loopback support (stdio/unknown)")
		void connect_withStdioTransport_returns400() {
			// given
			given(InspectorRestControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STDIO_NO_HTTP, null, null, "STDIO"));
			given(InspectorRestControllerTests.this.portHolder.port()).willReturn(1234);

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.connect(null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody().get("error").toString()).contains("Loopback connection unsupported");
			assertThat(InspectorRestControllerTests.this.controller.sessions()).isEmpty();
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() returns 500 when the loopback factory throws while building the client")
		void connect_whenFactoryThrows_returns500() {
			// given
			given(InspectorRestControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));
			given(InspectorRestControllerTests.this.portHolder.port()).willReturn(1234);
			given(InspectorRestControllerTests.this.loopbackFactory.forStreamable(anyString(), anyInt(), anyString(),
					any()))
				.willThrow(new RuntimeException("boom"));

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.connect(null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(response.getBody().get("error").toString()).contains("Failed to build loopback client");
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() returns 500 and closes the client when initialize() fails")
		void connect_whenInitializeFails_returns500AndClosesClient() {
			// given
			given(InspectorRestControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STATELESS, "/mcp", null, "WEBMVC"));
			given(InspectorRestControllerTests.this.portHolder.port()).willReturn(1234);
			final McpSyncClient client = mock(McpSyncClient.class);
			given(InspectorRestControllerTests.this.loopbackFactory.forStateless(anyString(), anyInt(), anyString(),
					any()))
				.willReturn(client);
			willThrow(new RuntimeException("init failed")).given(client).initialize();

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.connect(null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(response.getBody().get("error").toString()).contains("initialize() failed");
			verify(client).close();
			assertThat(InspectorRestControllerTests.this.controller.sessions()).isEmpty();
		}

	}

	@Nested
	@DisplayName("jsonrpc()")
	class JsonRpc {

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.CRITICAL)
		@Description("jsonrpc() dispatches tools/list to the client and wraps the result in a JSON-RPC envelope")
		void jsonrpc_withToolsList_returnsResultEnvelope() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final McpSchema.ListToolsResult toolsResult = mock(McpSchema.ListToolsResult.class);
			given(client.listTools()).willReturn(toolsResult);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 7, "tools/list", Map.of());

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).containsEntry("jsonrpc", "2.0").containsEntry("id", 7);
			assertThat(response.getBody().get("result")).isSameAs(toolsResult);
			verify(client).listTools();
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.CRITICAL)
		@Description("jsonrpc() dispatches initialize to the client's cached initialization result")
		void jsonrpc_withInitialize_returnsCurrentInitializationResult() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final McpSchema.InitializeResult initResult = mock(McpSchema.InitializeResult.class);
			given(client.getCurrentInitializationResult()).willReturn(initResult);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 9, "initialize", Map.of());

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody().get("result")).isSameAs(initResult);
			verify(client).getCurrentInitializationResult();
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonrpc() routes tools/call into a CallToolRequest with the supplied name and arguments")
		void jsonrpc_withToolsCall_buildsCallToolRequest() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final McpSchema.CallToolResult callResult = mock(McpSchema.CallToolResult.class);
			given(client.callTool(any(McpSchema.CallToolRequest.class))).willReturn(callResult);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "tools/call",
					Map.of("name", "echo", "arguments", Map.of("msg", "hi")));

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody().get("result")).isSameAs(callResult);
			verify(client).callTool(ArgumentMatchers.argThat((req) -> "echo".equals(req.name())));
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonrpc() with an unknown sessionId returns 404 with a JSON-RPC error body")
		void jsonrpc_withUnknownSession_returns404WithError() {
			// given
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "tools/list", Map.of());

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc("nope", relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
			@SuppressWarnings("unchecked")
			final Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
			assertThat(error.get("message").toString()).contains("Unknown sessionId");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonrpc() returns a method-not-found error (200) for an unrecognised method")
		void jsonrpc_withUnknownMethod_returnsMethodNotFoundError() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "does/notExist", Map.of());

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			@SuppressWarnings("unchecked")
			final Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
			assertThat(error.get("code")).isEqualTo(-32601);
			assertThat(error.get("message").toString()).contains("Unknown JSON-RPC method");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonrpc() maps a client exception to an internal-error JSON-RPC envelope")
		void jsonrpc_whenClientThrows_returnsInternalError() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			given(client.listResources()).willThrow(new RuntimeException("kaboom"));
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "resources/list", Map.of());

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			@SuppressWarnings("unchecked")
			final Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
			assertThat(error.get("code")).isEqualTo(-32603);
			assertThat(error.get("message").toString()).contains("kaboom");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() ping returns an empty map result when the client ping returns null")
		void jsonrpc_withPingReturningNull_returnsEmptyMap() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			given(client.ping()).willReturn(null);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "ping", null);

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody().get("result")).isEqualTo(Map.of());
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() logging/setLevel parses the level and forwards it to the client")
		void jsonrpc_withSetLoggingLevel_callsClientWithParsedLevel() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "logging/setLevel", Map.of("level", "debug"));

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			verify(client).setLoggingLevel(McpSchema.LoggingLevel.DEBUG);
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() with a null method yields a method-not-found error")
		void jsonrpc_withNullMethod_returnsMethodNotFound() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, null, null);

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			@SuppressWarnings("unchecked")
			final Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
			assertThat(error.get("code")).isEqualTo(-32601);
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() resources/read maps the uri into a ReadResourceRequest")
		void jsonrpc_withResourcesRead_buildsReadResourceRequest() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final McpSchema.ReadResourceResult result = mock(McpSchema.ReadResourceResult.class);
			given(client.readResource(any(McpSchema.ReadResourceRequest.class))).willReturn(result);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "resources/read", Map.of("uri", "file:///a"));

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody().get("result")).isSameAs(result);
			verify(client).readResource(
					ArgumentMatchers.<McpSchema.ReadResourceRequest>argThat((req) -> "file:///a".equals(req.uri())));
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() prompts/list delegates to the client")
		void jsonrpc_withPromptsList_delegatesToClient() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final McpSchema.ListPromptsResult result = mock(McpSchema.ListPromptsResult.class);
			given(client.listPrompts()).willReturn(result);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "prompts/list", Map.of());

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getBody().get("result")).isSameAs(result);
			verify(client).listPrompts();
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() prompts/get with null arguments uses an empty argument map")
		void jsonrpc_withPromptsGetNullArgs_buildsRequestWithEmptyArgs() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final McpSchema.GetPromptResult result = mock(McpSchema.GetPromptResult.class);
			given(client.getPrompt(any(McpSchema.GetPromptRequest.class))).willReturn(result);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "prompts/get",
					new java.util.HashMap<>(Map.of("name", "p")));

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getBody().get("result")).isSameAs(result);
			verify(client).getPrompt(ArgumentMatchers.argThat((req) -> "p".equals(req.name())));
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() resources/list delegates to the client")
		void jsonrpc_withResourcesList_delegatesToClient() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final McpSchema.ListResourcesResult result = mock(McpSchema.ListResourcesResult.class);
			given(client.listResources()).willReturn(result);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "resources/list", null);

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getBody().get("result")).isSameAs(result);
			verify(client).listResources();
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() roots/list returns the session's current roots")
		void jsonrpc_withRootsList_returnsSessionRoots() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "roots/list", null);

			// when
			final ResponseEntity<Map<String, Object>> response = InspectorRestControllerTests.this.controller
				.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			@SuppressWarnings("unchecked")
			final Map<String, Object> result = (Map<String, Object>) response.getBody().get("result");
			assertThat(result).containsKey("roots");
		}

	}

	@Nested
	@DisplayName("events()")
	class Events {

		@Test
		@Story("SSE channel")
		@Severity(SeverityLevel.NORMAL)
		@Description("events() delegates emitter registration to the SSE registry")
		void events_delegatesToEmitterRegistry() {
			// given
			given(InspectorRestControllerTests.this.emitterRegistry.register("s1")).willReturn(null);

			// when
			InspectorRestControllerTests.this.controller.events("s1");

			// then
			verify(InspectorRestControllerTests.this.emitterRegistry).register("s1");
		}

	}

	@Nested
	@DisplayName("deleteSession()")
	class DeleteSession {

		@Test
		@Story("Close session")
		@Severity(SeverityLevel.NORMAL)
		@Description("deleteSession() removes a known session, closes the emitter and returns 204")
		void deleteSession_whenKnown_removesSessionAndReturns204() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);

			// when
			final ResponseEntity<Void> response = InspectorRestControllerTests.this.controller.deleteSession(sessionId);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
			assertThat(InspectorRestControllerTests.this.controller.sessions()).isEmpty();
			verify(InspectorRestControllerTests.this.emitterRegistry).close(sessionId);
		}

		@Test
		@Story("Close session")
		@Severity(SeverityLevel.MINOR)
		@Description("deleteSession() on an unknown id still returns 204 and closes the emitter")
		void deleteSession_whenUnknown_returns204() {
			// when
			final ResponseEntity<Void> response = InspectorRestControllerTests.this.controller.deleteSession("unknown");

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
			verify(InspectorRestControllerTests.this.emitterRegistry).close("unknown");
		}

	}

	@Nested
	@DisplayName("getRoots() / putRoots()")
	class Roots {

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("getRoots() returns the empty roots envelope for a fresh session")
		void getRoots_withKnownSession_returnsRootsEnvelope() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.getRoots(sessionId);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isInstanceOf(RootsDto.class);
			assertThat(((RootsDto) response.getBody()).roots()).isEmpty();
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("getRoots() with an unknown session returns 404")
		void getRoots_withUnknownSession_returns404() {
			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.getRoots("unknown");

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("putRoots() replaces the session roots, notifies the client and broadcasts the change")
		void putRoots_withKnownSession_replacesRootsAndBroadcasts() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final RootsDto body = new RootsDto(List.of(new RootDto("file:///ws", "ws")));

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.putRoots(sessionId, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(((RootsDto) response.getBody()).roots()).hasSize(1);
			verify(client).addRoot(any(McpSchema.Root.class));
			verify(InspectorRestControllerTests.this.emitterRegistry).broadcast(eq(sessionId),
					eq("mcp:roots-list-changed"), any());
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("putRoots() with a null body treats it as an empty root list")
		void putRoots_withNullBody_replacesWithEmpty() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.putRoots(sessionId, null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(((RootsDto) response.getBody()).roots()).isEmpty();
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("putRoots() skips blank-uri roots when syncing them to the client")
		void putRoots_withBlankUriRoot_skipsAddRoot() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final RootsDto body = new RootsDto(List.of(new RootDto("", "blank")));

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.putRoots(sessionId, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			verify(client, never()).addRoot(any());
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("putRoots() still returns 200 when the client notification throws (no roots capability)")
		void putRoots_whenNotificationThrows_stillReturns200() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			willThrow(new RuntimeException("no roots capability")).given(client).rootsListChangedNotification();
			final String sessionId = registerSession(client);
			final RootsDto body = new RootsDto(List.of(new RootDto("file:///x", "x")));

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.putRoots(sessionId, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("putRoots() with an unknown session returns 404 without touching collaborators")
		void putRoots_withUnknownSession_returns404() {
			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.putRoots("unknown",
					new RootsDto(List.of()));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
			verify(InspectorRestControllerTests.this.emitterRegistry, never()).broadcast(anyString(), anyString(),
					any());
		}

	}

	@Nested
	@DisplayName("respond()")
	class Respond {

		@Test
		@Story("Server-request bridge")
		@Severity(SeverityLevel.NORMAL)
		@Description("respond() with a result completes a pending request and returns ok")
		void respond_withResult_completesPendingRequest() throws Exception {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final SessionState state = InspectorRestControllerTests.this.controller.sessions().get(sessionId);
			final var future = state.pendingServerRequests().create("req-1");
			final JsonNode body = InspectorRestControllerTests.this.objectMapper
				.readTree("{\"result\":{\"answer\":42}}");

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.respond(sessionId, "req-1",
					body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(future.get().path("answer").asInt()).isEqualTo(42);
		}

		@Test
		@Story("Server-request bridge")
		@Severity(SeverityLevel.NORMAL)
		@Description("respond() with an error payload completes the pending request exceptionally")
		void respond_withError_completesExceptionally() throws Exception {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final SessionState state = InspectorRestControllerTests.this.controller.sessions().get(sessionId);
			final var future = state.pendingServerRequests().create("req-err");
			final JsonNode body = InspectorRestControllerTests.this.objectMapper
				.readTree("{\"error\":{\"message\":\"nope\"}}");

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.respond(sessionId,
					"req-err", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(future.isCompletedExceptionally()).isTrue();
		}

		@Test
		@Story("Server-request bridge")
		@Severity(SeverityLevel.MINOR)
		@Description("respond() with a bare body (no result/error wrapper) completes the request with the whole body")
		void respond_withBareBody_completesWithBody() throws Exception {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final SessionState state = InspectorRestControllerTests.this.controller.sessions().get(sessionId);
			final var future = state.pendingServerRequests().create("req-bare");
			final JsonNode body = InspectorRestControllerTests.this.objectMapper.readTree("{\"answer\":7}");

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.respond(sessionId,
					"req-bare", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(future.get().path("answer").asInt()).isEqualTo(7);
		}

		@Test
		@Story("Server-request bridge")
		@Severity(SeverityLevel.MINOR)
		@Description("respond() returns 410 GONE when no pending request matches the requestId")
		void respond_withNoPendingRequest_returns410() throws Exception {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final JsonNode body = InspectorRestControllerTests.this.objectMapper.readTree("{\"result\":{}}");

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.respond(sessionId,
					"missing", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
		}

		@Test
		@Story("Server-request bridge")
		@Severity(SeverityLevel.MINOR)
		@Description("respond() with an unknown session returns 404")
		void respond_withUnknownSession_returns404() throws Exception {
			// given
			final JsonNode body = InspectorRestControllerTests.this.objectMapper.readTree("{\"result\":{}}");

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.respond("unknown", "req",
					body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

	}

	@Nested
	@DisplayName("oauthInitiate()")
	class OAuthInitiate {

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthInitiate() stores the OAuth context and returns the IdP authorization URL")
		void oauthInitiate_withKnownSession_returnsAuthUrlAndState() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final OAuthInitiateRequest req = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", "openid", "challenge");
			given(InspectorRestControllerTests.this.oauthClient.buildAuthUrl(eq("https://idp/authorize"),
					eq("client-1"), eq("https://app/cb"), eq("openid"), anyString(), eq("challenge")))
				.willReturn("https://idp/authorize?ok");

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.oauthInitiate(sessionId,
					req);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isInstanceOf(OAuthInitiateResponse.class);
			assertThat(((OAuthInitiateResponse) response.getBody()).authUrl()).isEqualTo("https://idp/authorize?ok");
			assertThat(((OAuthInitiateResponse) response.getBody()).state()).isNotBlank();
		}

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthInitiate() with an unknown session returns 404")
		void oauthInitiate_withUnknownSession_returns404() {
			// given
			final OAuthInitiateRequest req = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token", "c",
					"r", null, null);

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.oauthInitiate("unknown",
					req);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

	}

	@Nested
	@DisplayName("oauthCallback()")
	class OAuthCallback {

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthCallback() exchanges the code and caches the token when state matches")
		void oauthCallback_withMatchingState_returnsToken() throws Exception {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final SessionState state = InspectorRestControllerTests.this.controller.sessions().get(sessionId);
			state.oauthState("expected-state");
			state.oauthTokenEndpoint("https://idp/token");
			state.oauthClientId("client-1");
			state.oauthRedirectUri("https://app/cb");
			final OAuthTokenResponse token = new OAuthTokenResponse("access-123", "Bearer", 3600L, null, "openid");
			given(InspectorRestControllerTests.this.oauthClient.exchangeCode("https://idp/token", "client-1",
					"the-code", "https://app/cb", "verifier"))
				.willReturn(token);

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.oauthCallback(sessionId,
					"the-code", "expected-state", "verifier");

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isSameAs(token);
			assertThat(state.oauthToken()).isSameAs(token);
		}

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallback() returns 400 when the supplied state does not match the stored one")
		void oauthCallback_withStateMismatch_returns400() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			InspectorRestControllerTests.this.controller.sessions().get(sessionId).oauthState("expected-state");

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.oauthCallback(sessionId,
					"code", "wrong-state", null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallback() returns 502 BAD_GATEWAY when the token exchange throws")
		void oauthCallback_whenExchangeFails_returns502() throws Exception {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);
			final SessionState state = InspectorRestControllerTests.this.controller.sessions().get(sessionId);
			state.oauthState("st");
			given(InspectorRestControllerTests.this.oauthClient.exchangeCode(any(), any(), any(), any(), any()))
				.willThrow(new RuntimeException("idp down"));

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.oauthCallback(sessionId,
					"code", "st", null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		}

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallback() returns 400 when no OAuth state was ever stored on the session")
		void oauthCallback_withNoStoredState_returns400() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);

			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.oauthCallback(sessionId,
					"code", "any-state", null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallback() with an unknown session returns 404")
		void oauthCallback_withUnknownSession_returns404() {
			// when
			final ResponseEntity<?> response = InspectorRestControllerTests.this.controller.oauthCallback("unknown",
					"code", "st", null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

	}

	@Nested
	@DisplayName("server-request bridge (sampling / elicitation)")
	class ServerRequestBridge {

		@Test
		@Story("Server-request bridge")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a server sampling request is broadcast to the UI over SSE and resolved by /respond into a typed result")
		void samplingHandler_broadcastsRequestAndResolvesResult() throws Exception {
			// given — connect() to register the sampling/elicitation handlers
			given(InspectorRestControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBMVC"));
			given(InspectorRestControllerTests.this.portHolder.port()).willReturn(1234);
			final McpSyncClient client = mock(McpSyncClient.class);
			final ArgumentCaptor<InspectorClientHandlers> handlersCaptor = ArgumentCaptor
				.forClass(InspectorClientHandlers.class);
			given(InspectorRestControllerTests.this.loopbackFactory.forSse(anyString(), anyInt(), anyString(),
					handlersCaptor.capture()))
				.willReturn(client);

			final ResponseEntity<Map<String, Object>> connectResponse = InspectorRestControllerTests.this.controller
				.connect(null);
			final String sessionId = (String) connectResponse.getBody().get("sessionId");
			final InspectorClientHandlers handlers = handlersCaptor.getValue();

			final ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

			// when — invoke the sampling handler on a worker thread (it blocks on the
			// pending future until the UI answers via /respond). The request payload is
			// only serialized into the SSE envelope, so a null request is sufficient.
			final var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
			final var samplingFuture = pool.submit(() -> handlers.sampling().apply(null));

			// Wait for the SSE broadcast, learn the generated requestId, then answer.
			verify(InspectorRestControllerTests.this.emitterRegistry, org.mockito.Mockito.timeout(2000))
				.broadcast(eq(sessionId), eq("mcp:sampling-request"), payloadCaptor.capture());
			final JsonNode envelope = InspectorRestControllerTests.this.objectMapper
				.valueToTree(payloadCaptor.getValue());
			final String requestId = envelope.path("requestId").asText();
			assertThat(requestId).isNotBlank();
			final JsonNode result = InspectorRestControllerTests.this.objectMapper
				.readTree("{\"result\":{\"role\":\"assistant\","
						+ "\"content\":{\"type\":\"text\",\"text\":\"ok\"},\"model\":\"m\"}}");

			// then — /respond hands the result back; the blocked handler returns it
			// mapped
			InspectorRestControllerTests.this.controller.respond(sessionId, requestId, result);
			final McpSchema.CreateMessageResult mapped = samplingFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
			assertThat(mapped).isNotNull();
			assertThat(mapped.model()).isEqualTo("m");
			pool.shutdownNow();
		}

	}

	@Nested
	@DisplayName("sessions()")
	class Sessions {

		@Test
		@Story("Session registry")
		@Severity(SeverityLevel.MINOR)
		@Description("sessions() exposes the live session map for tests/shutdown hooks")
		void sessions_returnsLiveSessionMap() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			final String sessionId = registerSession(client);

			// when
			final ConcurrentMap<String, SessionState> map = InspectorRestControllerTests.this.controller.sessions();

			// then
			assertThat(map).containsKey(sessionId);
		}

		@Test
		@Story("Session registry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("ContextClosedEvent releases every live session and every SSE emitter, so Boot's "
				+ "graceful shutdown does not wait on them")
		void onContextClosed_closesEverySessionAndEmitter() {
			// given
			final McpSyncClient client = mock(McpSyncClient.class);
			registerSession(client);
			registerSession(mock(McpSyncClient.class));

			// when
			InspectorRestControllerTests.this.controller
				.onContextClosed(new org.springframework.context.event.ContextClosedEvent(
						new org.springframework.context.support.StaticApplicationContext()));

			// then
			assertThat(InspectorRestControllerTests.this.controller.sessions()).isEmpty();
			verify(client).closeGracefully();
			verify(InspectorRestControllerTests.this.emitterRegistry).closeAll();
		}

	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("constructor substitutes a default OAuth client when none is supplied")
		void constructor_withNullOAuthClient_usesDefault() {
			// given / when
			final InspectorRestController c = new InspectorRestController(InspectorRestControllerTests.this.properties,
					InspectorRestControllerTests.this.transportDetector,
					InspectorRestControllerTests.this.loopbackFactory,
					InspectorRestControllerTests.this.authTokenProvider,
					InspectorRestControllerTests.this.emitterRegistry, InspectorRestControllerTests.this.portHolder,
					InspectorRestControllerTests.this.objectMapper, null, "demo-server");

			// then
			assertThat(c).isNotNull();
			assertThat(c.sessions()).isEmpty();
		}

	}

	@Nested
	@DisplayName("introspection()")
	class Introspection {

		@Test
		@Story("Introspection endpoint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("introspection() reports every registered tool/resource with a non-null source and a warnings list")
		void introspectionEndpoint_returnsContract() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					IntrospectionToolConfig.class);
			InspectorRestControllerTests.this.controller.setApplicationContext(context);

			// when
			final IntrospectionReport report = InspectorRestControllerTests.this.controller.introspection();

			// then
			assertThat(report.tools()).singleElement().satisfies((tool) -> {
				assertThat(tool.name()).isEqualTo("introspectionTool");
				assertThat(tool.inputSchema()).isNotNull();
				assertThat(tool.source()).isNotNull();
				assertThat(tool.source().registered()).isTrue();
			});
			assertThat(report.resources()).isEmpty();
			assertThat(report.warnings()).isEmpty();
		}

		@Test
		@Story("Empty context")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a context without MCP elements yields NO_MCP_ELEMENTS and empty lists, never an error")
		void emptyContext_noMcpElements() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
			context.refresh();
			InspectorRestControllerTests.this.controller.setApplicationContext(context);

			// when
			final IntrospectionReport report = InspectorRestControllerTests.this.controller.introspection();

			// then
			assertThat(report.tools()).isEmpty();
			assertThat(report.resources()).isEmpty();
			assertThat(report.warnings()).extracting(SchemaWarning::code).contains(WarningCode.NO_MCP_ELEMENTS);
		}

	}

	@Configuration
	static class IntrospectionToolConfig {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
			final Map<String, Object> inputSchema = Map.of("type", "object", "properties",
					Map.of("message", Map.of("type", "string")));
			final McpSchema.Tool tool = McpSchema.Tool.builder("introspectionTool", inputSchema)
				.description("Introspection test tool")
				.build();
			return List.of(McpServerFeatures.SyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange, request) -> McpSchema.CallToolResult.builder(List.of()).build())
				.build());
		}

	}

}
