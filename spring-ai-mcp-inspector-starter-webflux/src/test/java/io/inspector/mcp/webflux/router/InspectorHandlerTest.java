/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux.router;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.bootstrap.BootstrapHtmlRenderer;
import io.inspector.mcp.core.bootstrap.InspectorBootstrap;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapAssembler;
import io.inspector.mcp.core.client.ExternalStdioClientFactory;
import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.dto.RootsDto;
import io.inspector.mcp.core.oauth.InspectorOAuthClient;
import io.inspector.mcp.core.oauth.OAuthInitiateRequest;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
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
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InspectorHandler}. The full reactive REST surface (config,
 * connect, jsonRpc, roots, oauth, events, respond, serveConfig, index) is exercised over
 * {@link ServerRequest} built from {@link MockServerHttpRequest} with the MCP / transport
 * collaborators mocked — no Spring context and no live sockets.
 */
@Epic("MCP Inspector WebFlux")
@Feature("InspectorHandler reactive REST surface")
class InspectorHandlerTest {

	private static final HandlerStrategies STRATEGIES = HandlerStrategies.withDefaults();

	private TransportDetector transportDetector;

	private LoopbackMcpClientFactory loopbackFactory;

	private ExternalStdioClientFactory externalStdioFactory;

	private InspectorAuthTokenProvider tokenProvider;

	private InspectorOAuthClient oauthClient;

	private InspectorBootstrapAssembler bootstrapAssembler;

	private BootstrapHtmlRenderer bootstrapHtmlRenderer;

	private McpInspectorProperties properties;

	private ObjectMapper objectMapper;

	private InspectorHandler handler;

	@BeforeEach
	void setUp() {
		transportDetector = mock(TransportDetector.class);
		loopbackFactory = mock(LoopbackMcpClientFactory.class);
		externalStdioFactory = mock(ExternalStdioClientFactory.class);
		oauthClient = mock(InspectorOAuthClient.class);
		bootstrapAssembler = mock(InspectorBootstrapAssembler.class);
		bootstrapHtmlRenderer = mock(BootstrapHtmlRenderer.class);
		objectMapper = new ObjectMapper();
		properties = new McpInspectorProperties();
		properties.setAuthToken("fixed-test-token");
		tokenProvider = new InspectorAuthTokenProvider(properties);
		handler = new InspectorHandler(transportDetector, loopbackFactory, externalStdioFactory, tokenProvider,
				objectMapper, oauthClient, properties, bootstrapAssembler, bootstrapHtmlRenderer);
	}

	private ServerRequest toServerRequest(MockServerHttpRequest request) {
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	private static McpSyncClient connectedClient() {
		McpSyncClient client = mock(McpSyncClient.class);
		when(client.initialize()).thenReturn(null);
		when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("loopback-server", "1.2.3"));
		return client;
	}

	@Nested
	@DisplayName("config()")
	class Config {

		@Test
		@Story("Config endpoint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("config() maps the detected transport, stack and auth token into the ConfigDto body")
		void config_withDetectedTransport_returnsOkWithTransportAndToken() {
			// given
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));
			ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector/api/config").build());

			// when
			ServerResponse response = handler.config(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
			verify(transportDetector).detect();
		}

	}

	@Nested
	@DisplayName("serveConfig()")
	class ServeConfig {

		@Test
		@Story("Bootstrap config endpoint")
		@Severity(SeverityLevel.NORMAL)
		@Description("serveConfig() serializes the assembled bootstrap as no-store JSON")
		void serveConfig_whenAssemblerWired_returnsOkJsonBootstrap() {
			// given
			when(bootstrapAssembler.assemble()).thenReturn(new InspectorBootstrap());
			ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector/config").build());

			// when
			ServerResponse response = handler.serveConfig(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
			assertThat(response.headers().getCacheControl()).contains("no-store");
		}

		@Test
		@Story("Bootstrap config endpoint")
		@Severity(SeverityLevel.NORMAL)
		@Description("serveConfig() surfaces an error when no assembler is wired into the handler")
		void serveConfig_whenAssemblerMissing_emitsIllegalStateError() {
			// given
			InspectorHandler bareHandler = new InspectorHandler(transportDetector, loopbackFactory,
					externalStdioFactory, tokenProvider, objectMapper);
			ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector/config").build());

			// when & then
			StepVerifier.create(bareHandler.serveConfig(request))
				.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("InspectorBootstrapAssembler"))
				.verify();
		}

	}

	@Nested
	@DisplayName("index()")
	class Index {

