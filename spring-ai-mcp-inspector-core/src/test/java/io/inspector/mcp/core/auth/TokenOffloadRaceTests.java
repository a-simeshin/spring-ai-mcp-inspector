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
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Race tests: verify that a concurrent delete/clear during an in-flight async token
 * exchange cannot resurrect credentials or tokens.
 */
class TokenOffloadRaceTests {

	private HttpServer stubServer;

	private int stubPort;

	private CountDownLatch tokenEndpointLatch;

	private CountDownLatch tokenEndpointArrived;

	@BeforeEach
	void setUp() throws IOException {
		this.stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.stubPort = this.stubServer.getAddress().getPort();
		this.tokenEndpointLatch = new CountDownLatch(1);
		this.tokenEndpointArrived = new CountDownLatch(1);
	}

	@AfterEach
	void tearDown() {
		if (this.stubServer != null) {
			this.stubServer.stop(0);
		}
	}

	@Test
	void registerBlockedAtTokenEndpoint_concurrentDelete_noResurrection() throws Exception {
		// given: token endpoint that blocks until released
		stubTokenEndpointWithLatch(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");

		final AuthProfileStore store = new AuthProfileStore();
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(
				HttpClient.newHttpClient(), new JsonMapper(), store::currentGeneration);
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger();
		store.setTokenEvictor((profileId) -> {
			manager.evict(profileId);
			exchanger.evict(profileId);
		});

		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", "sec", null, null, null, null, null);
		final String profileId = store.register("owner-a", profile);
		final long expectedGeneration = store.currentGeneration(profileId);

		// when: start the async acquire, then concurrently delete the profile
		final CompletableFuture<Void> acquireFuture = CompletableFuture
			.runAsync(() -> StepVerifier.create(manager.acquireAsync(profileId, profile))
				.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()))
				.verifyComplete());

		// Order check: the async exchange must be parked at the blocked endpoint
		// before the concurrent mutation runs
		assertThat(this.tokenEndpointArrived.await(5, TimeUnit.SECONDS))
			.as("async exchange reached the blocked token endpoint before the mutation")
			.isTrue();

		// Concurrent DELETE
		final boolean deleted = store.delete("owner-a", profileId);
		assertThat(deleted).isTrue();

		// Release the token endpoint
		this.tokenEndpointLatch.countDown();
		acquireFuture.get(10, TimeUnit.SECONDS);

		// Attempt to store the token: should fail with StaleProfileGenerationException
		final OAuth2ClientCredentialsTokenManager.TokenHandle handle = new OAuth2ClientCredentialsTokenManager.TokenHandle(
				"tok-1", Instant.now().plusSeconds(60));

		try {
			manager.storeIfCurrent(profileId, expectedGeneration, handle, profile);
		}
		catch (final StaleProfileGenerationException ex) {
			// expected
		}

