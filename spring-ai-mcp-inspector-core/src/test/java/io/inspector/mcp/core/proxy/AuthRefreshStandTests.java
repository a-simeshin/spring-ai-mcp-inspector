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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
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
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke tests for the auth-refresh test stand ({@link AuthorizationCaptureTransport} plus
 * {@link ScriptedTokenServer}), the reusable pinning surface for issue #375.
 *
 * <p>
 * The end-to-end scenario wires the production contract: ONE {@link AtomicReference}
 * instance handed to both the capturing transport and the {@link ProxySession}, a 401 on
 * the first outgoing request driving the real {@link McpProxy} one-retry refresh, and the
 * captured Authorization headers proving the retried request carries the fresh token.
 *
 * @author Artem Simeshin
 */
@Epic("MCP Inspector Core")
@Feature("Auth refresh test stand")
class AuthRefreshStandTests {

	private static JSONRPCRequest request(final String method) {
		return new JSONRPCRequest("2.0", method, 1, null);
	}

	@Nested
	@DisplayName("AuthorizationCaptureTransport")
	class Capture {

		@Test
		@Story("Ordered capture")
		@Severity(SeverityLevel.CRITICAL)
		@Description("every outgoing message is recorded in call order and the Authorization header is read lazily "
				+ "at call time, so a reference update between calls is visible in the next snapshot")
		void sendMessage_recordsOneSnapshotPerCall_withLazyHeaderRead() {
			// given
			final AtomicReference<String> ref = new AtomicReference<>("Bearer tok-a");
			final AuthorizationCaptureTransport transport = new AuthorizationCaptureTransport(ref);

			// when
			transport.sendMessage(request("tools/list")).block();
			ref.set("Bearer tok-b");
			transport.sendMessage(request("tools/call")).block();

			// then
			assertThat(transport.authorizationHeaders()).containsExactly("Bearer tok-a", "Bearer tok-b");
			assertThat(transport.capturedCalls().get(0).message()).isNotNull();
			assertThat(transport.capturedCalls().get(1).message()).isNotNull();
		}

		@Test
		@Story("Ordered capture")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the connect prologue is captured as the first snapshot with a null message, "
				+ "mirroring the production SSE prologue that carries the header")
		void connect_prologueIsCapturedBeforeMessages() {
			// given
			final AtomicReference<String> ref = new AtomicReference<>("Bearer tok-prologue");
			final AuthorizationCaptureTransport transport = new AuthorizationCaptureTransport(ref);

			// when
			transport.connect((inbound) -> inbound).block();
			transport.sendMessage(request("tools/list")).block();

			// then
			assertThat(transport.authorizationHeaders()).containsExactly("Bearer tok-prologue", "Bearer tok-prologue");
			assertThat(transport.capturedCalls().get(0).message()).isNull();
			assertThat(transport.capturedCalls().get(1).message()).isNotNull();
		}

