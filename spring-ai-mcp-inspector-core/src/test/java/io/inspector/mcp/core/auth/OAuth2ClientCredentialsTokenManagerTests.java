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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Unit tests for {@link OAuth2ClientCredentialsTokenManager} (D9/D9A) against an
 * in-process token endpoint.
 */
@Epic("MCP Inspector Core")
@Feature("OAuth2ClientCredentialsTokenManager")
class OAuth2ClientCredentialsTokenManagerTests {

	private StubTokenServer tokenServer;

	private OAuth2ClientCredentialsTokenManager manager;

	@BeforeEach
	void setUp() throws IOException {
		this.tokenServer = new StubTokenServer();
		OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.start();
		this.manager = new OAuth2ClientCredentialsTokenManager(
				OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.httpClient(), null);
	}

	@AfterEach
	void tearDown() {
		OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.stop();
	}

	private OAuth2Profile ccProfile(final String secret) {
		return ccProfile(secret, "mcp.read mcp.write");
	}

	private OAuth2Profile ccProfile(final String secret, final String scopes) {
		return new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.url(), "client-1", secret, scopes, null, null,
				null, null);
	}

	@Nested
	@DisplayName("acquire()")
	class Acquire {

		@Test
		@Story("Initial exchange")
		@Severity(SeverityLevel.CRITICAL)
		@Description("acquire() runs a client_credentials exchange and stores credentials + token")
		void acquire_successfulExchange_storesCredentialsAndToken() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");

			// when
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = OAuth2ClientCredentialsTokenManagerTests.this.manager
				.acquire("pid-1", ccProfile("secret-1"));

			// then
			assertThat(handle.accessToken()).isEqualTo("tok-1");
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.credentialCount()).isEqualTo(1);
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.cacheSize()).isEqualTo(1);
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestCount()).isEqualTo(1);
			// the initial exchange is a client_credentials grant — never a refresh_token
			// grant
			final String body = OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestBodies().get(0);
			assertThat(body).contains("grant_type=client_credentials");
			assertThat(body).contains("client_id=client-1");
			assertThat(body).contains("client_secret=secret-1");
			assertThat(body).contains("scope=mcp.read+mcp.write");
			assertThat(body).doesNotContain("refresh_token");
		}

		@Test
		@Story("Failure")
		@Severity(SeverityLevel.CRITICAL)
		@Description("acquire() on a non-2xx token response throws ProxyUpstreamException and leaves NO credentials behind")
		void acquire_non2xxResponse_throwsAndLeavesNothing() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(400, "{\"error\":\"invalid_client\"}");

			// when/then
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1")))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(400));
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.credentialCount()).isZero();
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.cacheSize()).isZero();
		}

		@Test
		@Story("Failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("acquire() on a malformed 2xx token response (no access_token) fails closed with 502")
		void acquire_missingAccessToken_throws() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200, "{\"expires_in\":3600}");

			// when/then
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1")))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.credentialCount()).isZero();
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("acquire() rejects non-CC profiles and missing client secrets")
		void acquire_invalidProfile_throws() {
			// given
			final OAuth2Profile authCode = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE,
					OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.url(), "client-1", null, null,
					"https://t/auth", "https://app/cb", "ch", "S256");

			// when/then
			assertThatThrownBy(() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", authCode))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile(" ")))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@Story("Initial exchange")
		@Severity(SeverityLevel.NORMAL)
		@Description("the no-arg constructor builds a fully working manager with real defaults")
		void defaultConstructor_works() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");
			final OAuth2ClientCredentialsTokenManager defaultManager = new OAuth2ClientCredentialsTokenManager();

			// when
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = defaultManager.acquire("pid-1",
					ccProfile("secret-1"));

			// then
			assertThat(handle.accessToken()).isEqualTo("tok-1");
			assertThat(defaultManager.credentialCount()).isEqualTo(1);
		}

		@Test
		@Story("Initial exchange")
		@Severity(SeverityLevel.NORMAL)
		@Description("the two-arg constructor falls back to real defaults when both arguments are null")
		void constructor_nullArguments_fallBackToDefaults() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");
			final OAuth2ClientCredentialsTokenManager defaultManager = new OAuth2ClientCredentialsTokenManager(null,
					null);

			// when
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = defaultManager.acquire("pid-1",
					ccProfile("secret-1"));

			// then
			assertThat(handle.accessToken()).isEqualTo("tok-1");
		}

		@Test
		@Story("Initial exchange")
		@Severity(SeverityLevel.NORMAL)
		@Description("acquire() omits the scope form field for null and blank scopes")
		void acquire_blankScopes_omitsScopeField() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");

			// when
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1", null));
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-2", ccProfile("secret-2", " "));

			// then
			final List<String> bodies = OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestBodies();
			assertThat(bodies.get(0)).doesNotContain("scope=");
			assertThat(bodies.get(1)).doesNotContain("scope=");
		}

		@Test
		@Story("Failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("acquire() treats an informational 1xx token response as a failed exchange")
		void acquire_1xxResponse_throws() throws Exception {
			// given — a stubbed client returning an informational status
			final java.net.http.HttpClient client = mock(java.net.http.HttpClient.class);
			final HttpResponse<String> response = mock(HttpResponse.class);
			given(response.statusCode()).willReturn(199);
			given(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).willReturn(response);
			final OAuth2ClientCredentialsTokenManager stubbed = new OAuth2ClientCredentialsTokenManager(client, null);

			// when/then
			assertThatThrownBy(() -> stubbed.acquire("pid-1", ccProfile("secret-1")))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(199));
		}

		@Test
		@Story("Failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("acquire() fails closed when the token response carries a blank access_token")
		void acquire_blankAccessToken_throws() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"\",\"expires_in\":3600}");

			// when/then
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1")))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
		}

		@Test
		@Story("Initial exchange")
		@Severity(SeverityLevel.NORMAL)
		@Description("acquire() defaults the token lifetime to 5 minutes when the response omits expires_in")
		void acquire_withoutExpiresIn_defaultsTokenTtl() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200, "{\"access_token\":\"tok-1\"}");

			// when
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = OAuth2ClientCredentialsTokenManagerTests.this.manager
				.acquire("pid-1", ccProfile("secret-1"));

			// then
			assertThat(handle.expiresAt()).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(310));
		}

		@Test
		@Story("Initial exchange")
		@Severity(SeverityLevel.NORMAL)
		@Description("acquire() defaults the token lifetime when expires_in is zero or negative")
		void acquire_nonPositiveExpiresIn_defaultsTokenTtl() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":0}");

			// when
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = OAuth2ClientCredentialsTokenManagerTests.this.manager
				.acquire("pid-1", ccProfile("secret-1"));

			// then
			assertThat(handle.expiresAt()).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(310));
		}

		@Test
		@Story("Failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("acquire() wraps a malformed JSON response as ProxyUpstreamException 502")
		void acquire_malformedJson_throws() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200, "this is not json");

			// when/then
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1")))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
		}

		@Test
		@Story("Failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("acquire() wraps a connection failure as ProxyUpstreamException 502")
		void acquire_connectionRefused_throws() {
			// given — the token endpoint is already down
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.stop();

			// when/then
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1")))
				.isInstanceOf(ProxyUpstreamException.class)
				.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
		}

		@Test
		@Story("Failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("an interrupted thread surfaces as ProxyUpstreamException and the interrupt flag is restored")
		void acquire_interruptedThread_throwsAndRestoresFlag() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");

			// when — simulate a client thread being interrupted mid-request
			Thread.currentThread().interrupt();
			try {
				assertThatThrownBy(() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1",
						ccProfile("secret-1")))
					.isInstanceOf(ProxyUpstreamException.class)
					.satisfies((ex) -> assertThat(((ProxyUpstreamException) ex).getStatus()).isEqualTo(502));
			}
			finally {
				// the manager restored the interrupt flag for the caller
				assertThat(Thread.interrupted()).isTrue();
			}
		}

	}

	@Nested
	@DisplayName("getAccessToken()")
	class GetAccessToken {

		@Test
		@Story("Caching")
		@Severity(SeverityLevel.CRITICAL)
		@Description("getAccessToken() returns the cached token without a new exchange while it is valid")
		void getAccessToken_validCache_returnsCachedToken() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1"));

			// when
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = OAuth2ClientCredentialsTokenManagerTests.this.manager
				.getAccessToken("pid-1", false);

			// then
			assertThat(handle.accessToken()).isEqualTo("tok-1");
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestCount()).isEqualTo(1);
		}

		@Test
		@Story("Refresh on expiry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("an expiring token (within the 30s skew) triggers a FRESH client_credentials re-exchange — never a refresh_token grant")
		void getAccessToken_expiringToken_reExchangesClientCredentials() {
			// given — 1s lifetime is already inside the 30s expiry skew
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":1}");
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1"));

			// when
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = OAuth2ClientCredentialsTokenManagerTests.this.manager
				.getAccessToken("pid-1", false);

			// then
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestCount()).isEqualTo(2);
			final String body = OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestBodies().get(1);
			assertThat(body).contains("grant_type=client_credentials");
			assertThat(body).contains("client_secret=secret-1");
			assertThat(body).doesNotContain("refresh_token");
			assertThat(handle.accessToken()).isEqualTo("tok-1");
		}

		@Test
		@Story("Force refresh")
		@Severity(SeverityLevel.CRITICAL)
		@Description("forceRefresh=true always re-exchanges from the STORED credentials")
		void getAccessToken_forceRefresh_reExchangesFromStoredCredentials() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1"));
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-2\",\"expires_in\":3600}");

			// when
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = OAuth2ClientCredentialsTokenManagerTests.this.manager
				.getAccessToken("pid-1", true);

			// then
			assertThat(handle.accessToken()).isEqualTo("tok-2");
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestCount()).isEqualTo(2);
			final String body = OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestBodies().get(1);
			assertThat(body).contains("grant_type=client_credentials");
			assertThat(body).doesNotContain("refresh_token");
		}

		@Test
		@Story("Single-flight")
		@Severity(SeverityLevel.CRITICAL)
		@Description("concurrent getAccessToken() calls on an expired cache produce exactly ONE re-exchange (single-flight)")
		void getAccessToken_concurrentCalls_singleExchange() throws Exception {
			// given — acquire returns an already-expiring token; the refresh response is
			// long-lived
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respondSequence(
					"{\"access_token\":\"tok-1\",\"expires_in\":1}",
					"{\"access_token\":\"tok-refreshed\",\"expires_in\":3600}");
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1"));
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.blockNextRequest();

			final ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				// when — two callers race for the same expired token
				final CountDownLatch start = new CountDownLatch(1);
				final Future<OAuth2ClientCredentialsTokenManager.TokenHandle> first = pool.submit(() -> {
					start.await();
					return OAuth2ClientCredentialsTokenManagerTests.this.manager.getAccessToken("pid-1", false);
				});
				final Future<OAuth2ClientCredentialsTokenManager.TokenHandle> second = pool.submit(() -> {
					start.await();
					return OAuth2ClientCredentialsTokenManagerTests.this.manager.getAccessToken("pid-1", false);
				});
				// start the race first, then wait for the first exchange to be in-flight
				start.countDown();
				OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.awaitRequestEntered();
				OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.releaseBlockedRequest();

				// then — exactly ONE refresh exchange hit the token endpoint; both
				// callers got the same token
				assertThat(OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestCount()).isEqualTo(2);
				assertThat(first.get(10, TimeUnit.SECONDS).accessToken()).isEqualTo("tok-refreshed");
				assertThat(second.get(10, TimeUnit.SECONDS).accessToken()).isEqualTo("tok-refreshed");
			}
			finally {
				pool.shutdownNow();
			}
		}

		@Test
		@Story("Fail closed")
		@Severity(SeverityLevel.CRITICAL)
		@Description("after eviction there are no stored credentials — getAccessToken() fails closed with IllegalStateException")
		void getAccessToken_afterEvict_throwsIllegalState() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1"));
			OAuth2ClientCredentialsTokenManagerTests.this.manager.evict("pid-1");

			// when/then — no stale-secret re-exchange, no silent fallback
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.getAccessToken("pid-1", true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no stored client credentials");
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestCount()).isEqualTo(1);
		}

		@Test
		@Story("Fail closed")
		@Severity(SeverityLevel.NORMAL)
		@Description("getAccessToken() for an unknown profile fails closed")
		void getAccessToken_unknownProfile_throwsIllegalState() {
			// when/then
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.getAccessToken("never-seen", false))
				.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@Story("Refresh on expiry")
		@Severity(SeverityLevel.NORMAL)
		@Description("an update evicts the cached token — the next non-forced lookup re-exchanges from stored credentials")
		void getAccessToken_afterUpdateWithEmptyCache_reExchanges() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-old"));
			OAuth2ClientCredentialsTokenManagerTests.this.manager.update("pid-1", ccProfile("secret-new"));

			// when — the cache was evicted by update(); a non-forced lookup must still
			// re-exchange
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = OAuth2ClientCredentialsTokenManagerTests.this.manager
				.getAccessToken("pid-1", false);

			// then
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestCount()).isEqualTo(2);
			final String body = OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestBodies().get(1);
			assertThat(body).contains("client_secret=secret-new");
			assertThat(handle.accessToken()).isEqualTo("tok-1");
		}

		@Test
		@Story("Refresh on expiry")
		@Severity(SeverityLevel.NORMAL)
		@Description("a cached entry with a null expiry is treated as expired and re-exchanged")
		void getAccessToken_nullExpiryCachedEntry_reExchanges() throws Exception {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1"));
			// plant a cache entry whose expiry is unknown (null) — must be treated as
			// expired
			final java.lang.reflect.Field cacheField = OAuth2ClientCredentialsTokenManager.class
				.getDeclaredField("tokenCache");
			cacheField.setAccessible(true);
			final ConcurrentMap<String, Object> cache = (ConcurrentMap<String, Object>) cacheField
				.get(OAuth2ClientCredentialsTokenManagerTests.this.manager);
			final Class<?> tokenEntry = Class
				.forName("io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager$TokenEntry");
			final java.lang.reflect.Constructor<?> ctor = tokenEntry.getDeclaredConstructor(String.class,
					Instant.class);
			ctor.setAccessible(true);
			cache.put("pid-1", ctor.newInstance("stale-token", null));

			// when
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = OAuth2ClientCredentialsTokenManagerTests.this.manager
				.getAccessToken("pid-1", false);

			// then
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestCount()).isEqualTo(2);
			assertThat(handle.accessToken()).isEqualTo("tok-1");
		}

	}

	@Nested
	@DisplayName("evict() / update()")
	class EvictAndUpdate {

		@Test
		@Story("Eviction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("evict() removes BOTH the cached token and the stored credentials")
		void evict_removesTokenAndCredentials() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-1"));
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.cacheSize()).isEqualTo(1);
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.credentialCount()).isEqualTo(1);

			// when
			OAuth2ClientCredentialsTokenManagerTests.this.manager.evict("pid-1");

			// then
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.cacheSize()).isZero();
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.credentialCount()).isZero();
			OAuth2ClientCredentialsTokenManagerTests.this.manager.evict(null); // null-safe
		}

		@Test
		@Story("Update")
		@Severity(SeverityLevel.CRITICAL)
		@Description("update() replaces the stored credentials and evicts the cached token; the next refresh uses the NEW secret")
		void update_replacesCredentialsAndEvictsToken() {
			// given
			OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.respond(200,
					"{\"access_token\":\"tok-1\",\"expires_in\":3600}");
			OAuth2ClientCredentialsTokenManagerTests.this.manager.acquire("pid-1", ccProfile("secret-old"));

			// when
			OAuth2ClientCredentialsTokenManagerTests.this.manager.update("pid-1", ccProfile("secret-new"));
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = OAuth2ClientCredentialsTokenManagerTests.this.manager
				.getAccessToken("pid-1", true);

			// then
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.cacheSize()).isEqualTo(1);
			assertThat(OAuth2ClientCredentialsTokenManagerTests.this.manager.credentialCount()).isEqualTo(1);
			assertThat(handle.accessToken()).isEqualTo("tok-1");
			final String body = OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.requestBodies().get(1);
			assertThat(body).contains("client_secret=secret-new");
			assertThat(body).doesNotContain("secret-old");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("update() rejects non-CLIENT_CREDENTIALS profiles and blank client secrets")
		void update_invalidProfile_throws() {
			// given
			final OAuth2Profile authCode = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE,
					OAuth2ClientCredentialsTokenManagerTests.this.tokenServer.url(), "client-1", null, null,
					"https://t/auth", "https://app/cb", "ch", "S256");

			// when/then
			assertThatThrownBy(() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.update("pid-1", authCode))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerTests.this.manager.update("pid-1", ccProfile(" ")))
				.isInstanceOf(IllegalArgumentException.class);
		}

	}

	/**
	 * In-process token endpoint stub (JDK {@link HttpServer}) capturing request bodies.
	 */
	static final class StubTokenServer {

		private final HttpServer server;

		private final List<String> requestBodies = new ArrayList<>();

		private final AtomicInteger requestCount = new AtomicInteger();

		private volatile int status = 200;

		private volatile String body = "{}";

		private final List<String> responseQueue = new ArrayList<>();

		private final AtomicInteger responseIndex = new AtomicInteger();

		private volatile CountDownLatch blockLatch;

		private volatile CountDownLatch entered = new CountDownLatch(1);

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

		void respond(final int status, final String body) {
			this.status = status;
			this.body = body;
			synchronized (this.responseQueue) {
				this.responseQueue.clear();
			}
			this.responseIndex.set(0);
		}

		/** Enqueues response bodies served in order; the last one repeats. */
		void respondSequence(final String... bodies) {
			synchronized (this.responseQueue) {
				this.responseQueue.clear();
				for (final String responseBody : bodies) {
					this.responseQueue.add(responseBody);
				}
			}
			this.responseIndex.set(0);
		}

		void blockNextRequest() {
			this.blockLatch = new CountDownLatch(1);
			this.entered = new CountDownLatch(1);
		}

		void releaseBlockedRequest() {
			final CountDownLatch latch = this.blockLatch;
			if (latch != null) {
				latch.countDown();
			}
		}

		void awaitRequestEntered() throws InterruptedException {
			assertThat(this.entered.await(10, TimeUnit.SECONDS)).isTrue();
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
			this.entered.countDown();
			final CountDownLatch latch = this.blockLatch;
			if (latch != null) {
				try {
					latch.await(10, TimeUnit.SECONDS);
				}
				catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			final String responseBody;
			synchronized (this.responseQueue) {
				final int index = Math.min(this.responseIndex.getAndIncrement(), this.responseQueue.size() - 1);
				responseBody = (index >= 0) ? this.responseQueue.get(index) : this.body;
			}
			final byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(this.status, response.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(response);
			}
		}

	}

}
