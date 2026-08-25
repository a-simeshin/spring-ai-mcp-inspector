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

package io.inspector.mcp.webflux.auth;

import java.time.Instant;
import java.util.Optional;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.inspector.mcp.core.auth.ApiKeyPlacement;
import io.inspector.mcp.core.auth.AuthProfile;
import io.inspector.mcp.core.auth.AuthProfilePrefillProvider;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.AuthProfileSummary;
import io.inspector.mcp.core.auth.AuthProfileType;
import io.inspector.mcp.core.auth.BearerProfile;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.ProxyUpstreamException;
import io.inspector.mcp.webflux.router.InspectorRouterConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AuthProfileHandler} — owner-scoped CRUD, prefill and /exchange
 * (D2/D8/D9).
 */
@Epic("MCP Inspector WebFlux")
@Feature("AuthProfileHandler")
class AuthProfileHandlerTests {

	private static final String OWNER_A = "owner-a";

	private static final String API_BASE = "/mcp-inspector/api/auth-profile";

	private final AuthProfileStore store = mock(AuthProfileStore.class);

	private final AuthProfilePrefillProvider prefillProvider = mock(AuthProfilePrefillProvider.class);

	private final OAuth2ClientCredentialsTokenManager tokenManager = mock(OAuth2ClientCredentialsTokenManager.class);

	private final OAuth2AuthCodeTokenExchanger exchanger = mock(OAuth2AuthCodeTokenExchanger.class);

	private final ReactiveSessionOwnerResolver resolver = mock(ReactiveSessionOwnerResolver.class);

	private WebTestClient client;

	@BeforeEach
	void setUp() {
		final McpInspectorProperties properties = new McpInspectorProperties();
		final AuthProfileHandler handler = new AuthProfileHandler(this.store, this.prefillProvider, this.tokenManager,
				this.exchanger, this.resolver);
		final InspectorRouterConfig routerConfig = new InspectorRouterConfig();
		this.client = WebTestClient.bindToRouterFunction(routerConfig.authProfileRouter(handler, properties)).build();
		// WebTestClient (Framework 7) does not propagate request attributes to the
		// handler, so the resolver fallback path supplies the owner, exactly as the
		// production filter would when the guard is disabled.
		given(this.resolver.resolve(any())).willReturn(OWNER_A);
	}

	private static org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec<?> withOwner(
			final org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec<?> spec) {
		return spec.attributes((attributes) -> attributes.put(InspectorSessionAttributes.OWNER_ID, OWNER_A));
	}

	@Nested
	@DisplayName("POST /auth-profile")
	class Register {

