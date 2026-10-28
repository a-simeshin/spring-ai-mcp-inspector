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

}
