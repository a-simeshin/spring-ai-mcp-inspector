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

package io.inspector.mcp.core.proxy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
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
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link McpProxy}. */
@Epic("MCP Inspector Core")
@Feature("MCP JSON-RPC frame relay")
class McpProxyTests {

	private final JsonMapper mapper = new JsonMapper();

	private McpProxy proxy;

	private McpClientTransport transport;

	private Sinks.Many<JsonNode> browserToTarget;

	private Sinks.Many<JsonNode> targetToBrowser;

	private ProxySession session;

	@BeforeEach
	void setUp() {
		this.proxy = new McpProxy(this.mapper);
		this.transport = mock(McpClientTransport.class);
		given(this.transport.closeGracefully()).willReturn(Mono.empty());
		this.browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		this.targetToBrowser = Sinks.many().replay().limit(64);
		this.session = new ProxySession("s-1", this.transport, this.browserToTarget, this.targetToBrowser);
	}

	@Nested
	@DisplayName("start() - browser → target")
	class BrowserToTarget {

		@Test
		@Story("Relay browser frame to target")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a JSON frame pushed into browserToTarget is deserialized and forwarded to the target transport")
		void start_relaysBrowserFrameToTargetTransport() {
			// given
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);
			final JsonNode frame = McpProxyTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 7)
				.put("method", "tools/list");

			// when
			McpProxyTests.this.browserToTarget.tryEmitNext(frame);

