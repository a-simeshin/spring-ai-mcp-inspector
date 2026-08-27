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

package io.inspector.mcp.core.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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

import io.inspector.mcp.core.proxy.ProxyUpstreamException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link OAuth2AuthCodeTokenExchanger} — server-issued state, PKCE and the
 * token exchange (D9B).
 */
@Epic("MCP Inspector Core")
@Feature("OAuth2AuthCodeTokenExchanger")
class OAuth2AuthCodeTokenExchangerTests {

	private StubTokenServer tokenServer;

	private OAuth2AuthCodeTokenExchanger exchanger;

	@BeforeEach
	void setUp() throws IOException {
		this.tokenServer = new StubTokenServer();
		OAuth2AuthCodeTokenExchangerTests.this.tokenServer.start();
		this.exchanger = new OAuth2AuthCodeTokenExchanger(
				OAuth2AuthCodeTokenExchangerTests.this.tokenServer.httpClient(), null);
	}

	@AfterEach
	void tearDown() {
		OAuth2AuthCodeTokenExchangerTests.this.tokenServer.stop();
	}

	private static String s256(final String codeVerifier) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (final java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private OAuth2Profile pendingProfile(final String codeChallenge) {
		return new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE,
				OAuth2AuthCodeTokenExchangerTests.this.tokenServer.url(), "client-1", null, "mcp.read",
				"https://idp/auth", "https://app/callback", codeChallenge, "S256");
	}

	@Nested
	@DisplayName("mintState() / verifyAndConsumeState()")
	class StateLifecycle {

		@Test
		@Story("State mint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("mintState() issues a random server-side state bound to (ownerId, profileId)")
		void mintState_returnsRandomState() {
			// when
			final String first = OAuth2AuthCodeTokenExchangerTests.this.exchanger.mintState("owner-a", "pid-1");
			final String second = OAuth2AuthCodeTokenExchangerTests.this.exchanger.mintState("owner-a", "pid-1");

			// then
			assertThat(first).isNotBlank();
			assertThat(second).isNotEqualTo(first);
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.stateCount()).isEqualTo(1);
		}

		@Test
		@Story("State verify")
		@Severity(SeverityLevel.CRITICAL)
		@Description("verifyAndConsumeState() accepts the matching state once and rejects the replay")
		void verifyAndConsumeState_happyThenReplay() {
			// given
			final String state = OAuth2AuthCodeTokenExchangerTests.this.exchanger.mintState("owner-a", "pid-1");

			// when
			final boolean first = OAuth2AuthCodeTokenExchangerTests.this.exchanger.verifyAndConsumeState("owner-a",
					"pid-1", state);
			final boolean replay = OAuth2AuthCodeTokenExchangerTests.this.exchanger.verifyAndConsumeState("owner-a",
					"pid-1", state);

			// then
			assertThat(first).isTrue();
			assertThat(replay).isFalse();
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.stateCount()).isZero();
		}

		@Test
		@Story("State verify")
		@Severity(SeverityLevel.CRITICAL)
		@Description("verifyAndConsumeState() rejects a mismatching state value")
		void verifyAndConsumeState_mismatch_returnsFalse() {
			// given
			OAuth2AuthCodeTokenExchangerTests.this.exchanger.mintState("owner-a", "pid-1");

			// when
			final boolean result = OAuth2AuthCodeTokenExchangerTests.this.exchanger.verifyAndConsumeState("owner-a",
					"pid-1", "attacker-state");

			// then
			assertThat(result).isFalse();
			// the state is NOT consumed on mismatch — a correct retry still works
			OAuth2AuthCodeTokenExchangerTests.this.exchanger.mintState("owner-a", "pid-1");
		}

