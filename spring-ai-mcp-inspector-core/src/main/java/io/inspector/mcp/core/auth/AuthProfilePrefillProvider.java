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
import java.util.Optional;

import org.springframework.util.Assert;

/**
 * Materialises the Spring-config prefill profiles declared under
 * {@code spring.ai.mcp.inspector.auth-profiles} (D7).
 *
 * <p>
 * {@link #list()} returns secret-free summaries for {@code GET /auth-profile/prefill};
 * {@link #resolve(String)} materialises the FULL profile (WITH secrets) when the UI posts
 * a {@code {name, type}} prefill reference. Secrets move only config → owner-scoped
 * store, never into a list response or log.
 *
 * @author Artem Simeshin
 */
public class AuthProfilePrefillProvider {

	/** Bound prefill configuration. */
	private final AuthProfileProperties properties;

	public AuthProfilePrefillProvider(final AuthProfileProperties properties) {
		Assert.notNull(properties, "properties must not be null");
		this.properties = properties;
	}

	/**
	 * Lists all declared prefill profiles as secret-free summaries.
	 * @return the summaries (never {@code null})
	 */
	public List<AuthProfileSummary> list() {
		return this.properties.getProfiles()
			.stream()
			.map((config) -> AuthProfileSummary.from(null, materialize(config)))
			.toList();
	}

	/**
	 * Materialises the full profile (WITH secrets) for {@code name}.
	 * @param name the declared profile name
	 * @return the full profile, or empty when unknown
	 */
	public Optional<AuthProfile> resolve(final String name) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		return this.properties.getProfiles()
			.stream()
			.filter((config) -> name.equals(config.getName()))
			.findFirst()
			.map(AuthProfilePrefillProvider::materialize);
	}

	/**
	 * Builds the typed profile from a config entry, validating required fields.
	 * @param config the declared entry
	 * @return the materialized profile
	 */
	private static AuthProfile materialize(final AuthProfileProperties.AuthProfileConfig config) {
		Assert.hasText(config.getName(), "auth-profile name must not be blank");
		Assert.notNull(config.getType(), "auth-profile type must not be null for '" + config.getName() + "'");
		final String name = config.getName();
		if (config.getType() == AuthProfileType.BEARER) {
			Assert.hasText(config.getBearer().getToken(), "bearer.token must not be blank for '" + name + "'");
			return new BearerProfile(name, config.getBearer().getToken());
		}
		if (config.getType() == AuthProfileType.API_KEY) {
			final AuthProfileProperties.ApiKeyConfig apiKey = config.getApiKey();
			Assert.hasText(apiKey.getName(), "api-key.name must not be blank for '" + name + "'");
			Assert.hasText(apiKey.getValue(), "api-key.value must not be blank for '" + name + "'");
			return new ApiKeyProfile(name, apiKey.getName(), apiKey.getValue(), apiKey.getPlacement());
		}
		if (config.getType() == AuthProfileType.OAUTH2) {
			final AuthProfileProperties.OAuth2Config oauth2 = config.getOauth2();
			Assert.hasText(oauth2.getTokenUrl(), "oauth2.tokenUrl must not be blank for '" + name + "'");
			Assert.hasText(oauth2.getClientId(), "oauth2.clientId must not be blank for '" + name + "'");
			final boolean cc = oauth2.getGrantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS;
			if (cc) {
				Assert.hasText(oauth2.getClientSecret(),
						"oauth2.clientSecret is required for CLIENT_CREDENTIALS profile '" + name + "'");
			}
			return new OAuth2Profile(name, oauth2.getGrantMode(), oauth2.getTokenUrl(), oauth2.getClientId(),
					oauth2.getClientSecret(), oauth2.getScopes(), null, null, null, null);
		}
		final List<CustomHeader> headers = config.getCustomHeaders().getHeaders().stream().map((header) -> {
			Assert.hasText(header.getName(), "custom-headers header name must not be blank for '" + name + "'");
			return new CustomHeader(header.getName(), header.getValue());
		}).toList();
		return new CustomHeadersProfile(name, headers);
	}

}
