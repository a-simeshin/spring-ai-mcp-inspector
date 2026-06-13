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

package io.inspector.mcp.webflux.proxy;

import java.net.URI;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
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
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.EntityResponse;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ProxyHandler}. Exercises the upstream-compatible reactive proxy
 * surface (health, config, fetch, SSE open, postMessage, Streamable-HTTP post/get/delete)
 * with the registry / transport factory / proxy collaborators mocked — no live sockets.
 */
@Epic("MCP Inspector WebFlux")
@Feature("ProxyHandler reactive proxy surface")
class ProxyHandlerTests {

	private static final HandlerStrategies STRATEGIES = HandlerStrategies.withDefaults();

	private ProxySessionRegistry registry;

	private ProxyTransportFactory transportFactory;

	private McpProxy mcpProxy;

	private TransportDetector transportDetector;

	private JsonMapper objectMapper;

	private McpInspectorProperties properties;

	private ProxyHandler handler;

	@BeforeEach
	void setUp() {
		this.registry = mock(ProxySessionRegistry.class);
		this.transportFactory = mock(ProxyTransportFactory.class);
		this.mcpProxy = mock(McpProxy.class);
		this.transportDetector = mock(TransportDetector.class);
		this.objectMapper = new JsonMapper();
		this.properties = new McpInspectorProperties();
		given(this.mcpProxy.start(any())).willReturn(Mono.empty());
		this.handler = new ProxyHandler(this.registry, this.transportFactory, this.mcpProxy, this.transportDetector,
				this.objectMapper, this.properties);
	}

