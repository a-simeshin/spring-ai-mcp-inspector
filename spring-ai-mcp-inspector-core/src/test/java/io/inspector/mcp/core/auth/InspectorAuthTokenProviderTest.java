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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link InspectorAuthTokenProvider}. */
class InspectorAuthTokenProviderTest {

	@Test
	void tokenIsThirtyTwoHexChars() {
		McpInspectorProperties props = new McpInspectorProperties();
		InspectorAuthTokenProvider provider = new InspectorAuthTokenProvider(props);

		String token = provider.token();

		// 32 random bytes → 64 lowercase hex chars
		assertThat(token).isNotNull().hasSize(64);
		assertThat(token).matches("^[0-9a-f]{64}$");
	}

	@Test
	void usesConfiguredTokenWhenSet() {
		McpInspectorProperties props = new McpInspectorProperties();
		props.setAuthToken("my-custom-token");
		InspectorAuthTokenProvider provider = new InspectorAuthTokenProvider(props);

		assertThat(provider.token()).isEqualTo("my-custom-token");
	}

	@Test
	void cachesGeneratedToken() {
		McpInspectorProperties props = new McpInspectorProperties();
		InspectorAuthTokenProvider provider = new InspectorAuthTokenProvider(props);

		String first = provider.token();
		String second = provider.token();

		assertThat(second).isSameAs(first);
	}

	@Test
	void blankConfiguredTokenFallsBackToRandom() {
		McpInspectorProperties props = new McpInspectorProperties();
		props.setAuthToken("   ");
		InspectorAuthTokenProvider provider = new InspectorAuthTokenProvider(props);

		String token = provider.token();

		assertThat(token).hasSize(64);
	}

}
