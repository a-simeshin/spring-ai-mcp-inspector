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

package io.inspector.mcp.webflux.auth;

import java.time.Duration;
import java.time.Instant;

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

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.webflux.McpInspectorWebFluxAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Proves that the production auto-configuration bean method creates a
 * {@link ProxySessionRegistry} with the {@link OAuth2AuthCodeTokenExchanger} wired
 * correctly, so that {@link ProxySessionRegistry#reap()} invokes the exchanger.
 */
@Epic("WebFlux Inspector")
@Feature("Auto-config wiring")
class AutoConfigWiringTests {

	private McpInspectorWebFluxAutoConfiguration config;

	@BeforeEach
	void setUp() {
		this.config = new McpInspectorWebFluxAutoConfiguration();
	}

	@Nested
	@DisplayName("ProxySessionRegistry wiring")
	class ProxySessionRegistryWiring {

		@Test
		@Story("auto-config creates registry with exchanger wired")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the auto-config bean method wires the exchanger so that reap() invokes removeExpiredStates")
		void autoConfigWiring_reap_invokesExchanger() {
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final OAuth2AuthCodeTokenExchanger exchanger = mock(OAuth2AuthCodeTokenExchanger.class);
			final McpInspectorProperties properties = new McpInspectorProperties();
			properties.getTimeouts().setSessionReaper(Duration.ofMinutes(1));

			final ProxySessionRegistry registry = AutoConfigWiringTests.this.config
				.mcpInspectorProxySessionRegistry(properties, store, exchanger);

			registry.reap();

			verify(exchanger).removeExpiredStates(any(Instant.class));
			verify(store).removeExpired(any(Instant.class));
			assertThat(registry).isNotNull();
		}

		@Test
		@Story("auto-config creates registry with store wired")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the auto-config bean method wires the store so that reap() invokes removeExpired")
		void autoConfigWiring_reap_invokesStore() {
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final OAuth2AuthCodeTokenExchanger exchanger = mock(OAuth2AuthCodeTokenExchanger.class);
			final McpInspectorProperties properties = new McpInspectorProperties();
			properties.getTimeouts().setSessionReaper(Duration.ofMinutes(1));

			final ProxySessionRegistry registry = AutoConfigWiringTests.this.config
				.mcpInspectorProxySessionRegistry(properties, store, exchanger);

			registry.reap();

			verify(store).removeExpired(any(Instant.class));
		}

	}

	@Nested
	@DisplayName("Token offload wiring")
	class TokenOffloadWiring {

		@Test
		@Story("auto-config wires the composite evictor and the exchanger generation guard")
		@Severity(SeverityLevel.CRITICAL)
		@Description("mcpInspectorTokenManager wires the store so delete evicts auth-code state and tokens")
		void autoConfigWiring_delete_evictsAuthCodeTokens() {
			final AuthProfileStore store = AutoConfigWiringTests.this.config.mcpInspectorAuthProfileStore();
			final OAuth2AuthCodeTokenExchanger exchanger = AutoConfigWiringTests.this.config
				.mcpInspectorAuthCodeTokenExchanger();
			AutoConfigWiringTests.this.config.mcpInspectorTokenManager(store, exchanger);

			final OAuth2Profile profile = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "http://t/token",
					"cid", null, null, "http://idp/auth", "http://app/cb", "ch", "S256");
			final String profileId = store.register("owner-a", profile);
			// Bump the generation past 0: only the wired guard accepts it, so the
			// store below proves the exchanger's guard wiring (the default 0L goes
			// stale).
			assertThat(store.update("owner-a", profileId, profile)).isTrue();
			final long generation = store.currentGeneration(profileId);
			assertThat(generation).isPositive();

			exchanger.mintState("owner-a", profileId);
			exchanger.storeTokensIfCurrent(profileId, generation,
					new OAuth2AuthCodeTokenExchanger.TokenHandle("tok-1", "rt-1", Instant.now().plusSeconds(60)));
			assertThat(exchanger.stateCount()).isOne();
			assertThat(exchanger.tokenCount()).isOne();

			// when: delete the profile through the wired store
			assertThat(store.delete("owner-a", profileId)).isTrue();

			// then: the composite evictor dropped the auth-code state and tokens
			assertThat(exchanger.stateCount()).isZero();
			assertThat(exchanger.tokenCount()).isZero();
		}

		@Test
		@Story("auto-config wires the composite evictor and the manager generation guard")
		@Severity(SeverityLevel.CRITICAL)
		@Description("mcpInspectorTokenManager wires the guard so storeIfCurrent accepts the current generation")
		void autoConfigWiring_delete_evictsCcCredentials() {
			final AuthProfileStore store = AutoConfigWiringTests.this.config.mcpInspectorAuthProfileStore();
			final OAuth2AuthCodeTokenExchanger exchanger = AutoConfigWiringTests.this.config
				.mcpInspectorAuthCodeTokenExchanger();
			final OAuth2ClientCredentialsTokenManager manager = AutoConfigWiringTests.this.config
				.mcpInspectorTokenManager(store, exchanger);

			final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS, "http://t/token",
					"cid", "sec", null, null, null, null, null);
			final String profileId = store.register("owner-a", profile);
			// Bump the generation past 0: only the wired guard accepts it, so the
			// store below proves the manager's guard wiring (the default 0L goes stale).
			assertThat(store.update("owner-a", profileId, profile)).isTrue();
			final long generation = store.currentGeneration(profileId);
			assertThat(generation).isPositive();

			manager.storeIfCurrent(profileId, generation,
					new OAuth2ClientCredentialsTokenManager.TokenHandle("tok-1", Instant.now().plusSeconds(60)),
					profile);
			assertThat(manager.credentialCount()).isOne();
			assertThat(manager.cacheSize()).isOne();

			// when: delete the profile through the wired store
			assertThat(store.delete("owner-a", profileId)).isTrue();

			// then: the composite evictor dropped the credentials and the cached token
			assertThat(manager.credentialCount()).isZero();
			assertThat(manager.cacheSize()).isZero();
		}

	}

}