		@Test
		@Story("SPA index serving")
		@Severity(SeverityLevel.NORMAL)
		@Description("index() renders the templated SPA HTML via the bootstrap renderer and returns it no-store")
		void index_always_returnsHtmlNoStoreResponse() throws Exception {
			// given
			when(bootstrapAssembler.assemble()).thenReturn(new InspectorBootstrap());
			when(bootstrapHtmlRenderer.renderIndexHtml(any(), any()))
				.thenReturn("<!doctype html><title>MCP Inspector</title>");
			ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector/index.html").build());

			// when
			ServerResponse response = handler.index(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_HTML);
			assertThat(response.headers().getCacheControl()).contains("no-store");
		}

	}

	@Nested
	@DisplayName("connect()")
	class Connect {

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("connect() builds a loopback client, initializes it, registers a session and returns its id + server info")
		void connect_withLoopbackTarget_returnsSessionIdAndServerInfo() {
			// given
			handler.onWebServerStarted(webServerStartedEvent(8081));
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			McpSyncClient client = connectedClient();
			when(loopbackFactory.forStreamable(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any()))
				.thenReturn(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			ServerResponse response = handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(client).initialize();
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() maps a target build failure (port not yet known) into a 500 error response")
		void connect_whenPortUnknown_returns500Error() {
			// given — no onWebServerStarted, so listeningPort stays -1
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			ServerResponse response = handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() with an externalCommand spawns the stdio client via the external factory instead of loopback")
		void connect_withExternalCommand_usesExternalStdioFactory() {
			// given
			handler.onWebServerStarted(webServerStartedEvent(8081));
			McpSyncClient client = connectedClient();
			when(externalStdioFactory.forCommand(any(), any())).thenReturn(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"externalCommand\":\"node server.js --flag\"}"));

			// when
			ServerResponse response = handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(externalStdioFactory).forCommand(any(), any());
			verify(client).initialize();
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() with an SSE detected transport builds the loopback client via the SSE factory branch")
		void connect_withSseTransport_usesSseLoopbackBranch() {
			// given
			handler.onWebServerStarted(webServerStartedEvent(8081));
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));
			McpSyncClient client = connectedClient();
			when(loopbackFactory.forSse(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any(), any()))
				.thenReturn(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			ServerResponse response = handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(loopbackFactory).forSse(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any(), any());
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() with a STATELESS detected transport builds the loopback client via the stateless factory branch")
		void connect_withStatelessTransport_usesStatelessLoopbackBranch() {
			// given
			handler.onWebServerStarted(webServerStartedEvent(8081));
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STATELESS, "/mcp", null, "WEBFLUX"));
			McpSyncClient client = connectedClient();
			when(loopbackFactory.forStateless(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any()))
				.thenReturn(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			ServerResponse response = handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(loopbackFactory).forStateless(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any());
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.MINOR)
		@Description("connect() with an UNKNOWN detected transport returns a 500 error (loopback unsupported)")
		void connect_withUnknownTransport_returns500Error() {
			// given
			handler.onWebServerStarted(webServerStartedEvent(8081));
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.UNKNOWN, null, null, "WEBFLUX"));
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			ServerResponse response = handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.MINOR)
		@Description("connect() with a STDIO_NO_HTTP detected transport returns a 500 error (loopback unsupported)")
		void connect_withStdioNoHttpTransport_returns500Error() {
			// given
			handler.onWebServerStarted(webServerStartedEvent(8081));
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STDIO_NO_HTTP, null, null, "STDIO"));
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			ServerResponse response = handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}

	}

	@Nested
	@DisplayName("jsonRpc()")
	class JsonRpc {

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.CRITICAL)
		@Description("jsonRpc() with no session id returns a JSON-RPC error envelope (-32600 missing session id)")
		void jsonRpc_withoutSessionId_returnsMissingSessionError() {
			// given
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"));

			// when
			ServerResponse response = handler.jsonRpc(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			Map<String, Object> body = entityBody(response);
			Map<?, ?> error = (Map<?, ?>) body.get("error");
			assertThat(error.get("code")).isEqualTo(-32600);
			assertThat(error.get("message").toString()).contains("missing session id");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() referencing an unknown session returns a -32600 'unknown session' error envelope")
		void jsonRpc_withUnknownSession_returnsUnknownSessionError() {
			// given
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", "does-not-exist")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"));

			// when
			ServerResponse response = handler.jsonRpc(request).block();

			// then
			assertThat(response).isNotNull();
			Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
			assertThat(error.get("code")).isEqualTo(-32600);
			assertThat(error.get("message").toString()).contains("unknown session");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.CRITICAL)
		@Description("jsonRpc() dispatches tools/list to the session's MCP client and wraps the result in a JSON-RPC envelope")
		void jsonRpc_withKnownSessionAndToolsList_dispatchesToClient() {
			// given
			McpSyncClient client = connectedClient();
			when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
			String sessionId = openLoopbackSession(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}"));

			// when
			ServerResponse response = handler.jsonRpc(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			Map<String, Object> body = entityBody(response);
			assertThat(body.get("jsonrpc")).isEqualTo("2.0");
			assertThat(body.get("id")).isEqualTo(9);
			assertThat(body).containsKey("result");
			verify(client).listTools();
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() converts an unsupported method into a -32000 JSON-RPC error envelope")
		void jsonRpc_withUnsupportedMethod_returnsJsonRpcError() {
			// given
			McpSyncClient client = connectedClient();
			String sessionId = openLoopbackSession(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"made/up\"}"));

			// when
			ServerResponse response = handler.jsonRpc(request).block();

			// then
			assertThat(response).isNotNull();
			Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
			assertThat(error.get("code")).isEqualTo(-32000);
			assertThat(error.get("message").toString()).contains("made/up");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() dispatches ping / roots/list and wraps a client exception into a -32000 error envelope")
		void jsonRpc_dispatchesCoreMethodsAndWrapsClientFailure() {
			// given — ping succeeds, then listResources throws
			McpSyncClient client = connectedClient();
			when(client.ping()).thenReturn("pong");
			when(client.listResources()).thenThrow(new IllegalStateException("transport down"));
			String sessionId = openLoopbackSession(client);

			// when — ping (happy)
			ServerResponse pingResponse = handler
				.jsonRpc(toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
					.header("X-Inspector-Session", sessionId)
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")))
				.block();
			// roots/list resolves from the session context (no client call)
			ServerResponse rootsResponse = handler
				.jsonRpc(toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
					.header("X-Inspector-Session", sessionId)
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"roots/list\"}")))
				.block();
			// resources/list throws -> JSON-RPC error envelope
			ServerResponse failResponse = handler
				.jsonRpc(toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
					.header("X-Inspector-Session", sessionId)
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}")))
				.block();

			// then
			assertThat(entityBody(pingResponse)).containsEntry("result", "pong");
			assertThat(entityBody(rootsResponse)).containsKey("result");
			Map<?, ?> error = (Map<?, ?>) entityBody(failResponse).get("error");
			assertThat(error.get("code")).isEqualTo(-32000);
			assertThat(error.get("message").toString()).contains("transport down");
			verify(client).ping();
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() dispatches every supported MCP method to the matching client call")
		void jsonRpc_dispatchesEverySupportedMethodToClient() {
			// given
			McpSyncClient client = connectedClient();
			when(client.getCurrentInitializationResult()).thenReturn(null);
			when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(), false));
			when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
				.thenReturn(new McpSchema.ReadResourceResult(List.of()));
			when(client.listResourceTemplates()).thenReturn(new McpSchema.ListResourceTemplatesResult(List.of(), null));
			when(client.listPrompts()).thenReturn(new McpSchema.ListPromptsResult(List.of(), null));
			when(client.getPrompt(any())).thenReturn(new McpSchema.GetPromptResult(null, List.of()));
			String sessionId = openLoopbackSession(client);

			// when & then — each method resolves to a 200 JSON-RPC envelope with a result
			assertThat(dispatch(sessionId, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
				.containsKey("result");
			assertThat(dispatch(sessionId,
					"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"t\"}}"))
				.containsKey("result");
			assertThat(dispatch(sessionId,
					"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\",\"params\":{\"uri\":\"file:///x\"}}"))
				.containsKey("result");
			assertThat(dispatch(sessionId, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/templates/list\"}"))
				.containsKey("result");
			assertThat(dispatch(sessionId, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/list\"}"))
				.containsKey("result");
			assertThat(dispatch(sessionId,
					"{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"prompts/get\",\"params\":{\"name\":\"p\"}}"))
				.containsKey("result");

			verify(client).callTool(any());
			verify(client).listPrompts();
		}

	}

	@Nested
	@DisplayName("events()")
	class Events {

		@Test
		@Story("SSE notification stream")
		@Severity(SeverityLevel.NORMAL)
		@Description("events() without a session query parameter returns 400 bad request")
		void events_withoutSessionParam_returnsBadRequest() {
			// given
			ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector/api/events").build());

			// when
			ServerResponse response = handler.events(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("SSE notification stream")
		@Severity(SeverityLevel.NORMAL)
		@Description("events() for an unknown session returns 404 not found")
		void events_withUnknownSession_returnsNotFound() {
			// given
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/events?session=nope").build());

			// when
			ServerResponse response = handler.events(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("SSE notification stream")
		@Severity(SeverityLevel.NORMAL)
		@Description("events() for a known session returns an SSE (text/event-stream) response")
		void events_withKnownSession_returnsEventStream() {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/events?session=" + sessionId).build());

			// when
			ServerResponse response = handler.events(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
		}

	}

	@Nested
	@DisplayName("deleteSession()")
	class DeleteSession {

		@Test
		@Story("Terminate session")
		@Severity(SeverityLevel.NORMAL)
		@Description("deleteSession() removes a known session, closes its client and returns 204 no content")
		void deleteSession_whenKnown_closesClientAndReturnsNoContent() {
			// given
			McpSyncClient client = connectedClient();
			String sessionId = openLoopbackSession(client);
			ServerRequest request = deleteSessionRequest(sessionId);

			// when
			ServerResponse response = handler.deleteSession(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT);
			assertThat(handler.hasSession(sessionId)).isFalse();
		}

		@Test
		@Story("Terminate session")
		@Severity(SeverityLevel.MINOR)
		@Description("deleteSession() for an unknown id still returns 204 no content (idempotent)")
		void deleteSession_whenUnknown_returnsNoContent() {
			// given
			ServerRequest request = deleteSessionRequest("ghost");

			// when
			ServerResponse response = handler.deleteSession(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		}

	}

	@Nested
	@DisplayName("getRoots() / putRoots()")
	class Roots {

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("getRoots() for an unknown session returns 404")
		void getRoots_withUnknownSession_returnsNotFound() {
			// given
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/roots?sessionId=nope").build());

			// when
			ServerResponse response = handler.getRoots(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("getRoots() for a known session returns 200 with the current roots envelope")
		void getRoots_withKnownSession_returnsRoots() {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/roots?sessionId=" + sessionId).build());

			// when
			ServerResponse response = handler.getRoots(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("putRoots() replaces the root list, notifies the MCP client and returns the updated roots")
		void putRoots_withKnownSession_appliesRootsAndNotifiesClient() {
			// given
			McpSyncClient client = connectedClient();
			String sessionId = openLoopbackSession(client);
			RootsDto incoming = new RootsDto(List.of(new RootDto("file:///workspace", "ws")));
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.put("/mcp-inspector/api/roots?sessionId=" + sessionId)
						.contentType(MediaType.APPLICATION_JSON)
						.body(toJson(incoming)));

			// when
			ServerResponse response = handler.putRoots(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(client).addRoot(any(McpSchema.Root.class));
			verify(client).rootsListChangedNotification();
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("putRoots() for an unknown session returns 404 without touching any client")
		void putRoots_withUnknownSession_returnsNotFound() {
			// given
			ServerRequest request = toServerRequest(MockServerHttpRequest.put("/mcp-inspector/api/roots?sessionId=nope")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"roots\":[]}"));

			// when
			ServerResponse response = handler.putRoots(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

	}

	@Nested
	@DisplayName("respond()")
	class Respond {

		@Test
		@Story("Server-to-client request completion")
		@Severity(SeverityLevel.NORMAL)
		@Description("respond() for an unknown session returns 404")
		void respond_withUnknownSession_returnsNotFound() {
			// given
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc/respond?sessionId=nope&requestId=r1")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			ServerResponse response = handler.respond(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Server-to-client request completion")
		@Severity(SeverityLevel.NORMAL)
		@Description("respond() without a requestId returns 400 bad request")
		void respond_withoutRequestId_returnsBadRequest() {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			ServerResponse response = handler.respond(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Server-to-client request completion")
		@Severity(SeverityLevel.NORMAL)
		@Description("respond() with a requestId that has no pending future returns 410 gone")
		void respond_withNoPendingRequest_returnsGone() {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=missing")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"result\":{}}"));

			// when
			ServerResponse response = handler.respond(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.GONE);
		}

		@Test
		@Story("Server-to-client request completion")
		@Severity(SeverityLevel.MINOR)
		@Description("respond() carrying an error body takes the completeExceptionally branch (410 when no pending future)")
		void respond_withErrorBody_takesExceptionalBranch() {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=missing")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"user rejected\"}}"));

			// when
			ServerResponse response = handler.respond(request).block();

			// then — no pending future exists, so the exceptional completion misses and
			// yields 410
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.GONE);
		}

	}

	@Nested
	@DisplayName("oauthInitiate() / oauthCallback()")
	class OAuth {

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthInitiate() for an unknown session returns 404")
		void oauthInitiate_withUnknownSession_returnsNotFound() {
			// given
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=nope")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			ServerResponse response = handler.oauthInitiate(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthInitiate() builds the IdP authorization URL via the OAuth client and returns it with a state token")
		void oauthInitiate_withKnownSession_returnsAuthUrlAndState() {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			when(oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.thenReturn("https://idp.example/authorize?state=abc");
			OAuthInitiateRequest body = new OAuthInitiateRequest("https://idp.example/authorize",
					"https://idp.example/token", "client-1", "https://app/cb", "openid", "challenge");
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
						.contentType(MediaType.APPLICATION_JSON)
						.body(toJson(body)));

			// when
			ServerResponse response = handler.oauthInitiate(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(oauthClient).buildAuthUrl(any(), any(), any(), any(), any(), any());
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthCallback() rejects a callback whose state does not match the stored state with 400")
		void oauthCallback_withStateMismatch_returnsBadRequest() {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			when(oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.thenReturn("https://idp/authorize");
			OAuthInitiateRequest initiate = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", null, null);
			handler
				.oauthInitiate(toServerRequest(
						MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
							.contentType(MediaType.APPLICATION_JSON)
							.body(toJson(initiate))))
				.block();
			ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=WRONG")
				.build());

			// when
			ServerResponse response = handler.oauthCallback(callback).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallback() missing code or state returns 400 bad request")
		void oauthCallback_withoutCode_returnsBadRequest() {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest callback = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&state=s")
						.build());

			// when
			ServerResponse response = handler.oauthCallback(callback).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.CRITICAL)
		@Description("oauthCallback() with a matching state exchanges the code and returns the token JSON")
		void oauthCallback_withMatchingState_exchangesCodeAndReturnsToken() throws Exception {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			when(oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.thenReturn("https://idp/authorize");
			OAuthInitiateRequest initiate = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", null, null);
			ServerResponse initiateResponse = handler
				.oauthInitiate(toServerRequest(
						MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
							.contentType(MediaType.APPLICATION_JSON)
							.body(toJson(initiate))))
				.block();
			String state = readState(initiateResponse);
			when(oauthClient.exchangeCode(any(), any(), any(), any(), any()))
				.thenReturn(new OAuthTokenResponse("at", "Bearer", 3600L, null, null));
			ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=" + state)
				.build());

			// when
			ServerResponse response = handler.oauthCallback(callback).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(oauthClient).exchangeCode(any(), any(), any(), any(), any());
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthCallback() maps an exchange failure into a 502 bad gateway error response")
		void oauthCallback_whenExchangeFails_returns502() throws Exception {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			when(oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.thenReturn("https://idp/authorize");
			OAuthInitiateRequest initiate = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", null, null);
			ServerResponse initiateResponse = handler
				.oauthInitiate(toServerRequest(
						MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
							.contentType(MediaType.APPLICATION_JSON)
							.body(toJson(initiate))))
				.block();
			String state = readState(initiateResponse);
			when(oauthClient.exchangeCode(any(), any(), any(), any(), any()))
				.thenThrow(new java.io.IOException("token endpoint returned 401"));
			ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=" + state)
				.build());

			// when
			ServerResponse response = handler.oauthCallback(callback).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		}

	}

	@Nested
	@DisplayName("onWebServerStarted()")
	class WebServerLifecycle {

		@Test
		@Story("Lifecycle")
		@Severity(SeverityLevel.MINOR)
		@Description("onWebServerStarted records the embedded server's port so loopback connects can target it")
		void onWebServerStarted_recordsListeningPort() {
			// given & when
			handler.onWebServerStarted(webServerStartedEvent(54321));

			// then
			assertThat(handler.listeningPort()).isEqualTo(54321);
		}

	}

	@Nested
	@DisplayName("constructor overloads")
	class Constructors {

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("the (…, oauthClient) constructor wires an explicit OAuth client and still serves config")
		void constructor_withOauthClientOnly_isUsable() {
			// given — the 6-arg overload that takes only an explicit oauthClient
			InspectorHandler sixArg = new InspectorHandler(transportDetector, loopbackFactory, externalStdioFactory,
					tokenProvider, objectMapper, oauthClient);
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));

			// when
			ServerResponse response = sixArg
				.config(toServerRequest(MockServerHttpRequest.get("/mcp-inspector/api/config").build()))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
		}

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("the (…, oauthClient, properties) constructor is usable and serves config")
		void constructor_withOauthClientAndProperties_isUsable() {
			// given — the 7-arg overload that adds the properties bean
			InspectorHandler sevenArg = new InspectorHandler(transportDetector, loopbackFactory, externalStdioFactory,
					tokenProvider, objectMapper, oauthClient, properties);
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));

			// when
			ServerResponse response = sevenArg
				.config(toServerRequest(MockServerHttpRequest.get("/mcp-inspector/api/config").build()))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
		}

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("a null objectMapper falls back to a fresh ObjectMapper (defensive null branch) and still dispatches JSON-RPC")
		void constructor_withNullObjectMapper_fallsBackToDefaultMapper() {
			// given — null objectMapper triggers the defensive `new ObjectMapper()`
			// branch
			InspectorHandler nullMapperHandler = new InspectorHandler(transportDetector, loopbackFactory,
					externalStdioFactory, tokenProvider, null, oauthClient, properties, bootstrapAssembler,
					bootstrapHtmlRenderer);
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));

			// when
			ServerResponse response = nullMapperHandler
				.config(toServerRequest(MockServerHttpRequest.get("/mcp-inspector/api/config").build()))
				.block();

			// then — the handler is functional despite the null mapper argument
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
		}

	}

	@Nested
	@DisplayName("session-id resolution / blank-input branches")
	class SessionIdResolution {

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() with a blank session header resolves no session and returns a -32600 missing-session error")
		void jsonRpc_withBlankSessionHeader_returnsMissingSessionError() {
			// given — a present but blank X-Inspector-Session header (resolveSessionId
			// blank branch + resolvedSessionId.isBlank() branch)
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", "   ")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"));

			// when
			ServerResponse response = handler.jsonRpc(request).block();

			// then
			Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
			assertThat(error.get("code")).isEqualTo(-32600);
			assertThat(error.get("message").toString()).contains("missing session id");
		}

		@Test
		@Story("SSE notification stream")
		@Severity(SeverityLevel.NORMAL)
		@Description("events() with a present but blank session query parameter returns 400 bad request (isBlank branch)")
		void events_withBlankSessionParam_returnsBadRequest() {
			// given
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/events?session= ").build());

			// when
			ServerResponse response = handler.events(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("getRoots() with no session id at all returns 404 (sessionId == null short-circuit branch)")
		void getRoots_withoutAnySessionId_returnsNotFound() {
			// given — no header, no query parameter, so resolveSessionId returns null
			ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector/api/roots").build());

			// when
			ServerResponse response = handler.getRoots(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("putRoots() with no session id at all returns 404 (sessionId == null short-circuit branch)")
		void putRoots_withoutAnySessionId_returnsNotFound() {
			// given
			ServerRequest request = toServerRequest(MockServerHttpRequest.put("/mcp-inspector/api/roots")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"roots\":[]}"));

			// when
			ServerResponse response = handler.putRoots(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Server-to-client request completion")
		@Severity(SeverityLevel.NORMAL)
		@Description("respond() with no session id at all returns 404 (sessionId == null short-circuit branch)")
		void respond_withoutAnySessionId_returnsNotFound() {
			// given
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc/respond?requestId=r1")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			ServerResponse response = handler.respond(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthInitiate() with no session id at all returns 404 (sessionId == null short-circuit branch)")
		void oauthInitiate_withoutAnySessionId_returnsNotFound() {
			// given
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			ServerResponse response = handler.oauthInitiate(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthCallback() with no session id at all returns 404 (sessionId == null short-circuit branch)")
		void oauthCallback_withoutAnySessionId_returnsNotFound() {
			// given
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/oauth/callback?code=c&state=s").build());

			// when
			ServerResponse response = handler.oauthCallback(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

	}

	@Nested
	@DisplayName("respond() — completion branches")
	class RespondCompletion {

		@Test
		@Story("Server-to-client request completion")
		@Severity(SeverityLevel.NORMAL)
		@Description("respond() with a blank requestId returns 400 bad request (requestId isBlank branch)")
		void respond_withBlankRequestId_returnsBadRequest() {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId= ")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			ServerResponse response = handler.respond(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Server-to-client request completion")
		@Severity(SeverityLevel.CRITICAL)
		@Description("respond() with a result body for a genuinely pending request completes it and returns 200 ok (completed == true branch)")
		void respond_withResultForPendingRequest_completesAndReturnsOk() {
			// given — register a pending server request on the live session, then answer
			// it
			String sessionId = openLoopbackSession(connectedClient());
			SessionContext ctx = sessions().get(sessionId);
			ctx.pendingServerRequests().create("req-42");
			ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=req-42")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"result\":{\"answer\":true}}"));

			// when
			ServerResponse response = handler.respond(request).block();

			// then — the pending future was completed, so 200 ok with {ok:true}
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(entityBody(response)).containsEntry("ok", true);
		}

		@Test
		@Story("Server-to-client request completion")
		@Severity(SeverityLevel.NORMAL)
		@Description("respond() with a bare body (no explicit result key) uses the whole body as the result and completes the request")
		void respond_withBareBody_usesWholeBodyAsResultAndCompletes() {
			// given — body without a top-level "result" key takes the `body` fallback
			// branch
			String sessionId = openLoopbackSession(connectedClient());
			SessionContext ctx = sessions().get(sessionId);
			ctx.pendingServerRequests().create("req-bare");
			ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=req-bare")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"action\":\"accept\"}"));

			// when
			ServerResponse response = handler.respond(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
		}

		@Test
		@Story("Server-to-client request completion")
		@Severity(SeverityLevel.NORMAL)
		@Description("respond() carrying an error body for a pending request completes it exceptionally and returns 200 ok")
		void respond_withErrorBodyForPendingRequest_completesExceptionallyAndReturnsOk() {
			// given — pending future + an error body exercises the completeExceptionally
			// branch with completed == true
			String sessionId = openLoopbackSession(connectedClient());
			SessionContext ctx = sessions().get(sessionId);
			ctx.pendingServerRequests().create("req-err");
			ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=req-err")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"user rejected\"}}"));

			// when
			ServerResponse response = handler.respond(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
		}

	}

	@Nested
	@DisplayName("oauthCallback() — extra branches")
	class OAuthCallbackBranches {

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallback() with a code but no state returns 400 bad request (state == null branch)")
		void oauthCallback_withCodeButNoState_returnsBadRequest() {
			// given — code present, state absent
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest callback = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c")
						.build());

			// when
			ServerResponse response = handler.oauthCallback(callback).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthCallback() before any initiate returns 400 state mismatch (ctx.oauthState() == null branch)")
		void oauthCallback_whenNoStoredState_returnsBadRequest() {
			// given — a fresh session that never called initiate, so oauthState() is null
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=anything")
				.build());

			// when
			ServerResponse response = handler.oauthCallback(callback).block();

			// then — null stored state can never match the presented state
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthCallback() maps an exchange failure with a null message into a 502 using the exception simple name")
		void oauthCallback_whenExchangeFailsWithNullMessage_returns502() throws Exception {
			// given
			String sessionId = openLoopbackSession(connectedClient());
			when(oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.thenReturn("https://idp/authorize");
			OAuthInitiateRequest initiate = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", null, null);
			ServerResponse initiateResponse = handler
				.oauthInitiate(toServerRequest(
						MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
							.contentType(MediaType.APPLICATION_JSON)
							.body(toJson(initiate))))
				.block();
			String state = readState(initiateResponse);
			// an exception whose getMessage() is null exercises the ex.getMessage() ==
			// null
			// branch in the onErrorResume mapper
			when(oauthClient.exchangeCode(any(), any(), any(), any(), any())).thenThrow(new RuntimeException());
			ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=" + state)
				.build());

			// when
			ServerResponse response = handler.oauthCallback(callback).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			assertThat(entityBody(response).get("error").toString()).contains("RuntimeException");
		}

	}

	@Nested
	@DisplayName("dispatch / openSession / applyRoots / render edge branches")
	class EdgeBranches {

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() with a null method falls through to the unsupported branch and returns a -32000 error (method == null branch)")
		void jsonRpc_withNullMethod_returnsUnsupportedError() {
			// given — a relay with no method field, so dispatch's method == null branch
			// substitutes the empty string and falls into default
			String sessionId = openLoopbackSession(connectedClient());
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":11}"));

			// when
			ServerResponse response = handler.jsonRpc(request).block();

			// then
			Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
			assertThat(error.get("code")).isEqualTo(-32000);
			assertThat(error.get("message").toString()).contains("not supported");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() wraps a client exception with a null message into a -32000 error using the exception class name (jsonRpcError null-message branch)")
		void jsonRpc_whenClientThrowsNullMessage_usesClassNameInError() {
			// given
			McpSyncClient client = connectedClient();
			when(client.listTools()).thenThrow(new IllegalStateException());
			String sessionId = openLoopbackSession(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}"));

			// when
			ServerResponse response = handler.jsonRpc(request).block();

			// then — the null-message branch falls back to the simple class name
			Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
			assertThat(error.get("code")).isEqualTo(-32000);
			assertThat(error.get("message")).isEqualTo("IllegalStateException");
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() with a blank externalCommand falls back to the loopback client (externalCommand isBlank branch)")
		void connect_withBlankExternalCommand_usesLoopbackBranch() {
			// given — a blank externalCommand must NOT route to the external stdio
			// factory
			handler.onWebServerStarted(webServerStartedEvent(8081));
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			McpSyncClient client = connectedClient();
			when(loopbackFactory.forStreamable(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any()))
				.thenReturn(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"externalCommand\":\"   \"}"));

			// when
			ServerResponse response = handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(loopbackFactory).forStreamable(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any());
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.MINOR)
		@Description("connect() returns null server name/version when the client exposes no server info (info == null branch)")
		void connect_whenServerInfoNull_returnsNullServerNameAndVersion() {
			// given — getServerInfo() returns null, exercising the info == null ternaries
			handler.onWebServerStarted(webServerStartedEvent(8081));
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			McpSyncClient client = mock(McpSyncClient.class);
			when(client.initialize()).thenReturn(null);
			when(client.getServerInfo()).thenReturn(null);
			when(loopbackFactory.forStreamable(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any()))
				.thenReturn(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			ServerResponse response = handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			Map<String, Object> body = entityBody(response);
			assertThat(body).containsKey("sessionId");
			assertThat(body.get("serverName")).isNull();
			assertThat(body.get("serverVersion")).isNull();
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("putRoots() skips roots whose uri is null or blank and only registers the valid one (r.uri() isBlank branch)")
		void putRoots_withBlankUriRoot_skipsInvalidRootsWhenApplying() {
			// given — a mix of a blank-uri root (skipped) and a valid root (added)
			McpSyncClient client = connectedClient();
			String sessionId = openLoopbackSession(client);
			ServerRequest request = toServerRequest(MockServerHttpRequest
				.put("/mcp-inspector/api/roots?sessionId=" + sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"roots\":[{\"uri\":\"   \",\"name\":\"blank\"},{\"uri\":\"file:///ok\",\"name\":\"ok\"}]}"));

			// when
			ServerResponse response = handler.putRoots(request).block();

			// then — only the valid root is forwarded to the client
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(client).addRoot(new McpSchema.Root("file:///ok", "ok"));
			verify(client).rootsListChangedNotification();
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("putRoots() with a null roots payload applies an empty list (body.roots() == null branch) and notifies count 0")
		void putRoots_withNullRootsField_appliesEmptyList() {
			// given — explicit null roots field exercises the `body.roots() != null`
			// branch
			McpSyncClient client = connectedClient();
			String sessionId = openLoopbackSession(client);
			ServerRequest request = toServerRequest(
					MockServerHttpRequest.put("/mcp-inspector/api/roots?sessionId=" + sessionId)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"roots\":null}"));

			// when
			ServerResponse response = handler.putRoots(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(((RootsDto) ((org.springframework.web.reactive.function.server.EntityResponse<Object>) response)
				.entity()).roots()).isEmpty();
		}

		@Test
		@Story("SPA index serving")
		@Severity(SeverityLevel.MINOR)
		@Description("index() returns the raw template untouched when no bootstrap renderer is wired (assembler/renderer == null branch)")
		void index_whenNoRendererWired_returnsTemplateOrFallback() {
			// given — the legacy 5-arg constructor wires neither assembler nor renderer
			InspectorHandler bareHandler = new InspectorHandler(transportDetector, loopbackFactory,
					externalStdioFactory, tokenProvider, objectMapper);
			ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector/index.html").build());

			// when
			ServerResponse response = bareHandler.index(request).block();

			// then — still serves HTML (raw template or the not-bundled fallback)
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_HTML);
		}

	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * Reflectively exposes the private sessions map so completion branches can be wired.
	 */
	@SuppressWarnings("unchecked")
	private java.util.Map<String, SessionContext> sessions() {
		try {
			java.lang.reflect.Field field = InspectorHandler.class.getDeclaredField("sessions");
			field.setAccessible(true);
			return (java.util.Map<String, SessionContext>) field.get(handler);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Opens a loopback session through the public connect() path and returns its id. */
	private String openLoopbackSession(McpSyncClient client) {
		handler.onWebServerStarted(webServerStartedEvent(8081));
		when(transportDetector.detect())
			.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
		when(loopbackFactory.forStreamable(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any()))
			.thenReturn(client);
		ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{}"));
		ServerResponse response = handler.connect(request).block();
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
		return entityBody(response).get("sessionId").toString();
	}

	/** Relays {@code body} for {@code sessionId} and returns the JSON-RPC envelope. */
	private Map<String, Object> dispatch(String sessionId, String body) {
		ServerResponse response = handler
			.jsonRpc(toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)))
			.block();
		assertThat(response).isNotNull();
		return entityBody(response);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> entityBody(ServerResponse response) {
		Object entity = ((org.springframework.web.reactive.function.server.EntityResponse<Object>) response).entity();
		return (Map<String, Object>) entity;
	}

	private String readState(ServerResponse response) {
		Object entity = ((org.springframework.web.reactive.function.server.EntityResponse<Object>) response).entity();
		JsonNode node = objectMapper.valueToTree(entity);
		return node.get("state").asText();
	}

	/** Builds a DELETE /session/{id} request with the {@code id} path variable bound. */
	private ServerRequest deleteSessionRequest(String id) {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.delete("/mcp-inspector/api/session/" + id).build());
		exchange.getAttributes()
			.put(org.springframework.web.reactive.function.server.RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
					Map.of("id", id));
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static WebServerInitializedEvent webServerStartedEvent(int port) {
		WebServer webServer = mock(WebServer.class);
		when(webServer.getPort()).thenReturn(port);
		WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
		when(event.getWebServer()).thenReturn(webServer);
		return event;
	}

}