	private ServerRequest toServerRequest(final MockServerHttpRequest request) {
		final MockServerWebExchange exchange = MockServerWebExchange.from(request);
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> entityBody(final ServerResponse response) {
		return (Map<String, Object>) ((EntityResponse<Object>) response).entity();
	}

	private static Object extractBodyFlux(final ServerResponse response) {
		return ((EntityResponse<Object>) response).entity();
	}

	private static ProxySession newSession(final String id) {
		final McpClientTransport transport = mock(McpClientTransport.class);
		given(transport.closeGracefully()).willReturn(Mono.empty());
		final reactor.core.publisher.Sinks.Many<JsonNode> b2t = reactor.core.publisher.Sinks.many()
			.unicast()
			.onBackpressureBuffer();
		final reactor.core.publisher.Sinks.Many<JsonNode> t2b = reactor.core.publisher.Sinks.many().replay().limit(256);
		return new ProxySession(id, transport, b2t, t2b);
	}

	private static WebServerInitializedEvent webServerStartedEvent(final int port) {
		final WebServer webServer = mock(WebServer.class);
		given(webServer.getPort()).willReturn(port);
		final WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
		given(event.getWebServer()).willReturn(webServer);
		return event;
	}

	@Nested
	@DisplayName("health()")
	class Health {

		@Test
		@Story("Liveness")
		@Severity(SeverityLevel.NORMAL)
		@Description("health() returns 200 with a {status:ok} JSON body")
		void health_always_returnsOkStatus() {
			// given
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/health").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.health(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(entityBody(response)).containsEntry("status", "ok");
		}

	}

	@Nested
	@DisplayName("config()")
	class Config {

		@Test
		@Story("Client form defaults")
		@Severity(SeverityLevel.NORMAL)
		@Description("config() maps an SSE detected transport into defaultTransport=sse and a server URL")
		void config_withSseTransport_mapsDefaultsAndServerUrl() {
			// given
			ProxyHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(7000));
			given(ProxyHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/config").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.config(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			final Map<String, Object> body = entityBody(response);
			assertThat(body).containsEntry("defaultTransport", "sse");
			assertThat(body.get("defaultServerUrl").toString()).contains("http://localhost:7000");
		}

		@Test
		@Story("Client form defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() with an UNKNOWN transport yields an empty defaultTransport and empty server URL")
		void config_withUnknownTransport_mapsEmptyDefaults() {
			// given — no onWebServerStarted, port stays -1
			given(ProxyHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.UNKNOWN, null, null, "WEBFLUX"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/config").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.config(request).block();

			// then
			final Map<String, Object> body = entityBody(response);
			assertThat(body).containsEntry("defaultTransport", "");
			assertThat(body).containsEntry("defaultServerUrl", "");
		}

		@Test
		@Story("Client form defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() maps a STREAMABLE transport into defaultTransport=streamable-http")
		void config_withStreamableTransport_mapsStreamableHttp() {
			// given
			ProxyHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(7000));
			given(ProxyHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/config").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.config(request).block();

			// then
			assertThat(entityBody(response)).containsEntry("defaultTransport", "streamable-http");
		}

		@Test
		@Story("Client form defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() maps a STDIO_NO_HTTP transport to defaultTransport=stdio and an empty server URL")
		void config_withStdioTransport_mapsStdioAndEmptyUrl() {
			// given
			ProxyHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(7000));
			given(ProxyHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STDIO_NO_HTTP, null, null, "STDIO"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/config").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.config(request).block();

			// then
			final Map<String, Object> body = entityBody(response);
			assertThat(body).containsEntry("defaultTransport", "stdio");
			assertThat(body).containsEntry("defaultServerUrl", "");
		}

		@Test
		@Story("Client form defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() with a STREAMABLE transport but a blank endpoint defaults the server URL path to /mcp")
		void config_withBlankEndpoint_defaultsServerUrlToMcp() {
			// given
			ProxyHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(7000));
			given(ProxyHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "", null, "WEBFLUX"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/config").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.config(request).block();

			// then
			assertThat(entityBody(response).get("defaultServerUrl").toString()).endsWith(":7000/mcp");
		}

	}

	@Nested
	@DisplayName("fetch()")
	class Fetch {

		@Test
		@Story("Outbound HTTP proxy")
		@Severity(SeverityLevel.CRITICAL)
		@Description("fetch() proxies a real http GET and maps the upstream status, headers and body into the envelope")
		void fetch_withRealHttpTarget_returnsUpstreamBody() throws Exception {
			// given — a tiny loopback HTTP server returning a known body
			final com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
				.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/echo", (exchange) -> {
				final byte[] payload = "hello-proxy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("X-Demo", "yes");
				exchange.sendResponseHeaders(200, payload.length);
				exchange.getResponseBody().write(payload);
				exchange.close();
			});
			server.start();
			try {
				final int port = server.getAddress().getPort();
				final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/fetch")
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"url\":\"http://127.0.0.1:" + port
							+ "/echo\",\"init\":{\"method\":\"GET\",\"headers\":{\"X-Req\":\"1\"}}}"));

				// when
				final ServerResponse response = ProxyHandlerTests.this.handler.fetch(request).block();

				// then
				assertThat(response).isNotNull();
				assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
				final Map<String, Object> body = entityBody(response);
				assertThat(body).containsEntry("ok", true)
					.containsEntry("status", 200)
					.containsEntry("body", "hello-proxy");
			}
			finally {
				server.stop(0);
			}
		}

		@Test
		@Story("Outbound HTTP proxy")
		@Severity(SeverityLevel.NORMAL)
		@Description("fetch() rejects a non-http(s) URL with a 502 error envelope")
		void fetch_withNonHttpUrl_returns502() {
			// given
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/fetch")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"url\":\"file:///etc/passwd\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.fetch(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			assertThat(entityBody(response).get("error").toString()).contains("http/https");
		}

		@Test
		@Story("Outbound HTTP proxy")
		@Severity(SeverityLevel.MINOR)
		@Description("fetch() rejects a body missing the url field with a 502 error envelope")
		void fetch_withMissingUrl_returns502() {
			// given
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/fetch")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.fetch(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			assertThat(entityBody(response).get("error").toString()).contains("url");
		}

	}

	@Nested
	@DisplayName("openSse() / openStdio()")
	class OpenSse {

		@Test
		@Story("SSE proxy open")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() builds the target transport, registers a session, starts the proxy and streams SSE")
		void openSse_withSseUrl_registersSessionAndReturnsEventStream() {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openSse(any(URI.class))).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse&url=http://up/sse").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
			verify(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
			verify(ProxyHandlerTests.this.mcpProxy).start(any(ProxySession.class));
		}

		@Test
		@Story("SSE proxy open")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with the streamable-http transport type builds the target via openStreamable")
		void openSse_withStreamableHttpType_usesStreamableFactory() {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class))).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=streamable-http&url=http://up/mcp")
						.build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).openStreamable(any(URI.class));
		}

		@Test
		@Story("SSE proxy open")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with an unsupported transportType returns 400 bad request")
		void openSse_withUnsupportedType_returnsBadRequest() {
			// given
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=carrier-pigeon&url=http://up")
						.build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("SSE proxy open")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() streams the SSE prologue then maps a target frame into a message event")
		void openSse_streamsPrologueThenMappedFrame() {
			// given
			final ProxySession[] captured = new ProxySession[1];
			willAnswer((inv) -> {
				captured[0] = inv.getArgument(0);
				return null;
			}).given(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openSse(any(URI.class))).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse&url=http://up/sse").build());

			// when — open the stream, then push one upstream frame into the session
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();
			final JsonNode frame = ProxyHandlerTests.this.objectMapper.createObjectNode().put("hello", "world");
			captured[0].targetToBrowser().tryEmitNext(frame);
			captured[0].targetToBrowser().tryEmitComplete();

			// then — the body flux begins with the endpoint prologue and then a message
			// event
			@SuppressWarnings("unchecked")
			final reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> body = (reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>>) extractBodyFlux(
					response);
			reactor.test.StepVerifier.create(body)
				.assertNext((sse) -> assertThat(sse.event()).isEqualTo("endpoint"))
				.assertNext((sse) -> {
					assertThat(sse.event()).isEqualTo("message");
					assertThat(sse.data()).contains("world");
				})
				.verifyComplete();
		}

		@Test
		@Story("SSE proxy open")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with an sse transport but missing url returns 400 bad request")
		void openSse_withMissingUrl_returnsBadRequest() {
			// given
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Stdio proxy open")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() without a command returns 400 bad request")
		void openStdio_withoutCommand_returnsBadRequest() {
			// given
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/stdio").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openStdio(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Stdio proxy open")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() with a command + args + env builds the stdio transport and streams SSE")
		void openStdio_withCommand_registersSessionAndReturnsEventStream() {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStdio(any(), any())).willReturn(target);
			final URI uri = URI
				.create("/mcp-inspector-api/stdio?command=node&args=server.js&env=%7B%22A%22%3A%221%22%7D");
			final ServerRequest request = toServerRequest(MockServerHttpRequest.method(HttpMethod.GET, uri).build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openStdio(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
			verify(ProxyHandlerTests.this.transportFactory).openStdio(any(), any());
		}

		@Test
		@Story("Stdio proxy open")
		@Severity(SeverityLevel.MINOR)
		@Description("openStdio() with malformed env JSON silently falls back to an empty environment")
		void openStdio_withMalformedEnv_fallsBackToEmptyEnv() {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStdio(any(), any())).willReturn(target);
			final URI uri = URI.create("/mcp-inspector-api/stdio?command=node&env=not-json");
			final ServerRequest request = toServerRequest(MockServerHttpRequest.method(HttpMethod.GET, uri).build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openStdio(request).block();

			// then — still opens (malformed env ignored), 200 SSE
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).openStdio(any(), org.mockito.ArgumentMatchers.eq(Map.of()));
		}

	}

	@Nested
	@DisplayName("postMessage()")
	class PostMessage {

		@Test
		@Story("SSE message push")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMessage() without a sessionId returns 400 bad request")
		void postMessage_withoutSessionId_returnsBadRequest() {
			// given
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/message")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMessage(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("SSE message push")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMessage() for an unknown session returns 404")
		void postMessage_withUnknownSession_returnsNotFound() {
			// given
			given(ProxyHandlerTests.this.registry.get("nope")).willReturn(null);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector-api/message?sessionId=nope")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMessage(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("SSE message push")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMessage() pushes the frame into the session's browser->target sink and returns 202 accepted")
		void postMessage_withKnownSession_emitsFrameAndReturnsAccepted() {
			// given
			final ProxySession session = newSession("s1");
			given(ProxyHandlerTests.this.registry.get("s1")).willReturn(session);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector-api/message?sessionId=s1")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMessage(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

		@Test
		@Story("SSE message push")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMessage() maps a sink emit failure into a 500 error envelope")
		void postMessage_whenEmitFails_returns500() {
			// given — a session whose browser->target sink is already terminated, so
			// emits fail
			final ProxySession session = newSession("s-fail");
			session.browserToTarget().tryEmitComplete();
			given(ProxyHandlerTests.this.registry.get("s-fail")).willReturn(session);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector-api/message?sessionId=s-fail")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMessage(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(entityBody(response).get("error").toString()).contains("emit failed");
		}

	}

	@Nested
	@DisplayName("postMcp() — Streamable-HTTP")
	class PostMcp {

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() opening a new session without a url returns 400 bad request")
		void postMcp_newSessionWithoutUrl_returnsBadRequest() {
			// given
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() opening a new session maps an upstream connect failure into a 502 bad gateway")
		void postMcp_newSessionWhenUpstreamConnectFails_returns502() {
			// given
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willThrow(new RuntimeException("connection refused"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector-api/mcp?url=http://up/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() relaying a request to a known session awaits and returns the matching upstream response")
		void postMcp_requestToKnownSession_returnsMatchingUpstreamResponse() {
			// given — a known session
			final ProxySession session = newSession("s-known");
			given(ProxyHandlerTests.this.registry.get("s-known")).willReturn(session);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s-known")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"ping\"}"));

			// when — feed the matching upstream response shortly after subscription
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request)
				.doOnSubscribe((s) -> new Thread(() -> {
					try {
						Thread.sleep(50);
					}
					catch (final InterruptedException ignored) {
						Thread.currentThread().interrupt();
					}
					final JsonNode reply = ProxyHandlerTests.this.objectMapper.createObjectNode()
						.put("jsonrpc", "2.0")
						.put("id", 99);
					session.targetToBrowser().tryEmitNext(reply);
				}).start())
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() referencing an unknown mcp-session-id returns 404")
		void postMcp_withUnknownSessionHeader_returnsNotFound() {
			// given
			given(ProxyHandlerTests.this.registry.get("ghost")).willReturn(null);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "ghost")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() relaying a notification (no id) into a known session emits and returns 202 accepted")
		void postMcp_notificationToKnownSession_returnsAccepted() {
			// given
			final ProxySession session = newSession("s2");
			given(ProxyHandlerTests.this.registry.get("s2")).willReturn(session);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s2")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() opening a new session relays a request frame and awaits the matching upstream response")
		void postMcp_newSessionRequest_awaitsAndReturnsUpstreamResponse() {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class))).willReturn(target);
			final ProxySession[] captured = new ProxySession[1];
			given(ProxyHandlerTests.this.registry.get(any())).willAnswer((inv) -> captured[0]);
			willAnswer((inv) -> {
				captured[0] = inv.getArgument(0);
				return null;
			}).given(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector-api/mcp?url=http://up/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\"}"));

			// when — subscribe, then feed the matching upstream response into the session
			final Mono<ServerResponse> mono = ProxyHandlerTests.this.handler.postMcp(request);
			final ServerResponse response = mono.doOnSubscribe((s) -> {
				try {
					final JsonNode reply = ProxyHandlerTests.this.objectMapper
						.readTree("{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{\"ok\":true}}");
					// give the awaiter a tick to register before emitting
					new Thread(() -> {
						try {
							Thread.sleep(50);
						}
						catch (final InterruptedException ignored) {
							Thread.currentThread().interrupt();
						}
						captured[0].targetToBrowser().tryEmitNext(reply);
					}).start();
				}
				catch (final Exception ex) {
					throw new IllegalStateException(ex);
				}
			}).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
			verify(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
		}

	}

	@Nested
	@DisplayName("getMcp() / deleteMcp()")
	class GetDeleteMcp {

		@Test
		@Story("Streamable-HTTP stream")
		@Severity(SeverityLevel.NORMAL)
		@Description("getMcp() without a known session returns 404")
		void getMcp_withUnknownSession_returnsNotFound() {
			// given
			final ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector-api/mcp").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.getMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Streamable-HTTP stream")
		@Severity(SeverityLevel.NORMAL)
		@Description("getMcp() for a known session returns an SSE (text/event-stream) response")
		void getMcp_withKnownSession_returnsEventStream() {
			// given
			final ProxySession session = newSession("s3");
			given(ProxyHandlerTests.this.registry.get("s3")).willReturn(session);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.get("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s3")
				.build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.getMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
		}

		@Test
		@Story("Streamable-HTTP delete")
		@Severity(SeverityLevel.NORMAL)
		@Description("deleteMcp() removing a known session returns 200 ok")
		void deleteMcp_whenSessionRemoved_returnsOk() {
			// given
			given(ProxyHandlerTests.this.registry.removeAndClose("s4")).willReturn(true);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.delete("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s4")
				.build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.deleteMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
		}

		@Test
		@Story("Streamable-HTTP delete")
		@Severity(SeverityLevel.MINOR)
		@Description("deleteMcp() for an unknown session returns 404 not found")
		void deleteMcp_whenSessionUnknown_returnsNotFound() {
			// given
			given(ProxyHandlerTests.this.registry.removeAndClose(any())).willReturn(false);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.delete("/mcp-inspector-api/mcp").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.deleteMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

	}

	@Nested
	@DisplayName("constructor / config edge branches")
	class ConstructorAndConfigEdges {

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("the 5-arg constructor (no properties) defaults the SSE prologue endpoint to the legacy proxy path")
		void openSse_withFiveArgConstructor_usesLegacyProxyBaseInPrologue() {
			// given — the 5-arg overload leaves properties null (proxyBase fallback +
			// objectMapper fallback branches)
			final ProxyHandler fiveArg = new ProxyHandler(ProxyHandlerTests.this.registry,
					ProxyHandlerTests.this.transportFactory, ProxyHandlerTests.this.mcpProxy,
					ProxyHandlerTests.this.transportDetector, null);
			final ProxySession[] captured = new ProxySession[1];
			willAnswer((inv) -> {
				captured[0] = inv.getArgument(0);
				return null;
			}).given(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openSse(any(URI.class))).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse&url=http://up/sse").build());

			// when
			final ServerResponse response = fiveArg.openSse(request).block();
			captured[0].targetToBrowser().tryEmitComplete();

			// then — the prologue carries the legacy /mcp-inspector-api path
			@SuppressWarnings("unchecked")
			final reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> body = (reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>>) extractBodyFlux(
					response);
			reactor.test.StepVerifier.create(body)
				.assertNext((sse) -> assertThat(sse.data()).startsWith("/mcp-inspector-api/message?sessionId="))
				.verifyComplete();
		}

		@Test
		@Story("Client form defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() maps a null transport type to an empty defaultTransport (mapTransport type == null branch)")
		void config_withNullTransportType_mapsEmptyTransport() {
			// given — a detected transport whose type is null
			ProxyHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(7000));
			given(ProxyHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(null, "/mcp", null, "WEBFLUX"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/config").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.config(request).block();

			// then — mapTransport's null arm yields "", while buildServerUrl still builds
			// a
			// URL because a null type is neither UNKNOWN nor STDIO_NO_HTTP
			final Map<String, Object> body = entityBody(response);
			assertThat(body).containsEntry("defaultTransport", "");
			assertThat(body.get("defaultServerUrl").toString()).isEqualTo("http://localhost:7000/mcp");
		}

		@Test
		@Story("Client form defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() maps a STATELESS transport into defaultTransport=streamable-http (combined switch arm)")
		void config_withStatelessTransport_mapsStreamableHttp() {
			// given
			ProxyHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(7000));
			given(ProxyHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STATELESS, "/mcp", null, "WEBFLUX"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/config").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.config(request).block();

			// then
			assertThat(entityBody(response)).containsEntry("defaultTransport", "streamable-http");
		}

		@Test
		@Story("Client form defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() yields an empty server URL when the listening port is still unknown (port <= 0 branch)")
		void config_whenPortUnknown_returnsEmptyServerUrl() {
			// given — no onWebServerStarted, so listeningPort stays -1 with a usable
			// transport
			given(ProxyHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBFLUX"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/config").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.config(request).block();

			// then
			assertThat(entityBody(response)).containsEntry("defaultServerUrl", "");
		}

	}

	@Nested
	@DisplayName("fetch() — request/response mapping branches")
	class FetchBranches {

		@Test
		@Story("Outbound HTTP proxy")
		@Severity(SeverityLevel.NORMAL)
		@Description("fetch() proxies a POST with a body + custom headers and maps a non-2xx upstream status to ok=false")
		void fetch_withPostBodyHeadersAndNon2xxStatus_mapsOkFalse() throws Exception {
			// given — an upstream that echoes a 404 so the ok = status in [200,300)
			// branch
			// resolves false, with init.method/body/headers all present
			final com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
				.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/notfound", (exchange) -> {
				final byte[] payload = "nope".getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(404, payload.length);
				exchange.getResponseBody().write(payload);
				exchange.close();
			});
			server.start();
			try {
				final int port = server.getAddress().getPort();
				final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/fetch")
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"url\":\"http://127.0.0.1:" + port + "/notfound\",\"init\":{\"method\":\"POST\","
							+ "\"body\":\"payload\",\"headers\":{\"X-Custom\":\"v\",\"Host\":\"illegal\"}}}"));

				// when
				final ServerResponse response = ProxyHandlerTests.this.handler.fetch(request).block();

				// then
				assertThat(response).isNotNull();
				assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
				final Map<String, Object> body = entityBody(response);
				assertThat(body).containsEntry("ok", false).containsEntry("status", 404).containsEntry("body", "nope");
			}
			finally {
				server.stop(0);
			}
		}

		@Test
		@Story("Outbound HTTP proxy")
		@Severity(SeverityLevel.MINOR)
		@Description("fetch() with a non-textual url node returns a 502 invalid-url error (urlNode not textual branch)")
		void fetch_withNonTextualUrl_returns502() {
			// given — url is a number, so urlNode.isTextual() is false
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/fetch")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"url\":123}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.fetch(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			assertThat(entityBody(response).get("error").toString()).contains("url");
		}

		@Test
		@Story("Outbound HTTP proxy")
		@Severity(SeverityLevel.MINOR)
		@Description("fetch() with a URL that has no scheme returns a 502 http/https-only error (scheme == null branch)")
		void fetch_withSchemelessUrl_returns502() {
			// given — a relative URL has a null scheme
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/fetch")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"url\":\"/relative/path\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.fetch(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			assertThat(entityBody(response).get("error").toString()).contains("http/https");
		}

	}

	@Nested
	@DisplayName("open transports — blank-input branches")
	class OpenBlankInputBranches {

		@Test
		@Story("Stdio proxy open")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() with a blank command returns 400 (command isBlank branch, distinct from null)")
		void openStdio_withBlankCommand_returnsBadRequest() {
			// given — a present but blank command value
			final URI uri = URI.create("/mcp-inspector-api/stdio?command=%20%20");
			final ServerRequest request = toServerRequest(MockServerHttpRequest.method(HttpMethod.GET, uri).build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openStdio(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Stdio proxy open")
		@Severity(SeverityLevel.MINOR)
		@Description("openStdio() with a blank args value adds no extra argv entries (args isBlank branch)")
		void openStdio_withBlankArgs_usesCommandOnly() {
			// given — blank args exercises the `args != null && !args.isBlank()` false
			// path
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStdio(any(), any())).willReturn(target);
			final URI uri = URI.create("/mcp-inspector-api/stdio?command=node&args=%20");
			final ServerRequest request = toServerRequest(MockServerHttpRequest.method(HttpMethod.GET, uri).build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openStdio(request).block();

			// then — only the command becomes argv, no split args
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).openStdio(
					org.mockito.ArgumentMatchers.eq(java.util.List.of("node")),
					org.mockito.ArgumentMatchers.eq(Map.of()));
		}

		@Test
		@Story("Stdio proxy open")
		@Severity(SeverityLevel.MINOR)
		@Description("openStdio() with a blank env value falls back to an empty environment (env isBlank branch)")
		void openStdio_withBlankEnv_fallsBackToEmptyEnv() {
			// given — blank env exercises parseEnv's `env.isBlank()` branch
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStdio(any(), any())).willReturn(target);
			final URI uri = URI.create("/mcp-inspector-api/stdio?command=node&env=%20");
			final ServerRequest request = toServerRequest(MockServerHttpRequest.method(HttpMethod.GET, uri).build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openStdio(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).openStdio(any(), org.mockito.ArgumentMatchers.eq(Map.of()));
		}

		@Test
		@Story("SSE proxy open")
		@Severity(SeverityLevel.MINOR)
		@Description("openSse() with a present but blank url returns 400 (requireUrl isBlank branch)")
		void openSse_withBlankUrl_returnsBadRequest() {
			// given
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse&url= ").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

	}

	@Nested
	@DisplayName("postMcp() — extra dispatch branches")
	class PostMcpBranches {

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() opening a new session with a blank url returns 400 (url isBlank branch)")
		void postMcp_newSessionWithBlankUrl_returnsBadRequest() {
			// given — blank url query parameter
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp?url= ")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() with a present but blank mcp-session-id opens a new session (mcpSessionId isBlank branch)")
		void postMcp_withBlankSessionHeader_opensNewSession() {
			// given — a blank session header must NOT be treated as an existing session;
			// with no url it must take the open-new-session path and 400 on missing url
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "   ")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() opening a new session with a notification (no id) returns 202 and includes the mcp-session-id header")
		void postMcp_newSessionNotification_returnsAcceptedWithSessionHeader() {
			// given — a new session + a notification frame: includeSessionHeader == true
			// on
			// the 202 path
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class))).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector-api/mcp?url=http://up/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
			assertThat(response.headers().getFirst(ProxyConstants.MCP_SESSION_ID_HEADER)).isNotBlank();
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() relaying a frame whose id is explicitly null is treated as a notification and returns 202 (id isNull branch)")
		void postMcp_frameWithNullId_treatedAsNotification() {
			// given — extractRequestId's id.isNull() branch: an explicit null id
			final ProxySession session = newSession("s-null-id");
			given(ProxyHandlerTests.this.registry.get("s-null-id")).willReturn(session);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s-null-id")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() relaying a non-object JSON frame is treated as a notification (extractRequestId body not-object branch)")
		void postMcp_nonObjectFrame_treatedAsNotification() {
			// given — a JSON array body is not an object
			final ProxySession session = newSession("s-array");
			given(ProxyHandlerTests.this.registry.get("s-array")).willReturn(session);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s-array")
				.contentType(MediaType.APPLICATION_JSON)
				.body("[1,2,3]"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() relaying a notification into a session whose sink is terminated returns a 500 emit-failed error")
		void postMcp_notificationWhenEmitFails_returns500() {
			// given — a known session whose browser->target sink is already completed
			final ProxySession session = newSession("s-emit-fail");
			session.browserToTarget().tryEmitComplete();
			given(ProxyHandlerTests.this.registry.get("s-emit-fail")).willReturn(session);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s-emit-fail")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(entityBody(response).get("error").toString()).contains("emit failed");
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() ignores non-matching upstream frames (different id, non-object) before returning the matching response")
		void postMcp_skipsNonMatchingFramesThenReturnsMatch() {
			// given — a known session; upstream emits a wrong-id frame and a non-object
			// frame (matchesId mismatch + body-not-object branches) before the real reply
			final ProxySession session = newSession("s-skip");
			given(ProxyHandlerTests.this.registry.get("s-skip")).willReturn(session);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s-skip")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request)
				.doOnSubscribe((s) -> new Thread(() -> {
					try {
						Thread.sleep(50);
					}
					catch (final InterruptedException ignored) {
						Thread.currentThread().interrupt();
					}
					// wrong id (matchesId false), an array (matchesId not-object false),
					// then
					// the genuine matching reply
					session.targetToBrowser()
						.tryEmitNext(ProxyHandlerTests.this.objectMapper.createObjectNode().put("id", 999));
					session.targetToBrowser()
						.tryEmitNext(ProxyHandlerTests.this.objectMapper.createArrayNode().add("x"));
					session.targetToBrowser()
						.tryEmitNext(ProxyHandlerTests.this.objectMapper.createObjectNode()
							.put("jsonrpc", "2.0")
							.put("id", 7));
				}).start())
				.block();

			// then — only the matching frame resolves the awaiter with a 200 json
			// response
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		}

	}

}
