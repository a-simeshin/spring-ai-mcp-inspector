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

	@BeforeEach
	void setUp() throws IOException {
		this.stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.stubPort = this.stubServer.getAddress().getPort();
		this.tokenEndpointLatch = new CountDownLatch(1);
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
		final long expectedGeneration = store.currentGeneration();

		// when: start the async acquire, then concurrently delete the profile
		final CompletableFuture<Void> acquireFuture = CompletableFuture
			.runAsync(() -> StepVerifier.create(manager.acquireAsync(profileId, profile))
				.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()))
				.verifyComplete());

		// Give the async exchange a moment to reach the blocked endpoint
		Thread.sleep(100);

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
		final long expectedGeneration = store.currentGeneration();

		// when: start the async exchange, then concurrently delete the profile
		final CompletableFuture<Void> exchangeFuture = CompletableFuture
			.runAsync(() -> StepVerifier.create(exchanger.exchangeAsync(profile, "auth-code", verifier))
				.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()))
				.verifyComplete());

		Thread.sleep(100);

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
		final long expectedGeneration = store.currentGeneration();

		// when: start the async acquire, then concurrently clear the profile
		final CompletableFuture<Void> acquireFuture = CompletableFuture
			.runAsync(() -> StepVerifier.create(manager.acquireAsync(profileId, profile))
				.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()))
				.verifyComplete());

		Thread.sleep(100);

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

	private void stubTokenEndpointWithLatch(final int status, final String body) {
		this.stubServer.createContext("/token", (exchange) -> {
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
