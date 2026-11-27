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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bounded behavior tests for {@link OAuth2ClientCredentialsTokenManager}: lock removal on
 * evict, orphan lock sweep, no lock leak on error paths, and lock map boundedness (D2).
 */
@Epic("MCP Inspector Core")
@Feature("OAuth2ClientCredentialsTokenManager bounded behavior")
class OAuth2ClientCredentialsTokenManagerBoundedTests {

	private StubTokenServer tokenServer;

	private OAuth2ClientCredentialsTokenManager manager;

	@BeforeEach
	void setUp() throws IOException {
		this.tokenServer = new StubTokenServer();
		OAuth2ClientCredentialsTokenManagerBoundedTests.this.tokenServer.start();
		this.manager = new OAuth2ClientCredentialsTokenManager(
				OAuth2ClientCredentialsTokenManagerBoundedTests.this.tokenServer.httpClient(), null);
	}

	@AfterEach
	void tearDown() {
		OAuth2ClientCredentialsTokenManagerBoundedTests.this.tokenServer.stop();
	}

	private OAuth2Profile ccProfile(final String secret) {
		return ccProfile(secret, "mcp.read mcp.write");
	}

	private OAuth2Profile ccProfile(final String secret, final String scopes) {
		return new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				OAuth2ClientCredentialsTokenManagerBoundedTests.this.tokenServer.url(), "client-1", secret, scopes,
				null, null, null, null);
	}

	@Nested
	@DisplayName("lock removal on evict")
	class LockRemovalOnEvict {

		@Test
		@Story("evict removes lock")
		@Severity(SeverityLevel.CRITICAL)
		@Description("evict() removes the profile lock alongside credentials and tokens")
		void evict_removesProfileLock() {
			// given: acquire + getAccessToken creates the lock
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.acquire("pid-1", ccProfile("secret-1"));
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.getAccessToken("pid-1", false);
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount()).isEqualTo(1);

			// when
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.evict("pid-1");

			// then
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount()).isZero();
		}

		@Test
		@Story("evict is idempotent")
		@Severity(SeverityLevel.CRITICAL)
		@Description("evict() on an already-evicted profile is a no-op")
		void evict_alreadyEvicted_noOp() {
			// given
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.acquire("pid-1", ccProfile("secret-1"));
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.evict("pid-1");

			// when
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.evict("pid-1");

			// then
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount()).isZero();
		}

	}

	@Nested
	@DisplayName("no lock leak on error paths")
	class NoLockLeak {

		@Test
		@Story("unknown profile leaves no lock")
		@Severity(SeverityLevel.CRITICAL)
		@Description("getAccessToken on an unknown profile throws and removes the lock")
		void getAccessToken_unknownProfile_noLockLeak() {
			// when/then
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.getAccessToken("unknown", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no stored client credentials");
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount()).isZero();
		}

		@Test
		@Story("repeated unknown ids leave no lock growth")
		@Severity(SeverityLevel.CRITICAL)
		@Description("repeated getAccessToken on unknown ids does not grow the lock map")
		void getAccessToken_repeatedUnknownIds_noLockGrowth() {
			// when
			for (int i = 0; i < 100; i++) {
				final String unknownId = "unknown-" + i;
				assertThatThrownBy(() -> OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager
					.getAccessToken(unknownId, false)).isInstanceOf(IllegalStateException.class);
			}

			// then
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount()).isZero();
		}

		@Test
		@Story("evicted profile leaves no lock")
		@Severity(SeverityLevel.CRITICAL)
		@Description("getAccessToken on an evicted profile throws and removes the lock")
		void getAccessToken_evictedProfile_noLockLeak() {
			// given
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.acquire("pid-1", ccProfile("secret-1"));
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.evict("pid-1");

			// when/then
			assertThatThrownBy(
					() -> OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.getAccessToken("pid-1", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no stored client credentials");
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount()).isZero();
		}

	}

	@Nested
	@DisplayName("orphan lock sweep")
	class OrphanLockSweep {

		@Test
		@Story("orphan sweep at threshold")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the orphan sweep removes only locks whose profile has no stored credentials")
		void orphanSweep_at10k_preservesLiveLock() throws Exception {
			// given: a live profile with credentials (lock will be created via
			// getAccessToken)
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.acquire("pid-live", ccProfile("secret-live"));
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.getAccessToken("pid-live", false);
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount()).isEqualTo(1);

			// given: seed 9,999 orphan locks directly via reflection (no credentials)
			seedOrphanLocks(9_999);

			// then: the lock map is at 10,000 (1 live + 9,999 orphans)
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount())
				.isEqualTo(10_000);

			// when: trigger the sweep via getAccessToken on a DIFFERENT orphan id
			// (the sweep triggers because profileLocks.size() >= 10_000, then the
			// unknown id throws as expected)
			assertThatThrownBy(() -> OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager
				.getAccessToken("orphan-trigger", false)).isInstanceOf(IllegalStateException.class);

			// then: the live lock is preserved, orphan locks are removed
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount()).isEqualTo(1);
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLocksOrphanedTotal())
				.isEqualTo(9_999);

			// and: verify the live lock is still present
			@SuppressWarnings("unchecked")
			final ConcurrentMap<String, Object> locks = (ConcurrentMap<String, Object>) getField("profileLocks");
			assertThat(locks).containsKey("pid-live");
		}

		@SuppressWarnings("unchecked")
		private void seedOrphanLocks(final int count) throws Exception {
			final ConcurrentMap<String, Object> locks = (ConcurrentMap<String, Object>) getField("profileLocks");
			for (int i = 0; i < count; i++) {
				locks.put("orphan-" + i, new Object());
			}
		}

		private Object getField(final String fieldName) throws Exception {
			final java.lang.reflect.Field field = OAuth2ClientCredentialsTokenManager.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			return field.get(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager);
		}

		@Test
		@Story("no sweep below threshold")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the orphan sweep does not trigger below the 10k threshold")
		void orphanSweep_belowThreshold_noSweep() {
			// given
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.acquire("pid-1", ccProfile("secret-1"));

			// when: call getAccessToken which triggers sweepOrphanedLocks
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.getAccessToken("pid-1", false);

			// then: the live lock is preserved
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount()).isEqualTo(1);
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLocksOrphanedTotal())
				.isZero();
		}

	}

	@Nested
	@DisplayName("lock map boundedness")
	class LockMapBoundedness {

		@Test
		@Story("lock map bounded across cycles")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the lock map does not grow unboundedly across acquire/evict cycles")
		void lockMap_boundedAcrossCycles() {
			// given
			OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.acquire("pid-1", ccProfile("secret-1"));

			// when: many acquire/evict cycles
			for (int i = 0; i < 1_000; i++) {
				final String pid = "pid-" + (i % 100);
				OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.acquire(pid, ccProfile("secret-" + i));
				OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.evict(pid);
			}

			// then: the lock map is bounded (evict removes locks)
			assertThat(OAuth2ClientCredentialsTokenManagerBoundedTests.this.manager.profileLockCount())
				.isLessThanOrEqualTo(100);
		}

	}

	/**
	 * In-process stub token endpoint (mirrors the one in
	 * OAuth2ClientCredentialsTokenManagerTests).
	 */
	private static final class StubTokenServer {

		private HttpServer server;

		private int port;

		private int status = 200;

		private String body = "{\"access_token\":\"tok-1\",\"expires_in\":300}";

		private final AtomicInteger requestCount = new AtomicInteger();

		private final List<String> requestBodies = new ArrayList<>();

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
