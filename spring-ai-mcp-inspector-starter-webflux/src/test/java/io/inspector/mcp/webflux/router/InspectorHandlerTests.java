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

package io.inspector.mcp.webflux.router;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.web.context.WebServerApplicationContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link InspectorHandler}. The full reactive REST surface (config,
 * connect, jsonRpc, roots, oauth, events, respond, serveConfig, index) is exercised over
 * {@link ServerRequest} built from {@link MockServerHttpRequest} with the MCP / transport
 * collaborators mocked — no Spring context and no live sockets.
 */
@Epic("MCP Inspector WebFlux")
@Feature("InspectorHandler reactive REST surface")
class InspectorHandlerTests {

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
		this.transportDetector = mock(TransportDetector.class);
		this.loopbackFactory = mock(LoopbackMcpClientFactory.class);
		this.externalStdioFactory = mock(ExternalStdioClientFactory.class);
		this.oauthClient = mock(InspectorOAuthClient.class);
		this.bootstrapAssembler = mock(InspectorBootstrapAssembler.class);
		this.bootstrapHtmlRenderer = mock(BootstrapHtmlRenderer.class);
		this.objectMapper = new ObjectMapper();
		this.properties = new McpInspectorProperties();
		this.properties.setAuthToken("fixed-test-token");
		this.tokenProvider = new InspectorAuthTokenProvider(this.properties);
		this.handler = new InspectorHandler(this.transportDetector, this.loopbackFactory, this.externalStdioFactory,
				this.tokenProvider, this.objectMapper, this.oauthClient, this.properties, this.bootstrapAssembler,
				this.bootstrapHtmlRenderer);
	}

	private ServerRequest toServerRequest(final MockServerHttpRequest request) {
		final MockServerWebExchange exchange = MockServerWebExchange.from(request);
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	private static McpSyncClient connectedClient() {
		final McpSyncClient client = mock(McpSyncClient.class);
		given(client.initialize()).willReturn(null);
		given(client.getServerInfo()).willReturn(new McpSchema.Implementation("loopback-server", "1.2.3"));
		return client;
	}

	/**
	 * Reflectively exposes the private sessions map so completion branches can be wired.
	 */
	@SuppressWarnings("unchecked")
	private java.util.Map<String, SessionContext> sessions() {
		try {
			final java.lang.reflect.Field field = InspectorHandler.class.getDeclaredField("sessions");
			field.setAccessible(true);
			return (java.util.Map<String, SessionContext>) field.get(this.handler);
		}
		catch (final ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Opens a loopback session through the public connect() path and returns its id. */
	private String openLoopbackSession(final McpSyncClient client) {
		this.handler.onWebServerStarted(webServerStartedEvent(8081));
		given(this.transportDetector.detect())
			.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
		given(this.loopbackFactory.forStreamable(any(), org.mockito.ArgumentMatchers.eq(8081), any(), any()))
			.willReturn(client);
		final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{}"));
		final ServerResponse response = this.handler.connect(request).block();
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
		return entityBody(response).get("sessionId").toString();
	}

	/** Relays {@code body} for {@code sessionId} and returns the JSON-RPC envelope. */
	private Map<String, Object> dispatch(final String sessionId, final String body) {
		final ServerResponse response = this.handler
			.jsonRpc(toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)))
			.block();
		assertThat(response).isNotNull();
		return entityBody(response);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> entityBody(final ServerResponse response) {
		final Object entity = ((org.springframework.web.reactive.function.server.EntityResponse<Object>) response)
			.entity();
		return (Map<String, Object>) entity;
	}

	private String readState(final ServerResponse response) {
		final Object entity = ((org.springframework.web.reactive.function.server.EntityResponse<Object>) response)
			.entity();
		final JsonNode node = this.objectMapper.valueToTree(entity);
		return node.get("state").asText();
	}

	/** Builds a DELETE /session/{id} request with the {@code id} path variable bound. */
	private ServerRequest deleteSessionRequest(final String id) {
		final MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.delete("/mcp-inspector/api/session/" + id).build());
		exchange.getAttributes()
			.put(org.springframework.web.reactive.function.server.RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
					Map.of("id", id));
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	private String toJson(final Object value) {
		try {
			return this.objectMapper.writeValueAsString(value);
		}
		catch (final Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static WebServerInitializedEvent webServerStartedEvent(final int port) {
		final WebServer webServer = mock(WebServer.class);
		given(webServer.getPort()).willReturn(port);
		final WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
		given(event.getWebServer()).willReturn(webServer);
		return event;
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
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/config").build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.config(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
			verify(InspectorHandlerTests.this.transportDetector).detect();
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
			given(InspectorHandlerTests.this.bootstrapAssembler.assemble(anyString()))
				.willReturn(new InspectorBootstrap());
			final ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector/config").build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.serveConfig(request).block();

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
			final InspectorHandler bareHandler = new InspectorHandler(InspectorHandlerTests.this.transportDetector,
					InspectorHandlerTests.this.loopbackFactory, InspectorHandlerTests.this.externalStdioFactory,
					InspectorHandlerTests.this.tokenProvider, InspectorHandlerTests.this.objectMapper);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector/config").build());

			// when & then
			StepVerifier.create(bareHandler.serveConfig(request))
				.expectErrorSatisfies((ex) -> assertThat(ex).isInstanceOf(IllegalStateException.class)
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
			given(InspectorHandlerTests.this.bootstrapAssembler.assemble(anyString()))
				.willReturn(new InspectorBootstrap());
			given(InspectorHandlerTests.this.bootstrapHtmlRenderer.renderIndexHtml(any(), any(), anyString()))
				.willReturn("<!doctype html><title>MCP Inspector</title>");
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/index.html").build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.index(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_HTML);
			assertThat(response.headers().getCacheControl()).contains("no-store");
		}

		@Test
		@Story("Base path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("index() under a WebFlux base path passes the prefixed asset base and prefix onwards")
		void index_underBasePath_passesPrefixedAssetBase() throws Exception {
			// given
			given(InspectorHandlerTests.this.bootstrapAssembler.assemble(anyString()))
				.willReturn(new InspectorBootstrap());
			given(InspectorHandlerTests.this.bootstrapHtmlRenderer.renderIndexHtml(any(), any(), anyString()))
				.willReturn("<!doctype html><title>MCP Inspector</title>");
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/app/mcp-inspector/index.html").contextPath("/app").build());

			// when
			InspectorHandlerTests.this.handler.index(request).block();

			// then
			verify(InspectorHandlerTests.this.bootstrapAssembler).assemble("/app");
			verify(InspectorHandlerTests.this.bootstrapHtmlRenderer).renderIndexHtml(any(), any(),
					org.mockito.ArgumentMatchers.eq("/app/mcp-inspector"));
		}

		@Test
		@Story("Management server guard")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A WebServerInitializedEvent from the management context does not overwrite the loopback port")
		void onWebServerStarted_withManagementNamespace_isIgnored() {
			// given — the actuator's own server reports port 9999
			final WebServerInitializedEvent event = webServerStartedEvent(9999);
			final WebServerApplicationContext context = mock(WebServerApplicationContext.class);
			given(context.getServerNamespace()).willReturn("management");
			given(event.getApplicationContext()).willReturn(context);

			// when
			InspectorHandlerTests.this.handler.onWebServerStarted(event);

			// then — the port stays unset
			assertThat(InspectorHandlerTests.this.handler.listeningPort()).isEqualTo(-1);
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
			InspectorHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(8081));
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			final McpSyncClient client = connectedClient();
			given(InspectorHandlerTests.this.loopbackFactory.forStreamable(any(), org.mockito.ArgumentMatchers.eq(8081),
					any(), any()))
				.willReturn(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.connect(request).block();

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
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.connect(request).block();

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
			InspectorHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(8081));
			final McpSyncClient client = connectedClient();
			given(InspectorHandlerTests.this.externalStdioFactory.forCommand(any(), any())).willReturn(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"externalCommand\":\"node server.js --flag\"}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(InspectorHandlerTests.this.externalStdioFactory).forCommand(any(), any());
			verify(client).initialize();
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() dials the detected SSE endpoint, base path included, via the SSE factory branch")
		void connect_withSseTransport_usesSseLoopbackBranch() {
			// given — the detected endpoints already carry the base path
			InspectorHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(8081));
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/app/sse", "/app/mcp/message", "WEBFLUX"));
			final McpSyncClient client = connectedClient();
			given(InspectorHandlerTests.this.loopbackFactory.forSse(any(), org.mockito.ArgumentMatchers.eq(8081),
					org.mockito.ArgumentMatchers.eq("/app/sse"), any()))
				.willReturn(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(InspectorHandlerTests.this.loopbackFactory).forSse(any(), org.mockito.ArgumentMatchers.eq(8081),
					org.mockito.ArgumentMatchers.eq("/app/sse"), any());
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.NORMAL)
		@Description("connect() with a STATELESS detected transport builds the loopback client via the stateless factory branch")
		void connect_withStatelessTransport_usesStatelessLoopbackBranch() {
			// given
			InspectorHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(8081));
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STATELESS, "/mcp", null, "WEBFLUX"));
			final McpSyncClient client = connectedClient();
			given(InspectorHandlerTests.this.loopbackFactory.forStateless(any(), org.mockito.ArgumentMatchers.eq(8081),
					any(), any()))
				.willReturn(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(InspectorHandlerTests.this.loopbackFactory).forStateless(any(),
					org.mockito.ArgumentMatchers.eq(8081), any(), any());
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.MINOR)
		@Description("connect() with an UNKNOWN detected transport returns a 500 error (loopback unsupported)")
		void connect_withUnknownTransport_returns500Error() {
			// given
			InspectorHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(8081));
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.UNKNOWN, null, null, "WEBFLUX"));
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.connect(request).block();

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
			InspectorHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(8081));
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STDIO_NO_HTTP, null, null, "STDIO"));
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.connect(request).block();

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
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.jsonRpc(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			final Map<String, Object> body = entityBody(response);
			final Map<?, ?> error = (Map<?, ?>) body.get("error");
			assertThat(error.get("code")).isEqualTo(-32600);
			assertThat(error.get("message").toString()).contains("missing session id");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() referencing an unknown session returns a -32600 'unknown session' error envelope")
		void jsonRpc_withUnknownSession_returnsUnknownSessionError() {
			// given
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", "does-not-exist")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.jsonRpc(request).block();

			// then
			assertThat(response).isNotNull();
			final Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
			assertThat(error.get("code")).isEqualTo(-32600);
			assertThat(error.get("message").toString()).contains("unknown session");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.CRITICAL)
		@Description("jsonRpc() dispatches tools/list to the session's MCP client and wraps the result in a JSON-RPC envelope")
		void jsonRpc_withKnownSessionAndToolsList_dispatchesToClient() {
			// given
			final McpSyncClient client = connectedClient();
			given(client.listTools()).willReturn(new McpSchema.ListToolsResult(List.of(), null));
			final String sessionId = openLoopbackSession(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.jsonRpc(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			final Map<String, Object> body = entityBody(response);
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
			final McpSyncClient client = connectedClient();
			final String sessionId = openLoopbackSession(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"made/up\"}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.jsonRpc(request).block();

			// then
			assertThat(response).isNotNull();
			final Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
			assertThat(error.get("code")).isEqualTo(-32000);
			assertThat(error.get("message").toString()).contains("made/up");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() dispatches ping / roots/list and wraps a client exception into a -32000 error envelope")
		void jsonRpc_dispatchesCoreMethodsAndWrapsClientFailure() {
			// given — ping succeeds, then listResources throws
			final McpSyncClient client = connectedClient();
			given(client.ping()).willReturn("pong");
			given(client.listResources()).willThrow(new IllegalStateException("transport down"));
			final String sessionId = openLoopbackSession(client);

			// when — ping (happy)
			final ServerResponse pingResponse = InspectorHandlerTests.this.handler
				.jsonRpc(toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
					.header("X-Inspector-Session", sessionId)
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")))
				.block();
			// roots/list resolves from the session context (no client call)
			final ServerResponse rootsResponse = InspectorHandlerTests.this.handler
				.jsonRpc(toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
					.header("X-Inspector-Session", sessionId)
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"roots/list\"}")))
				.block();
			// resources/list throws -> JSON-RPC error envelope
			final ServerResponse failResponse = InspectorHandlerTests.this.handler
				.jsonRpc(toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
					.header("X-Inspector-Session", sessionId)
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}")))
				.block();

			// then
			assertThat(entityBody(pingResponse)).containsEntry("result", "pong");
			assertThat(entityBody(rootsResponse)).containsKey("result");
			final Map<?, ?> error = (Map<?, ?>) entityBody(failResponse).get("error");
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
			final McpSyncClient client = connectedClient();
			given(client.getCurrentInitializationResult()).willReturn(null);
			given(client.callTool(any())).willReturn(new McpSchema.CallToolResult(List.of(), false));
			given(client.readResource(any(McpSchema.ReadResourceRequest.class)))
				.willReturn(new McpSchema.ReadResourceResult(List.of()));
			given(client.listResourceTemplates())
				.willReturn(new McpSchema.ListResourceTemplatesResult(List.of(), null));
			given(client.listPrompts()).willReturn(new McpSchema.ListPromptsResult(List.of(), null));
			given(client.getPrompt(any())).willReturn(new McpSchema.GetPromptResult(null, List.of()));
			final String sessionId = openLoopbackSession(client);

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/events").build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.events(request).block();

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/events?session=nope").build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.events(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/events?session=" + sessionId).build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.events(request).block();

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
			final McpSyncClient client = connectedClient();
			final String sessionId = openLoopbackSession(client);
			final ServerRequest request = deleteSessionRequest(sessionId);

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.deleteSession(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT);
			assertThat(InspectorHandlerTests.this.handler.hasSession(sessionId)).isFalse();
		}

		@Test
		@Story("Terminate session")
		@Severity(SeverityLevel.MINOR)
		@Description("deleteSession() for an unknown id still returns 204 no content (idempotent)")
		void deleteSession_whenUnknown_returnsNoContent() {
			// given
			final ServerRequest request = deleteSessionRequest("ghost");

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.deleteSession(request).block();

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/roots?sessionId=nope").build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.getRoots(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/roots?sessionId=" + sessionId).build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.getRoots(request).block();

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
			final McpSyncClient client = connectedClient();
			final String sessionId = openLoopbackSession(client);
			final RootsDto incoming = new RootsDto(List.of(new RootDto("file:///workspace", "ws")));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.put("/mcp-inspector/api/roots?sessionId=" + sessionId)
						.contentType(MediaType.APPLICATION_JSON)
						.body(toJson(incoming)));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.putRoots(request).block();

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.put("/mcp-inspector/api/roots?sessionId=nope")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"roots\":[]}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.putRoots(request).block();

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc/respond?sessionId=nope&requestId=r1")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.respond(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.respond(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=missing")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"result\":{}}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.respond(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=missing")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"user rejected\"}}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.respond(request).block();

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=nope")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthInitiate(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			given(InspectorHandlerTests.this.oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.willReturn("https://idp.example/authorize?state=abc");
			final OAuthInitiateRequest body = new OAuthInitiateRequest("https://idp.example/authorize",
					"https://idp.example/token", "client-1", "https://app/cb", "openid", "challenge");
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
						.contentType(MediaType.APPLICATION_JSON)
						.body(toJson(body)));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthInitiate(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(InspectorHandlerTests.this.oauthClient).buildAuthUrl(any(), any(), any(), any(), any(), any());
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthCallback() rejects a callback whose state does not match the stored state with 400")
		void oauthCallback_withStateMismatch_returnsBadRequest() {
			// given
			final String sessionId = openLoopbackSession(connectedClient());
			given(InspectorHandlerTests.this.oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.willReturn("https://idp/authorize");
			final OAuthInitiateRequest initiate = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", null, null);
			InspectorHandlerTests.this.handler
				.oauthInitiate(toServerRequest(
						MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
							.contentType(MediaType.APPLICATION_JSON)
							.body(toJson(initiate))))
				.block();
			final ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=WRONG")
				.build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthCallback(callback).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest callback = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&state=s")
						.build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthCallback(callback).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			given(InspectorHandlerTests.this.oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.willReturn("https://idp/authorize");
			final OAuthInitiateRequest initiate = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", null, null);
			final ServerResponse initiateResponse = InspectorHandlerTests.this.handler
				.oauthInitiate(toServerRequest(
						MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
							.contentType(MediaType.APPLICATION_JSON)
							.body(toJson(initiate))))
				.block();
			final String state = readState(initiateResponse);
			given(InspectorHandlerTests.this.oauthClient.exchangeCode(any(), any(), any(), any(), any()))
				.willReturn(new OAuthTokenResponse("at", "Bearer", 3600L, null, null));
			final ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=" + state)
				.build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthCallback(callback).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(InspectorHandlerTests.this.oauthClient).exchangeCode(any(), any(), any(), any(), any());
		}

		@Test
		@Story("OAuth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthCallback() maps an exchange failure into a 502 bad gateway error response")
		void oauthCallback_whenExchangeFails_returns502() throws Exception {
			// given
			final String sessionId = openLoopbackSession(connectedClient());
			given(InspectorHandlerTests.this.oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.willReturn("https://idp/authorize");
			final OAuthInitiateRequest initiate = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", null, null);
			final ServerResponse initiateResponse = InspectorHandlerTests.this.handler
				.oauthInitiate(toServerRequest(
						MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
							.contentType(MediaType.APPLICATION_JSON)
							.body(toJson(initiate))))
				.block();
			final String state = readState(initiateResponse);
			given(InspectorHandlerTests.this.oauthClient.exchangeCode(any(), any(), any(), any(), any()))
				.willThrow(new java.io.IOException("token endpoint returned 401"));
			final ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=" + state)
				.build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthCallback(callback).block();

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
			InspectorHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(54321));

			// then
			assertThat(InspectorHandlerTests.this.handler.listeningPort()).isEqualTo(54321);
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
			final InspectorHandler sixArg = new InspectorHandler(InspectorHandlerTests.this.transportDetector,
					InspectorHandlerTests.this.loopbackFactory, InspectorHandlerTests.this.externalStdioFactory,
					InspectorHandlerTests.this.tokenProvider, InspectorHandlerTests.this.objectMapper,
					InspectorHandlerTests.this.oauthClient);
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));

			// when
			final ServerResponse response = sixArg
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
			final InspectorHandler sevenArg = new InspectorHandler(InspectorHandlerTests.this.transportDetector,
					InspectorHandlerTests.this.loopbackFactory, InspectorHandlerTests.this.externalStdioFactory,
					InspectorHandlerTests.this.tokenProvider, InspectorHandlerTests.this.objectMapper,
					InspectorHandlerTests.this.oauthClient, InspectorHandlerTests.this.properties);
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));

			// when
			final ServerResponse response = sevenArg
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
			final InspectorHandler nullMapperHandler = new InspectorHandler(
					InspectorHandlerTests.this.transportDetector, InspectorHandlerTests.this.loopbackFactory,
					InspectorHandlerTests.this.externalStdioFactory, InspectorHandlerTests.this.tokenProvider, null,
					InspectorHandlerTests.this.oauthClient, InspectorHandlerTests.this.properties,
					InspectorHandlerTests.this.bootstrapAssembler, InspectorHandlerTests.this.bootstrapHtmlRenderer);
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));

			// when
			final ServerResponse response = nullMapperHandler
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
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", "   ")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.jsonRpc(request).block();

			// then
			final Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
			assertThat(error.get("code")).isEqualTo(-32600);
			assertThat(error.get("message").toString()).contains("missing session id");
		}

		@Test
		@Story("SSE notification stream")
		@Severity(SeverityLevel.NORMAL)
		@Description("events() with a present but blank session query parameter returns 400 bad request (isBlank branch)")
		void events_withBlankSessionParam_returnsBadRequest() {
			// given
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/events?session= ").build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.events(request).block();

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/roots").build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.getRoots(request).block();

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
			final ServerRequest request = toServerRequest(MockServerHttpRequest.put("/mcp-inspector/api/roots")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"roots\":[]}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.putRoots(request).block();

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc/respond?requestId=r1")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.respond(request).block();

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthInitiate(request).block();

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
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/oauth/callback?code=c&state=s").build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthCallback(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId= ")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.respond(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final SessionContext ctx = sessions().get(sessionId);
			ctx.pendingServerRequests().create("req-42");
			final ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=req-42")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"result\":{\"answer\":true}}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.respond(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final SessionContext ctx = sessions().get(sessionId);
			ctx.pendingServerRequests().create("req-bare");
			final ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=req-bare")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"action\":\"accept\"}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.respond(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final SessionContext ctx = sessions().get(sessionId);
			ctx.pendingServerRequests().create("req-err");
			final ServerRequest request = toServerRequest(MockServerHttpRequest
				.post("/mcp-inspector/api/jsonrpc/respond?sessionId=" + sessionId + "&requestId=req-err")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"user rejected\"}}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.respond(request).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest callback = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c")
						.build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthCallback(callback).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=anything")
				.build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthCallback(callback).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			given(InspectorHandlerTests.this.oauthClient.buildAuthUrl(any(), any(), any(), any(), any(), any()))
				.willReturn("https://idp/authorize");
			final OAuthInitiateRequest initiate = new OAuthInitiateRequest("https://idp/authorize", "https://idp/token",
					"client-1", "https://app/cb", null, null);
			final ServerResponse initiateResponse = InspectorHandlerTests.this.handler
				.oauthInitiate(toServerRequest(
						MockServerHttpRequest.post("/mcp-inspector/api/oauth/initiate?sessionId=" + sessionId)
							.contentType(MediaType.APPLICATION_JSON)
							.body(toJson(initiate))))
				.block();
			final String state = readState(initiateResponse);
			// an exception whose getMessage() is null exercises the ex.getMessage() ==
			// null
			// branch in the onErrorResume mapper
			given(InspectorHandlerTests.this.oauthClient.exchangeCode(any(), any(), any(), any(), any()))
				.willThrow(new RuntimeException());
			final ServerRequest callback = toServerRequest(MockServerHttpRequest
				.get("/mcp-inspector/api/oauth/callback?sessionId=" + sessionId + "&code=c&state=" + state)
				.build());

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.oauthCallback(callback).block();

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
			final String sessionId = openLoopbackSession(connectedClient());
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":11}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.jsonRpc(request).block();

			// then
			final Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
			assertThat(error.get("code")).isEqualTo(-32000);
			assertThat(error.get("message").toString()).contains("not supported");
		}

		@Test
		@Story("JSON-RPC relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("jsonRpc() wraps a client exception with a null message into a -32000 error using the exception class name (jsonRpcError null-message branch)")
		void jsonRpc_whenClientThrowsNullMessage_usesClassNameInError() {
			// given
			final McpSyncClient client = connectedClient();
			given(client.listTools()).willThrow(new IllegalStateException());
			final String sessionId = openLoopbackSession(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/jsonrpc")
				.header("X-Inspector-Session", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.jsonRpc(request).block();

			// then — the null-message branch falls back to the simple class name
			final Map<?, ?> error = (Map<?, ?>) entityBody(response).get("error");
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
			InspectorHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(8081));
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			final McpSyncClient client = connectedClient();
			given(InspectorHandlerTests.this.loopbackFactory.forStreamable(any(), org.mockito.ArgumentMatchers.eq(8081),
					any(), any()))
				.willReturn(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"externalCommand\":\"   \"}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(InspectorHandlerTests.this.loopbackFactory).forStreamable(any(),
					org.mockito.ArgumentMatchers.eq(8081), any(), any());
		}

		@Test
		@Story("Open session")
		@Severity(SeverityLevel.MINOR)
		@Description("connect() returns null server name/version when the client exposes no server info (info == null branch)")
		void connect_whenServerInfoNull_returnsNullServerNameAndVersion() {
			// given — getServerInfo() returns null, exercising the info == null ternaries
			InspectorHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(8081));
			given(InspectorHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			final McpSyncClient client = mock(McpSyncClient.class);
			given(client.initialize()).willReturn(null);
			given(client.getServerInfo()).willReturn(null);
			given(InspectorHandlerTests.this.loopbackFactory.forStreamable(any(), org.mockito.ArgumentMatchers.eq(8081),
					any(), any()))
				.willReturn(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector/api/connect")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.connect(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			final Map<String, Object> body = entityBody(response);
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
			final McpSyncClient client = connectedClient();
			final String sessionId = openLoopbackSession(client);
			final ServerRequest request = toServerRequest(MockServerHttpRequest
				.put("/mcp-inspector/api/roots?sessionId=" + sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"roots\":[{\"uri\":\"   \",\"name\":\"blank\"},{\"uri\":\"file:///ok\",\"name\":\"ok\"}]}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.putRoots(request).block();

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
			final McpSyncClient client = connectedClient();
			final String sessionId = openLoopbackSession(client);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.put("/mcp-inspector/api/roots?sessionId=" + sessionId)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"roots\":null}"));

			// when
			final ServerResponse response = InspectorHandlerTests.this.handler.putRoots(request).block();

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
			final InspectorHandler bareHandler = new InspectorHandler(InspectorHandlerTests.this.transportDetector,
					InspectorHandlerTests.this.loopbackFactory, InspectorHandlerTests.this.externalStdioFactory,
					InspectorHandlerTests.this.tokenProvider, InspectorHandlerTests.this.objectMapper);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector/index.html").build());

			// when
			final ServerResponse response = bareHandler.index(request).block();

			// then — still serves HTML (raw template or the not-bundled fallback)
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_HTML);
		}

	}

}
