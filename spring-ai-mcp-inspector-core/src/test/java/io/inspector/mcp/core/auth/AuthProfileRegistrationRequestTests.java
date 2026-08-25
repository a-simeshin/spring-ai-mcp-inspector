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

/** Unit tests for {@link AuthProfileRegistrationRequest} — the three wire shapes (D2). */
@Epic("MCP Inspector Core")
@Feature("AuthProfileRegistrationRequest")
class AuthProfileRegistrationRequestTests {

	private static AuthProfileRegistrationRequest inline(final AuthProfile profile) {
		return new AuthProfileRegistrationRequest(profile, null, null, null, null, null, null, null, null, null, null);
	}

	private static AuthProfileRegistrationRequest pending(final String name, final String tokenUrl,
			final String clientId, final String authorizationUrl, final String redirectUri, final String challenge,
			final String challengeMethod) {
		return new AuthProfileRegistrationRequest(null, name, AuthProfileType.OAUTH2,
				OAuth2GrantMode.AUTHORIZATION_CODE, tokenUrl, clientId, "mcp.read", authorizationUrl, redirectUri,
				challenge, challengeMethod);
	}

	private static AuthProfileRegistrationRequest prefill(final String name, final AuthProfileType type) {
		return new AuthProfileRegistrationRequest(null, name, type, null, null, null, null, null, null, null, null);
	}

	@Nested
	@DisplayName("shape detection")
	class ShapeDetection {

		@Test
		@Story("Registration shapes")
		@Severity(SeverityLevel.NORMAL)
		@Description("hasInlineProfile()/isPrefillReference()/isAuthCodePending() classify the three wire shapes")
		void shapes_areMutuallyExclusive() {
			final AuthProfileRegistrationRequest inline = inline(new BearerProfile("prod", "tok"));
			final AuthProfileRegistrationRequest pending = pending("ac", "https://t/token", "cid", "https://t/auth",
					"https://app/cb", "challenge", "S256");
			final AuthProfileRegistrationRequest reference = prefill("spring-prod", AuthProfileType.BEARER);

			assertThat(inline.hasInlineProfile()).isTrue();
			assertThat(inline.isPrefillReference()).isFalse();
			assertThat(inline.isAuthCodePending()).isFalse();

			assertThat(pending.hasInlineProfile()).isFalse();
			assertThat(pending.isPrefillReference()).isFalse();
			assertThat(pending.isAuthCodePending()).isTrue();

			assertThat(reference.hasInlineProfile()).isFalse();
			assertThat(reference.isPrefillReference()).isTrue();
			assertThat(reference.isAuthCodePending()).isFalse();
		}

		@Test
		@Story("Registration shapes")
		@Severity(SeverityLevel.NORMAL)
		@Description("an OAUTH2 reference without grantMode is a prefill reference, not a pending profile")
		void oauth2WithoutGrantMode_isPrefillReference() {
			final AuthProfileRegistrationRequest request = prefill("spring-oauth2", AuthProfileType.OAUTH2);
			assertThat(request.isPrefillReference()).isTrue();
			assertThat(request.isAuthCodePending()).isFalse();
		}

	}

	@Nested
	@DisplayName("validate()")
	class Validate {

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("an inline profile with a name passes validation")
		void validate_inlineProfile_passes() {
			inline(new BearerProfile("prod", "tok")).validate();
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("an inline profile without a name is rejected")
		void validate_inlineProfileBlankName_throws() {
			assertThatThrownBy(() -> inline(new BearerProfile("  ", "tok")).validate())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("profile.name");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a fully populated auth-code pending request passes validation")
		void validate_pendingComplete_passes() {
			pending("ac", "https://t/token", "cid", "https://t/auth", "https://app/cb", "challenge", "S256").validate();
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("each missing OAuth2 wire field of a pending request is rejected")
		void validate_pendingMissingField_throws() {
			assertThatThrownBy(
					() -> pending(null, "https://t/token", "cid", "https://t/auth", "https://app/cb", "ch", "S256")
						.validate())
				.hasMessageContaining("name");
			assertThatThrownBy(
					() -> pending("ac", null, "cid", "https://t/auth", "https://app/cb", "ch", "S256").validate())
				.hasMessageContaining("tokenUrl");
			assertThatThrownBy(
					() -> pending("ac", "https://t/token", null, "https://t/auth", "https://app/cb", "ch", "S256")
						.validate())
				.hasMessageContaining("clientId");
			assertThatThrownBy(
					() -> pending("ac", "https://t/token", "cid", null, "https://app/cb", "ch", "S256").validate())
				.hasMessageContaining("authorizationUrl");
			assertThatThrownBy(
					() -> pending("ac", "https://t/token", "cid", "https://t/auth", null, "ch", "S256").validate())
				.hasMessageContaining("redirectUri");
			assertThatThrownBy(
					() -> pending("ac", "https://t/token", "cid", "https://t/auth", "https://app/cb", null, "S256")
						.validate())
				.hasMessageContaining("codeChallenge");
			assertThatThrownBy(
					() -> pending("ac", "https://t/token", "cid", "https://t/auth", "https://app/cb", "ch", null)
						.validate())
				.hasMessageContaining("codeChallengeMethod");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("a prefill reference with a name passes validation")
		void validate_prefillReference_passes() {
			prefill("spring-prod", AuthProfileType.API_KEY).validate();
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("a prefill reference without a name is rejected")
		void validate_prefillReferenceBlankName_throws() {
			assertThatThrownBy(() -> prefill(" ", AuthProfileType.BEARER).validate())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("name");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a shape-less request (no inline profile, no pending, no reference) is rejected")
		void validate_unknownShape_throws() {
			final AuthProfileRegistrationRequest request = new AuthProfileRegistrationRequest(null, null, null, null,
					null, null, null, null, null, null, null);
			assertThatThrownBy(request::validate).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must carry an inline profile");
		}

	}

	@Nested
	@DisplayName("pendingOAuth2Profile()")
	class PendingOAuth2Profile {

		@Test
		@Story("Pending materialisation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("pendingOAuth2Profile() materialises the wire fields into a PENDING OAuth2Profile with the PKCE challenge")
		void pendingOAuth2Profile_validPending_materialisesProfile() {
			final OAuth2Profile profile = pending("ac", "https://t/token", "cid", "https://t/auth", "https://app/cb",
					"challenge", "S256")
				.pendingOAuth2Profile();
			assertThat(profile.name()).isEqualTo("ac");
			assertThat(profile.grantMode()).isEqualTo(OAuth2GrantMode.AUTHORIZATION_CODE);
			assertThat(profile.tokenUrl()).isEqualTo("https://t/token");
			assertThat(profile.clientId()).isEqualTo("cid");
			assertThat(profile.authorizationUrl()).isEqualTo("https://t/auth");
			assertThat(profile.redirectUri()).isEqualTo("https://app/cb");
			assertThat(profile.codeChallenge()).isEqualTo("challenge");
			assertThat(profile.codeChallengeMethod()).isEqualTo("S256");
			assertThat(profile.scopes()).isEqualTo("mcp.read");
		}

		@Test
		@Story("Pending materialisation")
		@Severity(SeverityLevel.NORMAL)
		@Description("pendingOAuth2Profile() rejects a request that is not an auth-code pending shape")
		void pendingOAuth2Profile_notPending_throws() {
			assertThatThrownBy(() -> prefill("spring-prod", AuthProfileType.OAUTH2).pendingOAuth2Profile())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not an auth-code PENDING profile");
		}

	}

}
