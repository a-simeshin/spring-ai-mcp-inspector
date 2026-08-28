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

package io.inspector.mcp.webmvc.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.inspector.mcp.core.auth.ApiKeyPlacement;
import io.inspector.mcp.core.auth.AuthProfile;
import io.inspector.mcp.core.auth.AuthProfilePrefillProvider;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.AuthProfileSummary;
import io.inspector.mcp.core.auth.BearerProfile;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;
import io.inspector.mcp.core.proxy.ProxyUpstreamException;
import io.inspector.mcp.webmvc.auth.InspectorSessionAttributes;
import io.inspector.mcp.webmvc.auth.ServletSessionOwnerResolver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link AuthProfileController} — owner-scoped CRUD, prefill and /exchange
 * (D2/D8/D9).
 */
@Epic("WebMvc Inspector")
@Feature("AuthProfileController")
class AuthProfileControllerTests {

	private static final String OWNER_A = "owner-a";

	private static final String API_BASE = "/mcp-inspector/api/auth-profile";

	private MockMvc mockMvc;

	private final AuthProfileStore store = mock(AuthProfileStore.class);

	private final AuthProfilePrefillProvider prefillProvider = mock(AuthProfilePrefillProvider.class);

	private final OAuth2ClientCredentialsTokenManager tokenManager = mock(OAuth2ClientCredentialsTokenManager.class);

	private final OAuth2AuthCodeTokenExchanger exchanger = mock(OAuth2AuthCodeTokenExchanger.class);

	private final ServletSessionOwnerResolver sessionOwnerResolver = mock(ServletSessionOwnerResolver.class);

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders
			.standaloneSetup(new AuthProfileController(this.store, this.prefillProvider, this.tokenManager,
					this.exchanger, this.sessionOwnerResolver))
			.build();
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withOwner(
			final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
		return builder.requestAttr(InspectorSessionAttributes.OWNER_ID, OWNER_A);
	}

	@Nested
	@DisplayName("POST /auth-profile")
	class Register {

