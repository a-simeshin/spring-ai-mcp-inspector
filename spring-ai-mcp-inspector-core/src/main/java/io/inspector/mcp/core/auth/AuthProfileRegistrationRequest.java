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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.util.Assert;

/**
 * Payload of {@code POST /mcp-inspector/api/auth-profile}. Exactly one of three shapes is
 * accepted:
 *
 * <ul>
 * <li><b>inline profile</b> — {@code profile} carries the full {@link AuthProfile} (all
 * secret fields included);</li>
 * <li><b>prefill reference</b> — {@code name} + {@code type} only; the backend
 * materialises the profile from Spring configuration
 * ({@link AuthProfilePrefillProvider});</li>
 * <li><b>auth-code PENDING</b> — {@code name}, {@code type: OAUTH2},
 * {@code grantMode: AUTHORIZATION_CODE} plus the OAuth2 wire fields; the backend stores a
 * PENDING profile and returns the server-issued {@code state}.</li>
 * </ul>
 *
 * <p>
 * No client-supplied session/owner id is accepted — ownership always comes from the
 * signed session cookie (see {@link OwnerTokenCodec}).
 *
 * @param profile inline profile (mutually exclusive with the reference and pending
 * shapes)
 * @param name profile name for the prefill reference / pending shapes
 * @param type profile type for the prefill reference / pending shapes
 * @param grantMode OAuth2 grant mode (pending shape)
 * @param tokenUrl OAuth2 token endpoint URL (pending shape)
 * @param clientId OAuth2 client id (pending shape)
 * @param scopes optional space-separated scope list (pending shape)
 * @param authorizationUrl OAuth2 authorization endpoint URL (pending shape)
 * @param redirectUri registered redirect URI (pending shape)
 * @param codeChallenge PKCE S256 code challenge (pending shape)
 * @param codeChallengeMethod PKCE code challenge method, {@code S256} (pending shape)
 * @author Artem Simeshin
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthProfileRegistrationRequest(AuthProfile profile, String name, AuthProfileType type,
		OAuth2GrantMode grantMode, String tokenUrl, String clientId, String scopes, String authorizationUrl,
		String redirectUri, String codeChallenge, String codeChallengeMethod) {

	/** Whether the request carries an inline {@link #profile()}. */
	public boolean hasInlineProfile() {
		return this.profile != null;
	}

	/** Whether the request is a prefill reference ({@code name} + {@code type}). */
	public boolean isPrefillReference() {
		return this.profile == null && this.type != null && this.grantMode == null;
	}

	/** Whether the request is an auth-code PENDING profile creation. */
	public boolean isAuthCodePending() {
		return this.profile == null && this.type == AuthProfileType.OAUTH2
				&& this.grantMode == OAuth2GrantMode.AUTHORIZATION_CODE;
	}

	/**
	 * Validates that exactly one of the three shapes is present and internally
	 * consistent. Throws {@link IllegalArgumentException} otherwise (mapped to
	 * {@code 400} by the callers).
	 */
	public void validate() {
		if (this.profile != null) {
			Assert.hasText(this.profile.name(), "profile.name must not be blank");
			validateInline(this.profile);
			return;
		}
		if (isAuthCodePending()) {
			Assert.hasText(this.name, "name must not be blank");
			Assert.hasText(this.tokenUrl, "tokenUrl must not be blank");
			Assert.hasText(this.clientId, "clientId must not be blank");
			Assert.hasText(this.authorizationUrl, "authorizationUrl must not be blank");
			Assert.hasText(this.redirectUri, "redirectUri must not be blank");
			Assert.hasText(this.codeChallenge, "codeChallenge must not be blank");
			Assert.hasText(this.codeChallengeMethod, "codeChallengeMethod must not be blank");
			Assert.isTrue("S256".equals(this.codeChallengeMethod), "codeChallengeMethod must be exactly S256");
			return;
		}
		if (isPrefillReference()) {
			Assert.hasText(this.name, "name must not be blank");
			return;
		}
		throw new IllegalArgumentException(
				"auth-profile registration must carry an inline profile, a prefill {name,type} reference, "
						+ "or an auth-code PENDING profile");
	}

	/**
	 * Validates the subtype-specific secret fields of an inline profile: every field the
	 * concrete {@link AuthProfile} kind requires must be present and non-blank, and an
	 * authorization-code OAuth2 profile must use exactly {@code S256} PKCE.
	 * @param profile the inline profile
	 */
	private static void validateInline(final AuthProfile profile) {
		if (profile instanceof BearerProfile bearer) {
			Assert.hasText(bearer.token(), "profile.token must not be blank");
		}
		else if (profile instanceof ApiKeyProfile apiKey) {
			Assert.hasText(apiKey.keyName(), "profile.keyName must not be blank");
			Assert.hasText(apiKey.keyValue(), "profile.keyValue must not be blank");
			Assert.notNull(apiKey.placement(), "profile.placement must not be null");
		}
		else if (profile instanceof OAuth2Profile oauth2) {
			Assert.hasText(oauth2.tokenUrl(), "profile.tokenUrl must not be blank");
			Assert.hasText(oauth2.clientId(), "profile.clientId must not be blank");
			if (oauth2.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS) {
				Assert.hasText(oauth2.clientSecret(), "profile.clientSecret must not be blank");
			}
			else if (oauth2.grantMode() == OAuth2GrantMode.AUTHORIZATION_CODE) {
				Assert.hasText(oauth2.authorizationUrl(), "profile.authorizationUrl must not be blank");
				Assert.hasText(oauth2.redirectUri(), "profile.redirectUri must not be blank");
				Assert.hasText(oauth2.codeChallenge(), "profile.codeChallenge must not be blank");
				Assert.hasText(oauth2.codeChallengeMethod(), "profile.codeChallengeMethod must not be blank");
				Assert.isTrue("S256".equals(oauth2.codeChallengeMethod()),
						"profile.codeChallengeMethod must be exactly S256");
			}
			else {
				throw new IllegalArgumentException(
						"profile.grantMode must be CLIENT_CREDENTIALS or AUTHORIZATION_CODE");
			}
		}
		else if (profile instanceof CustomHeadersProfile custom) {
			Assert.notEmpty(custom.headers(), "profile.headers must not be empty");
			custom.headers().forEach((header) -> {
				Assert.hasText(header.name(), "profile.headers[].name must not be blank");
				Assert.hasText(header.value(), "profile.headers[].value must not be blank");
			});
		}
	}

	/**
	 * Materialises the PENDING {@link OAuth2Profile} from the wire fields.
	 * @return the pending OAuth2 profile with the PKCE challenge captured
	 */
	public OAuth2Profile pendingOAuth2Profile() {
		validate();
		if (!isAuthCodePending()) {
			throw new IllegalArgumentException("request is not an auth-code PENDING profile");
		}
		return new OAuth2Profile(this.name, OAuth2GrantMode.AUTHORIZATION_CODE, this.tokenUrl, this.clientId, null,
				this.scopes, this.authorizationUrl, this.redirectUri, this.codeChallenge, this.codeChallengeMethod);
	}

}
