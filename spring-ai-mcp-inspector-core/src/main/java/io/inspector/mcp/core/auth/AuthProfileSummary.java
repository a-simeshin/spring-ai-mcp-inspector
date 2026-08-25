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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Non-secret projection of an {@link AuthProfile} returned by
 * {@code GET /mcp-inspector/api/auth-profile} and
 * {@code GET /mcp-inspector/api/auth-profile/prefill}.
 *
 * <p>
 * Secret values are always omitted: bearer tokens, API-key values, OAuth2 client secrets
 * and custom-header values never appear in a summary. Only the fields needed to render
 * the profile in the UI are present, with per-type fields {@code null} for other profile
 * kinds.
 *
 * @param profileId server-issued opaque profile id ({@code null} for prefill summaries)
 * @param name profile name
 * @param type profile kind
 * @param grantMode OAuth2 grant mode (OAuth2 only)
 * @param tokenUrl OAuth2 token endpoint URL (OAuth2 only)
 * @param clientId OAuth2 client id — not a secret (OAuth2 only)
 * @param scopes optional space-separated scopes (OAuth2 only)
 * @param authorizationUrl OAuth2 authorization endpoint URL (OAuth2 authorization-code
 * only)
 * @param redirectUri registered redirect URI (OAuth2 authorization-code only)
 * @param keyName API-key header / query parameter name (API-key only)
 * @param placement API-key placement (API-key only)
 * @param headerNames custom header names in order (custom-headers only)
 * @author Artem Simeshin
 */
@JsonInclude(Include.NON_NULL)
public record AuthProfileSummary(String profileId, String name, AuthProfileType type, OAuth2GrantMode grantMode,
		String tokenUrl, String clientId, String scopes, String authorizationUrl, String redirectUri, String keyName,
		ApiKeyPlacement placement, List<String> headerNames) {

	/**
	 * Builds the secret-free summary of {@code profile}. {@code profileId} is
	 * {@code null} for prefill summaries (config entries have no store id yet).
	 * @param profileId the store id, or {@code null}
	 * @param profile the profile to project
	 * @return the summary with all secret values omitted
	 */
	public static AuthProfileSummary from(final String profileId, final AuthProfile profile) {
		if (profile instanceof OAuth2Profile oauth2) {
			return new AuthProfileSummary(profileId, oauth2.name(), AuthProfileType.OAUTH2, oauth2.grantMode(),
					oauth2.tokenUrl(), oauth2.clientId(), oauth2.scopes(), oauth2.authorizationUrl(),
					oauth2.redirectUri(), null, null, null);
		}
		if (profile instanceof BearerProfile bearer) {
			return new AuthProfileSummary(profileId, bearer.name(), AuthProfileType.BEARER, null, null, null, null,
					null, null, null, null, null);
		}
		if (profile instanceof ApiKeyProfile apiKey) {
			return new AuthProfileSummary(profileId, apiKey.name(), AuthProfileType.API_KEY, null, null, null, null,
					null, null, apiKey.keyName(), apiKey.placement(), null);
		}
		final CustomHeadersProfile custom = (CustomHeadersProfile) profile;
		return new AuthProfileSummary(profileId, custom.name(), AuthProfileType.CUSTOM_HEADERS, null, null, null, null,
				null, null, null, null, custom.headers().stream().map(CustomHeader::name).toList());
	}

}
