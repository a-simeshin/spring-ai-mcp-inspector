/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.client.InspectorClientHandlers;
import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.dto.ConfigDto;
import io.inspector.mcp.core.dto.JsonRpcRelay;
import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.dto.RootsDto;
import io.inspector.mcp.core.oauth.InspectorOAuthClient;
import io.inspector.mcp.core.oauth.OAuthInitiateRequest;
import io.inspector.mcp.core.oauth.OAuthInitiateResponse;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;
import io.inspector.mcp.webmvc.sse.InspectorSseEmitterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InspectorRestController}, the REST surface backing the inspector
 * SPA. Collaborators are mocked; assertions cover {@code ResponseEntity} status / body,
 * JSON-RPC dispatch mapping, error branches and delegation to the session map.
 */
@Epic("WebMvc Inspector")
@Feature("InspectorRestController")
class InspectorRestControllerTest {

	private McpInspectorProperties properties;

	private TransportDetector transportDetector;

	private LoopbackMcpClientFactory loopbackFactory;

	private InspectorAuthTokenProvider authTokenProvider;

	private InspectorSseEmitterRegistry emitterRegistry;

	private InspectorServerPortHolder portHolder;

	private ObjectMapper objectMapper;

	private InspectorOAuthClient oauthClient;

	private InspectorRestController controller;

	@BeforeEach
	void setUp() {
		properties = new McpInspectorProperties();
		properties.setAuthToken("fixed-token-for-tests");
		transportDetector = mock(TransportDetector.class);
		loopbackFactory = mock(LoopbackMcpClientFactory.class);
		authTokenProvider = new InspectorAuthTokenProvider(properties);
		emitterRegistry = mock(InspectorSseEmitterRegistry.class);
		portHolder = mock(InspectorServerPortHolder.class);
		objectMapper = new ObjectMapper();
		oauthClient = mock(InspectorOAuthClient.class);
		controller = new InspectorRestController(properties, transportDetector, loopbackFactory, authTokenProvider,
				emitterRegistry, portHolder, objectMapper, oauthClient, "demo-server");
	}

	/** Registers a session with a mocked client and returns the session id. */
	private String registerSession(McpSyncClient client) {
		String sessionId = "session-" + System.nanoTime();
		controller.sessions().put(sessionId, new SessionState(client));
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
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBMVC"));

			// when
			ConfigDto dto = controller.config();

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
		@Description("connect() builds and initializes a loopback client and returns the new session id")
		void connect_withSseTransport_initializesClientAndReturnsSessionId() {
			// given
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBMVC"));
			when(portHolder.port()).thenReturn(1234);
			McpSyncClient client = mock(McpSyncClient.class);
			when(loopbackFactory.forSse(eq("127.0.0.1"), eq(1234), eq(""), eq("/mcp/message"), any()))
				.thenReturn(client);

			// when
			ResponseEntity<Map<String, Object>> response = controller.connect(null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).containsKey("sessionId");
			verify(client).initialize();
			assertThat(controller.sessions()).hasSize(1);
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() returns 400 when the transport has no loopback support (stdio/unknown)")
		void connect_withStdioTransport_returns400() {
			// given
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STDIO_NO_HTTP, null, null, "STDIO"));
			when(portHolder.port()).thenReturn(1234);

			// when
			ResponseEntity<Map<String, Object>> response = controller.connect(null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody().get("error").toString()).contains("Loopback connection unsupported");
			assertThat(controller.sessions()).isEmpty();
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() returns 500 when the loopback factory throws while building the client")
		void connect_whenFactoryThrows_returns500() {
			// given
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));
			when(portHolder.port()).thenReturn(1234);
			when(loopbackFactory.forStreamable(anyString(), anyInt(), anyString(), any()))
				.thenThrow(new RuntimeException("boom"));

