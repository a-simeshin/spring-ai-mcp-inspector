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

package io.inspector.mcp.webmvc;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;
import io.inspector.mcp.core.config.McpInspectorProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring test: drives the production bean methods of the WebMVC auto-configuration and
 * verifies the wiring they install, so a regression in
 * {@code McpInspectorWebMvcAutoConfiguration#mcpInspectorTokenManager} (composite
 * TokenEvictor or generation guards dropped) fails here instead of surviving as a
 * mutation.
 */
class WebMvcCompositeEvictorWiringTests {

	private final McpInspectorWebMvcAutoConfiguration autoConfiguration = new McpInspectorWebMvcAutoConfiguration(
			new McpInspectorProperties());

	private AuthProfileStore store;

	private OAuth2ClientCredentialsTokenManager ccManager;

	private OAuth2AuthCodeTokenExchanger authCodeExchanger;

	private void wireFromAutoConfiguration() {
		this.store = this.autoConfiguration.mcpInspectorAuthProfileStore();
		this.authCodeExchanger = this.autoConfiguration.mcpInspectorAuthCodeTokenExchanger();
		this.ccManager = this.autoConfiguration.mcpInspectorTokenManager(this.store, this.authCodeExchanger);
	}

	@Test
	void compositeEvictor_authCodeTokensEvictedOnDelete() {
		this.wireFromAutoConfiguration();

		final OAuth2Profile profile = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "http://t/token",
				"cid", null, null, "http://idp/auth", "http://app/cb", "ch", "S256");
		final String profileId = this.store.register("owner-a", profile);
		// Bump the generation past 0: only the wired guard
		// (store::currentGeneration) accepts it, so the store below proves the
		// exchanger's guard wiring, while the unwired default (0L) would go stale.
		assertThat(this.store.update("owner-a", profileId, profile)).isTrue();
		final long generation = this.store.currentGeneration(profileId);
		assertThat(generation).isPositive();

		this.authCodeExchanger.mintState("owner-a", profileId);
		this.authCodeExchanger.storeTokensIfCurrent(profileId, generation,
				new OAuth2AuthCodeTokenExchanger.TokenHandle("tok-1", "rt-1", Instant.now().plusSeconds(60)));
		assertThat(this.authCodeExchanger.stateCount()).isOne();
		assertThat(this.authCodeExchanger.tokenCount()).isOne();

		// when: delete the profile
		assertThat(this.store.delete("owner-a", profileId)).isTrue();

		// then: auth-code tokens AND state are evicted (the composite evictor works)
		assertThat(this.authCodeExchanger.stateCount()).isZero();
		assertThat(this.authCodeExchanger.tokenCount()).isZero();
		assertThat(this.ccManager.credentialCount()).isZero();
		assertThat(this.ccManager.cacheSize()).isZero();
	}

	@Test
	void compositeEvictor_ccTokensEvictedOnDelete() {
		this.wireFromAutoConfiguration();

		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS, "http://t/token",
				"cid", "sec", null, null, null, null, null);
		final String profileId = this.store.register("owner-a", profile);
		// Bump the generation past 0: only the wired guard accepts it, so the
		// store below proves the manager's guard wiring.
		assertThat(this.store.update("owner-a", profileId, profile)).isTrue();
		final long generation = this.store.currentGeneration(profileId);
		assertThat(generation).isPositive();

		this.ccManager.storeIfCurrent(profileId, generation,
				new OAuth2ClientCredentialsTokenManager.TokenHandle("tok-1", Instant.now().plusSeconds(60)), profile);
		assertThat(this.ccManager.credentialCount()).isOne();
		assertThat(this.ccManager.cacheSize()).isOne();

		// when: delete the profile
		assertThat(this.store.delete("owner-a", profileId)).isTrue();

		// then: CC credentials are evicted
		assertThat(this.ccManager.credentialCount()).isZero();
		assertThat(this.ccManager.cacheSize()).isZero();
	}

}
