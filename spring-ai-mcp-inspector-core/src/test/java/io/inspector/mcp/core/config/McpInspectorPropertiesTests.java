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

package io.inspector.mcp.core.config;

import java.time.Duration;

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
class McpInspectorPropertiesTests {

	@Nested
	@DisplayName("defaults")
	class Defaults {

		@Test
		@Story("Default values")
		@Severity(SeverityLevel.NORMAL)
		@Description("a freshly constructed properties bean exposes the documented framework defaults")
		void defaults_freshInstance_exposeFrameworkDefaults() {
			// given
			final McpInspectorProperties props = new McpInspectorProperties();

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
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.inspector.enabled", "false");
			env.setProperty("spring.ai.mcp.inspector.path", "/inspect");
			env.setProperty("spring.ai.mcp.inspector.auth-token", "my-token");
			env.setProperty("spring.ai.mcp.inspector.allowed-origins[0]", "https://a.example");
			env.setProperty("spring.ai.mcp.inspector.allowed-origins[1]", "https://b.example");

			// when
			final McpInspectorProperties bound = Binder.get(env)
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
			final McpInspectorProperties props = new McpInspectorProperties();

			// when
			props.setAllowedOrigins(null);

			// then
			assertThat(props.getAllowedOrigins()).isNotNull().isEmpty();
		}

	}

	@Nested
	@DisplayName("timeouts")
	class Timeouts {

		@Test
		@Story("Default values")
		@Severity(SeverityLevel.NORMAL)
		@Description("a freshly constructed timeouts group exposes the upstream-compatible defaults")
		void timeouts_freshInstance_exposeDefaults() {
			// given
			final McpInspectorProperties props = new McpInspectorProperties();

			// when
			final McpInspectorProperties.Timeouts timeouts = props.getTimeouts();

			// then
			assertThat(timeouts).isNotNull();
			assertThat(timeouts.getSseSession()).isEqualTo(Duration.ofMinutes(30));
			assertThat(timeouts.getStreamableRequest()).isEqualTo(Duration.ofSeconds(30));
			assertThat(timeouts.getFetchConnect()).isEqualTo(Duration.ofSeconds(10));
			assertThat(timeouts.getFetchRequest()).isEqualTo(Duration.ofSeconds(30));
			assertThat(timeouts.getServerRequest()).isEqualTo(Duration.ofSeconds(120));
		}

		@Test
		@Story("Relaxed binding")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the spring.ai.mcp.inspector.timeouts.* keys bind with Spring's relaxed Duration syntax")
		void bind_timeouts_fromEnvironment_overridesDefaults() {
			// given
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.inspector.timeouts.sse-session", "10m");
			env.setProperty("spring.ai.mcp.inspector.timeouts.streamable-request", "5s");
			env.setProperty("spring.ai.mcp.inspector.timeouts.fetch-connect", "2s");
			env.setProperty("spring.ai.mcp.inspector.timeouts.fetch-request", "15s");
			env.setProperty("spring.ai.mcp.inspector.timeouts.server-request", "60s");

			// when
			final McpInspectorProperties bound = Binder.get(env)
				.bind("spring.ai.mcp.inspector", McpInspectorProperties.class)
				.get();

			// then
			assertThat(bound.getTimeouts().getSseSession()).isEqualTo(Duration.ofMinutes(10));
			assertThat(bound.getTimeouts().getStreamableRequest()).isEqualTo(Duration.ofSeconds(5));
			assertThat(bound.getTimeouts().getFetchConnect()).isEqualTo(Duration.ofSeconds(2));
			assertThat(bound.getTimeouts().getFetchRequest()).isEqualTo(Duration.ofSeconds(15));
			assertThat(bound.getTimeouts().getServerRequest()).isEqualTo(Duration.ofSeconds(60));
		}

		@Test
		@Story("Null safety")
		@Severity(SeverityLevel.NORMAL)
		@Description("setTimeouts(null) normalizes back to a default group")
		void setTimeouts_withNull_defaultsToNewGroup() {
			// given
			final McpInspectorProperties props = new McpInspectorProperties();

			// when
			props.setTimeouts(null);

			// then
			assertThat(props.getTimeouts()).isNotNull();
			assertThat(props.getTimeouts().getStreamableRequest()).isEqualTo(Duration.ofSeconds(30));
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
			final McpInspectorProperties props = new McpInspectorProperties();

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
			final McpInspectorProperties props = new McpInspectorProperties();

			// when & then
			assertThatThrownBy(() -> props.setPath("")).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("setPath() rejects a trailing slash")
		void setPath_withTrailingSlash_throwsIllegalArgument() {
			// given
			final McpInspectorProperties props = new McpInspectorProperties();

			// when & then
			assertThatThrownBy(() -> props.setPath("/foo/")).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("setPath() rejects a path missing the leading slash")
		void setPath_withMissingLeadingSlash_throwsIllegalArgument() {
			// given
			final McpInspectorProperties props = new McpInspectorProperties();

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
			final McpInspectorProperties props = new McpInspectorProperties();
			props.setPath("/foo");

			// when & then
			assertThat(props.getProxyPath()).isEqualTo("/foo-api");
		}

	}

}
