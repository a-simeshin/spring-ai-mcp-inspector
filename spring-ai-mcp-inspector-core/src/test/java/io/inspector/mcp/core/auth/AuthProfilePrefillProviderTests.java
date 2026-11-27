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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AuthProfilePrefillProvider} (D7) — config materialization with
 * secrets excluded from lists.
 */
@Epic("MCP Inspector Core")
@Feature("AuthProfilePrefillProvider (D7 prefill)")
class AuthProfilePrefillProviderTests {

	private AuthProfilePrefillProvider provider;

	@BeforeEach
	void setUp() {
		final AuthProfileProperties properties = new AuthProfileProperties();

		final AuthProfileProperties.AuthProfileConfig bearer = new AuthProfileProperties.AuthProfileConfig();
		bearer.setName("prod-bearer");
		bearer.setType(AuthProfileType.BEARER);
		bearer.getBearer().setToken("super-secret-bearer-token");

		final AuthProfileProperties.AuthProfileConfig apiKey = new AuthProfileProperties.AuthProfileConfig();
		apiKey.setName("prod-api");
		apiKey.setType(AuthProfileType.API_KEY);
		apiKey.getApiKey().setName("X-API-Key");
		apiKey.getApiKey().setValue("super-secret-api-value");
		apiKey.getApiKey().setPlacement(ApiKeyPlacement.QUERY);

		final AuthProfileProperties.AuthProfileConfig oauth2 = new AuthProfileProperties.AuthProfileConfig();
		oauth2.setName("prod-oauth");
		oauth2.setType(AuthProfileType.OAUTH2);
		oauth2.getOauth2().setGrantMode(OAuth2GrantMode.CLIENT_CREDENTIALS);
		oauth2.getOauth2().setTokenUrl("https://auth.example.com/token");
		oauth2.getOauth2().setClientId("inspector");
		oauth2.getOauth2().setClientSecret("super-secret-client-secret");
		oauth2.getOauth2().setScopes("mcp.read");

		properties.setProfiles(List.of(bearer, apiKey, oauth2));
		this.provider = new AuthProfilePrefillProvider(properties);
	}

	@Nested
	@DisplayName("resolve()")
	class Resolve {

		@Test
		@Story("Prefill resolution")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() materializes the FULL profile WITH secrets for a declared name")
		void resolve_knownName_materializesWithSecrets() {
			// when
			final Optional<AuthProfile> bearer = AuthProfilePrefillProviderTests.this.provider.resolve("prod-bearer");
			final Optional<AuthProfile> apiKey = AuthProfilePrefillProviderTests.this.provider.resolve("prod-api");
			final Optional<AuthProfile> oauth2 = AuthProfilePrefillProviderTests.this.provider.resolve("prod-oauth");

			// then
			assertThat(bearer).contains(new BearerProfile("prod-bearer", "super-secret-bearer-token"));
			assertThat(apiKey)
				.contains(new ApiKeyProfile("prod-api", "X-API-Key", "super-secret-api-value", ApiKeyPlacement.QUERY));
			assertThat(oauth2).contains(new OAuth2Profile("prod-oauth", OAuth2GrantMode.CLIENT_CREDENTIALS,
					"https://auth.example.com/token", "inspector", "super-secret-client-secret", "mcp.read", null, null,
					null, null));
		}

		@Test
		@Story("Prefill resolution")
		@Severity(SeverityLevel.NORMAL)
		@Description("resolve() returns empty for an unknown, blank or null name")
		void resolve_unknownName_returnsEmpty() {
			// when/then
			assertThat(AuthProfilePrefillProviderTests.this.provider.resolve("no-such-profile")).isEmpty();
			assertThat(AuthProfilePrefillProviderTests.this.provider.resolve("")).isEmpty();
			assertThat(AuthProfilePrefillProviderTests.this.provider.resolve(null)).isEmpty();
		}

	}

	@Nested
	@DisplayName("list()")
	class ListSummaries {

		@Test
		@Story("Prefill listing")
		@Severity(SeverityLevel.CRITICAL)
		@Description("list() returns secret-free summaries: names and non-secret fields only, no secret values anywhere")
		void list_returnsSecretFreeSummaries() {
			// when
			final List<AuthProfileSummary> summaries = AuthProfilePrefillProviderTests.this.provider.list();

			// then
			assertThat(summaries).hasSize(3);
			assertThat(summaries).extracting(AuthProfileSummary::name)
				.containsExactlyInAnyOrder("prod-bearer", "prod-api", "prod-oauth");
			assertThat(summaries).extracting(AuthProfileSummary::profileId).containsOnlyNulls();

			final String rendered = summaries.toString();
			assertThat(rendered).doesNotContain("super-secret-bearer-token");
			assertThat(rendered).doesNotContain("super-secret-api-value");
			assertThat(rendered).doesNotContain("super-secret-client-secret");
			assertThat(rendered).contains("prod-bearer", "prod-api", "prod-oauth");

			final AuthProfileSummary apiKey = summaries.stream()
				.filter((summary) -> summary.name().equals("prod-api"))
				.findFirst()
				.orElseThrow();
			assertThat(apiKey.keyName()).isEqualTo("X-API-Key");
			assertThat(apiKey.placement()).isEqualTo(ApiKeyPlacement.QUERY);

			final AuthProfileSummary oauth2 = summaries.stream()
				.filter((summary) -> summary.name().equals("prod-oauth"))
				.findFirst()
				.orElseThrow();
			assertThat(oauth2.clientId()).isEqualTo("inspector");
			assertThat(oauth2.tokenUrl()).isEqualTo("https://auth.example.com/token");
			assertThat(oauth2.grantMode()).isEqualTo(OAuth2GrantMode.CLIENT_CREDENTIALS);
		}

	}

	@Nested
	@DisplayName("Materialization validation")
	class MaterializationValidation {

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("a declared profile with a blank bearer token fails materialization (fail-fast)")
		void resolve_blankBearerToken_throws() {
			// given
			final AuthProfileProperties properties = new AuthProfileProperties();
			final AuthProfileProperties.AuthProfileConfig broken = new AuthProfileProperties.AuthProfileConfig();
			broken.setName("broken");
			broken.setType(AuthProfileType.BEARER);
			properties.setProfiles(List.of(broken));

			// when/then
			assertThatThrownBy(() -> new AuthProfilePrefillProvider(properties).resolve("broken"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bearer.token");
		}

	}

}