		@Test
		@Story("State verify")
		@Severity(SeverityLevel.CRITICAL)
		@Description("verifyAndConsumeState() rejects a cross-owner presentation")
		void verifyAndConsumeState_crossOwner_returnsFalse() {
			// given
			final String state = OAuth2AuthCodeTokenExchangerTests.this.exchanger.mintState("owner-a", "pid-1");

			// when
			final boolean result = OAuth2AuthCodeTokenExchangerTests.this.exchanger.verifyAndConsumeState("owner-b",
					"pid-1", state);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@Story("State verify")
		@Severity(SeverityLevel.CRITICAL)
		@Description("verifyAndConsumeState() rejects an expired state (TTL 10 minutes)")
		void verifyAndConsumeState_expired_returnsFalse() throws Exception {
			// given — a state whose TTL has already passed (expiresAt in the past)
			final String state = OAuth2AuthCodeTokenExchangerTests.this.exchanger.mintState("owner-a", "pid-1");
			expireState("pid-1", state);

			// when
			final boolean result = OAuth2AuthCodeTokenExchangerTests.this.exchanger.verifyAndConsumeState("owner-a",
					"pid-1", state);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@Story("State verify")
		@Severity(SeverityLevel.NORMAL)
		@Description("verifyAndConsumeState() rejects null inputs and unknown profile ids")
		void verifyAndConsumeState_nullOrUnknown_returnsFalse() {
			// when/then
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.verifyAndConsumeState(null, "pid-1", "s"))
				.isFalse();
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.verifyAndConsumeState("owner-a", null, "s"))
				.isFalse();
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.verifyAndConsumeState("owner-a", "pid-1", null))
				.isFalse();
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.verifyAndConsumeState("owner-a", "never-minted",
					"s"))
				.isFalse();
		}

		/** Replaces the stored state with an expired entry via reflection (test seam). */
		private void expireState(final String profileId, final String state) throws Exception {
			final java.lang.reflect.Field statesField = OAuth2AuthCodeTokenExchanger.class.getDeclaredField("states");
			statesField.setAccessible(true);
			final ConcurrentMap<String, Object> states = (ConcurrentMap<String, Object>) statesField
				.get(OAuth2AuthCodeTokenExchangerTests.this.exchanger);
			final Class<?> pendingState = Class
				.forName("io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger$PendingState");
			final java.lang.reflect.Constructor<?> ctor = pendingState.getDeclaredConstructor(String.class,
					String.class, Instant.class);
			ctor.setAccessible(true);
			states.put(profileId, ctor.newInstance("owner-a", state, Instant.now().minusSeconds(1)));
		}

	}

	@Nested
	@DisplayName("exchange()")
	class Exchange {

		@Test
		@Story("Happy path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() sends the exact authorization_code form fields and returns the backend-held tokens")
		void exchange_happyPath_sendsExactFormFields() {
			// given
			final String codeVerifier = "verifier-1234567890-abcdef";
			OAuth2AuthCodeTokenExchangerTests.this.tokenServer.respond(
					"{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");

			// when
			final OAuth2AuthCodeTokenExchanger.TokenHandle handle = OAuth2AuthCodeTokenExchangerTests.this.exchanger
				.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier);

			// then
			assertThat(handle.accessToken()).isEqualTo("at-1");
			assertThat(handle.refreshToken()).isEqualTo("rt-1");
			assertThat(handle.expiresAt()).isAfter(Instant.now());
			final String body = OAuth2AuthCodeTokenExchangerTests.this.tokenServer.requestBodies().get(0);
			assertThat(body).contains("grant_type=authorization_code");
			assertThat(body).contains("client_id=client-1");
			assertThat(body).contains("code=auth-code-42");
			assertThat(body).contains("redirect_uri=https%3A%2F%2Fapp%2Fcallback");
			assertThat(body).contains("code_verifier=" + codeVerifier);
			assertThat(body).doesNotContain("client_secret");
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.tokenServer.requestCount()).isEqualTo(1);
		}

		@Test
		@Story("PKCE")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() rejects a codeVerifier whose S256 does not match the profile's codeChallenge")
		void exchange_pkceMismatch_throwsIllegalArgument() {
			// when/then
			assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger
				.exchange(pendingProfile(s256("the-right-verifier")), "auth-code-42", "the-wrong-verifier"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("PKCE verification failed");
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.tokenServer.requestCount()).isZero(); // no
																									// token
																									// request
																									// was
			// sent
		}

		@Test
		@Story("Upstream failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() surfaces a non-2xx token response as a status-carrying ProxyUpstreamException")
		void exchange_non2xx_throwsProxyUpstream() {
			// given
			final String codeVerifier = "verifier-1234567890-abcdef";
			OAuth2AuthCodeTokenExchangerTests.this.tokenServer.respondStatus(400, "{\"error\":\"invalid_grant\"}");

			// when/then
			assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger
				.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(400));
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() rejects non-auth-code profiles and missing inputs")
		void exchange_invalidProfile_throws() {
			// given
			final OAuth2Profile cc = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
					OAuth2AuthCodeTokenExchangerTests.this.tokenServer.url(), "client-1", "sec", null, null, null, null,
					null);

			// when/then
			assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger.exchange(cc, "code", "verifier"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger.exchange(pendingProfile("ch"),
					" ", "verifier"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(
					() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger.exchange(pendingProfile("ch"), "code", " "))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger.exchange(pendingProfile(" "),
					"code", "verifier"))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@Story("Upstream failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() treats an informational 1xx token response as a failed exchange")
		void exchange_1xxStatus_throwsProxyUpstream() throws Exception {
			// given — a stubbed client returning an informational status
			final java.net.http.HttpClient client = mock(java.net.http.HttpClient.class);
			final HttpResponse<String> response = mock(HttpResponse.class);
			given(response.statusCode()).willReturn(199);
			given(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).willReturn(response);
			final OAuth2AuthCodeTokenExchanger stubbed = new OAuth2AuthCodeTokenExchanger(client, null);
			final String codeVerifier = "verifier-1234567890-abcdef";

			// when/then
			assertThatThrownBy(() -> stubbed.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(199));
		}

		@Test
		@Story("Upstream failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() fails closed on a 2xx response without access_token")
		void exchange_missingAccessToken_throwsProxyUpstream() {
			// given
			final String codeVerifier = "verifier-1234567890-abcdef";
			OAuth2AuthCodeTokenExchangerTests.this.tokenServer.respond("{\"expires_in\":3600}");

			// when/then
			assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger
				.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
		}

		@Test
		@Story("Upstream failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() fails closed on a blank access_token")
		void exchange_blankAccessToken_throwsProxyUpstream() {
			// given
			final String codeVerifier = "verifier-1234567890-abcdef";
			OAuth2AuthCodeTokenExchangerTests.this.tokenServer.respond("{\"access_token\":\"\",\"expires_in\":3600}");

			// when/then
			assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger
				.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
		}

		@Test
		@Story("Happy path")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() defaults the token lifetime to 5 minutes when expires_in is omitted")
		void exchange_withoutExpiresIn_defaultsTokenTtl() {
			// given
			final String codeVerifier = "verifier-1234567890-abcdef";
			OAuth2AuthCodeTokenExchangerTests.this.tokenServer.respond("{\"access_token\":\"at-1\"}");

			// when
			final OAuth2AuthCodeTokenExchanger.TokenHandle handle = OAuth2AuthCodeTokenExchangerTests.this.exchanger
				.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier);

			// then
			assertThat(handle.expiresAt()).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(310));
		}

		@Test
		@Story("Happy path")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() defaults the token lifetime when expires_in is zero or negative")
		void exchange_nonPositiveExpiresIn_defaultsTokenTtl() {
			// given
			final String codeVerifier = "verifier-1234567890-abcdef";
			OAuth2AuthCodeTokenExchangerTests.this.tokenServer.respond("{\"access_token\":\"at-1\",\"expires_in\":0}");

			// when
			final OAuth2AuthCodeTokenExchanger.TokenHandle handle = OAuth2AuthCodeTokenExchangerTests.this.exchanger
				.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier);

			// then
			assertThat(handle.expiresAt()).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(310));
		}

		@Test
		@Story("Upstream failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() wraps a malformed JSON response as ProxyUpstreamException 502")
		void exchange_malformedJson_throwsProxyUpstream() {
			// given
			final String codeVerifier = "verifier-1234567890-abcdef";
			OAuth2AuthCodeTokenExchangerTests.this.tokenServer.respond("this is not json");

			// when/then
			assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger
				.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
		}

		@Test
		@Story("Upstream failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() wraps a connection failure as ProxyUpstreamException 502")
		void exchange_connectionRefused_throwsProxyUpstream() {
			// given — the token endpoint is already down
			OAuth2AuthCodeTokenExchangerTests.this.tokenServer.stop();
			final String codeVerifier = "verifier-1234567890-abcdef";

			// when/then
			assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger
				.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
		}

		@Test
		@Story("Upstream failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("an interrupted thread surfaces as ProxyUpstreamException and the interrupt flag is restored")
		void exchange_interruptedThread_throwsProxyUpstream() {
			// given
			final String codeVerifier = "verifier-1234567890-abcdef";
			OAuth2AuthCodeTokenExchangerTests.this.tokenServer
				.respond("{\"access_token\":\"at-1\",\"expires_in\":3600}");

			// when — simulate an interrupted client thread
			Thread.currentThread().interrupt();
			try {
				assertThatThrownBy(() -> OAuth2AuthCodeTokenExchangerTests.this.exchanger
					.exchange(pendingProfile(s256(codeVerifier)), "auth-code-42", codeVerifier))
					.isInstanceOf(ProxyUpstreamException.class)
					.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
			}
			finally {
				// the exchanger restored the interrupt flag for the caller
				assertThat(Thread.interrupted()).isTrue();
			}
		}

	}

	@Nested
	@DisplayName("storeTokens() / accessToken() / evict()")
	class TokenStore {

		@Test
		@Story("Backend token store")
		@Severity(SeverityLevel.CRITICAL)
		@Description("storeTokens()/accessToken() hold the exchanged token backend-side; evict() drops it")
		void storeAndAccess_andEvict() {
			// given
			final OAuth2AuthCodeTokenExchanger.TokenHandle handle = new OAuth2AuthCodeTokenExchanger.TokenHandle("at-1",
					"rt-1", Instant.now().plusSeconds(60));

			// when
			OAuth2AuthCodeTokenExchangerTests.this.exchanger.storeTokens("pid-1", handle);
			final Optional<String> present = OAuth2AuthCodeTokenExchangerTests.this.exchanger.accessToken("pid-1");
			OAuth2AuthCodeTokenExchangerTests.this.exchanger.evict("pid-1");

			// then
			assertThat(present).contains("at-1");
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.accessToken("pid-1")).isEmpty();
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.tokenCount()).isZero();
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.accessToken("unknown")).isEmpty();
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.accessToken(null)).isEmpty();
			OAuth2AuthCodeTokenExchangerTests.this.exchanger.evict(null); // null-safe
		}

		@Test
		@Story("Backend token store")
		@Severity(SeverityLevel.NORMAL)
		@Description("evict() also drops a minted state")
		void evict_dropsState() {
			// given
			OAuth2AuthCodeTokenExchangerTests.this.exchanger.mintState("owner-a", "pid-1");

			// when
			OAuth2AuthCodeTokenExchangerTests.this.exchanger.evict("pid-1");

			// then
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.stateCount()).isZero();
		}

		@Test
		@Story("Backend token store")
		@Severity(SeverityLevel.NORMAL)
		@Description("the no-arg constructor builds a fully working exchanger with real defaults")
		void defaultConstructor_works() {
			// given
			final OAuth2AuthCodeTokenExchanger defaultExchanger = new OAuth2AuthCodeTokenExchanger();

			// when
			final String state = defaultExchanger.mintState("owner-a", "pid-1");
			defaultExchanger.storeTokens("pid-1",
					new OAuth2AuthCodeTokenExchanger.TokenHandle("at-1", "rt-1", Instant.now().plusSeconds(60)));

			// then
			assertThat(state).isNotBlank();
			assertThat(defaultExchanger.accessToken("pid-1")).contains("at-1");
		}

		@Test
		@Story("Backend token store")
		@Severity(SeverityLevel.NORMAL)
		@Description("the two-arg constructor falls back to real defaults when both arguments are null")
		void constructor_nullArguments_fallBackToDefaults() {
			// given
			final OAuth2AuthCodeTokenExchanger defaultExchanger = new OAuth2AuthCodeTokenExchanger(null, null);

			// when
			final String state = defaultExchanger.mintState("owner-a", "pid-1");

			// then
			assertThat(state).isNotBlank();
		}

		@Test
		@Story("Backend token store")
		@Severity(SeverityLevel.NORMAL)
		@Description("accessToken() returns empty when the stored handle carries no access token")
		void accessToken_nullAccessToken_returnsEmpty() {
			// given
			OAuth2AuthCodeTokenExchangerTests.this.exchanger.storeTokens("pid-1",
					new OAuth2AuthCodeTokenExchanger.TokenHandle(null, "rt-1", Instant.now().plusSeconds(60)));

			// when/then
			assertThat(OAuth2AuthCodeTokenExchangerTests.this.exchanger.accessToken("pid-1")).isEmpty();
		}

	}

	/**
	 * In-process token endpoint stub capturing request bodies.
	 */
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

		java.net.http.HttpClient httpClient() {
			return java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		}

		void respond(final String body) {
			this.status = 200;
			this.body = body;
		}

		void respondStatus(final int status, final String body) {
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
			synchronized (this.requestBodies) {
				this.requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
