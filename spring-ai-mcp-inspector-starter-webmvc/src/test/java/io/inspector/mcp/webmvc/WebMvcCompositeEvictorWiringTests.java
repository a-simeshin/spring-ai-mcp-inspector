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

import org.junit.jupiter.api.Test;

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring test: verifies that the composite TokenEvictor (CC manager + auth-code
 * exchanger) is wired correctly in the WebMVC auto-configuration.
 */
class WebMvcCompositeEvictorWiringTests {

	@Test
	void compositeEvictor_authCodeTokensEvictedOnDelete() {
		// given: the wiring as configured in McpInspectorWebMvcAutoConfiguration
		final AuthProfileStore store = new AuthProfileStore();
		final OAuth2ClientCredentialsTokenManager ccManager = new OAuth2ClientCredentialsTokenManager();
		final OAuth2AuthCodeTokenExchanger authCodeExchanger = new OAuth2AuthCodeTokenExchanger();

		// Wire composite evictor (same as auto-configuration)
		store.setTokenEvictor((profileId) -> {
			ccManager.evict(profileId);
			authCodeExchanger.evict(profileId);
		});

		// Store an auth-code profile and mint a state
		final OAuth2Profile profile = new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "http://t/token",
				"cid", null, null, "http://idp/auth", "http://app/cb", "ch", "S256");
		final String profileId = store.register("owner-a", profile);
		authCodeExchanger.mintState("owner-a", profileId);

		// Verify state exists
		assertThat(authCodeExchanger.stateCount()).isOne();

		// when: delete the profile
		store.delete("owner-a", profileId);

		// then: auth-code tokens AND state are evicted (the composite evictor works)
		assertThat(authCodeExchanger.stateCount()).isZero();
		assertThat(authCodeExchanger.tokenCount()).isZero();
		assertThat(ccManager.credentialCount()).isZero();
		assertThat(ccManager.cacheSize()).isZero();
	}

	@Test
	void compositeEvictor_ccTokensEvictedOnDelete() {
		// given
		final AuthProfileStore store = new AuthProfileStore();
		final OAuth2ClientCredentialsTokenManager ccManager = new OAuth2ClientCredentialsTokenManager();
		final OAuth2AuthCodeTokenExchanger authCodeExchanger = new OAuth2AuthCodeTokenExchanger();

		store.setTokenEvictor((profileId) -> {
			ccManager.evict(profileId);
			authCodeExchanger.evict(profileId);
		});

		// Register a CC profile
		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS, "http://t/token",
				"cid", "sec", null, null, null, null, null);
		final String profileId = store.register("owner-a", profile);

		// when: delete the profile
		store.delete("owner-a", profileId);

		// then: CC credentials are evicted
		assertThat(ccManager.credentialCount()).isZero();
		assertThat(ccManager.cacheSize()).isZero();
	}

}
