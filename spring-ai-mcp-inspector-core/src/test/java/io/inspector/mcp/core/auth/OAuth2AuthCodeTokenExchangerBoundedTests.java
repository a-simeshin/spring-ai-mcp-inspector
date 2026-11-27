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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

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
import org.mockito.ArgumentCaptor;

import io.inspector.mcp.core.proxy.ProxySessionRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Bounded behavior tests for {@link OAuth2AuthCodeTokenExchanger}: expired state sweep,
 * orphaned token sweep, and registry wiring (D3).
 */
@Epic("MCP Inspector Core")
@Feature("OAuth2AuthCodeTokenExchanger bounded behavior")
class OAuth2AuthCodeTokenExchangerBoundedTests {

	private StubTokenServer tokenServer;

	private OAuth2AuthCodeTokenExchanger exchanger;

	@BeforeEach
	void setUp() throws IOException {
		this.tokenServer = new StubTokenServer();
		OAuth2AuthCodeTokenExchangerBoundedTests.this.tokenServer.start();
		this.exchanger = new OAuth2AuthCodeTokenExchanger(
				OAuth2AuthCodeTokenExchangerBoundedTests.this.tokenServer.httpClient(), null);
	}

	@AfterEach
	void tearDown() {
		OAuth2AuthCodeTokenExchangerBoundedTests.this.tokenServer.stop();
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
				OAuth2AuthCodeTokenExchangerBoundedTests.this.tokenServer.url(), "client-1", null, "mcp.read",
				"https://idp/auth", "https://app/callback", codeChallenge, "S256");
	}

	@Nested
	@DisplayName("expired state sweep")
	class ExpiredStateSweep {

		@Test
		@Story("expired state removed")
		@Severity(SeverityLevel.CRITICAL)
		@Description("removeExpiredStates removes states whose TTL has passed")
		void removeExpiredStates_expiredState_removed() {
			// given: a state minted 11 minutes ago (TTL is 10 minutes)
			final String state = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.mintState("owner-1", "pid-1");
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.stateCount()).isEqualTo(1);

			// when: sweep with a timestamp 11 minutes in the future
			final Instant afterTtl = Instant.now().plus(Duration.ofMinutes(11));
			final int removed = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.removeExpiredStates(afterTtl);

			// then
			assertThat(removed).isEqualTo(1);
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.stateCount()).isZero();
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.statesExpiredTotal()).isEqualTo(1);
		}

		@Test
		@Story("valid state survives")
		@Severity(SeverityLevel.CRITICAL)
		@Description("removeExpiredStates does not touch a state within its TTL")
		void removeExpiredStates_validState_survives() {
			// given: a state minted 1 minute ago (TTL is 10 minutes)
			final String state = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.mintState("owner-1", "pid-1");
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.stateCount()).isEqualTo(1);

			// when: sweep with a timestamp 5 minutes in the future
			final Instant withinTtl = Instant.now().plus(Duration.ofMinutes(5));
			final int removed = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.removeExpiredStates(withinTtl);

			// then
			assertThat(removed).isZero();
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.stateCount()).isEqualTo(1);
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.statesExpiredTotal()).isZero();
		}

		@Test
		@Story("TTL boundary exact")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a state at exactly its TTL boundary is removed (not after)")
		void removeExpiredStates_exactTtlBoundary_removed() {
			// given: a state minted exactly 10 minutes ago
			final String state = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.mintState("owner-1", "pid-1");

			// when: sweep with a timestamp exactly 10 minutes in the future (boundary)
			final Instant boundary = Instant.now().plus(Duration.ofMinutes(10));
			final int removed = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.removeExpiredStates(boundary);

			// then: at the boundary the state is expired (not after)
			assertThat(removed).isEqualTo(1);
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.stateCount()).isZero();
		}

		@Test
		@Story("TTL boundary 1ms after")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a state 1ms past its TTL is removed")
		void removeExpiredStates_1msAfterBoundary_removed() {
			// given: a state minted 10 minutes ago
			final String state = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.mintState("owner-1", "pid-1");

			// when: sweep with a timestamp 10 minutes + 1ms in the future
			final Instant after = Instant.now().plus(Duration.ofMinutes(10)).plusMillis(1);
			final int removed = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.removeExpiredStates(after);

			// then
			assertThat(removed).isEqualTo(1);
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.stateCount()).isZero();
		}

		@Test
		@Story("null timestamp is no-op")
		@Severity(SeverityLevel.NORMAL)
		@Description("removeExpiredStates with a null timestamp does nothing")
		void removeExpiredStates_nullTimestamp_noOp() {
			// given
			OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.mintState("owner-1", "pid-1");

			// when
			final int removed = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.removeExpiredStates(null);

			// then
			assertThat(removed).isZero();
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.stateCount()).isEqualTo(1);
		}

	}

	@Nested
	@DisplayName("orphaned token sweep")
	class OrphanedTokenSweep {

		@Test
		@Story("orphaned token removed")
		@Severity(SeverityLevel.CRITICAL)
		@Description("removeExpiredStates removes tokens whose TTL has passed")
		void removeExpiredStates_orphanedToken_removed() {
			// given: a stored token with a short expiry (simulating an orphaned token)
			// We store tokens directly to simulate a token without a live profile.
			final OAuth2AuthCodeTokenExchanger.TokenHandle handle = new OAuth2AuthCodeTokenExchanger.TokenHandle(
					"access-tok", "refresh-tok", Instant.now().plus(Duration.ofMinutes(5)));
			OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.storeTokens("pid-1", handle);
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.tokenCount()).isEqualTo(1);

			// when: sweep after the token's expiry
			final Instant afterExpiry = Instant.now().plus(Duration.ofMinutes(6));
			final int removed = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger
				.removeExpiredStates(afterExpiry);

			// then
			assertThat(removed).isEqualTo(1);
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.tokenCount()).isZero();
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.tokensExpiredTotal()).isEqualTo(1);
		}

		@Test
		@Story("live token survives")
		@Severity(SeverityLevel.CRITICAL)
		@Description("removeExpiredStates does not touch a token within its TTL")
		void removeExpiredStates_liveToken_survives() {
			// given: a token with 10-minute expiry
			final OAuth2AuthCodeTokenExchanger.TokenHandle handle = new OAuth2AuthCodeTokenExchanger.TokenHandle(
					"access-tok", "refresh-tok", Instant.now().plus(Duration.ofMinutes(10)));
			OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.storeTokens("pid-1", handle);

			// when: sweep before the token's expiry
			final Instant beforeExpiry = Instant.now().plus(Duration.ofMinutes(5));
			final int removed = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger
				.removeExpiredStates(beforeExpiry);

			// then
			assertThat(removed).isZero();
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.tokenCount()).isEqualTo(1);
		}

		@Test
		@Story("token with null expiry survives")
		@Severity(SeverityLevel.NORMAL)
		@Description("a token with null expiresAt is never removed by the sweep")
		void removeExpiredStates_nullExpiryToken_survives() {
			// given: a token with no expiry
			final OAuth2AuthCodeTokenExchanger.TokenHandle handle = new OAuth2AuthCodeTokenExchanger.TokenHandle(
					"access-tok", null, null);
			OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.storeTokens("pid-1", handle);

			// when: sweep far in the future
			final int removed = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger
				.removeExpiredStates(Instant.now().plus(Duration.ofDays(365)));

			// then
			assertThat(removed).isZero();
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.tokenCount()).isEqualTo(1);
		}

	}

	@Nested
	@DisplayName("registry wiring")
	class RegistryWiring {

		@Test
		@Story("reap calls removeExpiredStates")
		@Severity(SeverityLevel.CRITICAL)
		@Description("ProxySessionRegistry.reap() calls exchanger.removeExpiredStates with the same timestamp as store.removeExpired")
		void reap_wiring_callsRemoveExpiredStatesWithSameTimestamp() {
			// given: a registry wired with a mock store and a mock exchanger
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final OAuth2AuthCodeTokenExchanger exchanger = mock(OAuth2AuthCodeTokenExchanger.class);
			final ProxySessionRegistry registry = new ProxySessionRegistry();
			registry.setAuthProfileStore(store);
			registry.setAuthCodeExchanger(exchanger);

			// when
			registry.reap();

			// then: both were called with the SAME Instant
			final ArgumentCaptor<Instant> storeCaptor = ArgumentCaptor.forClass(Instant.class);
			final ArgumentCaptor<Instant> exchangerCaptor = ArgumentCaptor.forClass(Instant.class);
			verify(store).removeExpired(storeCaptor.capture());
			verify(exchanger).removeExpiredStates(exchangerCaptor.capture());
			assertThat(storeCaptor.getValue()).isEqualTo(exchangerCaptor.getValue());
		}

		@Test
		@Story("reap without exchanger does not fail")
		@Severity(SeverityLevel.CRITICAL)
		@Description("ProxySessionRegistry.reap() without an exchanger still works")
		void reap_wiring_noExchanger_noFail() {
			// given: a registry wired with only a mock store
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final ProxySessionRegistry registry = new ProxySessionRegistry();
			registry.setAuthProfileStore(store);

			// when
			registry.reap();

			// then
			verify(store).removeExpired(any(Instant.class));
		}

		@Test
		@Story("reap without store still calls exchanger")
		@Severity(SeverityLevel.CRITICAL)
		@Description("ProxySessionRegistry.reap() without a store still calls the exchanger")
		void reap_wiring_noStore_callsExchanger() {
			// given: a registry wired with only a mock exchanger
			final OAuth2AuthCodeTokenExchanger exchanger = mock(OAuth2AuthCodeTokenExchanger.class);
			final ProxySessionRegistry registry = new ProxySessionRegistry();
			registry.setAuthCodeExchanger(exchanger);

			// when
			registry.reap();

			// then
			verify(exchanger).removeExpiredStates(any(Instant.class));
		}

	}

	@Nested
	@DisplayName("counters")
	class Counters {

		@Test
		@Story("counter increments")
		@Severity(SeverityLevel.CRITICAL)
		@Description("statesExpiredTotal and tokensExpiredTotal accumulate across multiple sweeps")
		void counters_accumulateAcrossSweeps() {
			// given: mint two states (TTL 10 min) and one token (TTL 15 min)
			OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.mintState("owner-1", "pid-1");
			OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.mintState("owner-1", "pid-2");
			final OAuth2AuthCodeTokenExchanger.TokenHandle handle = new OAuth2AuthCodeTokenExchanger.TokenHandle("tok",
					null, Instant.now().plus(Duration.ofMinutes(15)));
			OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.storeTokens("pid-3", handle);

			// when: sweep after states expire (11 min) but before token expires
			final Instant afterStates = Instant.now().plus(Duration.ofMinutes(11));
			final int removed1 = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger
				.removeExpiredStates(afterStates);

			// then: 2 states removed, token still alive
			assertThat(removed1).isEqualTo(2);
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.statesExpiredTotal()).isEqualTo(2);
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.tokensExpiredTotal()).isZero();

			// when: sweep after token expires (16 min)
			final Instant afterToken = Instant.now().plus(Duration.ofMinutes(16));
			final int removed2 = OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger
				.removeExpiredStates(afterToken);

			// then: 1 token removed
			assertThat(removed2).isEqualTo(1);
			assertThat(OAuth2AuthCodeTokenExchangerBoundedTests.this.exchanger.tokensExpiredTotal()).isEqualTo(1);
		}

	}

	/**
	 * In-process stub token endpoint.
	 */
	private static final class StubTokenServer {

		private HttpServer server;

		private int port;

		private int status = 200;

		private String body = "{\"access_token\":\"tok-1\",\"expires_in\":300}";

		void start() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress(0), 0);
			this.port = this.server.getAddress().getPort();
			this.server.createContext("/token", this::handle);
			this.server.start();
		}

		void stop() {
			if (this.server != null) {
				this.server.stop(0);
			}
		}

		String url() {
			return "http://127.0.0.1:" + this.port + "/token";
		}

		java.net.http.HttpClient httpClient() {
			return java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		}

		void respond(final String body) {
			this.status = 200;
			this.body = body;
		}

		void respondStatus(final int status, final String body) {
			this.status = status;
			this.body = body;
		}

		private void handle(final HttpExchange exchange) throws IOException {
			final byte[] response = this.body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(this.status, response.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(response);
			}
		}

	}

}
