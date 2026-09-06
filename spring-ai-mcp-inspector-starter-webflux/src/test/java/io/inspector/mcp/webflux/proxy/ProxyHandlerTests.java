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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
 *
 * <p>
 * After the WAF-safe loopback fix:
 * <ul>
 * <li>{@code buildServerUrl} returns the detected endpoint as a <em>relative path</em>
 * (e.g. {@code /sse}, {@code /mcp}) rather than {@code http://localhost:<port>/...}.</li>
 * <li>A missing or blank {@code url} in {@code openSse} / {@code postMcp} is resolved to
 * the loopback {@code /sse} or {@code /mcp} endpoint server-side (port defaults to 8080
 * when the server has not yet started); the request is no longer rejected with 400.</li>
 * </ul>
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

	private static WebServerInitializedEvent managementServerStartedEvent(final int port) {
		final WebServerInitializedEvent event = webServerStartedEvent(port);
		final WebServerApplicationContext context = mock(WebServerApplicationContext.class);
		given(context.getServerNamespace()).willReturn("management");
		given(event.getApplicationContext()).willReturn(context);
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
		@Description("config() maps an SSE detected transport into defaultTransport=sse and the relative /sse path")
		void config_withSseTransport_mapsDefaultsAndServerUrl() {
			// given
			ProxyHandlerTests.this.handler.onWebServerStarted(webServerStartedEvent(7000));
			given(ProxyHandlerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBFLUX"));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/config").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.config(request).block();

			// then — defaultServerUrl is now the WAF-safe relative path "/sse", not
			// "http://localhost:7000/sse"
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			final Map<String, Object> body = entityBody(response);
			assertThat(body).containsEntry("defaultTransport", "sse");
			assertThat(body).containsEntry("defaultServerUrl", "/sse");
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

			// then — blank endpoint falls back to "/mcp" (relative), not ":7000/mcp"
			assertThat(entityBody(response)).containsEntry("defaultServerUrl", "/mcp");
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
		@Description("fetch() runs its blocking HttpClient.send() off the subscribing (event-loop) thread")
		void fetch_whenSubscribedOnANonBlockingThread_leavesThatThreadFree() throws Exception {
			// given — an upstream that holds the response open for 3s
			final CountDownLatch inFlight = new CountDownLatch(1);
			final com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
				.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/slow", (exchange) -> {
				inFlight.countDown();
				try {
					Thread.sleep(3000);
				}
				catch (final InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				exchange.sendResponseHeaders(204, -1);
				exchange.close();
			});
			server.start();
			final Scheduler loop = Schedulers.newParallel("fake-event-loop", 1);
			try {
				final int port = server.getAddress().getPort();
				final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/fetch")
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"url\":\"http://127.0.0.1:" + port + "/slow\"}"));

				// when — the exchange is subscribed on a single-worker (event-loop-like)
				// scheduler
				ProxyHandlerTests.this.handler.fetch(request).subscribeOn(loop).subscribe();
				assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

				// then — that worker is still able to run other work
				final CountDownLatch free = new CountDownLatch(1);
				loop.schedule(free::countDown);
				assertThat(free.await(1, TimeUnit.SECONDS))
					.as("the subscribing (event-loop) thread must not be parked inside HttpClient.send()")
					.isTrue();
			}
			finally {
				loop.dispose();
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
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
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
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
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
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() under a base path emits a prologue carrying the prefix so the first browser frame is routable")
		void openSse_underBasePath_prologueCarriesPrefix() {
			// given
			final ProxySession[] captured = new ProxySession[1];
			willAnswer((inv) -> {
				captured[0] = inv.getArgument(0);
				return null;
			}).given(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/app/mcp-inspector-api/sse?transportType=sse&url=http://up/sse")
						.contextPath("/app")
						.build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();
			captured[0].targetToBrowser().tryEmitComplete();

			// then
			@SuppressWarnings("unchecked")
			final reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> body = (reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>>) extractBodyFlux(
					response);
			reactor.test.StepVerifier.create(body)
				.assertNext((sse) -> assertThat(sse.data()).startsWith("/app/mcp-inspector-api/message?sessionId="))
				.verifyComplete();
		}

		@Test
		@Story("Management server guard")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A WebServerInitializedEvent from the management context does not overwrite the loopback port")
		void onWebServerStarted_withManagementNamespace_isIgnored() {
			// given — the actuator's own server reports port 9999
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
			ProxyHandlerTests.this.handler.onWebServerStarted(managementServerStartedEvent(9999));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse").build());

			// when
			ProxyHandlerTests.this.handler.openSse(request).block();

			// then — the port is still unset, so the resolver falls back to 8080
			verify(ProxyHandlerTests.this.transportFactory).buildSse(URI.create("http://127.0.0.1:8080/sse"));
		}

		@Test
		@Story("SSE proxy open")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with a missing url resolves to the loopback /sse endpoint and opens a session")
		void openSse_withMissingUrl_resolvesToLoopbackAndOpensSession() {
			// given — no url param; ProxyTargetResolver resolves null to
			// http://127.0.0.1:8080/sse (loopback port defaults to 8080 before server
			// start)
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then — NOT 400; the resolver produces a valid loopback URI, session IS
			// opened
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).buildSse(URI.create("http://127.0.0.1:8080/sse"));
			verify(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
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
			// emits
			// fail
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
		@Description("postMcp() opening a new session without a url resolves to loopback and opens a session")
		void postMcp_newSessionWithoutUrl_resolvesToLoopbackAndOpensSession() {
			// given — null url: ProxyTargetResolver produces http://127.0.0.1:8080/mcp
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class))).willReturn(target);
			final ProxySession[] captured = new ProxySession[1];
			given(ProxyHandlerTests.this.registry.get(any())).willAnswer((inv) -> captured[0]);
			willAnswer((inv) -> {
				captured[0] = inv.getArgument(0);
				return null;
			}).given(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then — resolver succeeds, session is opened (not 400); notification body →
			// 202
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
			verify(ProxyHandlerTests.this.transportFactory).openStreamable(URI.create("http://127.0.0.1:8080/mcp"));
			verify(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() opening a new session maps an upstream connect failure into a 502 bad gateway")
		void postMcp_newSessionWhenUpstreamConnectFails_returns502() {
			// given - ConnectException is what the SDK throws when the upstream
			// port is closed; ProxyConnectFailure.classify maps it to
			// CONNECTION_REFUSED. Wrapped in RuntimeException because
			// openStreamable does not declare checked exceptions.
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willThrow(new RuntimeException(new java.net.ConnectException("Connection refused")));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector-api/mcp?url=http://up/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then - 502 bad gateway with the structured MCP_CONNECT_FAILED payload
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			assertThat(entityBody(response).get("error").toString()).contains("MCP_CONNECT_FAILED");
			assertThat(entityBody(response).get("error").toString()).contains("connection_refused");
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

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() when session closes while awaiting the upstream response, the response completes without hanging")
		void postMcp_sessionClosesWhileAwaiting_completesWithoutHanging() {
			// given - a known session; a short streamable-request timeout so the
			// test cannot hang CI for the full 30s default even if the fix regresses.
			final McpInspectorProperties fastProps = new McpInspectorProperties();
			fastProps.getTimeouts().setStreamableRequest(Duration.ofMillis(200));
			final ProxyHandler fastHandler = new ProxyHandler(ProxyHandlerTests.this.registry,
					ProxyHandlerTests.this.transportFactory, ProxyHandlerTests.this.mcpProxy,
					ProxyHandlerTests.this.transportDetector, ProxyHandlerTests.this.objectMapper, fastProps);
			final ProxySession session = newSession("s-close-await");
			given(ProxyHandlerTests.this.registry.get("s-close-await")).willReturn(session);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s-close-await")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

			// when - targetToBrowser completes while the POST is awaiting the
			// upstream response. This is what ProxySession.close() does internally
			// (ProxySession.java:272). The completion triggers the onComplete handler
			// that calls tryEmitEmpty on the Sinks.One, so the await Mono completes
			// empty.
			final ServerResponse response = fastHandler.postMcp(request).doOnSubscribe((s) -> new Thread(() -> {
				try {
					Thread.sleep(50);
				}
				catch (final InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				session.targetToBrowser().tryEmitComplete();
			}).start()).block(Duration.ofSeconds(5));

			// then - the response completes (does not hang); the onComplete runnable
			// calls tryEmitEmpty on the Sinks.One, so the Mono completes empty
			assertThat(response).isNull();
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
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
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

			// then — mapTransport's null arm yields ""; buildServerUrl returns relative
			// "/mcp"
			// because a null type is neither UNKNOWN nor STDIO_NO_HTTP
			final Map<String, Object> body = entityBody(response);
			assertThat(body).containsEntry("defaultTransport", "");
			assertThat(body).containsEntry("defaultServerUrl", "/mcp");
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
		@Description("openSse() with a present but blank url resolves to the loopback /sse endpoint (blank resolves, not rejected)")
		void openSse_withBlankUrl_resolvesToLoopback() {
			// given — blank url (trimmed to "") is treated as a blank by
			// ProxyTargetResolver
			// and resolved to http://127.0.0.1:8080/sse (default path for sse transport)
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse&url= ").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then — resolver handles blank url as loopback default, session IS opened
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).buildSse(URI.create("http://127.0.0.1:8080/sse"));
		}

	}

	@Nested
	@DisplayName("postMcp() — extra dispatch branches")
	class PostMcpBranches {

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() opening a new session with a blank url resolves to the loopback /mcp endpoint")
		void postMcp_newSessionWithBlankUrl_resolvesToLoopback() {
			// given — blank url (space) is resolved to http://127.0.0.1:8080/mcp by
			// ProxyTargetResolver
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class))).willReturn(target);
			final ProxySession[] captured = new ProxySession[1];
			given(ProxyHandlerTests.this.registry.get(any())).willAnswer((inv) -> captured[0]);
			willAnswer((inv) -> {
				captured[0] = inv.getArgument(0);
				return null;
			}).given(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp?url= ")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then — resolver resolves blank to loopback, session IS opened (not 400)
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
			verify(ProxyHandlerTests.this.transportFactory).openStreamable(URI.create("http://127.0.0.1:8080/mcp"));
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() with a present but blank mcp-session-id opens a new session (mcpSessionId isBlank branch)")
		void postMcp_withBlankSessionHeader_opensNewSession() {
			// given — a blank session header must NOT be treated as an existing session;
			// null url resolves to loopback, session is opened with a notification (202)
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class))).willReturn(target);
			final ProxySession[] captured = new ProxySession[1];
			given(ProxyHandlerTests.this.registry.get(any())).willAnswer((inv) -> captured[0]);
			willAnswer((inv) -> {
				captured[0] = inv.getArgument(0);
				return null;
			}).given(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "   ")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then — blank header → open new session; null url resolves to loopback → 202
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
			verify(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
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

	@Nested
	@DisplayName("relayAndAwait() — JSON-RPC response framing + new-session timeout")
	class RelayResponseFramingAndTimeout {

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() relaying a server->client JSON-RPC response (id + result, no method) is fire-and-forget 202, not relay-and-await")
		void postMcp_jsonRpcResponseFrame_treatedAsFireAndForget() {
			// given — a frame that carries an id and a result but NO method: a response,
			// so extractRequestId returns null and the 202 fire-and-forget path is taken
			final ProxySession session = newSession("s-response");
			given(ProxyHandlerTests.this.registry.get("s-response")).willReturn(session);
			final ServerRequest request = toServerRequest(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
				.header(ProxyConstants.MCP_SESSION_ID_HEADER, "s-response")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"ok\":true}}"));

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

		@Test
		@Story("Streamable-HTTP relay")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() opening a new session whose upstream never replies times out with a 504 and tears down the orphaned session")
		void postMcp_newSessionTimeout_returnsGatewayTimeoutAndRemovesSession() {
			// given — a fast streamable request timeout so the awaiter trips quickly, and
			// a
			// brand-new session (includeSessionHeader == true) whose upstream stays
			// silent
			final McpInspectorProperties fastProps = new McpInspectorProperties();
			fastProps.getTimeouts().setStreamableRequest(java.time.Duration.ofMillis(100));
			final ProxyHandler fastHandler = new ProxyHandler(ProxyHandlerTests.this.registry,
					ProxyHandlerTests.this.transportFactory, ProxyHandlerTests.this.mcpProxy,
					ProxyHandlerTests.this.transportDetector, ProxyHandlerTests.this.objectMapper, fastProps);
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class))).willReturn(target);
			final ProxySession[] captured = new ProxySession[1];
			willAnswer((inv) -> {
				captured[0] = inv.getArgument(0);
				return null;
			}).given(ProxyHandlerTests.this.registry).put(any(ProxySession.class));
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector-api/mcp?url=http://up/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\"}"));

			// when — never feed a reply, so the awaiter times out
			final ServerResponse response = fastHandler.postMcp(request).block();

			// then — 504 gateway timeout and the orphaned new session is removed
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
			assertThat(entityBody(response).get("error").toString()).contains("MCP_CONNECT_FAILED");
			assertThat(entityBody(response).get("error").toString()).contains("timeout");
			verify(ProxyHandlerTests.this.registry).removeAndClose(captured[0].sessionId());
		}

	}

	@Nested
	@DisplayName("inbound header forwarding")
	class HeaderForwarding {

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() forwards the inbound Authorization header into the header-aware openSse factory overload")
		void openSse_withAuthorizationHeader_usesHeaderAwareSseFactory() {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class), any(), any())).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse&url=http://up/sse")
						.header("Authorization", "Bearer abc")
						.build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then — the 3-arg header-aware overload is used, not the bare single-arg one
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).buildSse(any(URI.class),
					org.mockito.ArgumentMatchers.eq("Bearer abc"), org.mockito.ArgumentMatchers.eq(Map.of()));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() without any inbound auth headers uses the bare single-arg openSse factory overload")
		void openSse_withoutHeaders_usesBareSseFactory() {
			// given — no Authorization, no x-custom-auth-headers: inboundAuthorization
			// null
			// and inboundCustomHeaders empty, so the noHeaders branch picks the 1-arg
			// overload
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse&url=http://up/sse").build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).buildSse(any(URI.class));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() forwards the named custom headers (x-custom-auth-headers) into the header-aware factory and skips blank / absent names")
		void openSse_withCustomHeaders_forwardsNamedValuesOnly() {
			// given — x-custom-auth-headers names two headers (one with surrounding
			// blanks)
			// plus a blank entry and an unset name, exercising inboundCustomHeaders' trim
			// /
			// isEmpty / null-value branches
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class), any(), any())).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse&url=http://up/sse")
						.header("x-custom-auth-headers", " X-Tenant , , X-Absent ,X-Trace")
						.header("X-Tenant", "acme")
						.header("X-Trace", "t-1")
						.build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then — only the present named headers are forwarded; the blank token and
			// the
			// unset X-Absent name are dropped; no Authorization is present
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).buildSse(any(URI.class),
					org.mockito.ArgumentMatchers.isNull(),
					org.mockito.ArgumentMatchers.eq(Map.of("X-Tenant", "acme", "X-Trace", "t-1")));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.MINOR)
		@Description("openSse() with a blank x-custom-auth-headers value forwards no custom headers (named isBlank branch)")
		void openSse_withBlankCustomHeaderList_forwardsNoCustomHeaders() {
			// given — a present but blank x-custom-auth-headers triggers the
			// named.isBlank()
			// early return of an empty map; the Authorization header keeps the
			// header-aware
			// overload selected
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.buildSse(any(URI.class), any(), any())).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?transportType=sse&url=http://up/sse")
						.header("Authorization", "Bearer z")
						.header("x-custom-auth-headers", "  ")
						.build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openSse(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).buildSse(any(URI.class),
					org.mockito.ArgumentMatchers.eq("Bearer z"), org.mockito.ArgumentMatchers.eq(Map.of()));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() with an inbound Authorization header still opens via the stdio factory (stdio ignores forwarded headers)")
		void openStdio_withAuthorizationHeader_opensStdioTransport() {
			// given — stdio's switch arm ignores the forwarded headers, but the header
			// plumbing (inboundAuthorization / inboundCustomHeaders) still runs through
			// openProxiedSession
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStdio(any(), any())).willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.get("/mcp-inspector-api/stdio?command=node")
						.header("Authorization", "Bearer abc")
						.header("x-custom-auth-headers", "X-Tenant")
						.header("X-Tenant", "acme")
						.build());

			// when
			final ServerResponse response = ProxyHandlerTests.this.handler.openStdio(request).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			verify(ProxyHandlerTests.this.transportFactory).openStdio(any(), any());
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() opening a new session forwards the inbound Authorization header into the header-aware openStreamable overload")
		void postMcp_newSessionWithAuthorizationHeader_usesHeaderAwareStreamableFactory() {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerTests.this.transportFactory.openStreamable(any(URI.class), any(), any()))
				.willReturn(target);
			final ServerRequest request = toServerRequest(
					MockServerHttpRequest.post("/mcp-inspector-api/mcp?url=http://up/mcp")
						.header("Authorization", "Bearer abc")
						.header("x-custom-auth-headers", "X-Tenant")
						.header("X-Tenant", "acme")
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

			// when — a notification frame keeps the relay on the 202 path (no awaiter)
			final ServerResponse response = ProxyHandlerTests.this.handler.postMcp(request).block();

			// then — the 3-arg header-aware streamable overload is used for the new
			// session
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
			verify(ProxyHandlerTests.this.transportFactory).openStreamable(any(URI.class),
					org.mockito.ArgumentMatchers.eq("Bearer abc"),
					org.mockito.ArgumentMatchers.eq(Map.of("X-Tenant", "acme")));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() opening a new session without inbound auth headers uses the bare single-arg openStreamable overload")
		void postMcp_newSessionWithoutHeaders_usesBareStreamableFactory() {
			// given — no inbound auth headers: openSessionAndRelay's noHeaders branch
			// picks
			// the 1-arg openStreamable overload
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
			verify(ProxyHandlerTests.this.transportFactory).openStreamable(any(URI.class));
		}

	}

}
