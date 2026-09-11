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

package io.inspector.mcp.webmvc.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.task.TaskHandle;
import io.inspector.mcp.core.task.TaskNotCancelableException;
import io.inspector.mcp.core.task.TaskNotFoundException;
import io.inspector.mcp.core.task.TaskService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link StreamableHttpProxyController}. Collaborators are mocked;
 * assertions cover the request/notification split on {@code POST /mcp}, the
 * session-id-header behaviour, the {@code GET}/{@code DELETE} branches and the
 * request/response correlation via the replay sink.
 *
 * <p>
 * After the WAF-safe loopback fix, a {@code null} url is resolved to the loopback
 * {@code /mcp} endpoint server-side (via {@code ProxyTargetResolver}). In unit tests the
 * controller is constructed without a {@code portHolder}, so {@code loopbackPort()} falls
 * back to 8080. Consequently, calling {@code postMcp(null, null, body)} no longer returns
 * 400; instead the resolver produces {@code http://127.0.0.1:8080/mcp} and the mocked
 * factory is invoked.
 */
@Epic("WebMvc Inspector")
@Feature("StreamableHttpProxyController")
class StreamableHttpProxyControllerTests {

	private ProxySessionRegistry registry;

	private ProxyTransportFactory transportFactory;

	private McpProxy mcpProxy;

	private JsonMapper objectMapper;

	private StreamableHttpProxyController controller;

	@BeforeEach
	void setUp() {
		this.registry = mock(ProxySessionRegistry.class);
		this.transportFactory = mock(ProxyTransportFactory.class);
		this.mcpProxy = mock(McpProxy.class);
		this.objectMapper = new JsonMapper();
		given(this.mcpProxy.start(any())).willReturn(Mono.empty());
		this.controller = new StreamableHttpProxyController(this.registry, this.transportFactory, this.mcpProxy,
				this.objectMapper);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
	}

	private ProxySession newSession(final String id, final McpClientTransport target) {
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
		return new ProxySession(id, target, browserToTarget, targetToBrowser);
	}

	@Nested
	@DisplayName("POST /mcp — new session")
	class PostNewSession {

		@Test
		@Story("New session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() without a session id and without url resolves to loopback and calls the transport factory")
		void postMcp_withoutSessionAndUrl_resolvesToLoopbackAndOpensSession() throws Exception {
			// given — null url is resolved to http://127.0.0.1:8080/mcp by
			// ProxyTargetResolver;
			// the factory is stubbed to return a transport so the session opens cleanly
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode answer = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitNext(answer);
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when — no url: ProxyTargetResolver resolves blank to
			// http://127.0.0.1:8080/mcp
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					null, body);

			// then — NOT 400; the factory is called with the loopback URI and a session
			// is
			// opened
			assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
			verify(StreamableHttpProxyControllerTests.this.transportFactory)
				.openStreamable(URI.create("http://127.0.0.1:8080/mcp"));
			verify(StreamableHttpProxyControllerTests.this.registry).put(any(ProxySession.class));
		}

		@Test
		@Story("New session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() returns a structured 502 MCP_CONNECT_FAILED payload when the upstream transport cannot be built")
		void postMcp_whenUpstreamConnectFails_returnsStructured502() throws Exception {
			// given
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willThrow(new RuntimeException("connect failed"));
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then — non-2xx with the machine-readable error contract; stack details
			// stay out of the body
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(response.getBody())
				.path("error");
			assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
			assertThat(error.path("reason").asText()).isEqualTo("unknown");
			assertThat(error.path("message").asText()).isNotBlank();
			assertThat(error.path("retryable").asBoolean()).isTrue();
		}

		@Test
		@Story("New session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() reports connection_refused when building the transport throws a ConnectException")
		void postMcp_whenUpstreamRefusesConnection_returnsStructured502ConnectionRefused() throws Exception {
			// given
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willThrow(new RuntimeException(new java.net.ConnectException("Connection refused")));
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(response.getBody())
				.path("error");
			assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
			assertThat(error.path("reason").asText()).isEqualTo("connection_refused");
			assertThat(error.path("retryable").asBoolean()).isTrue();
		}

		@Test
		@Story("New session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() reports dns when building the transport throws an UnknownHostException")
		void postMcp_whenUpstreamHostUnknown_returnsStructured502Dns() throws Exception {
			// given
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willThrow(new RuntimeException(new java.net.UnknownHostException("no-such-host")));
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(response.getBody())
				.path("error");
			assertThat(error.path("reason").asText()).isEqualTo("dns");
		}

		@Test
		@Story("New session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() new-session request relays, awaits the matching response and attaches the session-id header")
		void postMcp_newSessionRequest_returnsResponseWithSessionHeader() throws Exception {
			// given a transport whose proxy echoes the matching response onto
			// targetToBrowser
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitNext(response);
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(entity.getBody()).isEqualTo(response);
			assertThat(entity.getHeaders().getFirst(ProxyConstants.MCP_SESSION_ID_HEADER)).isNotBlank();
			verify(StreamableHttpProxyControllerTests.this.registry).put(any(ProxySession.class));
		}

	}

	@Nested
	@DisplayName("POST /mcp — existing session")
	class PostExistingSession {

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() with an unknown session id returns 404")
		void postMcp_withUnknownSession_returns404() throws Exception {
			// given
			given(StreamableHttpProxyControllerTests.this.registry.get("missing")).willReturn(null);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper.readTree("{}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller
				.postMcp("missing", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() of a notification (no id) on an existing session returns 202 without a session header")
		void postMcp_existingSessionNotification_returns202() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp("s1",
					null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
			assertThat(response.getHeaders().getFirst(ProxyConstants.MCP_SESSION_ID_HEADER)).isNull();
			assertThat(session.browserToTarget().asFlux().blockFirst()).isEqualTo(body);
		}

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() request on an existing session correlates the matching response and returns 200")
		void postMcp_existingSessionRequest_returnsMatchingResponse() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"v\":1}}");
			session.targetToBrowser().tryEmitNext(response);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp("s1", null,
					body);

			// then
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(entity.getBody()).isEqualTo(response);
		}

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() request returns a structured 504 MCP_CONNECT_FAILED payload when no matching response arrives in time")
		void postMcp_existingSessionRequest_timesOutReturns504() throws Exception {
			// given — no response ever emitted on the sink
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp("s1", null,
					body);

			// then — the timeout is surfaced as a machine-readable reason=timeout
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(entity.getBody())
				.path("error");
			assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
			assertThat(error.path("reason").asText()).isEqualTo("timeout");
			assertThat(error.path("retryable").asBoolean()).isTrue();
		}

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.MINOR)
		@Description("postMcp() request ignores non-matching frames (wrong id, null id, non-object) and returns the matching one")
		void postMcp_existingSessionRequest_skipsNonMatchingFrames() throws Exception {
			// given — the replay sink already holds noise frames before the real answer
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			session.targetToBrowser()
				.tryEmitNext(StreamableHttpProxyControllerTests.this.objectMapper.readTree("\"not-an-object\""));
			session.targetToBrowser()
				.tryEmitNext(StreamableHttpProxyControllerTests.this.objectMapper
					.readTree("{\"jsonrpc\":\"2.0\",\"id\":99}"));
			session.targetToBrowser()
				.tryEmitNext(StreamableHttpProxyControllerTests.this.objectMapper
					.readTree("{\"jsonrpc\":\"2.0\",\"id\":null}"));
			final JsonNode answer = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"v\":1}}");
			session.targetToBrowser().tryEmitNext(answer);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp("s1", null,
					body);

			// then
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(entity.getBody()).isEqualTo(answer);
		}

		@Test
		@Story("New session")
		@Severity(SeverityLevel.MINOR)
		@Description("postMcp() treats a blank mcp-session-id header as a new-session request")
		void postMcp_withBlankSessionHeader_opensNewSession() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode answer = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitNext(answer);
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp("   ",
					"http://target/mcp", body);

			// then
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
			verify(StreamableHttpProxyControllerTests.this.registry).put(any(ProxySession.class));
		}

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.MINOR)
		@Description("postMcp() of a request frame whose id is JSON null is treated as a notification (202)")
		void postMcp_existingSessionNullId_treatedAsNotification() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp("s1",
					null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.MINOR)
		@Description("postMcp() of a non-object JSON frame is treated as a notification (202)")
		void postMcp_existingSessionNonObjectBody_treatedAsNotification() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper.readTree("[1,2,3]");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp("s1",
					null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.MINOR)
		@Description("postMcp() notification returns 500 when the browserToTarget sink rejects the emit")
		void postMcp_notificationEmitFails_returns500() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			session.browserToTarget().tryEmitComplete();
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp("s1",
					null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() when session closes while awaiting the upstream response, responds without hanging")
		void postMcp_sessionClosesWhileAwaiting_respondsWithoutHanging() throws Exception {
			// given - a session with a mock transport; no upstream answer will arrive,
			// so the POST would block for the full request timeout if the session
			// close path did not terminate the awaiter.
			final McpClientTransport target = mock(McpClientTransport.class);
			given(target.closeGracefully()).willReturn(Mono.empty());
			final ProxySession session = newSession("s-close-await", target);
			given(StreamableHttpProxyControllerTests.this.registry.get("s-close-await")).willReturn(session);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when - run postMcp on a worker thread so the test thread can
			// close the session while the POST is awaiting the upstream response.
			//
			// Subscribe to browserToTarget to detect when the body has been emitted.
			// relayWithSessionHeader emits the body AFTER subscribing the awaiter,
			// so this is a deterministic signal that the awaiter is ready.
			final CountDownLatch awaiterSubscribed = new CountDownLatch(1);
			session.browserToTarget()
				.asFlux()
				.subscribe((frame) -> awaiterSubscribed.countDown(), (error) -> awaiterSubscribed.countDown(),
						() -> awaiterSubscribed.countDown());
			final AtomicReference<ResponseEntity<Object>> holder = new AtomicReference<>();
			final Thread poster = new Thread(() -> {
				final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller
					.postMcp("s-close-await", null, body);
				holder.set(entity);
			});
			poster.setDaemon(true);
			poster.start();
			// Wait for the deterministic signal: the body was emitted to
			// browserToTarget, which means the awaiter is subscribed and
			// postMcp is now blocking on the await Mono.
			assertThat(awaiterSubscribed.await(2, TimeUnit.SECONDS))
				.as("awaiter should subscribe to targetToBrowser before block()")
				.isTrue();
			// Close the session via the real close-path (ProxySession.close()).
			// This completes targetToBrowser, which triggers the onComplete handler
			// that calls tryEmitEmpty on the Sinks.One, unblocking the awaiter.
			session.close();
			poster.join(5_000);

			// then - the response completes (does not hang); the onComplete handler
			// calls tryEmitEmpty on the Sinks.One, completing the await Mono empty,
			// so block() returns null which is accepted as the response body.
			assertThat(holder.get()).as("postMcp should have completed within 5s").isNotNull();
			assertThat(holder.get().getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(holder.get().getBody()).isNull();
		}

	}

	@Nested
	@DisplayName("GET /mcp")
	class GetMcp {

		@Test
		@Story("SSE stream")
		@Severity(SeverityLevel.NORMAL)
		@Description("getMcp() subscribes to the session's targetToBrowser stream for a known session")
		void getMcp_withKnownSession_returnsEmitter() {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final SseEmitter emitter = StreamableHttpProxyControllerTests.this.controller.getMcp("s1");

			// then
			assertThat(emitter).isNotNull();
		}

		@Test
		@Story("SSE stream")
		@Severity(SeverityLevel.MINOR)
		@Description("getMcp() for an unknown session emits an error event and completes the emitter")
		void getMcp_withUnknownSession_returnsErrorEmitter() {
			// given
			given(StreamableHttpProxyControllerTests.this.registry.get("missing")).willReturn(null);

			// when
			final SseEmitter emitter = StreamableHttpProxyControllerTests.this.controller.getMcp("missing");

			// then
			assertThat(emitter).isNotNull();
		}

	}

	@Nested
	@DisplayName("DELETE /mcp")
	class DeleteMcp {

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("deleteMcp() returns 200 when the registry removed an existing session")
		void deleteMcp_whenRemoved_returns200() {
			// given
			given(StreamableHttpProxyControllerTests.this.registry.removeAndClose("s1")).willReturn(true);

			// when
			final ResponseEntity<Void> response = StreamableHttpProxyControllerTests.this.controller.deleteMcp("s1");

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			verify(StreamableHttpProxyControllerTests.this.registry).removeAndClose("s1");
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.MINOR)
		@Description("deleteMcp() returns 404 when no session matched the id")
		void deleteMcp_whenNotRemoved_returns404() {
			// given
			given(StreamableHttpProxyControllerTests.this.registry.removeAndClose("missing")).willReturn(false);

			// when
			final ResponseEntity<Void> response = StreamableHttpProxyControllerTests.this.controller
				.deleteMcp("missing");

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

	}

	@Nested
	@DisplayName("openSession() — inbound header forwarding")
	class HeaderForwarding {

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSession() with no request attributes uses the single-arg transport factory overload")
		void openSession_withoutRequestAttributes_usesSingleArgOverload() throws Exception {
			// given — no RequestContextHolder bound (tearDown clears it)
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}");

			// when
			StreamableHttpProxyControllerTests.this.controller.postMcp(null, "http://target/mcp", body);

			// then
			verify(StreamableHttpProxyControllerTests.this.transportFactory)
				.openStreamable(URI.create("http://target/mcp"));
			verify(StreamableHttpProxyControllerTests.this.transportFactory, never()).openStreamable(any(URI.class),
					any(), any());
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSession() forwards the inbound Authorization and custom headers via the header-aware overload")
		void openSession_withInboundHeaders_usesHeaderAwareOverload() throws Exception {
			// given
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader("Authorization", "Bearer tok");
			request.addHeader("x-custom-auth-headers", "X-Tenant, X-Trace");
			request.addHeader("X-Tenant", "acme");
			request.addHeader("X-Trace", "abc123");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class), any(), any()))
				.willReturn(target);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}");

			// when
			StreamableHttpProxyControllerTests.this.controller.postMcp(null, "http://target/mcp", body);

			// then
			verify(StreamableHttpProxyControllerTests.this.transportFactory).openStreamable(
					eq(URI.create("http://target/mcp")), eq("Bearer tok"),
					eq(Map.of("X-Tenant", "acme", "X-Trace", "abc123")));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.MINOR)
		@Description("openSession() with request attributes but no relevant headers falls back to the single-arg overload")
		void openSession_withRequestButNoHeaders_usesSingleArgOverload() throws Exception {
			// given — a bound request that carries neither Authorization nor the
			// custom-header list
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}");

			// when
			StreamableHttpProxyControllerTests.this.controller.postMcp(null, "http://target/mcp", body);

			// then
			verify(StreamableHttpProxyControllerTests.this.transportFactory)
				.openStreamable(URI.create("http://target/mcp"));
			verify(StreamableHttpProxyControllerTests.this.transportFactory, never()).openStreamable(any(URI.class),
					any(), any());
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.MINOR)
		@Description("openSession() with a blank x-custom-auth-headers list forwards only the Authorization header")
		void openSession_withBlankCustomHeaderList_forwardsAuthorizationOnly() throws Exception {
			// given
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader("Authorization", "Bearer tok");
			request.addHeader("x-custom-auth-headers", "  ");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class), any(), any()))
				.willReturn(target);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}");

			// when
			StreamableHttpProxyControllerTests.this.controller.postMcp(null, "http://target/mcp", body);

			// then
			verify(StreamableHttpProxyControllerTests.this.transportFactory)
				.openStreamable(eq(URI.create("http://target/mcp")), eq("Bearer tok"), eq(Map.of()));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.MINOR)
		@Description("openSession() skips blank and absent custom header names from the x-custom-auth-headers list")
		void openSession_skipsBlankAndAbsentCustomHeaderNames() throws Exception {
			// given — empty token, a present header and a named-but-absent header
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader("x-custom-auth-headers", "X-Present, , X-Missing");
			request.addHeader("X-Present", "yes");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class), any(), any()))
				.willReturn(target);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}");

			// when
			StreamableHttpProxyControllerTests.this.controller.postMcp(null, "http://target/mcp", body);

			// then — only the present header survives; null authorization is forwarded
			verify(StreamableHttpProxyControllerTests.this.transportFactory)
				.openStreamable(eq(URI.create("http://target/mcp")), eq(null), eq(Map.of("X-Present", "yes")));
		}

	}

	@Nested
	@DisplayName("new-session await failure")
	class NewSessionAwaitFailure {

		@Test
		@Story("New session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() new-session request that times out tears down the orphaned session and returns a structured 504")
		void postMcp_newSessionRequestTimesOut_closesSessionAndReturns504() throws Exception {
			// given — a short request timeout so the await fails fast, and a transport
			// that
			// builds but whose proxy never emits a matching response
			final McpInspectorProperties props = new McpInspectorProperties();
			props.getTimeouts().setStreamableRequest(Duration.ofMillis(200));
			final StreamableHttpProxyController shortTimeoutController = new StreamableHttpProxyController(
					StreamableHttpProxyControllerTests.this.registry,
					StreamableHttpProxyControllerTests.this.transportFactory,
					StreamableHttpProxyControllerTests.this.mcpProxy,
					StreamableHttpProxyControllerTests.this.objectMapper, props);
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> entity = shortTimeoutController.postMcp(null, "http://target/mcp", body);

			// then
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(entity.getBody())
				.path("error");
			assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
			assertThat(error.path("reason").asText()).isEqualTo("timeout");
			assertThat(error.path("retryable").asBoolean()).isTrue();
			verify(StreamableHttpProxyControllerTests.this.registry).removeAndClose(any(String.class));
		}

		@Test
		@Story("New session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() new-session request whose upstream dies with ConnectException fails fast with a structured 502 connection_refused")
		void postMcp_newSessionUpstreamRefused_failsFastWithStructured502() throws Exception {
			// given — the session opens, but the upstream transport errors immediately
			// (emulated connect refusal on the first send), releasing the awaiter
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitError(new java.net.ConnectException("Connection refused"));
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then — the refusal is classified, not masked as a timeout
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(entity.getBody())
				.path("error");
			assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
			assertThat(error.path("reason").asText()).isEqualTo("connection_refused");
			assertThat(error.path("retryable").asBoolean()).isTrue();
			verify(StreamableHttpProxyControllerTests.this.registry).removeAndClose(any(String.class));
		}

	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("constructor falls back to a default JsonMapper when none supplied")
		void constructor_withNullMapper_usesDefault() {
			// given / when
			final StreamableHttpProxyController c = new StreamableHttpProxyController(
					StreamableHttpProxyControllerTests.this.registry,
					StreamableHttpProxyControllerTests.this.transportFactory,
					StreamableHttpProxyControllerTests.this.mcpProxy, null);

			// then
			assertThat(c).isNotNull();
			verify(StreamableHttpProxyControllerTests.this.mcpProxy, never()).start(any());
		}

	}

	@Nested
	@DisplayName("task method interception")
	class TaskMethodInterception {

		private TaskService taskService;

		private StreamableHttpProxyController controllerWithTasks;

		@BeforeEach
		void setUpTaskController() {
			this.taskService = mock(TaskService.class);
			this.controllerWithTasks = new StreamableHttpProxyController(
					StreamableHttpProxyControllerTests.this.registry,
					StreamableHttpProxyControllerTests.this.transportFactory,
					StreamableHttpProxyControllerTests.this.mcpProxy,
					StreamableHttpProxyControllerTests.this.objectMapper, null, null,
					new org.springframework.beans.factory.ObjectProvider<TaskService>() {
						@Override
						public TaskService getObject() {
							return TaskMethodInterception.this.taskService;
						}
					});
		}

		private ProxySession newSession(final String id) {
			final McpClientTransport target = mock(McpClientTransport.class);
			final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
			final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
			return new ProxySession(id, target, browserToTarget, targetToBrowser);
		}

		@Test
		@Story("tasks/get interception")
		@Severity(SeverityLevel.CRITICAL)
		@Description("tasks/get is intercepted locally when a TaskService bean is available, and returns the task handle")
		void tasksGet_interceptedLocally_returnsTaskHandle() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"t-123\"}}");
			final TaskHandle handle = new TaskHandle("t-123", "working", null, "2025-01-01T00:00:00Z",
					"2025-01-01T00:00:00Z", 60000, 5000);
			given(this.taskService.getTask("t-123")).willReturn(handle);
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("result").get("taskId").asText()).isEqualTo("t-123");
			assertThat(responseBody.get("result").get("status").asText()).isEqualTo("working");
			verify(this.taskService).getTask("t-123");
			// The upstream is never consulted for task methods
			verify(session.targetTransport(), never()).sendMessage(any());
		}

		@Test
		@Story("tasks/get interception")
		@Severity(SeverityLevel.NORMAL)
		@Description("tasks/get with an unknown taskId returns a JSON-RPC -32602 error")
		void tasksGet_unknownTask_returnsError() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper.readTree(
					"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"missing\"}}");
			given(this.taskService.getTask("missing")).willThrow(new TaskNotFoundException("missing"));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("error").get("code").asInt()).isEqualTo(-32602);
			assertThat(responseBody.get("error").get("message").asText()).containsIgnoringCase("not found");
		}

		@Test
		@Story("tasks/cancel interception")
		@Severity(SeverityLevel.CRITICAL)
		@Description("tasks/cancel is intercepted locally and returns the cancelled task handle")
		void tasksCancel_interceptedLocally_returnsCancelledHandle() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper.readTree(
					"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tasks/cancel\",\"params\":{\"taskId\":\"t-456\"}}");
			final TaskHandle handle = new TaskHandle("t-456", "cancelled", null, "2025-01-01T00:00:00Z",
					"2025-01-01T00:00:00Z", 60000, 5000);
			given(this.taskService.cancelTask("t-456")).willReturn(handle);
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("result").get("status").asText()).isEqualTo("cancelled");
			verify(this.taskService).cancelTask("t-456");
		}

		@Test
		@Story("tasks/cancel interception")
		@Severity(SeverityLevel.NORMAL)
		@Description("tasks/cancel on an already-terminal task returns a JSON-RPC -32602 error")
		void tasksCancel_terminalTask_returnsError() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper.readTree(
					"{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tasks/cancel\",\"params\":{\"taskId\":\"t-789\"}}");
			given(this.taskService.cancelTask("t-789")).willThrow(new TaskNotCancelableException("t-789", "completed"));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("error").get("code").asInt()).isEqualTo(-32602);
			assertThat(responseBody.get("error").get("message").asText()).containsIgnoringCase("terminal");
		}

		@Test
		@Story("tasks/get interception")
		@Severity(SeverityLevel.MINOR)
		@Description("tasks/get without a taskId returns a JSON-RPC -32602 error")
		void tasksGet_missingTaskId_returnsError() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tasks/get\",\"params\":{}}");

			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("error").get("code").asInt()).isEqualTo(-32602);
		}

		@Test
		@Story("tasks/get interception")
		@Severity(SeverityLevel.NORMAL)
		@Description("tasks/get with a task that has a statusMessage includes it in the response")
		void tasksGet_withStatusMessage_includesIt() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"t-msg\"}}");
			final TaskHandle handle = new TaskHandle("t-msg", "failed", "Tool execution failed: timeout",
					"2025-01-01T00:00:00Z", "2025-01-01T00:01:00Z", 60000, 5000);
			given(this.taskService.getTask("t-msg")).willReturn(handle);
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("result").get("statusMessage").asText())
				.isEqualTo("Tool execution failed: timeout");
		}

		@Test
		@Story("no interception without TaskService")
		@Severity(SeverityLevel.NORMAL)
		@Description("when no TaskService bean is available, tasks/get falls through to the regular relay path")
		void tasksGet_withoutTaskService_relaysToUpstream() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"t-1\"}}");
			// Default controller has no TaskService
			final StreamableHttpProxyController noTaskController = new StreamableHttpProxyController(
					StreamableHttpProxyControllerTests.this.registry,
					StreamableHttpProxyControllerTests.this.transportFactory,
					StreamableHttpProxyControllerTests.this.mcpProxy,
					StreamableHttpProxyControllerTests.this.objectMapper);
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willReturn(Mono.empty());

			// when
			final ResponseEntity<Object> response = noTaskController.postMcp("s1", null, body);

			// then - the frame is relayed, not intercepted locally
			assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			verify(StreamableHttpProxyControllerTests.this.registry).get("s1");
		}

		@Test
		@Story("tasks/list interception")
		@Severity(SeverityLevel.NORMAL)
		@Description("tasks/list is intercepted locally when a TaskService is available, returns a list of task handles")
		void tasksList_interceptedLocally_returnsTaskList() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tasks/list\"}");
			final TaskHandle handle = new TaskHandle("t-1", "working", null, "2025-01-01T00:00:00Z",
					"2025-01-01T00:00:00Z", 60000, 5000);
			given(this.taskService.listTasks()).willReturn(java.util.List.of(handle));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("result").get("tasks")).isNotNull();
			assertThat(responseBody.get("result").get("tasks").isArray()).isTrue();
			assertThat(responseBody.get("result").get("tasks").get(0).get("taskId").asText()).isEqualTo("t-1");
			verify(this.taskService).listTasks();
		}

		@Test
		@Story("tasks/list interception")
		@Severity(SeverityLevel.MINOR)
		@Description("tasks/list returns an empty array when no tasks are tracked")
		void tasksList_whenNoTasks_returnsEmptyArray() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tasks/list\"}");
			given(this.taskService.listTasks()).willReturn(java.util.List.of());
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("result").get("tasks")).isNotNull();
			assertThat(responseBody.get("result").get("tasks").isArray()).isTrue();
			assertThat(responseBody.get("result").get("tasks")).isEmpty();
		}

		@Test
		@Story("task method on new session")
		@Severity(SeverityLevel.MINOR)
		@Description("a task method intercepted on the first POST includes the session-id header in the response")
		void tasksGet_onNewSession_includesSessionIdHeader() throws Exception {
			// given: a new session request (no session id) that opens a session and
			// relays
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"t-1\"}}");
			final TaskHandle handle = new TaskHandle("t-1", "working", null, "2025-01-01T00:00:00Z",
					"2025-01-01T00:00:00Z", 60000, 5000);
			given(this.taskService.getTask("t-1")).willReturn(handle);
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				return Mono.empty();
			});

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp(null, "http://target/mcp", body);

			// then - session-id header is present even though the task was handled
			// locally
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getHeaders().getFirst(ProxyConstants.MCP_SESSION_ID_HEADER))
				.as("session-id header must be present on first POST task response")
				.isNotBlank();
		}

	}

	@Nested
	@DisplayName("Initialize capability injection")
	class InitializeCapabilityInjection {

		private TaskService taskService;

		private StreamableHttpProxyController controllerWithTasks;

		@BeforeEach
		void setUpCapabilityInjection() {
			this.taskService = mock(TaskService.class);
			this.controllerWithTasks = new StreamableHttpProxyController(
					StreamableHttpProxyControllerTests.this.registry,
					StreamableHttpProxyControllerTests.this.transportFactory,
					StreamableHttpProxyControllerTests.this.mcpProxy,
					StreamableHttpProxyControllerTests.this.objectMapper, null, null,
					new org.springframework.beans.factory.ObjectProvider<TaskService>() {
						@Override
						public TaskService getObject() {
							return InitializeCapabilityInjection.this.taskService;
						}
					});
		}

		@Test
		@Story("Initialize capability injection")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() with an initialize request injects capabilities.tasks when a TaskService is available and the upstream does not advertise it")
		void postMcp_initializeRequest_injectsTasksCapability() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper.readTree(
					"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"logging\":{}}}}");
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitNext(response);
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> entity = this.controllerWithTasks.postMcp(null, "http://target/mcp", body);

			// then
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = StreamableHttpProxyControllerTests.this.objectMapper
				.valueToTree(entity.getBody());
			final JsonNode tasks = responseBody.path("result").path("capabilities").path("tasks");
			assertThat(tasks.isObject()).as("capabilities.tasks must be injected").isTrue();
			assertThat(tasks.has("list")).as("tasks must contain list").isTrue();
			assertThat(tasks.has("cancel")).as("tasks must contain cancel").isTrue();
			assertThat(tasks.path("requests").path("tools").path("call").isObject())
				.as("tasks.requests.tools.call must be an object")
				.isTrue();
		}

		@Test
		@Story("Initialize capability injection")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() with an initialize request does not overwrite capabilities.tasks when the upstream already advertises it")
		void postMcp_initializeRequest_whenTasksAlreadyPresent_doesNotOverwrite() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{\"tasks\":{\"custom\":true}}}}");
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitNext(response);
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = StreamableHttpProxyControllerTests.this.objectMapper
				.valueToTree(entity.getBody());
			assertThat(responseBody.path("result").path("capabilities").path("tasks").path("custom").asBoolean())
				.as("upstream tasks must be preserved")
				.isTrue();
		}

		@Test
		@Story("Initialize capability injection")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() with an initialize request does not inject tasks when no TaskService bean is available")
		void postMcp_initializeRequest_withoutTaskService_doesNotInjectTasks() throws Exception {
			// given: default controller has no TaskService
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper.readTree(
					"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"logging\":{}}}}");
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitNext(response);
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when: using the default controller without TaskService
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then: no tasks capability is injected
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = StreamableHttpProxyControllerTests.this.objectMapper
				.valueToTree(entity.getBody());
			assertThat(responseBody.path("result").path("capabilities").has("tasks"))
				.as("tasks must not be injected without a TaskService")
				.isFalse();
		}

		@Test
		@Story("Initialize capability injection")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() with an initialize request passes through malformed responses unchanged")
		void postMcp_initializeRequest_whenMalformedResponse_passesThroughUnchanged() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}");
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitNext(response);
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then - no injection happens, no crash
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = StreamableHttpProxyControllerTests.this.objectMapper
				.valueToTree(entity.getBody());
			assertThat(responseBody.has("error")).as("error response must pass through").isTrue();
			assertThat(responseBody.path("error").path("code").asInt()).isEqualTo(-32603);
		}

	}

}