			// then
			final ArgumentCaptor<JSONRPCMessage> captor = ArgumentCaptor.forClass(JSONRPCMessage.class);
			verify(McpProxyTests.this.transport, timeout(1000)).sendMessage(captor.capture());
			assertThat(captor.getValue()).isInstanceOf(McpSchema.JSONRPCRequest.class);
			final McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) captor.getValue();
			assertThat(request.method()).isEqualTo("tools/list");
		}

		@Test
		@Story("Malformed frame is dropped")
		@Severity(SeverityLevel.NORMAL)
		@Description("a malformed JSON-RPC frame is dropped and never reaches the target transport")
		void start_withMalformedFrame_doesNotForwardToTarget() {
			// given
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);
			final JsonNode malformed = McpProxyTests.this.mapper.createObjectNode().put("totally", "not-json-rpc");

			// when
			McpProxyTests.this.browserToTarget.tryEmitNext(malformed);

			// then
			verify(McpProxyTests.this.transport, never()).sendMessage(any());
		}

		@Test
		@Story("sendMessage failure is contained")
		@Severity(SeverityLevel.NORMAL)
		@Description("a sendMessage failure is logged and the relay keeps forwarding subsequent frames")
		void start_whenSendMessageFails_continuesRelayingNextFrame() {
			// given
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any()))
				.willReturn(Mono.error(new RuntimeException("send failed")), Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// when - two valid frames; the first send errors, the second must still be
			// relayed
			McpProxyTests.this.browserToTarget
				.tryEmitNext(McpProxyTests.this.mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "ping"));
			McpProxyTests.this.browserToTarget.tryEmitNext(McpProxyTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 2)
				.put("method", "tools/list"));

			// then
			verify(McpProxyTests.this.transport, timeout(1000).times(2)).sendMessage(any());
		}

		@Test
		@Story("sendMessage failure is surfaced")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a sendMessage failure (e.g. connection refused) fails the session upstream so per-request awaiters and the SSE backchannel wake fast")
		void start_whenSendMessageFails_terminatesUpstream() {
			// given - the SDK masks sendMessage errors and re-surfaces them on the
			// pump as a wrapped completion failure
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			final java.util.concurrent.CompletionException connectError = new java.util.concurrent.CompletionException(
					new java.net.ConnectException("Connection refused"));
			given(McpProxyTests.this.transport.sendMessage(any())).willReturn(Mono.error(connectError));
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// when
			McpProxyTests.this.browserToTarget.tryEmitNext(McpProxyTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "initialize"));

			// then - the upstream failure is propagated to the browser side instead of
			// being swallowed until the streamable-request timeout
			verify(McpProxyTests.this.transport, timeout(1000)).sendMessage(any());
			assertThat(McpProxyTests.this.session.isUpstreamTerminated()).isTrue();
			StepVerifier.create(McpProxyTests.this.targetToBrowser.asFlux())
				.expectError()
				.verify(Duration.ofSeconds(1));
		}

		@Test
		@Story("Handshake ordering")
		@Severity(SeverityLevel.CRITICAL)
		@Description("initialize and tools/list sent back-to-back must reach the transport in order: "
				+ "initialize first, then tools/list. Without the handshake gate, flatMap would send "
				+ "tools/list concurrently and the server could reject it as an unknown session")
		void start_handshakeGate_ordersInitializeBeforeNonHandshakeFrames() {
			// given
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// when: send initialize and tools/list back-to-back without waiting for a
			// response
			final JsonNode initFrame = McpProxyTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "initialize");
			final JsonNode toolsListFrame = McpProxyTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 2)
				.put("method", "tools/list");
			McpProxyTests.this.browserToTarget.tryEmitNext(initFrame);
			McpProxyTests.this.browserToTarget.tryEmitNext(toolsListFrame);

			// then: initialize must be sent before tools/list
			final ArgumentCaptor<JSONRPCMessage> captor = ArgumentCaptor.forClass(JSONRPCMessage.class);
			verify(McpProxyTests.this.transport, timeout(1000).times(2)).sendMessage(captor.capture());
			final java.util.List<JSONRPCMessage> messages = captor.getAllValues();
			assertThat(messages).hasSize(2);
			assertThat(messages.get(0)).isInstanceOfSatisfying(McpSchema.JSONRPCRequest.class,
					(req) -> assertThat(req.method()).isEqualTo("initialize"));
			assertThat(messages.get(1)).isInstanceOfSatisfying(McpSchema.JSONRPCRequest.class,
					(req) -> assertThat(req.method()).isEqualTo("tools/list"));
		}

		@Test
		@Story("Handshake gate release on error")
		@Severity(SeverityLevel.CRITICAL)
		@Description("when initialize sendMessage errors, the handshake gate is released with the error, "
				+ "and subsequent frames waiting on the gate fail fast instead of hanging forever")
		void start_whenInitializeErrors_releasesGateWaiters() {
			// given - initialize send fails
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any()))
				.willReturn(Mono.error(new RuntimeException("init failed")));
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// when: send initialize (which errors) and tools/list back-to-back
			McpProxyTests.this.browserToTarget.tryEmitNext(initFrame());
			McpProxyTests.this.browserToTarget.tryEmitNext(toolsListFrame());

			// then: initialize was sent, the gate was released with the error,
			// and the session is terminated. The tools/list frame waiting on the
			// gate completed (via error) instead of hanging forever.
			verify(McpProxyTests.this.transport, timeout(1000).atLeast(1)).sendMessage(any());
			assertThat(McpProxyTests.this.session.isUpstreamTerminated()).isTrue();
			StepVerifier.create(McpProxyTests.this.targetToBrowser.asFlux())
				.expectError()
				.verify(Duration.ofSeconds(3));
		}

		@Test
		@Story("Handshake gate release on timeout")
		@Severity(SeverityLevel.CRITICAL)
		@Description("when initialize sendMessage never completes, the 1-minute timeout releases the "
				+ "handshake gate with an error, and subsequent frames do not hang forever")
		void start_whenInitializeTimesOut_releasesGateWaiters() {
			// given - initialize send never completes
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any())).willReturn(Mono.never());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// when: send initialize (which never returns) and tools/list back-to-back
			McpProxyTests.this.browserToTarget.tryEmitNext(initFrame());
			McpProxyTests.this.browserToTarget.tryEmitNext(toolsListFrame());

			// then: the 1-minute timeout should fire, releasing the gate and failing
			// the session. tools/list must complete (not hang) - we verify this by
			// waiting for the session to be terminated (via the targetToBrowser error)
			// within a reasonable time after the 1-minute timeout.
			// sendMessage is called once for initialize (the mock returns Mono.never());
			// the tools/list frame is blocked by the gate, so its sendMessage is never
			// reached before the timeout fires. After the timeout the gate error is
			// propagated through onErrorContinue and the session is terminated.
			StepVerifier.create(McpProxyTests.this.targetToBrowser.asFlux())
				.expectError()
				.verify(Duration.ofMinutes(2));
			assertThat(McpProxyTests.this.session.isUpstreamTerminated()).isTrue();
		}

		private static JsonNode initFrame() {
			return new JsonMapper().createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize");
		}

		private static JsonNode toolsListFrame() {
			return new JsonMapper().createObjectNode().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list");
		}

	}

	@Nested
	@DisplayName("start() - target → browser")
	class TargetToBrowser {

		@Test
		@Story("Relay target frame to browser")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the connect handler converts an inbound typed frame to a JsonNode, "
				+ "emits it on targetToBrowser and touches the session")
		@SuppressWarnings("unchecked")
		void start_relaysTargetFrameToBrowserSinkAndTouches() {
			// given
			final ArgumentCaptor<Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>> handlerCaptor = ArgumentCaptor
				.forClass(Function.class);
			given(McpProxyTests.this.transport.connect(handlerCaptor.capture())).willReturn(Mono.empty());
			final java.time.Instant before = McpProxyTests.this.session.lastActivity();

			// when
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);
			final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = handlerCaptor.getValue();
			final McpSchema.JSONRPCResponse inbound = new McpSchema.JSONRPCResponse("2.0", 7,
					McpProxyTests.this.mapper.createObjectNode().put("ok", true), null);
			final Mono<JSONRPCMessage> reply = handler.apply(Mono.just(inbound));

			// then - handler returns empty (proxy never originates a reply)
			StepVerifier.create(reply).verifyComplete();
			// and the inbound frame was emitted to the browser sink
			StepVerifier.create(McpProxyTests.this.targetToBrowser.asFlux().next())
				.assertNext((node) -> assertThat(node.get("id").asInt()).isEqualTo(7))
				.thenCancel()
				.verify(Duration.ofSeconds(1));
			assertThat(McpProxyTests.this.session.lastActivity()).isAfterOrEqualTo(before);
		}

		@Test
		@Story("Browser sink emit failure is contained")
		@Severity(SeverityLevel.NORMAL)
		@Description("when the browser sink is already complete, the connect handler logs the emit failure "
				+ "and still returns an empty reply")
		@SuppressWarnings("unchecked")
		void start_whenBrowserSinkComplete_handlerSwallowsEmitFailure() {
			// given - complete the browser sink so tryEmitNext reports a failure result
			McpProxyTests.this.targetToBrowser.tryEmitComplete();
			final ArgumentCaptor<Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>> handlerCaptor = ArgumentCaptor
				.forClass(Function.class);
			given(McpProxyTests.this.transport.connect(handlerCaptor.capture())).willReturn(Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);
			final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = handlerCaptor.getValue();
			final McpSchema.JSONRPCResponse inbound = new McpSchema.JSONRPCResponse("2.0", 1,
					McpProxyTests.this.mapper.createObjectNode().put("ok", true), null);

			// when
			final Mono<JSONRPCMessage> reply = handler.apply(Mono.just(inbound));

			// then - the handler never originates a reply even when emission fails
			StepVerifier.create(reply).verifyComplete();
		}

	}

	@Nested
	@DisplayName("start() - connect wiring")
	class ConnectWiring {

		@Test
		@Story("Readiness signal")
		@Severity(SeverityLevel.NORMAL)
		@Description("start() returns the Mono produced by the transport's connect() call")
		void start_returnsConnectReadinessMono() {
			// given
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());

			// when
			final Mono<Void> started = McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// then
			StepVerifier.create(started).verifyComplete();
			verify(McpProxyTests.this.transport).connect(any());
		}

		@Test
		@Story("Connect failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("a connect() failure is surfaced through the returned Mono")
		void start_whenConnectFails_propagatesError() {
			// given
			given(McpProxyTests.this.transport.connect(any()))
				.willReturn(Mono.error(new IllegalStateException("connect failed")));

			// when
			final Mono<Void> started = McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// then
			StepVerifier.create(started)
				.expectErrorMatches(
						(err) -> err instanceof IllegalStateException && err.getMessage().equals("connect failed"))
				.verify(Duration.ofSeconds(1));
		}

	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@Story("Null JsonMapper fallback")
		@Severity(SeverityLevel.MINOR)
		@Description("a null JsonMapper falls back to a default instance and the proxy still wires connect()")
		void constructor_withNullMapper_stillWiresProxy() {
			// given
			final McpProxy nullMapperProxy = new McpProxy(null);
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());

			// when
			final Mono<Void> started = nullMapperProxy.start(McpProxyTests.this.session);

			// then
			StepVerifier.create(started).verifyComplete();
		}

	}

	@Nested
	@DisplayName("OAuth2 one-retry (D9)")
	class OAuth2OneRetry {

		private StubTokenServer tokenServer;

		private OAuth2ClientCredentialsTokenManager manager;

		private AuthProfileStore store;

		@BeforeEach
		void setUp() throws IOException {
			this.tokenServer = new StubTokenServer();
			this.tokenServer.start();
			this.manager = new OAuth2ClientCredentialsTokenManager(
					java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), null);
			this.store = new AuthProfileStore();
			McpProxyTests.this.proxy = new McpProxy(McpProxyTests.this.mapper, this.store, this.manager, null);
		}

		@AfterEach
		void tearDown() {
			this.tokenServer.stop();
		}

		@Test
		@Story("One-retry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a 401 on a client-credentials session refreshes the token (client_credentials, never refresh_token) and re-sends ONCE")
		void sendMessage_401_refreshesTokenAndRetriesOnce() {
			// given - a session bound to a client-credentials profile with stored
			// credentials and a cached token
			bindClientCredentialsSession();
			this.tokenServer.respond(200, "{\"access_token\":\"tok-2\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
			final Throwable unauthorized = authz401();
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any())).willReturn(Mono.error(unauthorized))
				.willReturn(Mono.empty());
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// when
			McpProxyTests.this.browserToTarget.tryEmitNext(toolsListFrame());

			// then - exactly two send attempts: the original and the retry with the
			// fresh token; the refresh re-exchanged stored client credentials (no
			// refresh_token grant) and the session survived
			awaitTrue(() -> "Bearer tok-2".equals(McpProxyTests.this.session.authorizationRef().get()),
					Duration.ofSeconds(3));
			verify(McpProxyTests.this.transport, timeout(3000).times(2)).sendMessage(any());
			final String refreshBody = this.tokenServer.requestBodies().get(1);
			assertThat(refreshBody).contains("grant_type=client_credentials");
			assertThat(refreshBody).doesNotContain("refresh_token");
			assertThat(this.tokenServer.requestCount()).isEqualTo(2);
			assertThat(McpProxyTests.this.session.authorizationRef().get()).isEqualTo("Bearer tok-2");
			assertThat(McpProxyTests.this.session.isUpstreamTerminated()).isFalse();
		}

		@Test
		@Story("One-retry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a second 401 after the refresh aborts: exactly one retry, then the session fails upstream")
		void sendMessage_second401_abortsAfterOneRetry() {
			// given
			bindClientCredentialsSession();
			this.tokenServer.respond(200, "{\"access_token\":\"tok-2\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
			final Throwable unauthorized = authz401();
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any())).willReturn(Mono.error(unauthorized));
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// when
			McpProxyTests.this.browserToTarget.tryEmitNext(toolsListFrame());

			// then - one retry happened (two sends total), then the session failed
			// upstream instead of retrying again
			awaitTrue(() -> McpProxyTests.this.session.isUpstreamTerminated(), Duration.ofSeconds(3));
			verify(McpProxyTests.this.transport, timeout(3000).times(2)).sendMessage(any());
			assertThat(McpProxyTests.this.session.isUpstreamTerminated()).isTrue();
		}

		@Test
		@Story("One-retry")
		@Severity(SeverityLevel.NORMAL)
		@Description("a non-auth transport failure is NOT retried")
		void sendMessage_plainFailure_isNotRetried() {
			// given
			bindClientCredentialsSession();
			given(McpProxyTests.this.transport.connect(any())).willReturn(Mono.empty());
			given(McpProxyTests.this.transport.sendMessage(any()))
				.willReturn(Mono.error(new IllegalStateException("boom")));
			McpProxyTests.this.proxy.start(McpProxyTests.this.session);

			// when
			McpProxyTests.this.browserToTarget.tryEmitNext(toolsListFrame());

			// then - a single send attempt, no refresh, session failed upstream
			awaitTrue(() -> McpProxyTests.this.session.isUpstreamTerminated(), Duration.ofSeconds(3));
			verify(McpProxyTests.this.transport, timeout(3000)).sendMessage(any());
			assertThat(this.tokenServer.requestCount()).isEqualTo(1);
			assertThat(McpProxyTests.this.session.isUpstreamTerminated()).isTrue();
		}

		private void bindClientCredentialsSession() {
			this.tokenServer.respond(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
			final OAuth2Profile cc = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS, this.tokenServer.url(),
					"client-1", "secret-1", "mcp.read mcp.write", null, null, null, null);
			final String profileId = this.store.register("owner-a", cc);
			this.manager.acquire(profileId, cc);
			McpProxyTests.this.session.bindProfile("owner-a", profileId);
		}

		private static McpHttpClientTransportAuthorizationException authz401() {
			final HttpRequestSnapshot snapshot = new HttpRequestSnapshot(URI.create("https://target/sse"), "POST",
					HttpHeaders.of(java.util.Map.of(), (name, value) -> true));
			final HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);
			given(responseInfo.statusCode()).willReturn(401);
			return new McpHttpClientTransportAuthorizationException("Unauthorized", snapshot, responseInfo);
		}

		private static JsonNode toolsListFrame() {
			return new JsonMapper().createObjectNode().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/list");
		}

		private static void awaitTrue(final java.util.function.BooleanSupplier condition, final Duration timeout) {
			final long deadline = System.nanoTime() + timeout.toNanos();
			while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
				try {
					Thread.sleep(10);
				}
				catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			assertThat(condition.getAsBoolean()).isTrue();
		}

	}

	static final class StubTokenServer {

		private final HttpServer server;

		private final List<String> requestBodies = new ArrayList<>();

		private final AtomicInteger requestCount = new AtomicInteger();

		private volatile int status = 200;

		private volatile String body = "{}";

		StubTokenServer() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/token", this::handle);
			this.server.setExecutor(null);
		}

		void start() {
			this.server.start();
		}

		void stop() {
			this.server.stop(0);
		}

		String url() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
		}

		void respond(final int status, final String body) {
			this.status = status;
			this.body = body;
		}

		int requestCount() {
			return this.requestCount.get();
		}

		List<String> requestBodies() {
			return this.requestBodies;
		}

		private void handle(final HttpExchange exchange) throws IOException {
			final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			synchronized (this.requestBodies) {
				this.requestBodies.add(requestBody);
			}
			this.requestCount.incrementAndGet();
			final byte[] response = this.body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(this.status, response.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(response);
			}
		}

	}

}
