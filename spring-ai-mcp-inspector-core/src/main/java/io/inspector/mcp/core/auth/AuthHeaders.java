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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.util.Assert;

/**
 * Resolved outbound auth of an {@link AuthProfile}, ready for the proxy transport: an
 * {@code Authorization} header value, ordered custom headers and query parameters
 * (API-key {@code QUERY} placement).
 *
 * @param authorization the {@code Authorization} header value (may be {@code null})
 * @param customHeaders extra headers to forward (never {@code null})
 * @param queryParams query parameters appended to the target URI (never {@code null})
 * @author Artem Simeshin
 */
public record AuthHeaders(String authorization, Map<String, String> customHeaders, Map<String, String> queryParams) {

	/** Empty headers — no auth to apply. */
	public static AuthHeaders none() {
		return new AuthHeaders(null, Map.of(), Map.of());
	}

	/**
	 * Resolves the profile into transport headers. OAuth2 profiles pull the current
	 * backend-held token from the corresponding manager ({@code CLIENT_CREDENTIALS} →
	 * {@link OAuth2ClientCredentialsTokenManager}, {@code AUTHORIZATION_CODE} →
	 * {@link OAuth2AuthCodeTokenExchanger}).
	 * @param profile the active profile (never {@code null})
	 * @param profileId the profile id used for token lookups
	 * @param tokenManager the client-credentials token manager (may be {@code null} in
	 * unwired contexts; required for CC profiles)
	 * @param exchanger the auth-code exchanger (may be {@code null} in unwired contexts;
	 * required for auth-code profiles)
	 * @return the resolved headers
	 */
	public static AuthHeaders resolve(final AuthProfile profile, final String profileId,
			final OAuth2ClientCredentialsTokenManager tokenManager, final OAuth2AuthCodeTokenExchanger exchanger) {
		Assert.notNull(profile, "profile must not be null");
		if (profile instanceof BearerProfile bearer) {
			return new AuthHeaders("Bearer " + bearer.token(), Map.of(), Map.of());
		}
		if (profile instanceof ApiKeyProfile apiKey) {
			return (apiKey.placement() == ApiKeyPlacement.HEADER)
					? new AuthHeaders(null, Map.of(apiKey.keyName(), apiKey.keyValue()), Map.of())
					: new AuthHeaders(null, Map.of(), Map.of(apiKey.keyName(), apiKey.keyValue()));
		}
		if (profile instanceof CustomHeadersProfile custom) {
			return new AuthHeaders(null, orderedHeaders(custom.headers()), Map.of());
		}
		final OAuth2Profile oauth2 = (OAuth2Profile) profile;
		return new AuthHeaders("Bearer " + oauth2Token(oauth2, profileId, tokenManager, exchanger), Map.of(), Map.of());
	}

	/**
	 * Returns a copy with the {@code Authorization} value replaced (used by the one-retry
	 * call site after a token refresh).
	 * @param newAuthorization the new header value (may be {@code null})
	 * @return the updated headers
	 */
	public AuthHeaders withAuthorization(final String newAuthorization) {
		return new AuthHeaders(newAuthorization, this.customHeaders, this.queryParams);
	}

	private static String oauth2Token(final OAuth2Profile profile, final String profileId,
			final OAuth2ClientCredentialsTokenManager tokenManager, final OAuth2AuthCodeTokenExchanger exchanger) {
		if (profile.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS) {
			Assert.state(tokenManager != null, "OAuth2ClientCredentialsTokenManager is not wired");
			return tokenManager.getAccessToken(profileId, false).accessToken();
		}
		Assert.state(exchanger != null, "OAuth2AuthCodeTokenExchanger is not wired");
		return exchanger.accessToken(profileId)
			.orElseThrow(() -> new IllegalStateException("no backend-held token for auth-code profile " + profileId));
	}

	private static Map<String, String> orderedHeaders(final List<CustomHeader> headers) {
		final Map<String, String> out = new LinkedHashMap<>();
		if (headers != null) {
			headers.forEach((header) -> {
				if (header.name() != null && !header.name().isBlank() && header.value() != null) {
					out.put(header.name(), header.value());
				}
			});
		}
		return out;
	}

}