		@Test
		@Story("Inline profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() stores an inline bearer profile under the session owner and returns {profileId}")
		void register_inlineProfile_returnsProfileId() {
			// given
			given(AuthProfileHandlerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class))).willReturn("pid-1");

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"profile\": {\"name\": \"prod\", \"type\": \"BEARER\", \"token\": \"tok-1\"}}"))
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.profileId")
				.isEqualTo("pid-1");
			verify(AuthProfileHandlerTests.this.store).register(eq(OWNER_A), any(AuthProfile.class));
		}

		@Test
		@Story("Prefill reference")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() materializes a prefill {name,type} reference via the prefill provider")
		void register_prefillReference_resolvesAndStores() {
			// given
			given(AuthProfileHandlerTests.this.prefillProvider.resolve("prod-bearer"))
				.willReturn(Optional.of(new BearerProfile("prod-bearer", "cfg-token")));
			given(AuthProfileHandlerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class))).willReturn("pid-2");

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"name\": \"prod-bearer\", \"type\": \"BEARER\"}")).exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.profileId")
				.isEqualTo("pid-2");
			verify(AuthProfileHandlerTests.this.prefillProvider).resolve("prod-bearer");
		}

		@Test
		@Story("Prefill reference")
		@Severity(SeverityLevel.NORMAL)
		@Description("register() with an unknown prefill name returns 400")
		void register_unknownPrefillName_returns400() {
			// given
			given(AuthProfileHandlerTests.this.prefillProvider.resolve("nope")).willReturn(Optional.empty());

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"name\": \"nope\", \"type\": \"BEARER\"}")).exchange()
				.expectStatus()
				.isBadRequest()
				.expectBody()
				.jsonPath("$.error")
				.isEqualTo("unknown prefill profile: nope");
			verify(AuthProfileHandlerTests.this.store, never()).register(anyString(), any(AuthProfile.class));
		}

		@Test
		@Story("Client-credentials")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() of a CLIENT_CREDENTIALS profile runs the initial token exchange after registration")
		void register_clientCredentials_runsAcquire() {
			// given
			given(AuthProfileHandlerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class)))
				.willReturn("pid-cc");

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(
						"{\"profile\": {\"name\": \"cc\", \"type\": \"OAUTH2\", \"grantMode\": \"CLIENT_CREDENTIALS\", "
								+ "\"tokenUrl\": \"https://t/token\", \"clientId\": \"cid\", \"clientSecret\": \"sec\"}}"))
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.profileId")
				.isEqualTo("pid-cc");
			verify(AuthProfileHandlerTests.this.tokenManager).acquire(eq("pid-cc"), any(OAuth2Profile.class));
		}

		@Test
		@Story("Client-credentials")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a failed initial exchange rolls back the store entry, evicts and returns 502 token_exchange_failed")
		void register_failedAcquire_rollsBackAndReturns502() {
			// given
			given(AuthProfileHandlerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class)))
				.willReturn("pid-cc");
			willThrow(new ProxyUpstreamException(400, "OAuth2 client_credentials exchange failed: HTTP 400"))
				.given(AuthProfileHandlerTests.this.tokenManager)
				.acquire(eq("pid-cc"), any(OAuth2Profile.class));

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(
						"{\"profile\": {\"name\": \"cc\", \"type\": \"OAUTH2\", \"grantMode\": \"CLIENT_CREDENTIALS\", "
								+ "\"tokenUrl\": \"https://t/token\", \"clientId\": \"cid\", \"clientSecret\": \"sec\"}}"))
				.exchange()
				.expectStatus()
				.isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY)
				.expectBody()
				.jsonPath("$.code")
				.isEqualTo("token_exchange_failed")
				.jsonPath("$.status")
				.isEqualTo(502);
			verify(AuthProfileHandlerTests.this.store).delete(OWNER_A, "pid-cc");
			verify(AuthProfileHandlerTests.this.tokenManager).evict("pid-cc");
		}

		@Test
		@Story("Auth-code pending (D9B)")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() of an AUTHORIZATION_CODE profile returns the server-issued state and authorizationUrl")
		void register_authCodePending_returnsStateAndAuthorizationUrl() {
			// given
			given(AuthProfileHandlerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class)))
				.willReturn("pid-ac");
			given(AuthProfileHandlerTests.this.exchanger.mintState(OWNER_A, "pid-ac")).willReturn("server-state-1");

			// when/then — D9B phase 1: {profileId, state, authorizationUrl}
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"name\": \"ac\", \"type\": \"OAUTH2\", \"grantMode\": \"AUTHORIZATION_CODE\", "
						+ "\"tokenUrl\": \"https://t/token\", \"clientId\": \"cid\", \"scopes\": \"read\", "
						+ "\"authorizationUrl\": \"https://idp/authorize\", \"redirectUri\": \"https://app/cb\", "
						+ "\"codeChallenge\": \"ch\", \"codeChallengeMethod\": \"S256\"}"))
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.profileId")
				.isEqualTo("pid-ac")
				.jsonPath("$.state")
				.isEqualTo("server-state-1")
				.jsonPath("$.authorizationUrl")
				.isEqualTo("https://idp/authorize");
			verify(AuthProfileHandlerTests.this.exchanger).mintState(OWNER_A, "pid-ac");
		}

	}

	@Nested
	@DisplayName("GET /auth-profile")
	class List {

		@Test
		@Story("Listing")
		@Severity(SeverityLevel.CRITICAL)
		@Description("list() returns the OWNER-scoped summaries from the store")
		void list_returnsOwnerScopedSummaries() {
			// given
			given(AuthProfileHandlerTests.this.store.list(OWNER_A))
				.willReturn(java.util.List.of(new AuthProfileSummary("pid-1", "prod", AuthProfileType.BEARER, null,
						null, null, null, null, null, null, null, null)));

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.get().uri(API_BASE)).exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$[0].profileId")
				.isEqualTo("pid-1")
				.jsonPath("$[0].name")
				.isEqualTo("prod");
			verify(AuthProfileHandlerTests.this.store).list(OWNER_A);
		}

		@Test
		@Story("Prefill")
		@Severity(SeverityLevel.NORMAL)
		@Description("GET /auth-profile/prefill returns the config summaries (no secrets)")
		void prefill_returnsConfigSummaries() {
			// given
			given(AuthProfileHandlerTests.this.prefillProvider.list())
				.willReturn(java.util.List.of(new AuthProfileSummary(null, "cfg", AuthProfileType.API_KEY, null, null,
						null, null, null, null, "X-Key", ApiKeyPlacement.HEADER, null)));

			// when/then
			AuthProfileHandlerTests.this.client.get()
				.uri(API_BASE + "/prefill")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$[0].name")
				.isEqualTo("cfg");
		}

	}

	@Nested
	@DisplayName("PUT /auth-profile/{profileId}")
	class Update {

		@Test
		@Story("Update")
		@Severity(SeverityLevel.CRITICAL)
		@Description("update() returns 204 for an owned profile")
		void update_ownedProfile_returns204() {
			// given
			given(AuthProfileHandlerTests.this.store.update(eq(OWNER_A), eq("pid-1"), any(AuthProfile.class)))
				.willReturn(true);

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.put()
				.uri(API_BASE + "/pid-1")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"profile\": {\"name\": \"prod\", \"type\": \"BEARER\", \"token\": \"tok-2\"}}"))
				.exchange()
				.expectStatus()
				.isNoContent();
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("update() of an unknown or foreign id returns 404 (existence not leaked)")
		void update_unknownOrForeign_returns404() {
			// given
			given(AuthProfileHandlerTests.this.store.update(eq(OWNER_A), eq("foreign"), any(AuthProfile.class)))
				.willReturn(false);

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.put()
				.uri(API_BASE + "/foreign")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"profile\": {\"name\": \"prod\", \"type\": \"BEARER\", \"token\": \"tok-2\"}}"))
				.exchange()
				.expectStatus()
				.isNotFound();
		}

	}

	@Nested
	@DisplayName("DELETE /auth-profile/{profileId}")
	class Delete {

		@Test
		@Story("Deletion")
		@Severity(SeverityLevel.CRITICAL)
		@Description("DELETE uses the path variable and returns 204 for an owned profile")
		void delete_ownedProfile_returns204() {
			// given
			given(AuthProfileHandlerTests.this.store.delete(OWNER_A, "pid-1")).willReturn(true);

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.delete().uri(API_BASE + "/pid-1")).exchange()
				.expectStatus()
				.isNoContent();
			verify(AuthProfileHandlerTests.this.store).delete(OWNER_A, "pid-1");
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("delete() of an unknown or foreign id returns 404")
		void delete_unknownOrForeign_returns404() {
			// given
			given(AuthProfileHandlerTests.this.store.delete(OWNER_A, "foreign")).willReturn(false);

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.delete().uri(API_BASE + "/foreign")).exchange()
				.expectStatus()
				.isNotFound();
		}

	}

	@Nested
	@DisplayName("POST /auth-profile/{profileId}/exchange")
	class Exchange {

		private static final String EXCHANGE_BODY = "{\"code\": \"auth-code-1\", \"codeVerifier\": \"verifier-1\", "
				+ "\"state\": \"server-state-1\"}";

		@Test
		@Story("Exchange")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() verifies state, exchanges the code and marks the profile ACTIVE (200 {profileId})")
		void exchange_happyPath_returnsProfileId() {
			// given
			final OAuth2Profile pending = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
					"cid", null, null, "https://idp/authorize", "https://app/cb", "ch", "S256");
			given(AuthProfileHandlerTests.this.store.resolvePending(OWNER_A, "pid-ac"))
				.willReturn(Optional.of(pending));
			given(AuthProfileHandlerTests.this.exchanger.verifyAndConsumeState(OWNER_A, "pid-ac", "server-state-1"))
				.willReturn(true);
			given(AuthProfileHandlerTests.this.exchanger.exchange(pending, "auth-code-1", "verifier-1")).willReturn(
					new OAuth2AuthCodeTokenExchanger.TokenHandle("at-1", "rt-1", Instant.now().plusSeconds(60)));
			given(AuthProfileHandlerTests.this.store.markActive(OWNER_A, "pid-ac")).willReturn(true);

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE + "/pid-ac/exchange")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(EXCHANGE_BODY)).exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.profileId")
				.isEqualTo("pid-ac");
			verify(AuthProfileHandlerTests.this.exchanger).storeTokens(eq("pid-ac"),
					any(OAuth2AuthCodeTokenExchanger.TokenHandle.class));
			verify(AuthProfileHandlerTests.this.store).markActive(OWNER_A, "pid-ac");
		}

		@Test
		@Story("State")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() rejects a state mismatch / replay / expired state with 400")
		void exchange_stateMismatch_returns400() {
			// given
			given(AuthProfileHandlerTests.this.store.resolvePending(OWNER_A, "pid-ac"))
				.willReturn(Optional.of(new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
						"cid", null, null, "https://idp/authorize", "https://app/cb", "ch", "S256")));
			given(AuthProfileHandlerTests.this.exchanger.verifyAndConsumeState(OWNER_A, "pid-ac", "server-state-1"))
				.willReturn(false);

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE + "/pid-ac/exchange")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(EXCHANGE_BODY)).exchange()
				.expectStatus()
				.isBadRequest()
				.expectBody()
				.jsonPath("$.error")
				.isEqualTo("state mismatch, expired or already consumed");
			verify(AuthProfileHandlerTests.this.exchanger, never()).exchange(any(OAuth2Profile.class), anyString(),
					anyString());
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() of an unknown or foreign pending id returns 404")
		void exchange_unknownOrForeignPending_returns404() {
			// given
			given(AuthProfileHandlerTests.this.store.resolvePending(OWNER_A, "foreign")).willReturn(Optional.empty());

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE + "/foreign/exchange")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(EXCHANGE_BODY)).exchange().expectStatus().isNotFound();
		}

		@Test
		@Story("PKCE")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() with a PKCE mismatch returns 400 with the exchanger's message")
		void exchange_pkceMismatch_returns400() {
			// given
			final OAuth2Profile pending = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
					"cid", null, null, "https://idp/authorize", "https://app/cb", "ch", "S256");
			given(AuthProfileHandlerTests.this.store.resolvePending(OWNER_A, "pid-ac"))
				.willReturn(Optional.of(pending));
			given(AuthProfileHandlerTests.this.exchanger.verifyAndConsumeState(OWNER_A, "pid-ac", "server-state-1"))
				.willReturn(true);
			given(AuthProfileHandlerTests.this.exchanger.exchange(pending, "auth-code-1", "verifier-1"))
				.willThrow(new IllegalArgumentException(
						"PKCE verification failed: S256(codeVerifier) does not match codeChallenge"));

			// when/then
			withOwner(AuthProfileHandlerTests.this.client.post()
				.uri(API_BASE + "/pid-ac/exchange")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(EXCHANGE_BODY)).exchange()
				.expectStatus()
				.isBadRequest()
				.expectBody()
				.jsonPath("$.error")
				.isEqualTo("PKCE verification failed: S256(codeVerifier) does not match codeChallenge");
			verify(AuthProfileHandlerTests.this.store, never()).markActive(OWNER_A, "pid-ac");
		}

	}

}
