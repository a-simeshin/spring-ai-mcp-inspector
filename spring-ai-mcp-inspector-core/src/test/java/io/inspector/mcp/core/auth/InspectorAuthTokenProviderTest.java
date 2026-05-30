/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.auth;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link InspectorAuthTokenProvider}. */
@Epic("MCP Inspector Core")
@Feature("Auth token provider")
class InspectorAuthTokenProviderTest {

	@Nested
	@DisplayName("token()")
	class Token {

		@Test
		@Story("Random token generation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("token() generates a 64-char lowercase hex token from 32 random bytes when none is configured")
		void token_whenNoneConfigured_generatesThirtyTwoByteHex() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();
			InspectorAuthTokenProvider provider = new InspectorAuthTokenProvider(props);

			// when
			String token = provider.token();

			// then — 32 random bytes → 64 lowercase hex chars
			assertThat(token).isNotNull().hasSize(64);
			assertThat(token).matches("^[0-9a-f]{64}$");
		}

		@Test
		@Story("Configured token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("token() returns the explicitly configured token verbatim")
		void token_whenConfigured_returnsConfiguredValue() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();
			props.setAuthToken("my-custom-token");
			InspectorAuthTokenProvider provider = new InspectorAuthTokenProvider(props);

			// when & then
			assertThat(provider.token()).isEqualTo("my-custom-token");
		}

		@Test
		@Story("Caching")
		@Severity(SeverityLevel.NORMAL)
		@Description("token() caches the generated value so repeated calls return the same instance")
		void token_whenCalledTwice_returnsCachedInstance() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();
			InspectorAuthTokenProvider provider = new InspectorAuthTokenProvider(props);

			// when
			String first = provider.token();
			String second = provider.token();

			// then
			assertThat(second).isSameAs(first);
		}

		@Test
		@Story("Blank token fallback")
		@Severity(SeverityLevel.NORMAL)
		@Description("token() falls back to a generated token when the configured value is blank")
		void token_whenConfiguredBlank_fallsBackToRandom() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();
			props.setAuthToken("   ");
			InspectorAuthTokenProvider provider = new InspectorAuthTokenProvider(props);

			// when
			String token = provider.token();

			// then
			assertThat(token).hasSize(64);
		}

	}

}
