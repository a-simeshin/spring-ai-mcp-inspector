/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link McpInspectorProperties}. */
class McpInspectorPropertiesTest {

	@Test
	void defaultEnabledTrue() {
		McpInspectorProperties props = new McpInspectorProperties();

		assertThat(props.isEnabled()).isTrue();
	}

	@Test
	void defaultPathIsMcpInspector() {
		McpInspectorProperties props = new McpInspectorProperties();

		assertThat(props.getPath()).isEqualTo("/mcp-inspector");
	}

	@Test
	void defaultAuthEnabledTrue() {
		McpInspectorProperties props = new McpInspectorProperties();

		assertThat(props.isAuthEnabled()).isTrue();
	}

	@Test
	void defaultAuthTokenIsNull() {
		McpInspectorProperties props = new McpInspectorProperties();

		assertThat(props.getAuthToken()).isNull();
	}

	@Test
	void defaultAllowedOriginsIsEmpty() {
		McpInspectorProperties props = new McpInspectorProperties();

		assertThat(props.getAllowedOrigins()).isEmpty();
	}

	@Test
	void bindsAllowedOrigins() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty("spring.ai.mcp.inspector.enabled", "false");
		env.setProperty("spring.ai.mcp.inspector.path", "/inspect");
		env.setProperty("spring.ai.mcp.inspector.auth-token", "my-token");
		env.setProperty("spring.ai.mcp.inspector.allowed-origins[0]", "https://a.example");
		env.setProperty("spring.ai.mcp.inspector.allowed-origins[1]", "https://b.example");

		McpInspectorProperties bound = Binder.get(env)
			.bind("spring.ai.mcp.inspector", McpInspectorProperties.class)
			.get();

		assertThat(bound.isEnabled()).isFalse();
		assertThat(bound.getPath()).isEqualTo("/inspect");
		assertThat(bound.getAuthToken()).isEqualTo("my-token");
		assertThat(bound.getAllowedOrigins()).containsExactly("https://a.example", "https://b.example");
	}

	@Test
	void setAllowedOriginsNullDefaultsToEmptyList() {
		McpInspectorProperties props = new McpInspectorProperties();
		props.setAllowedOrigins(null);

		assertThat(props.getAllowedOrigins()).isNotNull().isEmpty();
	}

	@Test
	void setPath_acceptsLeadingSlashWithoutTrailing() {
		McpInspectorProperties props = new McpInspectorProperties();

		props.setPath("/foo");

		assertThat(props.getPath()).isEqualTo("/foo");
	}

	@Test
	void setPath_rejectsEmpty() {
		McpInspectorProperties props = new McpInspectorProperties();

		assertThatThrownBy(() -> props.setPath("")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void setPath_rejectsTrailingSlash() {
		McpInspectorProperties props = new McpInspectorProperties();

		assertThatThrownBy(() -> props.setPath("/foo/")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void setPath_rejectsMissingLeadingSlash() {
		McpInspectorProperties props = new McpInspectorProperties();

		assertThatThrownBy(() -> props.setPath("foo")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void proxyPath_isDerivedFromPath() {
		McpInspectorProperties props = new McpInspectorProperties();
		props.setPath("/foo");

		assertThat(props.getProxyPath()).isEqualTo("/foo-api");
	}

}
