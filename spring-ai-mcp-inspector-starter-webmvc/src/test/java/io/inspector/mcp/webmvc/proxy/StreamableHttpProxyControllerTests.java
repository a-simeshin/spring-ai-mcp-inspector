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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link StreamableHttpProxyController}. Collaborators are mocked;
 * assertions cover the request/notification split on {@code POST /mcp}, the
 * session-id-header behaviour, the {@code GET}/{@code DELETE} branches and the
 * request/response correlation via the replay sink.
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
		@Description("postMcp() without a session id and without url returns 400")
		void postMcp_withoutSessionAndUrl_returns400() throws Exception {
			// given
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					null, body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("New session")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() returns 502 BAD_GATEWAY when the upstream transport cannot be built")
		void postMcp_whenUpstreamConnectFails_returns502() throws Exception {
			// given
			given(StreamableHttpProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willThrow(new RuntimeException("connect failed"));
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");

			// when
			final ResponseEntity<Object> response = StreamableHttpProxyControllerTests.this.controller.postMcp(null,
					"http://target/mcp", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
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
		@Description("postMcp() request returns 504 GATEWAY_TIMEOUT when no matching response arrives in time")
		void postMcp_existingSessionRequest_timesOutReturns504() throws Exception {
			// given — no response ever emitted on the sink
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(StreamableHttpProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode body = StreamableHttpProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Object> entity = StreamableHttpProxyControllerTests.this.controller.postMcp("s1", null,
					body);

			// then
			assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
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

}