		@Test
		@Story("Scripted 401")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a scripted 401 fails the send with the SDK authorization exception after the snapshot "
				+ "has been recorded, and the following send succeeds: the one-retry trigger shape")
		void failNextSendWithUnauthorized_recordsSnapshotThenFailsWithSdkAuthException() {
			// given
			final AuthorizationCaptureTransport transport = new AuthorizationCaptureTransport(
					new AtomicReference<>("Bearer tok-a"));
			transport.failNextSendWithUnauthorized();

			// when
			StepVerifier.create(transport.sendMessage(request("tools/list")))
				.expectError(McpHttpClientTransportAuthorizationException.class)
				.verify(Duration.ofSeconds(1));
			StepVerifier.create(transport.sendMessage(request("tools/list"))).verifyComplete();

			// then: the failing send was still captured before the error surfaced
			assertThat(transport.authorizationHeaders()).containsExactly("Bearer tok-a", "Bearer tok-a");
			assertThat(transport.capturedCalls()).hasSize(2);
		}

		@Test
		@Story("Scripted failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("a scripted non-auth failure surfaces as the given error type and still records the snapshot")
		void failNextSendWith_recordsSnapshotAndSurfacesGivenError() {
			// given
			final AuthorizationCaptureTransport transport = new AuthorizationCaptureTransport(
					new AtomicReference<>("Bearer tok-a"));
			transport.failNextSendWith(new IllegalStateException("boom"));

			// when + then
			StepVerifier.create(transport.sendMessage(request("tools/list")))
				.expectError(IllegalStateException.class)
				.verify(Duration.ofSeconds(1));
			assertThat(transport.authorizationHeaders()).containsExactly("Bearer tok-a");
		}

		@Test
		@Story("Contract")
		@Severity(SeverityLevel.NORMAL)
		@Description("the stand refuses inbound unmarshalling instead of silently returning null, "
				+ "so a test that accidentally relies on wire decoding fails loudly")
		void unmarshalFrom_failsLoudly() {
			final AuthorizationCaptureTransport transport = new AuthorizationCaptureTransport(new AtomicReference<>());
			assertThatThrownBy(() -> transport.unmarshalFrom(new Object(),
					new io.modelcontextprotocol.json.TypeRef<JSONRPCRequest>() {
					}))
				.isInstanceOf(UnsupportedOperationException.class);
		}

	}

	@Nested
	@DisplayName("ScriptedTokenServer")
	class TokenServer {

		private ScriptedTokenServer server;

		@BeforeEach
		void setUp() throws IOException {
			this.server = new ScriptedTokenServer();
			this.server.start();
		}

		@AfterEach
		void tearDown() {
			this.server.stop();
		}

		@Test
		@Story("Token sequence")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the first exchange returns token A, the second exchange token B, and exchanges past "
				+ "the script repeat the last token; every request body is recorded")
		void issueTokensInSequence_firstCallReturnsA_secondReturnsB() {
			// given
			this.server.issueTokensInSequence("tok-a", "tok-b");

			// when
			final String first = exchangeAccessToken(this.server);
			final String second = exchangeAccessToken(this.server);
			final String third = exchangeAccessToken(this.server);

			// then
			assertThat(first).contains("\"access_token\":\"tok-a\"");
			assertThat(second).contains("\"access_token\":\"tok-b\"");
			assertThat(third).contains("\"access_token\":\"tok-b\"");
			assertThat(this.server.requestCount()).isEqualTo(3);
			assertThat(this.server.requestBodies()).hasSize(3);
			assertThat(this.server.requestBodies().get(0)).contains("grant_type=client_credentials");
		}

		@Test
		@Story("Fixed response")
		@Severity(SeverityLevel.NORMAL)
		@Description("a scripted fixed response overrides the token sequence and is served with the given status")
		void respond_fixedModeOverridesSequence() {
			// given
			this.server.issueTokensInSequence("tok-a", "tok-b");
			this.server.respond(500, "{\"error\":\"boom\"}");

			// when
			final HttpResponse<String> response = exchange(this.server);

			// then
			assertThat(response.statusCode()).isEqualTo(500);
			assertThat(response.body()).contains("boom");
			assertThat(this.server.requestCount()).isEqualTo(1);
		}

		private static HttpResponse<String> exchange(final ScriptedTokenServer server) {
			final HttpRequest request = HttpRequest.newBuilder(URI.create(server.url()))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials", StandardCharsets.UTF_8))
				.build();
			try {
				return HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(5))
					.build()
					.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			}
			catch (final Exception ex) {
				throw new IllegalStateException("token exchange against the stand failed", ex);
			}
		}

		private static String exchangeAccessToken(final ScriptedTokenServer server) {
			final HttpResponse<String> response = exchange(server);
			assertThat(response.statusCode()).isEqualTo(200);
			return response.body();
		}

	}

	@Nested
	@DisplayName("Refresh scenario through McpProxy")
	class RefreshScenario {

		private ScriptedTokenServer tokenServer;

		private OAuth2ClientCredentialsTokenManager manager;

		private AuthProfileStore store;

		private final JsonMapper mapper = new JsonMapper();

		@BeforeEach
		void setUp() throws IOException {
			this.tokenServer = new ScriptedTokenServer();
			this.tokenServer.start();
			this.manager = new OAuth2ClientCredentialsTokenManager(
					HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), null);
			this.store = new AuthProfileStore();
		}

		@AfterEach
		void tearDown() {
			this.tokenServer.stop();
		}

		@Test
		@Story("Fresh header after refresh")
		@Severity(SeverityLevel.CRITICAL)
		@Description("wired like the production proxy (the same AtomicReference instance into the capturing "
				+ "transport and the ProxySession), a 401 on the first outgoing request drives the real "
				+ "McpProxy refresh, and the captured Authorization headers prove the retried outgoing "
				+ "request carries the fresh token: [prologue(A), send(A), retry(B)]")
		void afterRefresh_nextOutgoingRequestCarriesFreshHeader() {
			// given: token A on the first exchange, token B on the refresh exchange
			this.tokenServer.issueTokensInSequence("tok-a", "tok-b");
			final OAuth2Profile cc = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS, this.tokenServer.url(),
					"client-1", "secret-1", "mcp.read mcp.write", null, null, null, null);
			final String profileId = this.store.register("owner-a", cc);
			this.manager.acquire(profileId, cc);
			// production wiring: ONE shared reference instance, transport and session
			final AtomicReference<String> authorizationRef = new AtomicReference<>(
					"Bearer " + this.manager.getAccessToken(profileId, false).accessToken());
			final AuthorizationCaptureTransport transport = new AuthorizationCaptureTransport(authorizationRef);
			final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
			final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(64);
			final ProxySession session = new ProxySession("s-stand", transport, browserToTarget, targetToBrowser,
					authorizationRef);
			session.bindProfile("owner-a", profileId);
			transport.failNextSendWithUnauthorized();
			final McpProxy proxy = new McpProxy(this.mapper, this.store, this.manager, null);

			// when: the prologue goes out with token A, then a frame 401s and the
			// one-retry path refreshes to token B and re-sends
			proxy.start(session).block(Duration.ofSeconds(5));
			browserToTarget.tryEmitNext(toolsListFrame(this.mapper));

			// then: call order is prologue(A), send(A), refresh(B), retry(B)
			awaitTrue(() -> transport.capturedCalls().size() >= 3, Duration.ofSeconds(5));
			assertThat(transport.authorizationHeaders()).containsExactly("Bearer tok-a", "Bearer tok-a",
					"Bearer tok-b");
			assertThat(transport.capturedCalls().get(0).message()).isNull();
			assertThat(this.tokenServer.requestCount()).isEqualTo(2);
			assertThat(this.tokenServer.requestBodies().get(1)).contains("grant_type=client_credentials");
		}

		private static JsonNode toolsListFrame(final JsonMapper mapper) {
			return mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/list");
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

}
