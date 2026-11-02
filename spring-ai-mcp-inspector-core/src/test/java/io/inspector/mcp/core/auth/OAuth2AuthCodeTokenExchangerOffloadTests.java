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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.proxy.ProxyUpstreamException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the async offload seams of {@link OAuth2AuthCodeTokenExchanger}.
 */
class OAuth2AuthCodeTokenExchangerOffloadTests {

	private HttpServer stubServer;

	private int stubPort;

	private ThreadEvidenceHttpClient evidenceClient;

	@BeforeEach
	void setUp() throws IOException {
		this.stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.stubPort = this.stubServer.getAddress().getPort();
		this.evidenceClient = new ThreadEvidenceHttpClient(HttpClient.newHttpClient());
	}

	@AfterEach
	void tearDown() {
		if (this.stubServer != null) {
			this.stubServer.stop(0);
		}
	}

	@Test
	void exchangeAsync_returnsTokenOnBoundedElasticThread() {
		// given
		stubTokenEndpoint(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger(this.evidenceClient,
				new JsonMapper());
		final String verifier = "test-verifier-123";
		final String challenge = s256(verifier);
		final OAuth2Profile profile = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", null, null, "http://idp/auth", "http://app/cb",
				challenge, "S256");

		// when/then
		StepVerifier.create(exchanger.exchangeAsync(profile, "auth-code-1", verifier))
			.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()) && handle.expiresAt() != null)
			.verifyComplete();

		assertThat(this.evidenceClient.recordedThreadName()).doesNotContain("Test worker");
		assertThat(this.evidenceClient.recordedThreadName()).contains("boundedElastic");
		assertThat(this.evidenceClient.requestCount()).isGreaterThan(0);
	}

	@Test
	void exchangeAsync_propagatesUpstreamError() {
		// given
		stubTokenEndpoint(500, "Internal Server Error");
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger(this.evidenceClient,
				new JsonMapper());
		final String verifier = "test-verifier-123";
		final String challenge = s256(verifier);
		final OAuth2Profile profile = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", null, null, "http://idp/auth", "http://app/cb",
				challenge, "S256");

		// when/then
		StepVerifier.create(exchanger.exchangeAsync(profile, "auth-code-1", verifier))
			.expectError(ProxyUpstreamException.class)
			.verify();
	}

	@Test
	void exchangeAsync_propagatesPkceMismatch() {
		// given
		stubTokenEndpoint(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger(this.evidenceClient,
				new JsonMapper());
		final String challenge = s256("correct-verifier");
		final OAuth2Profile profile = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", null, null, "http://idp/auth", "http://app/cb",
				challenge, "S256");

		// when/then
		StepVerifier.create(exchanger.exchangeAsync(profile, "auth-code-1", "wrong-verifier"))
			.expectError(IllegalArgumentException.class)
			.verify();
	}

	@Test
	void storeTokensIfCurrent_storesTokenWhenGenerationMatches() {
		// given
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger(this.evidenceClient,
				new JsonMapper(), () -> 3L);
		final OAuth2AuthCodeTokenExchanger.TokenHandle handle = new OAuth2AuthCodeTokenExchanger.TokenHandle("tok-1",
				"rt-1", Instant.now().plusSeconds(60));

		// when
		exchanger.storeTokensIfCurrent("pid-ac", 3L, handle);

		// then
		assertThat(exchanger.tokenCount()).isOne();
	}

	@Test
	void storeTokensIfCurrent_throwsStaleProfileGenerationExceptionWhenGenerationChanged() {
		// given
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger(this.evidenceClient,
				new JsonMapper(), () -> 1L);
		final OAuth2AuthCodeTokenExchanger.TokenHandle handle = new OAuth2AuthCodeTokenExchanger.TokenHandle("tok-1",
				"rt-1", Instant.now().plusSeconds(60));

		// when/then
		assertThatThrownBy(() -> exchanger.storeTokensIfCurrent("pid-ac", 0L, handle))
			.isInstanceOf(StaleProfileGenerationException.class)
			.hasMessageContaining("pid-ac");

		assertThat(exchanger.tokenCount()).isZero();
	}

	private void stubTokenEndpoint(final int status, final String body) {
		this.stubServer.createContext("/token", (exchange) -> {
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
			final byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(verifier.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (final Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
