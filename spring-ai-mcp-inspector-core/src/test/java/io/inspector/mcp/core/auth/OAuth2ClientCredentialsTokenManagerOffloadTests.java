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
 * Unit tests for the async offload seams of {@link OAuth2ClientCredentialsTokenManager}.
 */
class OAuth2ClientCredentialsTokenManagerOffloadTests {

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
	void acquireAsync_returnsTokenOnBoundedElasticThread() {
		// given
		stubTokenEndpoint(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(this.evidenceClient,
				new JsonMapper());
		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", "sec", null, null, null, null, null);

		// when/then
		StepVerifier.create(manager.acquireAsync("pid-cc", profile))
			.expectNextMatches((handle) -> "tok-1".equals(handle.accessToken()) && handle.expiresAt() != null)
			.verifyComplete();

		assertThat(this.evidenceClient.recordedThreadName()).doesNotContain("Test worker");
		assertThat(this.evidenceClient.recordedThreadName()).contains("boundedElastic");
		assertThat(this.evidenceClient.requestCount()).isGreaterThan(0);
	}

	@Test
	void acquireAsync_propagatesUpstreamError() {
		// given
		stubTokenEndpoint(500, "Internal Server Error");
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(this.evidenceClient,
				new JsonMapper());
		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubPort + "/token", "cid", "sec", null, null, null, null, null);

		// when/then
		StepVerifier.create(manager.acquireAsync("pid-cc", profile)).expectError(ProxyUpstreamException.class).verify();
	}

	@Test
	void storeIfCurrent_storesTokenWhenGenerationMatches() {
		// given
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(this.evidenceClient,
				new JsonMapper(), () -> 5L);
		final OAuth2ClientCredentialsTokenManager.TokenHandle handle = new OAuth2ClientCredentialsTokenManager.TokenHandle(
				"tok-1", Instant.now().plusSeconds(60));
		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://localhost/token", "cid", "sec", null, null, null, null, null);

		// when
		manager.storeIfCurrent("pid-cc", 5L, handle, profile);

		// then
		assertThat(manager.credentialCount()).isOne();
		assertThat(manager.cacheSize()).isOne();
	}

	@Test
	void storeIfCurrent_throwsStaleProfileGenerationExceptionWhenGenerationChanged() {
		// given
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(this.evidenceClient,
				new JsonMapper(), () -> 1L);
		final OAuth2ClientCredentialsTokenManager.TokenHandle handle = new OAuth2ClientCredentialsTokenManager.TokenHandle(
				"tok-1", Instant.now().plusSeconds(60));
		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://localhost/token", "cid", "sec", null, null, null, null, null);

		// when/then
		assertThatThrownBy(() -> manager.storeIfCurrent("pid-cc", 0L, handle, profile))
			.isInstanceOf(StaleProfileGenerationException.class)
			.hasMessageContaining("pid-cc");
	}

	@Test
	void storeIfCurrent_storesNothingOnStaleGeneration() {
		// given
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(this.evidenceClient,
				new JsonMapper(), () -> 1L);
		final OAuth2ClientCredentialsTokenManager.TokenHandle handle = new OAuth2ClientCredentialsTokenManager.TokenHandle(
				"tok-1", Instant.now().plusSeconds(60));
		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://localhost/token", "cid", "sec", null, null, null, null, null);

		// when
		assertThatThrownBy(() -> manager.storeIfCurrent("pid-cc", 0L, handle, profile))
			.isInstanceOf(StaleProfileGenerationException.class);

		// then
		assertThat(manager.credentialCount()).isZero();
		assertThat(manager.cacheSize()).isZero();
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

}
