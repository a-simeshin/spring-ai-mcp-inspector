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

package io.inspector.mcp.core.oauth;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link InspectorOAuthClient}. */
@Epic("MCP Inspector Core")
@Feature("OAuth authorization-code client")
class InspectorOAuthClientTests {

	private final HttpClient httpClient = mock(HttpClient.class);

	private final InspectorOAuthClient client = new InspectorOAuthClient(this.httpClient, new JsonMapper());

	@SuppressWarnings("unchecked")
	private static HttpResponse<String> response(final int status, final String body) {
		final HttpResponse<String> response = mock(HttpResponse.class);
		given(response.statusCode()).willReturn(status);
		given(response.body()).willReturn(body);
		return response;
	}

	private void stubSend(final HttpResponse<String> response) throws Exception {
		willReturn(response).given(this.httpClient).send(any(HttpRequest.class), any());
	}

	@Nested
	@DisplayName("buildAuthUrl()")
	class BuildAuthUrl {

		@Test
		@Story("URL construction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("buildAuthUrl() appends the standard authorization-code query parameters")
		void buildAuthUrl_withRequiredParams_buildsStandardQuery() {
			// when
			final String url = InspectorOAuthClientTests.this.client.buildAuthUrl("https://idp.example/authorize",
					"client-1", "https://app/callback", null, "state-xyz", null);

			// then
			assertThat(url).startsWith("https://idp.example/authorize?")
				.contains("response_type=code")
				.contains("client_id=client-1")
				.contains("state=state-xyz")
				.contains("redirect_uri=https%3A%2F%2Fapp%2Fcallback");
			assertThat(url).doesNotContain("scope=").doesNotContain("code_challenge");
		}

		@Test
		@Story("PKCE and scope")
		@Severity(SeverityLevel.NORMAL)
		@Description("buildAuthUrl() includes scope and PKCE challenge when supplied")
		void buildAuthUrl_withScopeAndPkce_includesChallengeAndScope() {
			// when
			final String url = InspectorOAuthClientTests.this.client.buildAuthUrl("https://idp.example/authorize",
					"client-1", "https://app/callback", "openid profile", "state-xyz", "challenge-123");

			// then
			assertThat(url).contains("scope=openid+profile")
				.contains("code_challenge=challenge-123")
				.contains("code_challenge_method=S256");
		}

		@Test
		@Story("Existing query string")
		@Severity(SeverityLevel.NORMAL)
		@Description("buildAuthUrl() uses '&' as the separator when the endpoint already carries a query string")
		void buildAuthUrl_whenEndpointHasQuery_usesAmpersandSeparator() {
			// when
			final String url = InspectorOAuthClientTests.this.client.buildAuthUrl(
					"https://idp.example/authorize?foo=bar", "client-1", "https://app/callback", null, "state-xyz",
					null);

			// then
			assertThat(url).startsWith("https://idp.example/authorize?foo=bar&");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("buildAuthUrl() rejects a blank authorization endpoint")
		void buildAuthUrl_withBlankEndpoint_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> InspectorOAuthClientTests.this.client.buildAuthUrl("  ", "client-1",
					"https://app/callback", null, "state", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("authEndpoint");
		}

	}

	@Nested
	@DisplayName("exchangeCode()")
	class ExchangeCode {

		@Test
		@Story("Successful exchange")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchangeCode() posts a form-encoded request and maps a 2xx JSON body to OAuthTokenResponse")
		void exchangeCode_mapsSuccessResponse() throws Exception {
			// given
			final String body = "{\"access_token\":\"tok-abc\",\"token_type\":\"Bearer\","
					+ "\"expires_in\":3600,\"refresh_token\":\"refresh-xyz\",\"scope\":\"openid\"}";
			stubSend(response(200, body));

			// when
			final OAuthTokenResponse token = InspectorOAuthClientTests.this.client.exchangeCode(
					"https://idp.example/token", "client-1", "the-code", "https://app/callback", "verifier-1");

			// then
			assertThat(token.accessToken()).isEqualTo("tok-abc");
			assertThat(token.tokenType()).isEqualTo("Bearer");
			assertThat(token.expiresIn()).isEqualTo(3600L);
			assertThat(token.refreshToken()).isEqualTo("refresh-xyz");
			assertThat(token.scope()).isEqualTo("openid");
		}

		@Test
		@Story("Request construction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchangeCode() targets the token endpoint, sets form headers and form-encodes the grant body")
		void exchangeCode_buildsFormEncodedPostToTokenEndpoint() throws Exception {
			// given
			stubSend(response(200, "{\"access_token\":\"t\"}"));
			final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);

			// when
			InspectorOAuthClientTests.this.client.exchangeCode("https://idp.example/token", "client-1", "the-code",
					"https://app/callback", "verifier-1");

			// then
			verify(InspectorOAuthClientTests.this.httpClient).send(captor.capture(), any());
			final HttpRequest request = captor.getValue();
			assertThat(request.uri().toString()).isEqualTo("https://idp.example/token");
			assertThat(request.method()).isEqualTo("POST");
			assertThat(request.headers().firstValue("Content-Type")).contains("application/x-www-form-urlencoded");
			assertThat(request.headers().firstValue("Accept")).contains("application/json");
		}

		@Test
		@Story("HTTP error mapping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("exchangeCode() maps a non-2xx response to an IOException carrying status and body")
		void exchangeCode_whenNon2xx_throwsIoExceptionWithStatusAndBody() throws Exception {
			// given
			stubSend(response(400, "invalid_grant"));

			// when & then
			assertThatThrownBy(() -> InspectorOAuthClientTests.this.client.exchangeCode("https://idp.example/token",
					"client-1", "bad-code", "https://app/callback", null))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("400")
				.hasMessageContaining("invalid_grant");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("exchangeCode() rejects a blank authorization code before contacting the IdP")
		void exchangeCode_withBlankCode_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> InspectorOAuthClientTests.this.client.exchangeCode("https://idp.example/token",
					"client-1", "  ", "https://app/callback", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("code");
		}

	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@Story("Null collaborators fallback")
		@Severity(SeverityLevel.MINOR)
		@Description("null HttpClient/JsonMapper fall back to defaults and the client still builds URLs")
		void constructor_withNulls_buildsUsableClient() {
			// given
			final InspectorOAuthClient defaulted = new InspectorOAuthClient(null, null);

			// when
			final String url = defaulted.buildAuthUrl("https://idp/authorize", "c", "https://app/cb", null, "s", null);

			// then
			assertThat(url).contains("client_id=c");
			assertThat(Optional.of(defaulted)).isPresent();
		}

	}

}
