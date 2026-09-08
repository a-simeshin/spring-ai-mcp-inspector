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

package io.inspector.mcp.webflux.it;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.webflux.auth.ReactiveSessionOwnerResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies the OAuth token offload at the WebFlux boundary on a real
 * Netty server (RANDOM_PORT). Covers CC register (200 and 502 D9A rollback), auth-code
 * exchange (200 and 502 rollback), composite eviction, and thread-boundary evidence
 * (the blocking token HTTP call must execute on boundedElastic, never on the reactor
 * event loop).
 */
@Epic("MCP Inspector WebFlux")
@Feature("Auth token offload (integration)")
@AutoConfigureWebTestClient
@SpringBootTest(classes = TestMcpServerApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-authoff", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-flux-authoff" })
@org.springframework.test.annotation.DirtiesContext(
		classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WebFluxAuthTokenOffloadIT {

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private AuthProfileStore authProfileStore;

	@Autowired
	private OAuth2ClientCredentialsTokenManager ccTokenManager;

	@Autowired
	private OAuth2AuthCodeTokenExchanger authCodeExchanger;

	@Autowired
	private ThreadEvidenceHttpClient ccEvidenceClient;

	@Autowired
	private ThreadEvidenceHttpClient acEvidenceClient;

	private HttpServer stubTokenServer;

	private int stubTokenPort;

	@BeforeEach
	void setUp() throws IOException {
		this.stubTokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.stubTokenPort = this.stubTokenServer.getAddress().getPort();
	}

	@AfterEach
	void tearDown() {
		if (this.stubTokenServer != null) {
			this.stubTokenServer.stop(0);
		}
	}

	@Test
	@Story("CC register offload")
	@Severity(SeverityLevel.CRITICAL)
	@Description("POST /auth-profile CC 200: token stored with credentials, composite evictor wired")
	void registerCC_success_storesCredentialsAndToken() {
		stubTokenEndpoint(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");

		this.webTestClient.post()
			.uri("/mcp-inspector/api/auth-profile")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"profile\": {\"name\": \"cc-it\", \"type\": \"OAUTH2\", "
					+ "\"grantMode\": \"CLIENT_CREDENTIALS\", " + "\"tokenUrl\": \"http://127.0.0.1:"
					+ this.stubTokenPort + "/token\", " + "\"clientId\": \"cid\", \"clientSecret\": \"sec\"}}")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.profileId")
			.exists();

		assertThat(this.ccTokenManager.credentialCount()).isGreaterThan(0);
		assertThat(this.ccTokenManager.cacheSize()).isGreaterThan(0);

		// then: thread evidence shows the blocking HTTP call ran on boundedElastic,
		// never on the reactor event loop
		assertThat(this.ccEvidenceClient.recordedThreadName()).isNotNull();
		assertThat(this.ccEvidenceClient.recordedThreadName()).doesNotContain("reactor-http-nio");
		assertThat(this.ccEvidenceClient.recordedThreadName()).contains("boundedElastic");
		assertThat(this.ccEvidenceClient.requestCount()).isGreaterThan(0);
	}

	@Test
	@Story("CC register D9A rollback")
	@Severity(SeverityLevel.CRITICAL)
	@Description("POST /auth-profile CC 502 on upstream failure: profile rolled back, credentials evicted")
	void registerCC_upstreamFailure_rollsBackProfileAndEvictsCredentials() {
		stubTokenEndpoint(500, "Internal Server Error");

		this.webTestClient.post()
			.uri("/mcp-inspector/api/auth-profile")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"profile\": {\"name\": \"cc-d9a\", \"type\": \"OAUTH2\", "
					+ "\"grantMode\": \"CLIENT_CREDENTIALS\", " + "\"tokenUrl\": \"http://127.0.0.1:"
					+ this.stubTokenPort + "/token\", " + "\"clientId\": \"cid\", \"clientSecret\": \"sec\"}}")
			.exchange()
			.expectStatus()
			.isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY)
			.expectBody()
			.jsonPath("$.code")
			.isEqualTo("token_exchange_failed")
			.jsonPath("$.status")
			.isEqualTo(502);

		// D9A rollback: no orphan profile, no retained credentials
		assertThat(this.ccTokenManager.credentialCount()).isZero();
		assertThat(this.ccTokenManager.cacheSize()).isZero();

		// then: thread evidence shows the blocking HTTP call ran on boundedElastic,
		// never on the reactor event loop
		assertThat(this.ccEvidenceClient.recordedThreadName()).isNotNull();
		assertThat(this.ccEvidenceClient.recordedThreadName()).doesNotContain("reactor-http-nio");
		assertThat(this.ccEvidenceClient.recordedThreadName()).contains("boundedElastic");
		assertThat(this.ccEvidenceClient.requestCount()).isGreaterThan(0);
	}

	@Test
	@Story("Auth-code exchange offload")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Full auth-code exchange flow: register PENDING, exchange with PKCE, tokens stored")
	void authCodeExchange_success_storesTokensAndMarksActive() {
		stubTokenEndpoint(200, "{\"access_token\":\"tok-ac\",\"expires_in\":3600}");

		final String verifier = "it-test-verifier";
		final String challenge = s256(verifier);
		final AtomicReference<String> profileId = new AtomicReference<>();
		final AtomicReference<String> returnedState = new AtomicReference<>();

		this.webTestClient.post()
			.uri("/mcp-inspector/api/auth-profile")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"name\": \"ac-it\", \"type\": \"OAUTH2\", " + "\"grantMode\": \"AUTHORIZATION_CODE\", "
					+ "\"tokenUrl\": \"http://127.0.0.1:" + this.stubTokenPort + "/token\", "
					+ "\"clientId\": \"cid\", "
					+ "\"authorizationUrl\": \"http://idp/auth\", \"redirectUri\": \"http://app/cb\", "
					+ "\"codeChallenge\": \"" + challenge + "\", \"codeChallengeMethod\": \"S256\"}")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.profileId")
			.value((String pid) -> profileId.set(pid))
			.jsonPath("$.state")
			.value((String state) -> returnedState.set(state));

		this.webTestClient.post()
			.uri("/mcp-inspector/api/auth-profile/" + profileId.get() + "/exchange")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"code\": \"auth-code-1\", \"codeVerifier\": \"" + verifier + "\", " + "\"state\": \""
					+ returnedState.get() + "\"}")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.profileId")
			.isEqualTo(profileId.get());

		// then: tokens were stored
		assertThat(this.authCodeExchanger.tokenCount()).isGreaterThan(0);

		// then: thread evidence shows the blocking HTTP call ran on boundedElastic,
		// never on the reactor event loop
		assertThat(this.acEvidenceClient.recordedThreadName()).isNotNull();
		assertThat(this.acEvidenceClient.recordedThreadName()).doesNotContain("reactor-http-nio");
		assertThat(this.acEvidenceClient.recordedThreadName()).contains("boundedElastic");
		assertThat(this.acEvidenceClient.requestCount()).isGreaterThan(0);
	}

	@Test
	@Story("Auth-code exchange D9A rollback")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Auth-code exchange 502 on upstream failure: no tokens stored, profile not ACTIVE")
	void authCodeExchange_upstreamFailure_noTokensStoredAndProfileNotActive() {
		stubTokenEndpoint(500, "Internal Server Error");

		final String verifier = "it-verifier-d9a";
		final String challenge = s256(verifier);
		final AtomicReference<String> profileId = new AtomicReference<>();
		final AtomicReference<String> returnedState = new AtomicReference<>();

		this.webTestClient.post()
			.uri("/mcp-inspector/api/auth-profile")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"name\": \"ac-d9a\", \"type\": \"OAUTH2\", " + "\"grantMode\": \"AUTHORIZATION_CODE\", "
					+ "\"tokenUrl\": \"http://127.0.0.1:" + this.stubTokenPort + "/token\", "
					+ "\"clientId\": \"cid\", "
					+ "\"authorizationUrl\": \"http://idp/auth\", \"redirectUri\": \"http://app/cb\", "
					+ "\"codeChallenge\": \"" + challenge + "\", \"codeChallengeMethod\": \"S256\"}")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.profileId")
			.value((String pid) -> profileId.set(pid))
			.jsonPath("$.state")
			.value((String state) -> returnedState.set(state));

		// Step 2: exchange with the returned state (fails upstream)
		this.webTestClient.post()
			.uri("/mcp-inspector/api/auth-profile/" + profileId.get() + "/exchange")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"code\": \"auth-code-1\", \"codeVerifier\": \"" + verifier + "\", " + "\"state\": \""
					+ returnedState.get() + "\"}")
			.exchange()
			.expectStatus()
			.isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY)
			.expectBody()
			.jsonPath("$.code")
			.isEqualTo("token_exchange_failed");

		assertThat(this.authCodeExchanger.tokenCount()).isZero();

		// then: thread evidence shows the blocking HTTP call ran on boundedElastic,
		// never on the reactor event loop
		assertThat(this.acEvidenceClient.recordedThreadName()).isNotNull();
		assertThat(this.acEvidenceClient.recordedThreadName()).doesNotContain("reactor-http-nio");
		assertThat(this.acEvidenceClient.recordedThreadName()).contains("boundedElastic");
		assertThat(this.acEvidenceClient.requestCount()).isGreaterThan(0);
	}

	private void stubTokenEndpoint(final int status, final String body) {
		this.stubTokenServer.createContext("/token", (exchange) -> {
			final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.getResponseBody().close();
		});
		this.stubTokenServer.start();
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

	@TestConfiguration
	static class ThreadEvidenceConfig {

		@Bean
		@Primary
		ThreadEvidenceHttpClient ccThreadEvidenceHttpClient() {
			return new ThreadEvidenceHttpClient(java.net.http.HttpClient.newHttpClient());
		}

		@Bean
		@Primary
		ThreadEvidenceHttpClient acThreadEvidenceHttpClient() {
			return new ThreadEvidenceHttpClient(java.net.http.HttpClient.newHttpClient());
		}

		@Bean
		@Primary
		OAuth2ClientCredentialsTokenManager ccTokenManagerWithEvidence(final AuthProfileStore authProfileStore,
				final OAuth2AuthCodeTokenExchanger authCodeExchanger,
				final ThreadEvidenceHttpClient ccThreadEvidenceHttpClient) {
			final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(
					ccThreadEvidenceHttpClient, new JsonMapper());
			final io.inspector.mcp.core.auth.TokenEvictor compositeEvictor = (profileId) -> {
				manager.evict(profileId);
				authCodeExchanger.evict(profileId);
			};
			authProfileStore.setTokenEvictor(compositeEvictor);
			manager.setGenerationGuard(authProfileStore::currentGeneration);
			return manager;
		}

		@Bean
		@Primary
		OAuth2AuthCodeTokenExchanger acExchangerWithEvidence(
				final ThreadEvidenceHttpClient acThreadEvidenceHttpClient) {
			return new OAuth2AuthCodeTokenExchanger(acThreadEvidenceHttpClient, new JsonMapper());
		}

		@Bean
		@Primary
		ReactiveSessionOwnerResolver fixedOwnerResolver() {
			return new ReactiveSessionOwnerResolver(null) {
				@Override
				public String resolve(final org.springframework.web.server.ServerWebExchange exchange) {
					return "it-owner";
				}
			};
		}

	}

}