		// then: no resurrection
		assertThat(manager.credentialCount()).isZero();
		assertThat(manager.cacheSize()).isZero();
		assertThat(store.resolve("owner-a", profileId)).isEmpty();
	}

	@Test
	void exchangeBlockedAtTokenEndpoint_concurrentDelete_noResurrection() throws Exception {
		// given: token endpoint that blocks until released
		stubTokenEndpointWithLatch(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");

		final AuthProfileStore store = new AuthProfileStore();
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger(HttpClient.newHttpClient(),
				new JsonMapper(), store::currentGeneration);
		store.setTokenEvictor(exchanger);

		final String verifier = "test-verifier";
		final String challenge = s256(verifier);
		final OAuth2Profile profile = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", null, null, "http://idp/auth", "http://app/cb",
				challenge, "S256");
		final String profileId = store.register("owner-a", profile);
		exchanger.mintState("owner-a", profileId);
		final long expectedGeneration = store.currentGeneration(profileId);

		// when: start the async exchange, then concurrently delete the profile
		final CompletableFuture<Void> exchangeFuture = CompletableFuture
			.runAsync(() -> StepVerifier.create(exchanger.exchangeAsync(profile, "auth-code", verifier))
				.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()))
				.verifyComplete());

		assertThat(this.tokenEndpointArrived.await(5, TimeUnit.SECONDS))
			.as("async exchange reached the blocked token endpoint before the mutation")
			.isTrue();

		final boolean deleted = store.delete("owner-a", profileId);
		assertThat(deleted).isTrue();

		this.tokenEndpointLatch.countDown();
		exchangeFuture.get(10, TimeUnit.SECONDS);

		// Attempt to store the token: should fail with StaleProfileGenerationException
		final OAuth2AuthCodeTokenExchanger.TokenHandle handle = new OAuth2AuthCodeTokenExchanger.TokenHandle("tok-1",
				"rt-1", Instant.now().plusSeconds(60));

		try {
			exchanger.storeTokensIfCurrent(profileId, expectedGeneration, handle);
		}
		catch (final StaleProfileGenerationException ex) {
			// expected
		}

		// then: no resurrection
		assertThat(exchanger.tokenCount()).isZero();
		assertThat(exchanger.stateCount()).isZero();
		assertThat(store.resolve("owner-a", profileId)).isEmpty();
	}

	@Test
	void registerBlockedAtTokenEndpoint_concurrentClear_noResurrection() throws Exception {
		// given: token endpoint that blocks until released
		stubTokenEndpointWithLatch(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");

		final AuthProfileStore store = new AuthProfileStore();
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(
				HttpClient.newHttpClient(), new JsonMapper(), store::currentGeneration);
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger();
		store.setTokenEvictor((profileId) -> {
			manager.evict(profileId);
			exchanger.evict(profileId);
		});

		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", "sec", null, null, null, null, null);
		final String profileId = store.register("owner-a", profile);
		final long expectedGeneration = store.currentGeneration(profileId);

		// when: start the async acquire, then concurrently clear the profile
		final CompletableFuture<Void> acquireFuture = CompletableFuture
			.runAsync(() -> StepVerifier.create(manager.acquireAsync(profileId, profile))
				.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()))
				.verifyComplete());

		assertThat(this.tokenEndpointArrived.await(5, TimeUnit.SECONDS))
			.as("async exchange reached the blocked token endpoint before the mutation")
			.isTrue();

		// Concurrent CLEAR (internal cleanup path)
		final boolean cleared = store.clear(profileId);
		assertThat(cleared).isTrue();

		this.tokenEndpointLatch.countDown();
		acquireFuture.get(10, TimeUnit.SECONDS);

		// Attempt to store the token: should fail with StaleProfileGenerationException
		final OAuth2ClientCredentialsTokenManager.TokenHandle handle = new OAuth2ClientCredentialsTokenManager.TokenHandle(
				"tok-1", Instant.now().plusSeconds(60));

		try {
			manager.storeIfCurrent(profileId, expectedGeneration, handle, profile);
		}
		catch (final StaleProfileGenerationException ex) {
			// expected
		}

		// then: no resurrection
		assertThat(manager.credentialCount()).isZero();
		assertThat(manager.cacheSize()).isZero();
		assertThat(store.resolve("owner-a", profileId)).isEmpty();
	}

	@Test
	void deterministicInterleaving_generationReadThenDeleteThenPut_ccZeroCounts() throws Exception {
		// given: a controllable generation supplier that pauses after each read
		final AuthProfileStore store = new AuthProfileStore();
		final java.util.concurrent.CountDownLatch generationReadLatch = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.CountDownLatch deleteLatch = new java.util.concurrent.CountDownLatch(1);
		final java.util.function.ToLongFunction<String> pausingGuard = (pid) -> {
			final long g = store.currentGeneration(pid);
			generationReadLatch.countDown();
			try {
				deleteLatch.await(5, TimeUnit.SECONDS);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return g;
		};
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(
				HttpClient.newHttpClient(), new JsonMapper(), pausingGuard);
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger();
		store.setTokenEvictor((profileId) -> {
			manager.evict(profileId);
			exchanger.evict(profileId);
		});

		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", "sec", null, null, null, null, null);
		final String profileId = store.register("owner-a", profile);
		final long expectedGeneration = store.currentGeneration(profileId);

		// when: storeIfCurrent starts and pauses after reading generation
		final CompletableFuture<Void> storeFuture = CompletableFuture.runAsync(() -> {
			try {
				manager.storeIfCurrent(profileId, expectedGeneration,
						new OAuth2ClientCredentialsTokenManager.TokenHandle("tok-1", Instant.now().plusSeconds(60)),
						profile);
			}
			catch (final StaleProfileGenerationException ex) {
				// expected after delete
			}
		});

		// Order check: the generation must have been read (inside the lock,
		// before the put) before the concurrent DELETE runs
		assertThat(generationReadLatch.await(5, TimeUnit.SECONDS))
			.as("generation was read inside the lock before the concurrent delete")
			.isTrue();

		// Concurrent DELETE: bumps generation and evicts
		final boolean deleted = store.delete("owner-a", profileId);
		assertThat(deleted).isTrue();

		// Release the paused generation read
		deleteLatch.countDown();
		storeFuture.get(10, TimeUnit.SECONDS);

		// then: no resurrection of credentials or cache
		assertThat(manager.credentialCount()).isZero();
		assertThat(manager.cacheSize()).isZero();
		assertThat(store.resolve("owner-a", profileId)).isEmpty();
	}

	@Test
	void deterministicInterleaving_generationReadThenDeleteThenPut_authCodeZeroTokens() throws Exception {
		// given: a controllable generation supplier that pauses after each read
		final AuthProfileStore store = new AuthProfileStore();
		final java.util.concurrent.CountDownLatch generationReadLatch = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.CountDownLatch deleteLatch = new java.util.concurrent.CountDownLatch(1);
		final java.util.function.ToLongFunction<String> pausingGuard = (pid) -> {
			final long g = store.currentGeneration(pid);
			generationReadLatch.countDown();
			try {
				deleteLatch.await(5, TimeUnit.SECONDS);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return g;
		};
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger(HttpClient.newHttpClient(),
				new JsonMapper(), pausingGuard);
		store.setTokenEvictor(exchanger);

		final String verifier = "test-verifier";
		final String challenge = s256(verifier);
		final OAuth2Profile profile = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", null, null, "http://idp/auth", "http://app/cb",
				challenge, "S256");
		final String profileId = store.register("owner-a", profile);
		exchanger.mintState("owner-a", profileId);
		final long expectedGeneration = store.currentGeneration(profileId);

		// when: storeTokensIfCurrent starts and pauses after reading generation
		final CompletableFuture<Void> storeFuture = CompletableFuture.runAsync(() -> {
			try {
				exchanger.storeTokensIfCurrent(profileId, expectedGeneration,
						new OAuth2AuthCodeTokenExchanger.TokenHandle("tok-1", "rt-1", Instant.now().plusSeconds(60)));
			}
			catch (final StaleProfileGenerationException ex) {
				// expected after delete
			}
		});

		// Order check: the generation must have been read (inside the lock,
		// before the put) before the concurrent DELETE runs
		assertThat(generationReadLatch.await(5, TimeUnit.SECONDS))
			.as("generation was read inside the lock before the concurrent delete")
			.isTrue();

		// Concurrent DELETE: bumps generation and evicts
		final boolean deleted = store.delete("owner-a", profileId);
		assertThat(deleted).isTrue();

		// Release the paused generation read
		deleteLatch.countDown();
		storeFuture.get(10, TimeUnit.SECONDS);

		// then: no resurrection of tokens or states
		assertThat(exchanger.tokenCount()).isZero();
		assertThat(exchanger.stateCount()).isZero();
		assertThat(store.resolve("owner-a", profileId)).isEmpty();
	}

	@Test
	void registerBlockedAtTokenEndpoint_foreignDeleteAndSweep_registrationSurvives() throws Exception {
		// given: token endpoint that blocks until released
		stubTokenEndpointWithLatch(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");

		final AuthProfileStore store = new AuthProfileStore();
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(
				HttpClient.newHttpClient(), new JsonMapper(), store::currentGeneration);
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger();
		store.setTokenEvictor((profileId) -> {
			manager.evict(profileId);
			exchanger.evict(profileId);
		});

		// an unrelated owner's profile the TTL reaper will sweep mid-flight
		store.setProfileTtl(Duration.ofMillis(1));
		final String expiredId = store.register("owner-c", new OAuth2Profile("cc-c", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid-c", "sec-c", null, null, null, null, null));
		store.setProfileTtl(Duration.ofHours(24));

		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", "sec", null, null, null, null, null);
		final String profileId = store.register("owner-a", profile);
		final long expectedGeneration = store.currentGeneration(profileId);

		// when: start the async acquire, then mutate OTHER profiles while it is blocked
		final CompletableFuture<Void> acquireFuture = CompletableFuture
			.runAsync(() -> StepVerifier.create(manager.acquireAsync(profileId, profile))
				.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()))
				.verifyComplete());

		// Order check: the async exchange must be parked at the blocked endpoint
		// before the concurrent mutation runs
		assertThat(this.tokenEndpointArrived.await(5, TimeUnit.SECONDS))
			.as("async exchange reached the blocked token endpoint before the mutation")
			.isTrue();

		// Foreign owner's concurrent registration + delete and the TTL sweep: none of
		// these may invalidate owner-a's in-flight registration
		final String foreignId = store.register("owner-b", new OAuth2Profile("cc-b", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid-b", "sec-b", null, null, null, null, null));
		assertThat(store.delete("owner-b", foreignId)).isTrue();
		assertThat(store.removeExpired(Instant.now().plusSeconds(60))).isEqualTo(1);
		assertThat(store.resolve("owner-c", expiredId)).isEmpty();

		// Release the token endpoint
		this.tokenEndpointLatch.countDown();
		acquireFuture.get(10, TimeUnit.SECONDS);

		// then: the registration survives the foreign mutations and the token is stored
		final OAuth2ClientCredentialsTokenManager.TokenHandle handle = new OAuth2ClientCredentialsTokenManager.TokenHandle(
				"tok-1", Instant.now().plusSeconds(60));
		manager.storeIfCurrent(profileId, expectedGeneration, handle, profile);
		assertThat(manager.credentialCount()).isEqualTo(1);
		assertThat(manager.cacheSize()).isEqualTo(1);
		assertThat(store.resolve("owner-a", profileId)).isPresent();
	}

	@Test
	void exchangeBlockedAtTokenEndpoint_foreignDelete_exchangeSurvives() throws Exception {
		// given: token endpoint that blocks until released
		stubTokenEndpointWithLatch(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");

		final AuthProfileStore store = new AuthProfileStore();
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger(HttpClient.newHttpClient(),
				new JsonMapper(), store::currentGeneration);
		store.setTokenEvictor(exchanger);

		final String verifier = "test-verifier";
		final String challenge = s256(verifier);
		final OAuth2Profile profile = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", null, null, "http://idp/auth", "http://app/cb",
				challenge, "S256");
		final String profileId = store.register("owner-a", profile);
		exchanger.mintState("owner-a", profileId);
		final long expectedGeneration = store.currentGeneration(profileId);

		// when: start the async exchange, then delete ANOTHER owner's profile mid-flight
		final CompletableFuture<Void> exchangeFuture = CompletableFuture
			.runAsync(() -> StepVerifier.create(exchanger.exchangeAsync(profile, "auth-code", verifier))
				.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()))
				.verifyComplete());

		// Order check: the async exchange must be parked at the blocked endpoint
		// before the concurrent mutation runs
		assertThat(this.tokenEndpointArrived.await(5, TimeUnit.SECONDS))
			.as("async exchange reached the blocked token endpoint before the mutation")
			.isTrue();

		// Foreign owner's concurrent delete: must not invalidate owner-a's exchange
		final String foreignId = store.register("owner-b", new OAuth2Profile("cc-b", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid-b", "sec-b", null, null, null, null, null));
		assertThat(store.delete("owner-b", foreignId)).isTrue();

		// Release the token endpoint
		this.tokenEndpointLatch.countDown();
		exchangeFuture.get(10, TimeUnit.SECONDS);

		// then: the exchange survives the foreign delete and the tokens are stored
		final OAuth2AuthCodeTokenExchanger.TokenHandle handle = new OAuth2AuthCodeTokenExchanger.TokenHandle("tok-1",
				"rt-1", Instant.now().plusSeconds(60));
		exchanger.storeTokensIfCurrent(profileId, expectedGeneration, handle);
		assertThat(exchanger.tokenCount()).isEqualTo(1);
		assertThat(store.resolve("owner-a", profileId)).isPresent();
	}

	private void stubTokenEndpointWithLatch(final int status, final String body) {
		this.stubServer.createContext("/token", (exchange) -> {
			// signal arrival FIRST so tests assert the in-flight order instead of
			// sleeping and hoping the exchange reached the endpoint
			this.tokenEndpointArrived.countDown();
			try {
				this.tokenEndpointLatch.await(5, TimeUnit.SECONDS);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.getResponseBody().close();
		});
		this.stubServer.start();
	}

	private static String s256(final String verifier) {
		try {
			final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
				.digest(verifier.getBytes(StandardCharsets.UTF_8));
			return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (final Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