			// when
			ResponseEntity<Map<String, Object>> response = controller.connect(null);

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
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STATELESS, "/mcp", null, "WEBMVC"));
			when(portHolder.port()).thenReturn(1234);
			McpSyncClient client = mock(McpSyncClient.class);
			when(loopbackFactory.forStateless(anyString(), anyInt(), anyString(), any())).thenReturn(client);
			doThrow(new RuntimeException("init failed")).when(client).initialize();

			// when
			ResponseEntity<Map<String, Object>> response = controller.connect(null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(response.getBody().get("error").toString()).contains("initialize() failed");
			verify(client).close();
			assertThat(controller.sessions()).isEmpty();
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
			McpSyncClient client = mock(McpSyncClient.class);
			McpSchema.ListToolsResult toolsResult = mock(McpSchema.ListToolsResult.class);
			when(client.listTools()).thenReturn(toolsResult);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 7, "tools/list", Map.of());

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).containsEntry("jsonrpc", "2.0").containsEntry("id", 7);
			assertThat(response.getBody().get("result")).isSameAs(toolsResult);
			verify(client).listTools();
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonrpc() routes tools/call into a CallToolRequest with the supplied name and arguments")
		void jsonrpc_withToolsCall_buildsCallToolRequest() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			McpSchema.CallToolResult callResult = mock(McpSchema.CallToolResult.class);
			when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(callResult);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "tools/call",
					Map.of("name", "echo", "arguments", Map.of("msg", "hi")));

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody().get("result")).isSameAs(callResult);
			verify(client).callTool(ArgumentMatchers.argThat(req -> "echo".equals(req.name())));
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonrpc() with an unknown sessionId returns 404 with a JSON-RPC error body")
		void jsonrpc_withUnknownSession_returns404WithError() {
			// given
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "tools/list", Map.of());

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc("nope", relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
			@SuppressWarnings("unchecked")
			Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
			assertThat(error.get("message").toString()).contains("Unknown sessionId");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonrpc() returns a method-not-found error (200) for an unrecognised method")
		void jsonrpc_withUnknownMethod_returnsMethodNotFoundError() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "does/notExist", Map.of());

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			@SuppressWarnings("unchecked")
			Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
			assertThat(error.get("code")).isEqualTo(-32601);
			assertThat(error.get("message").toString()).contains("Unknown JSON-RPC method");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonrpc() maps a client exception to an internal-error JSON-RPC envelope")
		void jsonrpc_whenClientThrows_returnsInternalError() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			when(client.listResources()).thenThrow(new RuntimeException("kaboom"));
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "resources/list", Map.of());

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			@SuppressWarnings("unchecked")
			Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
			assertThat(error.get("code")).isEqualTo(-32603);
			assertThat(error.get("message").toString()).contains("kaboom");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() ping returns an empty map result when the client ping returns null")
		void jsonrpc_withPingReturningNull_returnsEmptyMap() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			when(client.ping()).thenReturn(null);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "ping", null);

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "logging/setLevel", Map.of("level", "debug"));

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, null, null);

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			@SuppressWarnings("unchecked")
			Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
			assertThat(error.get("code")).isEqualTo(-32601);
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() resources/read maps the uri into a ReadResourceRequest")
		void jsonrpc_withResourcesRead_buildsReadResourceRequest() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			McpSchema.ReadResourceResult result = mock(McpSchema.ReadResourceResult.class);
			when(client.readResource(any(McpSchema.ReadResourceRequest.class))).thenReturn(result);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "resources/read", Map.of("uri", "file:///a"));

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody().get("result")).isSameAs(result);
			verify(client).readResource(
					ArgumentMatchers.<McpSchema.ReadResourceRequest>argThat(req -> "file:///a".equals(req.uri())));
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() prompts/list delegates to the client")
		void jsonrpc_withPromptsList_delegatesToClient() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			McpSchema.ListPromptsResult result = mock(McpSchema.ListPromptsResult.class);
			when(client.listPrompts()).thenReturn(result);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "prompts/list", Map.of());

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

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
			McpSyncClient client = mock(McpSyncClient.class);
			McpSchema.GetPromptResult result = mock(McpSchema.GetPromptResult.class);
			when(client.getPrompt(any(McpSchema.GetPromptRequest.class))).thenReturn(result);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "prompts/get",
					new java.util.HashMap<>(Map.of("name", "p")));

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getBody().get("result")).isSameAs(result);
			verify(client).getPrompt(ArgumentMatchers.argThat(req -> "p".equals(req.name())));
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.MINOR)
		@Description("jsonrpc() resources/list delegates to the client")
		void jsonrpc_withResourcesList_delegatesToClient() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			McpSchema.ListResourcesResult result = mock(McpSchema.ListResourcesResult.class);
			when(client.listResources()).thenReturn(result);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "resources/list", null);

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			JsonRpcRelay relay = new JsonRpcRelay("2.0", 1, "roots/list", null);

			// when
			ResponseEntity<Map<String, Object>> response = controller.jsonrpc(sessionId, relay);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			@SuppressWarnings("unchecked")
			Map<String, Object> result = (Map<String, Object>) response.getBody().get("result");
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
			when(emitterRegistry.register("s1")).thenReturn(null);

			// when
			controller.events("s1");

			// then
			verify(emitterRegistry).register("s1");
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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);

			// when
			ResponseEntity<Void> response = controller.deleteSession(sessionId);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
			assertThat(controller.sessions()).isEmpty();
			verify(emitterRegistry).close(sessionId);
		}

		@Test
		@Story("Close session")
		@Severity(SeverityLevel.MINOR)
		@Description("deleteSession() on an unknown id still returns 204 and closes the emitter")
		void deleteSession_whenUnknown_returns204() {
			// when
			ResponseEntity<Void> response = controller.deleteSession("unknown");

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
			verify(emitterRegistry).close("unknown");
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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);

			// when
			ResponseEntity<?> response = controller.getRoots(sessionId);

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
			ResponseEntity<?> response = controller.getRoots("unknown");

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("putRoots() replaces the session roots, notifies the client and broadcasts the change")
		void putRoots_withKnownSession_replacesRootsAndBroadcasts() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			RootsDto body = new RootsDto(List.of(new RootDto("file:///ws", "ws")));

			// when
			ResponseEntity<?> response = controller.putRoots(sessionId, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(((RootsDto) response.getBody()).roots()).hasSize(1);
			verify(client).addRoot(any(McpSchema.Root.class));
			verify(emitterRegistry).broadcast(eq(sessionId), eq("mcp:roots-list-changed"), any());
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("putRoots() with a null body treats it as an empty root list")
		void putRoots_withNullBody_replacesWithEmpty() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);

			// when
			ResponseEntity<?> response = controller.putRoots(sessionId, null);

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			RootsDto body = new RootsDto(List.of(new RootDto("", "blank")));

			// when
			ResponseEntity<?> response = controller.putRoots(sessionId, body);

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
			McpSyncClient client = mock(McpSyncClient.class);
			doThrow(new RuntimeException("no roots capability")).when(client).rootsListChangedNotification();
			String sessionId = registerSession(client);
			RootsDto body = new RootsDto(List.of(new RootDto("file:///x", "x")));

			// when
			ResponseEntity<?> response = controller.putRoots(sessionId, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("putRoots() with an unknown session returns 404 without touching collaborators")
		void putRoots_withUnknownSession_returns404() {
			// when
			ResponseEntity<?> response = controller.putRoots("unknown", new RootsDto(List.of()));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
			verify(emitterRegistry, never()).broadcast(anyString(), anyString(), any());
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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			SessionState state = controller.sessions().get(sessionId);
			var future = state.pendingServerRequests().create("req-1");
			JsonNode body = objectMapper.readTree("{\"result\":{\"answer\":42}}");

			// when
			ResponseEntity<?> response = controller.respond(sessionId, "req-1", body);

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			SessionState state = controller.sessions().get(sessionId);
			var future = state.pendingServerRequests().create("req-err");
			JsonNode body = objectMapper.readTree("{\"error\":{\"message\":\"nope\"}}");

			// when
			ResponseEntity<?> response = controller.respond(sessionId, "req-err", body);

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			SessionState state = controller.sessions().get(sessionId);
			var future = state.pendingServerRequests().create("req-bare");
			JsonNode body = objectMapper.readTree("{\"answer\":7}");

			// when
			ResponseEntity<?> response = controller.respond(sessionId, "req-bare", body);

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			JsonNode body = objectMapper.readTree("{\"result\":{}}");

			// when
			ResponseEntity<?> response = controller.respond(sessionId, "missing", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
		}

		@Test
		@Story("Server-request bridge")
		@Severity(SeverityLevel.MINOR)
		@Description("respond() with an unknown session returns 404")
		void respond_withUnknownSession_returns404() throws Exception {
			// given
			JsonNode body = objectMapper.readTree("{\"result\":{}}");

			// when
			ResponseEntity<?> response = controller.respond("unknown", "req", body);

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			OAuthInitiateRequest req = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", "openid", "challenge");
			when(oauthClient.buildAuthUrl(eq("https://idp/authorize"), eq("client-1"), eq("https://app/cb"),
					eq("openid"), anyString(), eq("challenge")))
				.thenReturn("https://idp/authorize?ok");

			// when
			ResponseEntity<?> response = controller.oauthInitiate(sessionId, req);

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
			OAuthInitiateRequest req = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token", "c", "r",
					null, null);

			// when
			ResponseEntity<?> response = controller.oauthInitiate("unknown", req);

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			SessionState state = controller.sessions().get(sessionId);
			state.oauthState("expected-state");
			state.oauthTokenEndpoint("https://idp/token");
			state.oauthClientId("client-1");
			state.oauthRedirectUri("https://app/cb");
			OAuthTokenResponse token = new OAuthTokenResponse("access-123", "Bearer", 3600L, null, "openid");
			when(oauthClient.exchangeCode("https://idp/token", "client-1", "the-code", "https://app/cb", "verifier"))
				.thenReturn(token);

			// when
			ResponseEntity<?> response = controller.oauthCallback(sessionId, "the-code", "expected-state", "verifier");

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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			controller.sessions().get(sessionId).oauthState("expected-state");

			// when
			ResponseEntity<?> response = controller.oauthCallback(sessionId, "code", "wrong-state", null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallback() returns 502 BAD_GATEWAY when the token exchange throws")
		void oauthCallback_whenExchangeFails_returns502() throws Exception {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);
			SessionState state = controller.sessions().get(sessionId);
			state.oauthState("st");
			when(oauthClient.exchangeCode(any(), any(), any(), any(), any()))
				.thenThrow(new RuntimeException("idp down"));

			// when
			ResponseEntity<?> response = controller.oauthCallback(sessionId, "code", "st", null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		}

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallback() returns 400 when no OAuth state was ever stored on the session")
		void oauthCallback_withNoStoredState_returns400() {
			// given
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);

			// when
			ResponseEntity<?> response = controller.oauthCallback(sessionId, "code", "any-state", null);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallback() with an unknown session returns 404")
		void oauthCallback_withUnknownSession_returns404() {
			// when
			ResponseEntity<?> response = controller.oauthCallback("unknown", "code", "st", null);

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
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBMVC"));
			when(portHolder.port()).thenReturn(1234);
			McpSyncClient client = mock(McpSyncClient.class);
			ArgumentCaptor<InspectorClientHandlers> handlersCaptor = ArgumentCaptor
				.forClass(InspectorClientHandlers.class);
			when(loopbackFactory.forSse(anyString(), anyInt(), anyString(), anyString(), handlersCaptor.capture()))
				.thenReturn(client);

			ResponseEntity<Map<String, Object>> connectResponse = controller.connect(null);
			String sessionId = (String) connectResponse.getBody().get("sessionId");
			InspectorClientHandlers handlers = handlersCaptor.getValue();

			ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

			// when — invoke the sampling handler on a worker thread (it blocks on the
			// pending future until the UI answers via /respond). The request payload is
			// only serialized into the SSE envelope, so a null request is sufficient.
			var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
			var samplingFuture = pool.submit(() -> handlers.sampling().apply(null));

			// Wait for the SSE broadcast, learn the generated requestId, then answer.
			verify(emitterRegistry, org.mockito.Mockito.timeout(2000)).broadcast(eq(sessionId),
					eq("mcp:sampling-request"), payloadCaptor.capture());
			JsonNode envelope = objectMapper.valueToTree(payloadCaptor.getValue());
			String requestId = envelope.path("requestId").asText();
			assertThat(requestId).isNotBlank();
			JsonNode result = objectMapper.readTree("{\"result\":{\"role\":\"assistant\","
					+ "\"content\":{\"type\":\"text\",\"text\":\"ok\"},\"model\":\"m\"}}");

			// then — /respond hands the result back; the blocked handler returns it
			// mapped
			controller.respond(sessionId, requestId, result);
			McpSchema.CreateMessageResult mapped = samplingFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
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
			McpSyncClient client = mock(McpSyncClient.class);
			String sessionId = registerSession(client);

			// when
			ConcurrentMap<String, SessionState> map = controller.sessions();

			// then
			assertThat(map).containsKey(sessionId);
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
			InspectorRestController c = new InspectorRestController(properties, transportDetector, loopbackFactory,
					authTokenProvider, emitterRegistry, portHolder, objectMapper, null, "demo-server");

			// then
			assertThat(c).isNotNull();
			assertThat(c.sessions()).isEmpty();
		}

	}

}
