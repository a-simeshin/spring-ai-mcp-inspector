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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AuthHeaders} — per-type profile resolution (D1) + placement
 * (API-key).
 */
@Epic("MCP Inspector Core")
@Feature("AuthHeaders resolution")
class AuthHeadersTests {

	@Nested
	@DisplayName("resolve()")
	class Resolve {

		@Test
		@Story("Bearer profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() turns a bearer profile into an Authorization header with the Bearer scheme")
		void resolve_bearerProfile_setsAuthorizationHeader() {
			// when
			final AuthHeaders headers = AuthHeaders.resolve(new BearerProfile("p", "tok-123"), "pid", null, null);

			// then
			assertThat(headers.authorization()).isEqualTo("Bearer tok-123");
			assertThat(headers.customHeaders()).isEmpty();
			assertThat(headers.queryParams()).isEmpty();
		}

		@Test
		@Story("API-key profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() with HEADER placement puts the key into custom headers, not the query")
		void resolve_apiKeyHeaderPlacement_putsKeyIntoHeaders() {
			// when
			final AuthHeaders headers = AuthHeaders
				.resolve(new ApiKeyProfile("p", "X-API-Key", "secret-key", ApiKeyPlacement.HEADER), "pid", null, null);

			// then
			assertThat(headers.customHeaders()).containsExactly(Map.entry("X-API-Key", "secret-key"));
			assertThat(headers.queryParams()).isEmpty();
			assertThat(headers.authorization()).isNull();
		}

		@Test
		@Story("API-key profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() with QUERY placement puts the key into query params, not the headers")
		void resolve_apiKeyQueryPlacement_putsKeyIntoQueryParams() {
			// when
			final AuthHeaders headers = AuthHeaders
				.resolve(new ApiKeyProfile("p", "api_key", "secret-key", ApiKeyPlacement.QUERY), "pid", null, null);

			// then
			assertThat(headers.queryParams()).containsExactly(Map.entry("api_key", "secret-key"));
			assertThat(headers.customHeaders()).isEmpty();
			assertThat(headers.authorization()).isNull();
		}

		@Test
		@Story("Custom-headers profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("resolve() keeps the ordered custom header list, skipping blank names")
		void resolve_customHeadersProfile_preservesOrderedHeaders() {
			// given
			final CustomHeadersProfile profile = new CustomHeadersProfile("p",
					List.of(new CustomHeader("X-Tenant", "acme"), new CustomHeader("X-Trace", "t-1"),
							new CustomHeader(" ", "skipped"), new CustomHeader("X-Null-Value", null)));

			// when
			final AuthHeaders headers = AuthHeaders.resolve(profile, "pid", null, null);

			// then
			assertThat(headers.customHeaders()).containsExactly(Map.entry("X-Tenant", "acme"),
					Map.entry("X-Trace", "t-1"));
			assertThat(headers.authorization()).isNull();
		}

		@Test
		@Story("OAuth2 profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() pulls the client-credentials token from the token manager")
		void resolve_oauth2ClientCredentials_usesManagerToken() {
			// given
			final OAuth2ClientCredentialsTokenManager manager = mock(OAuth2ClientCredentialsTokenManager.class);
			given(manager.getAccessToken("pid", false)).willReturn(
					new OAuth2ClientCredentialsTokenManager.TokenHandle("cc-token", Instant.now().plusSeconds(60)));

			// when
			final AuthHeaders headers = AuthHeaders.resolve(new OAuth2Profile("p", OAuth2GrantMode.CLIENT_CREDENTIALS,
					"https://t/token", "cid", "sec", null, null, null, null, null), "pid", manager, null);

			// then
			assertThat(headers.authorization()).isEqualTo("Bearer cc-token");
		}

		@Test
		@Story("OAuth2 profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() pulls the authorization-code token from the exchanger's backend store")
		void resolve_oauth2AuthCode_usesExchangerToken() {
			// given
			final OAuth2AuthCodeTokenExchanger exchanger = mock(OAuth2AuthCodeTokenExchanger.class);
			given(exchanger.accessToken("pid")).willReturn(java.util.Optional.of("authcode-token"));

			// when
			final AuthHeaders headers = AuthHeaders.resolve(new OAuth2Profile("p", OAuth2GrantMode.AUTHORIZATION_CODE,
					"https://t/token", "cid", null, null, "https://t/auth", "https://app/cb", "challenge", "S256"),
					"pid", null, exchanger);

			// then
			assertThat(headers.authorization()).isEqualTo("Bearer authcode-token");
		}

		@Test
		@Story("OAuth2 profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("resolve() fails closed when the client-credentials manager is not wired")
		void resolve_oauth2CcWithoutManager_throws() {
			// when/then
			assertThatThrownBy(() -> AuthHeaders.resolve(new OAuth2Profile("p", OAuth2GrantMode.CLIENT_CREDENTIALS,
					"https://t/token", "cid", "sec", null, null, null, null, null), "pid", null, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("OAuth2ClientCredentialsTokenManager is not wired");
		}

		@Test
		@Story("OAuth2 profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("resolve() fails closed when no backend-held auth-code token exists")
		void resolve_oauth2AuthCodeWithoutToken_throws() {
			// given
			final OAuth2AuthCodeTokenExchanger exchanger = mock(OAuth2AuthCodeTokenExchanger.class);
			given(exchanger.accessToken("pid")).willReturn(java.util.Optional.empty());

			// when/then
			assertThatThrownBy(
					() -> AuthHeaders.resolve(
							new OAuth2Profile("p", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token", "cid", null,
									null, "https://t/auth", "https://app/cb", "challenge", "S256"),
							"pid", null, exchanger))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no backend-held token");
		}

		@Test
		@Story("None")
		@Severity(SeverityLevel.MINOR)
		@Description("none() produces an empty headers instance")
		void none_returnsEmptyHeaders() {
			// when
			final AuthHeaders headers = AuthHeaders.none();

			// then
			assertThat(headers.authorization()).isNull();
			assertThat(headers.customHeaders()).isEmpty();
			assertThat(headers.queryParams()).isEmpty();
		}

	}

	@Nested
	@DisplayName("withAuthorization()")
	class WithAuthorization {

		@Test
		@Story("One-retry call site")
		@Severity(SeverityLevel.NORMAL)
		@Description("withAuthorization() replaces only the Authorization value, keeping custom headers and query params")
		void withAuthorization_replacesAuthorizationKeepsRest() {
			// given
			final AuthHeaders headers = new AuthHeaders("Bearer old", Map.of("X-Tenant", "acme"),
					Map.of("api_key", "k"));

			// when
			final AuthHeaders updated = headers.withAuthorization("Bearer new");

			// then
			assertThat(updated.authorization()).isEqualTo("Bearer new");
			assertThat(updated.customHeaders()).containsExactly(Map.entry("X-Tenant", "acme"));
			assertThat(updated.queryParams()).containsExactly(Map.entry("api_key", "k"));
			assertThat(headers.authorization()).isEqualTo("Bearer old");
		}

	}

}
