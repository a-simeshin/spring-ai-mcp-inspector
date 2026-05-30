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

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link McpInspectorProperties}. */
@Epic("MCP Inspector Core")
@Feature("Inspector configuration properties")
class McpInspectorPropertiesTest {

	@Nested
	@DisplayName("defaults")
	class Defaults {

		@Test
		@Story("Default values")
		@Severity(SeverityLevel.NORMAL)
		@Description("a freshly constructed properties bean exposes the documented framework defaults")
		void defaults_freshInstance_exposeFrameworkDefaults() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();

			// when & then
			assertThat(props.isEnabled()).isTrue();
			assertThat(props.getPath()).isEqualTo("/mcp-inspector");
			assertThat(props.isAuthEnabled()).isTrue();
			assertThat(props.getAuthToken()).isNull();
			assertThat(props.getAllowedOrigins()).isEmpty();
		}

	}

	@Nested
	@DisplayName("binding")
	class Binding {

		@Test
		@Story("Relaxed binding")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Spring relaxed binding maps the spring.ai.mcp.inspector.* keys onto the properties bean")
		void bind_fromEnvironment_populatesAllProperties() {
			// given
			MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.inspector.enabled", "false");
			env.setProperty("spring.ai.mcp.inspector.path", "/inspect");
			env.setProperty("spring.ai.mcp.inspector.auth-token", "my-token");
			env.setProperty("spring.ai.mcp.inspector.allowed-origins[0]", "https://a.example");
			env.setProperty("spring.ai.mcp.inspector.allowed-origins[1]", "https://b.example");

			// when
			McpInspectorProperties bound = Binder.get(env)
				.bind("spring.ai.mcp.inspector", McpInspectorProperties.class)
				.get();

			// then
			assertThat(bound.isEnabled()).isFalse();
			assertThat(bound.getPath()).isEqualTo("/inspect");
			assertThat(bound.getAuthToken()).isEqualTo("my-token");
			assertThat(bound.getAllowedOrigins()).containsExactly("https://a.example", "https://b.example");
		}

		@Test
		@Story("Null safety")
		@Severity(SeverityLevel.NORMAL)
		@Description("setAllowedOrigins(null) normalizes to an empty list")
		void setAllowedOrigins_withNull_defaultsToEmptyList() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();

			// when
			props.setAllowedOrigins(null);

			// then
			assertThat(props.getAllowedOrigins()).isNotNull().isEmpty();
		}

	}

	@Nested
	@DisplayName("setPath()")
	class SetPath {

		@Test
		@Story("Valid path")
		@Severity(SeverityLevel.NORMAL)
		@Description("setPath() accepts a path with a leading slash and no trailing slash")
		void setPath_withLeadingSlashNoTrailing_isAccepted() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();

			// when
			props.setPath("/foo");

			// then
			assertThat(props.getPath()).isEqualTo("/foo");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("setPath() rejects an empty path")
		void setPath_withEmpty_throwsIllegalArgument() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();

			// when & then
			assertThatThrownBy(() -> props.setPath("")).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("setPath() rejects a trailing slash")
		void setPath_withTrailingSlash_throwsIllegalArgument() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();

			// when & then
			assertThatThrownBy(() -> props.setPath("/foo/")).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("setPath() rejects a path missing the leading slash")
		void setPath_withMissingLeadingSlash_throwsIllegalArgument() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();

			// when & then
			assertThatThrownBy(() -> props.setPath("foo")).isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	@DisplayName("getProxyPath()")
	class GetProxyPath {

		@Test
		@Story("Derived path")
		@Severity(SeverityLevel.NORMAL)
		@Description("getProxyPath() derives the proxy path by appending -api to the configured path")
		void proxyPath_isDerivedFromPath() {
			// given
			McpInspectorProperties props = new McpInspectorProperties();
			props.setPath("/foo");

			// when & then
			assertThat(props.getProxyPath()).isEqualTo("/foo-api");
		}

	}

}
