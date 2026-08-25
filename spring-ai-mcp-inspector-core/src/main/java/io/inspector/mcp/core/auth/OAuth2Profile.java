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

/**
 * OAuth2 authentication profile.
 *
 * <p>
 * For {@code CLIENT_CREDENTIALS} profiles the {@code clientSecret} is required and the
 * token lifecycle is executed by the backend
 * ({@link OAuth2ClientCredentialsTokenManager}). For {@code AUTHORIZATION_CODE} profiles
 * the browser drives a two-phase PKCE flow ({@link OAuth2AuthCodeTokenExchanger}): the
 * profile is stored in a PENDING state with the {@code codeChallenge} captured at create
 * time and exchanged later via {@code POST /auth-profile/{profileId}/exchange}.
 *
 * <p>
 * {@code accessToken} / {@code refreshToken} / {@code expiresIn} are BACKEND-owned and
 * never part of the wire model — they are held by the token managers keyed by
 * {@code profileId}.
 *
 * @param name profile display name (required, unique per owner)
 * @param grantMode OAuth2 grant mode (client-credentials or authorization-code)
 * @param tokenUrl token endpoint URL (required)
 * @param clientId OAuth2 client id (required)
 * @param clientSecret OAuth2 client secret; required for {@code CLIENT_CREDENTIALS} only
 * @param scopes optional space-separated scope list
 * @param authorizationUrl authorization endpoint URL (authorization-code only)
 * @param redirectUri registered redirect URI (authorization-code only)
 * @param codeChallenge PKCE S256 code challenge (authorization-code only)
 * @param codeChallengeMethod PKCE code challenge method, typically {@code S256}
 * (authorization-code only)
 * @author Artem Simeshin
 */
public record OAuth2Profile(String name, OAuth2GrantMode grantMode, String tokenUrl, String clientId,
		String clientSecret, String scopes, String authorizationUrl, String redirectUri, String codeChallenge,
		String codeChallengeMethod) implements AuthProfile {

	@Override
	public AuthProfileType type() {
		return AuthProfileType.OAUTH2;
	}

}