		@Test
		@Story("Inline profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() stores an inline bearer profile under the session owner and returns {profileId}")
		void register_inlineProfile_returnsProfileId() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class)))
				.willReturn("pid-1");

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE)).contentType(MediaType.APPLICATION_JSON).content("""
						{"profile": {"name": "prod", "type": "BEARER", "token": "tok-1"}}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profileId").value("pid-1"));
			verify(AuthProfileControllerTests.this.store).register(eq(OWNER_A),
					org.mockito.ArgumentMatchers.<AuthProfile>argThat(
							(profile) -> profile instanceof BearerProfile bearer && bearer.token().equals("tok-1")));
		}

		@Test
		@Story("Prefill reference")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() materializes a prefill {name,type} reference via the prefill provider")
		void register_prefillReference_resolvesAndStores() throws Exception {
			// given
			given(AuthProfileControllerTests.this.prefillProvider.resolve("prod-bearer"))
				.willReturn(Optional.of(new BearerProfile("prod-bearer", "cfg-token")));
			given(AuthProfileControllerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class)))
				.willReturn("pid-2");

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE)).contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\": \"prod-bearer\", \"type\": \"BEARER\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profileId").value("pid-2"));
			verify(AuthProfileControllerTests.this.prefillProvider).resolve("prod-bearer");
		}

		@Test
		@Story("Prefill reference")
		@Severity(SeverityLevel.NORMAL)
		@Description("register() with an unknown prefill name returns 400")
		void register_unknownPrefillName_returns400() throws Exception {
			// given
			given(AuthProfileControllerTests.this.prefillProvider.resolve("nope")).willReturn(Optional.empty());

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE)).contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\": \"nope\", \"type\": \"BEARER\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("unknown prefill profile: nope"));
			verify(AuthProfileControllerTests.this.store, never()).register(anyString(), any(AuthProfile.class));
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("register() with an empty body returns 400")
		void register_invalidBody_returns400() throws Exception {
			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE)).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		}

		@Test
		@Story("Name uniqueness")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() maps a duplicate-name store rejection to 400 (client error, not a server fault)")
		void register_duplicateName_returns400() throws Exception {
			// given — the store rejects a duplicate name within the owner
			given(AuthProfileControllerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class)))
				.willThrow(new IllegalArgumentException("a profile named 'prod' already exists for this session"));

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE)).contentType(MediaType.APPLICATION_JSON)
					.content("{\"profile\": {\"name\": \"prod\", \"type\": \"BEARER\", \"token\": \"tok-1\"}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("a profile named 'prod' already exists for this session"));
		}

		@Test
		@Story("Client-credentials")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() of a CLIENT_CREDENTIALS profile runs the initial token exchange after registration")
		void register_clientCredentials_runsAcquire() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class)))
				.willReturn("pid-cc");

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE)).contentType(MediaType.APPLICATION_JSON).content("""
						{"profile": {"name": "cc", "type": "OAUTH2", "grantMode": "CLIENT_CREDENTIALS",
						 "tokenUrl": "https://t/token", "clientId": "cid", "clientSecret": "sec"}}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profileId").value("pid-cc"));
			verify(AuthProfileControllerTests.this.tokenManager).acquire(eq("pid-cc"), org.mockito.ArgumentMatchers
				.<OAuth2Profile>argThat((profile) -> profile.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS));
		}

		@Test
		@Story("Client-credentials")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a failed initial exchange rolls back the store entry, evicts and returns 502 token_exchange_failed")
		void register_failedAcquire_rollsBackAndReturns502() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class)))
				.willReturn("pid-cc");
			willThrow(new ProxyUpstreamException(400, "OAuth2 client_credentials exchange failed: HTTP 400"))
				.given(AuthProfileControllerTests.this.tokenManager)
				.acquire(eq("pid-cc"), any(OAuth2Profile.class));

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE)).contentType(MediaType.APPLICATION_JSON).content("""
						{"profile": {"name": "cc", "type": "OAUTH2", "grantMode": "CLIENT_CREDENTIALS",
						 "tokenUrl": "https://t/token", "clientId": "cid", "clientSecret": "sec"}}"""))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.status").value(502))
				.andExpect(jsonPath("$.code").value("token_exchange_failed"));
			verify(AuthProfileControllerTests.this.store).delete(OWNER_A, "pid-cc");
			verify(AuthProfileControllerTests.this.tokenManager).evict("pid-cc");
		}

		@Test
		@Story("Auth-code pending (D9B)")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() of an AUTHORIZATION_CODE profile returns the server-issued state and authorizationUrl")
		void register_authCodePending_returnsStateAndAuthorizationUrl() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.register(eq(OWNER_A), any(AuthProfile.class)))
				.willReturn("pid-ac");
			given(AuthProfileControllerTests.this.exchanger.mintState(OWNER_A, "pid-ac")).willReturn("server-state-1");

			// when/then — D9B phase 1: {profileId, state, authorizationUrl}
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE)).contentType(MediaType.APPLICATION_JSON).content("""
						{"name": "ac", "type": "OAUTH2", "grantMode": "AUTHORIZATION_CODE",
						 "tokenUrl": "https://t/token", "clientId": "cid", "scopes": "read",
						 "authorizationUrl": "https://idp/authorize", "redirectUri": "https://app/cb",
						 "codeChallenge": "ch", "codeChallengeMethod": "S256"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profileId").value("pid-ac"))
				.andExpect(jsonPath("$.state").value("server-state-1"))
				.andExpect(jsonPath("$.authorizationUrl").value("https://idp/authorize"));
			verify(AuthProfileControllerTests.this.exchanger).mintState(OWNER_A, "pid-ac");
		}

	}

	@Nested
	@DisplayName("GET /auth-profile")
	class List {

		@Test
		@Story("Listing")
		@Severity(SeverityLevel.CRITICAL)
		@Description("list() returns the OWNER-scoped summaries from the store")
		void list_returnsOwnerScopedSummaries() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.list(OWNER_A)).willReturn(java.util.List
				.of(new AuthProfileSummary("pid-1", "prod", io.inspector.mcp.core.auth.AuthProfileType.BEARER, null,
						null, null, null, null, null, null, null, null)));

			// when/then
			AuthProfileControllerTests.this.mockMvc.perform(withOwner(get(API_BASE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].profileId").value("pid-1"))
				.andExpect(jsonPath("$[0].name").value("prod"));
			verify(AuthProfileControllerTests.this.store).list(OWNER_A);
		}

		@Test
		@Story("Prefill")
		@Severity(SeverityLevel.NORMAL)
		@Description("GET /auth-profile/prefill returns the config summaries (no secrets)")
		void prefill_returnsConfigSummaries() throws Exception {
			// given
			given(AuthProfileControllerTests.this.prefillProvider.list()).willReturn(java.util.List
				.of(new AuthProfileSummary(null, "cfg", io.inspector.mcp.core.auth.AuthProfileType.API_KEY, null, null,
						null, null, null, null, "X-Key", ApiKeyPlacement.HEADER, null)));

			// when/then
			AuthProfileControllerTests.this.mockMvc.perform(get(API_BASE + "/prefill"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("cfg"));
		}

	}

	@Nested
	@DisplayName("PUT /auth-profile/{profileId}")
	class Update {

		@Test
		@Story("Update")
		@Severity(SeverityLevel.CRITICAL)
		@Description("update() returns 204 for an owned profile")
		void update_ownedProfile_returns204() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.update(eq(OWNER_A), eq("pid-1"), any(AuthProfile.class)))
				.willReturn(true);

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(put(API_BASE + "/pid-1")).contentType(MediaType.APPLICATION_JSON)
					.content("{\"profile\": {\"name\": \"prod\", \"type\": \"BEARER\", \"token\": \"tok-2\"}}"))
				.andExpect(status().isNoContent());
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("update() of an unknown or foreign id returns 404 (existence not leaked)")
		void update_unknownOrForeign_returns404() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.update(eq(OWNER_A), eq("foreign"), any(AuthProfile.class)))
				.willReturn(false);

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(put(API_BASE + "/foreign")).contentType(MediaType.APPLICATION_JSON)
					.content("{\"profile\": {\"name\": \"prod\", \"type\": \"BEARER\", \"token\": \"tok-2\"}}"))
				.andExpect(status().isNotFound());
		}

		@Test
		@Story("Name uniqueness")
		@Severity(SeverityLevel.CRITICAL)
		@Description("update() mapping a rename onto an existing name returns 400 (client error, never a silent overwrite)")
		void update_duplicateName_returns400() throws Exception {
			// given — the store rejects renaming onto an existing profile name
			given(AuthProfileControllerTests.this.store.update(eq(OWNER_A), eq("pid-1"), any(AuthProfile.class)))
				.willThrow(new IllegalArgumentException("a profile named 'prod' already exists for this session"));

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(put(API_BASE + "/pid-1")).contentType(MediaType.APPLICATION_JSON)
					.content("{\"profile\": {\"name\": \"prod\", \"type\": \"BEARER\", \"token\": \"tok-2\"}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("a profile named 'prod' already exists for this session"));
		}

	}

	@Nested
	@DisplayName("DELETE /auth-profile/{profileId}")
	class Delete {

		@Test
		@Story("Deletion")
		@Severity(SeverityLevel.CRITICAL)
		@Description("DELETE uses the path variable and returns 204 for an owned profile")
		void delete_ownedProfile_returns204() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.delete(OWNER_A, "pid-1")).willReturn(true);

			// when/then
			AuthProfileControllerTests.this.mockMvc.perform(withOwner(delete(API_BASE + "/pid-1")))
				.andExpect(status().isNoContent());
			verify(AuthProfileControllerTests.this.store).delete(OWNER_A, "pid-1");
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("delete() of an unknown or foreign id returns 404")
		void delete_unknownOrForeign_returns404() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.delete(OWNER_A, "foreign")).willReturn(false);

			// when/then
			AuthProfileControllerTests.this.mockMvc.perform(withOwner(delete(API_BASE + "/foreign")))
				.andExpect(status().isNotFound());
		}

	}

	@Nested
	@DisplayName("POST /auth-profile/{profileId}/exchange")
	class Exchange {

		private static final String EXCHANGE_BODY = """
				{"code": "auth-code-1", "codeVerifier": "verifier-1", "state": "server-state-1"}""";

		@Test
		@Story("Exchange")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() verifies state, exchanges the code and marks the profile ACTIVE (200 {profileId})")
		void exchange_happyPath_returnsProfileId() throws Exception {
			// given
			final OAuth2Profile pending = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
					"cid", null, null, "https://idp/authorize", "https://app/cb", "ch", "S256");
			given(AuthProfileControllerTests.this.store.resolvePending(OWNER_A, "pid-ac"))
				.willReturn(Optional.of(pending));
			given(AuthProfileControllerTests.this.exchanger.verifyAndConsumeState(OWNER_A, "pid-ac", "server-state-1"))
				.willReturn(true);
			given(AuthProfileControllerTests.this.exchanger.exchange(pending, "auth-code-1", "verifier-1")).willReturn(
					new OAuth2AuthCodeTokenExchanger.TokenHandle("at-1", "rt-1", Instant.now().plusSeconds(60)));
			given(AuthProfileControllerTests.this.store.markActive(OWNER_A, "pid-ac")).willReturn(true);

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE + "/pid-ac/exchange")).contentType(MediaType.APPLICATION_JSON)
					.content(EXCHANGE_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profileId").value("pid-ac"));
			verify(AuthProfileControllerTests.this.exchanger).storeTokens(eq("pid-ac"),
					any(OAuth2AuthCodeTokenExchanger.TokenHandle.class));
			verify(AuthProfileControllerTests.this.store).markActive(OWNER_A, "pid-ac");
		}

		@Test
		@Story("State")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() rejects a state mismatch / replay / expired state with 400")
		void exchange_stateMismatch_returns400() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.resolvePending(OWNER_A, "pid-ac"))
				.willReturn(Optional.of(new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
						"cid", null, null, "https://idp/authorize", "https://app/cb", "ch", "S256")));
			given(AuthProfileControllerTests.this.exchanger.verifyAndConsumeState(OWNER_A, "pid-ac", "server-state-1"))
				.willReturn(false);

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE + "/pid-ac/exchange")).contentType(MediaType.APPLICATION_JSON)
					.content(EXCHANGE_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("state mismatch, expired or already consumed"));
			verify(AuthProfileControllerTests.this.exchanger, never()).exchange(any(OAuth2Profile.class), anyString(),
					anyString());
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() of an unknown or foreign pending id returns 404")
		void exchange_unknownOrForeignPending_returns404() throws Exception {
			// given
			given(AuthProfileControllerTests.this.store.resolvePending(OWNER_A, "foreign"))
				.willReturn(Optional.empty());

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE + "/foreign/exchange")).contentType(MediaType.APPLICATION_JSON)
					.content(EXCHANGE_BODY))
				.andExpect(status().isNotFound());
			verify(AuthProfileControllerTests.this.exchanger, never()).verifyAndConsumeState(anyString(), anyString(),
					anyString());
		}

		@Test
		@Story("PKCE")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchange() with a PKCE mismatch returns 400 with the exchanger's message")
		void exchange_pkceMismatch_returns400() throws Exception {
			// given
			final OAuth2Profile pending = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
					"cid", null, null, "https://idp/authorize", "https://app/cb", "ch", "S256");
			given(AuthProfileControllerTests.this.store.resolvePending(OWNER_A, "pid-ac"))
				.willReturn(Optional.of(pending));
			given(AuthProfileControllerTests.this.exchanger.verifyAndConsumeState(OWNER_A, "pid-ac", "server-state-1"))
				.willReturn(true);
			given(AuthProfileControllerTests.this.exchanger.exchange(pending, "auth-code-1", "verifier-1"))
				.willThrow(new IllegalArgumentException(
						"PKCE verification failed: S256(codeVerifier) does not match codeChallenge"));

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE + "/pid-ac/exchange")).contentType(MediaType.APPLICATION_JSON)
					.content(EXCHANGE_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error")
					.value("PKCE verification failed: S256(codeVerifier) does not match codeChallenge"));
			verify(AuthProfileControllerTests.this.store, never()).markActive(OWNER_A, "pid-ac");
		}

		@Test
		@Story("Upstream failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchange() with a failing token endpoint returns 502 token_exchange_failed")
		void exchange_upstreamFailure_returns502() throws Exception {
			// given
			final OAuth2Profile pending = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
					"cid", null, null, "https://idp/authorize", "https://app/cb", "ch", "S256");
			given(AuthProfileControllerTests.this.store.resolvePending(OWNER_A, "pid-ac"))
				.willReturn(Optional.of(pending));
			given(AuthProfileControllerTests.this.exchanger.verifyAndConsumeState(OWNER_A, "pid-ac", "server-state-1"))
				.willReturn(true);
			given(AuthProfileControllerTests.this.exchanger.exchange(pending, "auth-code-1", "verifier-1"))
				.willThrow(new ProxyUpstreamException(400, "OAuth2 authorization_code exchange failed: HTTP 400"));

			// when/then
			AuthProfileControllerTests.this.mockMvc
				.perform(withOwner(post(API_BASE + "/pid-ac/exchange")).contentType(MediaType.APPLICATION_JSON)
					.content(EXCHANGE_BODY))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("token_exchange_failed"));
		}

	}

}
