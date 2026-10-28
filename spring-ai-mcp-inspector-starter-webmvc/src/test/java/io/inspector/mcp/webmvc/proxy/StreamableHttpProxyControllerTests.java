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
import java.time.Instant;
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

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.BearerProfile;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.InvalidProxyRequestException;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.task.TaskHandle;
import io.inspector.mcp.core.task.TaskNotCancelableException;
import io.inspector.mcp.core.task.TaskNotFoundException;
import io.inspector.mcp.core.task.TaskService;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;
import io.inspector.mcp.webmvc.auth.ServletSessionOwnerResolver;

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

	/**
	 * Builds a controller with the D8/D9 auth wiring: a signed-cookie owner resolver and
	 * an owner-scoped auth-profile store plus the OAuth2 managers.
	 * @param store the auth-profile store
	 * @param resolver the session-owner resolver
	 * @return the wired controller
	 */
	private StreamableHttpProxyController authController(final AuthProfileStore store,
			final ServletSessionOwnerResolver resolver) {
		return new StreamableHttpProxyController(StreamableHttpProxyControllerTests.this.registry,
				StreamableHttpProxyControllerTests.this.transportFactory,
				StreamableHttpProxyControllerTests.this.mcpProxy, StreamableHttpProxyControllerTests.this.objectMapper,
				new McpInspectorProperties(), null, resolver, store, mock(OAuth2ClientCredentialsTokenManager.class),
				mock(OAuth2AuthCodeTokenExchanger.class));
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
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() surfaces the offending header name and JDK reason when a restricted "
				+ "custom header name is rejected at transport build time")
		void postMcp_whenRestrictedCustomHeaderRejected_surfacesHeaderNameAndReason() throws Exception {
			// given - inbound custom headers route the controller onto the header-aware
			// overload; the factory throws the value-safe IllegalArgumentException built
			// by validateHeaderNames(), which the controller must not collapse into the
			// generic "failed to connect" message
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader("x-custom-auth-headers", "[\"X-Tenant\"]");
			request.addHeader("X-Tenant", "acme");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class), any(), any()))
				.willThrow(new InvalidProxyRequestException(
						"custom header 'host' is not allowed by the JDK HTTP client: restricted header name"));
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then - the header name and the JDK reason reach the caller
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(response.getBody())
				.path("error");
			assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
			assertThat(error.path("reason").asText()).isEqualTo("invalid_request");
			assertThat(error.path("message").asText())
				.isEqualTo("custom header 'host' is not allowed by the JDK HTTP client: restricted header name");
			assertThat(error.path("retryable").asBoolean()).isFalse();
		}

		@Test
		@Story("New session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() keeps a foreign IllegalArgumentException (NumberFormatException) unknown "
				+ "and never copies its raw message into the response body")
		void postMcp_whenForeignIllegalArgument_returnsUnknownWithoutRawMessage() throws Exception {
			// given - a JDK parser blow-up is NOT a proxy rejection; its message may
			// quote user input, so it must not travel to the browser
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willThrow(new RuntimeException(new NumberFormatException("For input string: \"pw-123\"")));
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then - classified unknown, generic message, retryable stays true
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(response.getBody())
				.path("error");
			assertThat(error.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
			assertThat(error.path("reason").asText()).isEqualTo("unknown");
			assertThat(error.path("message").asText()).doesNotContain("pw-123");
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
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
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
			request.addHeader("x-custom-auth-headers", "[\"X-Tenant\", \"X-Trace\"]");
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
		@Severity(SeverityLevel.NORMAL)
		@Description("openSession() with a malformed x-custom-auth-headers payload returns 400 and opens no session")
		void openSession_withMalformedCustomHeaderList_returns400() throws Exception {
			// given
			// the legacy comma-separated shape the UI never sent
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader("x-custom-auth-headers", "X-Tenant, X-Trace");
			request.addHeader("X-Tenant", "acme");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then
			// explicit 400, no session opened, no transport built
			assertThat(response).isNotNull();
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			verify(StreamableHttpProxyControllerTests.this.transportFactory, never()).openStreamable(any(URI.class));
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
			request.addHeader("x-custom-auth-headers", "[\"X-Present\", \" \", \"X-Missing\"]");
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

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("the portHolder-aware constructor keeps the supplied loopback port")
		void constructor_withPortHolder_usesGivenPort() {
			// given / when
			final StreamableHttpProxyController c = new StreamableHttpProxyController(
					StreamableHttpProxyControllerTests.this.registry,
					StreamableHttpProxyControllerTests.this.transportFactory,
					StreamableHttpProxyControllerTests.this.mcpProxy,
					StreamableHttpProxyControllerTests.this.objectMapper, new McpInspectorProperties(),
					mock(InspectorServerPortHolder.class));

			// then
			assertThat(c).isNotNull();
		}

	}

	@Nested
	@DisplayName("D8 auth-profile session bring-up")
	class AuthProfileSession {

		@Test
		@Story("Auth profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() with a foreign/unknown profileId returns a structured 400 bad_request and opens no session")
		void postMcp_withForeignProfile_returnsStructured400() throws Exception {
			// given — the owner is resolved but the store has no such profile
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			final AuthProfileStore store = mock(AuthProfileStore.class);
			given(resolver.resolve(any(), any())).willReturn("owner-1");
			given(store.resolve(any(), any())).willReturn(java.util.Optional.empty());
			final StreamableHttpProxyController wired = authController(store, resolver);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> response = wired.postMcp(null, null, "foreign", body);

			// then — structured 400, no session opened
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(response.getBody())
				.path("code");
			assertThat(error.asText()).isEqualTo("bad_request");
			verify(StreamableHttpProxyControllerTests.this.registry, never()).put(any());
		}

		@Test
		@Story("Auth profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() with an owned profile whose one-time bind is rejected falls back to 502")
		void postMcp_withOwnedProfileButBindRejected_returns502() throws Exception {
			// given — the profile resolves (owned) but the one-time bind is rejected
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			final AuthProfileStore store = mock(AuthProfileStore.class);
			given(resolver.resolve(any(), any())).willReturn("owner-1");
			given(store.resolve(any(), any())).willReturn(java.util.Optional.of(new BearerProfile("p", "tok")));
			given(store.bind(any(), any(), any())).willReturn(false);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamableWithAuth(any(), any(), any()))
				.willReturn(mock(McpClientTransport.class));
			final StreamableHttpProxyController wired = authController(store, resolver);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> response = wired.postMcp(null, null, "p", body);

			// then — owned profile means no 400; the null session surfaces as legacy 502
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			verify(StreamableHttpProxyControllerTests.this.transportFactory).openStreamableWithAuth(any(), any(),
					any());
			verify(StreamableHttpProxyControllerTests.this.registry, never()).put(any());
		}

		@Test
		@Story("Auth profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() with an owned profile applies auth headers to the upstream transport and opens the session")
		void postMcp_withOwnedProfile_appliesAuthHeadersAndOpensSession() throws Exception {
			// given — the profile resolves and binds; the proxy echoes a matching
			// response
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			final AuthProfileStore store = mock(AuthProfileStore.class);
			given(resolver.resolve(any(), any())).willReturn("owner-1");
			given(store.resolve(any(), any())).willReturn(java.util.Optional.of(new BearerProfile("p", "tok")));
			given(store.bind(any(), any(), any())).willReturn(true);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamableWithAuth(any(), any(), any()))
				.willReturn(mock(McpClientTransport.class));
			final JsonNode answer = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitNext(answer);
				return Mono.empty();
			});
			final StreamableHttpProxyController wired = authController(store, resolver);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> response = wired.postMcp(null, null, "p", body);

			// then — the auth-bearing transport is used, the session binds and the
			// response relays
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getHeaders().getFirst(ProxyConstants.MCP_SESSION_ID_HEADER)).isNotBlank();
			verify(StreamableHttpProxyControllerTests.this.transportFactory).openStreamableWithAuth(any(), any(),
					any());
			verify(store).bind(any(), any(), any());
			verify(StreamableHttpProxyControllerTests.this.registry).put(any(ProxySession.class));
		}

	}

	@Nested
	@DisplayName("D3 auth-failure mapping")
	class AuthFailureMapping {

		@Test
		@Story("Auth failure")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() existing-session request whose upstream answers with 401 returns the structured unauthorized DTO")
		void postMcp_existingSessionAuthFailure_returnsStructured401() throws Exception {
			// given — the replay sink carries an authorization failure as the upstream
			// answer
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			session.targetToBrowser().tryEmitError(new RuntimeException("401 Unauthorized"));
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp("s1", null,
					body);

			// then — the live-server 401 becomes the structured DTO, not a connect
			// failure
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(entity.getBody())
				.path("code");
			assertThat(error.asText()).isEqualTo("unauthorized");
		}

		@Test
		@Story("Auth failure")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() new-session request whose upstream answers with 401 tears down the orphaned session and returns the structured DTO")
		void postMcp_newSessionAuthFailure_closesSessionAndReturns401() throws Exception {
			// given — a new session whose proxy immediately fails the handshake with 401
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitError(new RuntimeException("401 Unauthorized"));
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then — structured 401 and the never-returned session is cleaned up
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			final JsonNode error = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(entity.getBody())
				.path("code");
			assertThat(error.asText()).isEqualTo("unauthorized");
			// D5: the redacted target url is attached — scheme/host/path present, the
			// query (with its secret) absent.
			final JsonNode url = StreamableHttpProxyControllerTests.this.objectMapper.valueToTree(entity.getBody())
				.path("url");
			assertThat(url.asText()).isEqualTo("http://target/mcp");
			verify(StreamableHttpProxyControllerTests.this.registry).removeAndClose(any(String.class));
		}

		@Test
		@Story("Existing session")
		@Severity(SeverityLevel.MINOR)
		@Description("postMcp() request whose emit into a terminated sink fails returns 500")
		void postMcp_existingSessionRequestEmitFails_returns500() throws Exception {
			// given — the browserToTarget sink is already terminated
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			session.browserToTarget().tryEmitComplete();
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp("s1",
					null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
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

		/**
		 * Session whose {@code capabilities.tasks} was proxy-injected, i.e. one that the
		 * proxy may intercept task methods for.
		 */
		private ProxySession newSession(final String id) {
			final McpClientTransport target = mock(McpClientTransport.class);
			final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
			final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
			final ProxySession session = new ProxySession(id, target, browserToTarget, targetToBrowser);
			session.enableTasksInterception();
			return session;
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
		@Story("tasks/cancel interception")
		@Severity(SeverityLevel.MINOR)
		@Description("tasks/cancel without a taskId returns a JSON-RPC -32602 error")
		void tasksCancel_missingTaskId_returnsError() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tasks/cancel\",\"params\":{}}");
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("error").get("code").asInt()).isEqualTo(-32602);
			verify(this.taskService, never()).cancelTask(any());
		}

		@Test
		@Story("tasks/cancel interception")
		@Severity(SeverityLevel.NORMAL)
		@Description("tasks/cancel with an unknown taskId returns a JSON-RPC -32602 error")
		void tasksCancel_unknownTask_returnsError() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper.readTree(
					"{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tasks/cancel\",\"params\":{\"taskId\":\"missing\"}}");
			given(this.taskService.cancelTask("missing")).willThrow(new TaskNotFoundException("missing"));
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
		@Story("tasks/get interception")
		@Severity(SeverityLevel.MINOR)
		@Description("tasks/get without a params object returns a JSON-RPC -32602 error")
		void tasksGet_missingParams_returnsError() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"tasks/get\"}");
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
		@Severity(SeverityLevel.MINOR)
		@Description("tasks/get with a non-string taskId returns a JSON-RPC -32602 error")
		void tasksGet_nonStringTaskId_returnsError() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":33,\"method\":\"tasks/get\",\"params\":{\"taskId\":123}}");
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("error").get("code").asInt()).isEqualTo(-32602);
		}

		@Test
		@Story("task method on new session")
		@Severity(SeverityLevel.MINOR)
		@Description("a task method on a brand-new uninitialized session is relayed: interception requires the injection, which only an initialize response sets")
		void tasksGet_onNewSession_isRelayedNotIntercepted() throws Exception {
			// given: a new session request (no session id, no initialize yet) carrying a
			// tasks/get; the interception flag can only be set by a proxy-injected
			// initialize response, which this session never had
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"t-1\"}}");
			final JsonNode upstreamAnswer = StreamableHttpProxyControllerTests.this.objectMapper.readTree(
					"{\"jsonrpc\":\"2.0\",\"id\":20,\"result\":{\"taskId\":\"t-1\",\"status\":\"upstream-says\"}}");
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitNext(upstreamAnswer);
				return Mono.empty();
			});

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp(null, "http://target/mcp", body);

			// then: the frame is relayed (upstream answer returned verbatim), the session
			// header is present, and the TaskService was never consulted
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isEqualTo(upstreamAnswer);
			assertThat(response.getHeaders().getFirst(ProxyConstants.MCP_SESSION_ID_HEADER))
				.as("session-id header must be present on first POST response")
				.isNotBlank();
			verify(this.taskService, never()).getTask(any());
		}

		@Test
		@Story("task method relay")
		@Severity(SeverityLevel.CRITICAL)
		@Description("tasks/get on a session whose upstream advertised capabilities.tasks is relayed, not answered from the local TaskRegistry")
		void tasksGet_upstreamAdvertisedTasks_isRelayed() throws Exception {
			// given: a session whose initialize response carried an upstream-advertised
			// capabilities.tasks; injectTasksCapability backs off (no injection) and the
			// interception flag is never set, so task methods must take the plain relay
			final McpClientTransport target = mock(McpClientTransport.class);
			final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
			final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
			final ProxySession upstreamTasksSession = new ProxySession("s-upstream", target, browserToTarget,
					targetToBrowser);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"t-1\"}}");
			final JsonNode upstreamAnswer = StreamableHttpProxyControllerTests.this.objectMapper.readTree(
					"{\"jsonrpc\":\"2.0\",\"id\":40,\"result\":{\"taskId\":\"t-1\",\"status\":\"vendor-implementation\"}}");
			targetToBrowser.tryEmitNext(upstreamAnswer);
			given(StreamableHttpProxyControllerTests.this.registry.get("s-upstream")).willReturn(upstreamTasksSession);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s-upstream", null, body);

			// then: the upstream answer is returned verbatim and the local TaskService
			// was never consulted
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isEqualTo(upstreamAnswer);
			verify(this.taskService, never()).getTask(any());
		}

		@Test
		@Story("task method interception")
		@Severity(SeverityLevel.NORMAL)
		@Description("an intercepted tasks/get refreshes the session activity timestamp so the idle reaper keeps the session")
		void tasksGet_interceptedLocally_touchesSession() throws Exception {
			// given
			final ProxySession session = newSession("s1");
			final Instant before = session.lastActivity();
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"t-123\"}}");
			final TaskHandle handle = new TaskHandle("t-123", "working", null, "2025-01-01T00:00:00Z",
					"2025-01-01T00:00:00Z", 60000, 5000);
			given(this.taskService.getTask("t-123")).willReturn(handle);
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			Thread.sleep(5);

			// when
			this.controllerWithTasks.postMcp("s1", null, body);

			// then: lastActivity advanced past the pre-call value
			assertThat(session.lastActivity()).isAfter(before);
		}

		@Test
		@Story("tasks/list interception")
		@Severity(SeverityLevel.NORMAL)
		@Description("tasks/list honors params.cursor and emits nextCursor while more tasks remain")
		void tasksList_withCursor_returnsNextPageAndCursor() throws Exception {
			// given: more tasks than one page (page size 100), cursor skips the first
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tasks/list\",\"params\":{\"cursor\":\"1\"}}");
			final java.util.List<TaskHandle> handles = new java.util.ArrayList<>(102);
			for (int i = 1; i <= 102; i++) {
				handles.add(new TaskHandle("t-" + i, "working", null, "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z",
						60000, 5000));
			}
			given(this.taskService.listTasks()).willReturn(handles);
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then: a full page is returned past the cursor and a nextCursor is emitted
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			final JsonNode tasks = responseBody.get("result").get("tasks");
			assertThat(tasks).hasSize(100);
			assertThat(tasks.get(0).get("taskId").asText()).isEqualTo("t-2");
			assertThat(responseBody.get("result").get("nextCursor").asText()).isEqualTo("101");
		}

		@Test
		@Story("tasks/list interception")
		@Severity(SeverityLevel.MINOR)
		@Description("tasks/list emits no nextCursor when the cursor already consumed every task")
		void tasksList_lastPage_hasNoNextCursor() throws Exception {
			// given: two tasks, cursor already consumed the first
			final ProxySession session = newSession("s1");
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"tasks/list\",\"params\":{\"cursor\":\"1\"}}");
			final TaskHandle first = new TaskHandle("t-1", "working", null, "2025-01-01T00:00:00Z",
					"2025-01-01T00:00:00Z", 60000, 5000);
			final TaskHandle second = new TaskHandle("t-2", "working", null, "2025-01-01T00:00:00Z",
					"2025-01-01T00:00:00Z", 60000, 5000);
			given(this.taskService.listTasks()).willReturn(java.util.List.of(first, second));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);

			// when
			final ResponseEntity<Object> response = this.controllerWithTasks.postMcp("s1", null, body);

			// then: the remaining task is returned and the page reaches the list end, so
			// no nextCursor
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			final JsonNode responseBody = (JsonNode) response.getBody();
			assertThat(responseBody.get("result").get("tasks")).hasSize(1);
			assertThat(responseBody.get("result").get("tasks").get(0).get("taskId").asText()).isEqualTo("t-2");
			assertThat(responseBody.get("result").has("nextCursor")).as("last page has no nextCursor").isFalse();
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
			assertThat(tasks.has("requests"))
				.as("no tasks.requests.tools.call is advertised: no task-augmented call path exists")
				.isFalse();
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
		@Severity(SeverityLevel.MINOR)
		@Description("an initialize response without a result object passes through without injection")
		void postMcp_initializeRequest_withoutResult_passesThroughUnchanged() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1}");
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
			assertThat(responseBody.has("result")).as("no result is synthesised").isFalse();
		}

		@Test
		@Story("Initialize capability injection")
		@Severity(SeverityLevel.MINOR)
		@Description("an initialize response whose result is not an object passes through without injection")
		void postMcp_initializeRequest_nonObjectResult_passesThroughUnchanged() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"ok\"}");
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
			assertThat(responseBody.path("result").asText()).as("non-object result is preserved").isEqualTo("ok");
		}

		@Test
		@Story("Initialize capability injection")
		@Severity(SeverityLevel.MINOR)
		@Description("an initialize response whose result lacks a capabilities object gets no tasks injection")
		void postMcp_initializeRequest_withoutCapabilities_doesNotInjectTasks() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}");
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
			assertThat(responseBody.path("result").has("capabilities")).as("no capabilities object is synthesised")
				.isFalse();
		}

		@Test
		@Story("Initialize capability injection")
		@Severity(SeverityLevel.MINOR)
		@Description("an initialize response with a non-object capabilities value passes through without injection")
		void postMcp_initializeRequest_nonObjectCapabilities_doesNotInjectTasks() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			final JsonNode response = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":\"legacy\"}}");
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
			assertThat(responseBody.path("result").path("capabilities").asText())
				.as("non-object capabilities value is preserved")
				.isEqualTo("legacy");
		}

		@Test
		@Story("Initialize capability injection")
		@Severity(SeverityLevel.MINOR)
		@Description("an initialize request whose upstream completes without a response returns an empty body and injects nothing")
		void postMcp_initializeRequest_emptyUpstream_noInjection() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(target);
			given(StreamableHttpProxyControllerTests.this.mcpProxy.start(any())).willAnswer((inv) -> {
				final ProxySession s = inv.getArgument(0);
				s.targetToBrowser().tryEmitComplete();
				return Mono.empty();
			});
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

			// when
			final ResponseEntity<Object> entity = this.controllerWithTasks.postMcp(null, "http://target/mcp", body);

			// then - a null upstream response passes through without injection or crash
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(entity.getBody()).isNull();
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

	@Nested
	@DisplayName("D8 legacy-session WARN")
	class LegacySessionWarn {

		@Test
		@Story("Legacy session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("repeat access to the same owner-less session logs the legacy WARN exactly once")
		void legacySession_repeatAccess_logsWarnOnce() {
			// given - an unbound (legacy) session; no owner resolver wired
			final ProxySession session = newSession("s-legacy", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s-legacy")).willReturn(session);
			final ch.qos.logback.classic.Logger warnerLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
				.getLogger(io.inspector.mcp.core.proxy.LegacySessionAccessWarner.class);
			final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
			appender.start();
			warnerLogger.addAppender(appender);
			try {
				// when - the same session is hit on every endpoint that checks ownership
				StreamableHttpProxyControllerTests.this.controller.getMcp("s-legacy");
				StreamableHttpProxyControllerTests.this.controller.deleteMcp("s-legacy");
				StreamableHttpProxyControllerTests.this.controller.getMcp("s-legacy");

				// then - exactly one WARN was emitted for the session
				final long warns = appender.list.stream()
					.filter((e) -> e.getFormattedMessage() != null
							&& e.getFormattedMessage().contains("legacy session without owner binding"))
					.count();
				assertThat(warns).isEqualTo(1);
			}
			finally {
				warnerLogger.detachAppender(appender);
			}
		}

	}

	@Nested
	@DisplayName("D8 owner-check on delete")
	class DeleteOwnerCheck {

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.CRITICAL)
		@Description("deleteMcp() returns 404 when the session owner differs from the caller")
		void deleteMcp_foreignOwner_returns404() {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			session.bindProfile("owner-A", "profile-1");
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			given(resolver.resolve(any(), any())).willReturn("owner-B");
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final StreamableHttpProxyController wired = authController(store, resolver);

			// when
			final ResponseEntity<Void> response = wired.deleteMcp("s1");

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
			verify(StreamableHttpProxyControllerTests.this.registry, never()).removeAndClose(any());
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("deleteMcp() returns 404 when the caller owner cannot be resolved")
		void deleteMcp_nullCallerOwner_returns404() {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			session.bindProfile("owner-A", "profile-1");
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			given(resolver.resolve(any(), any())).willReturn(null);
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final StreamableHttpProxyController wired = authController(store, resolver);

			// when
			final ResponseEntity<Void> response = wired.deleteMcp("s1");

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
			verify(StreamableHttpProxyControllerTests.this.registry, never()).removeAndClose(any());
		}

	}

}
