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

package io.inspector.mcp.core.dto;

import java.util.HashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
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

/** Unit tests for {@link SkillsExtensionSupport}. */
@Epic("MCP Inspector Core")
@Feature("SEP-2640 skills capability detection")
class SkillsExtensionSupportTests {

	private static McpSchema.InitializeResult initResult(final Map<String, Object> meta) {
		final McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder().tools(true).build();
		return new McpSchema.InitializeResult("2025-06-18", capabilities,
				new McpSchema.Implementation("test-server", "1.0"), null, meta);
	}

	private static Map<String, Object> metaWithExtensions(final Map<String, Object> extensions) {
		final Map<String, Object> meta = new HashMap<>();
		meta.put("extensions", extensions);
		return meta;
	}

	@Nested
	@DisplayName("present")
	class Present {

		@Test
		@Story("extension declared with directoryRead")
		@Severity(SeverityLevel.CRITICAL)
		@Description("_meta.extensions carries io.modelcontextprotocol/skills with directoryRead=true")
		void present_withDirectoryReadTrue_supportedAndDirectoryRead() {
			final SkillsExtensionSupport support = SkillsExtensionSupport.from(initResult(
					metaWithExtensions(Map.of("io.modelcontextprotocol/skills", Map.of("directoryRead", true)))));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isTrue();
		}

		@Test
		@Story("extension declared with empty object")
		@Severity(SeverityLevel.CRITICAL)
		@Description("an empty object declares support without optional features")
		void present_withEmptyObject_supportedWithoutDirectoryRead() {
			final SkillsExtensionSupport support = SkillsExtensionSupport
				.from(initResult(metaWithExtensions(Map.of("io.modelcontextprotocol/skills", Map.of()))));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isFalse();
		}

		@Test
		@Story("directoryRead false")
		@Severity(SeverityLevel.NORMAL)
		@Description("explicit directoryRead=false keeps directoryRead off")
		void present_withDirectoryReadFalse_supportedWithoutDirectoryRead() {
			final SkillsExtensionSupport support = SkillsExtensionSupport.from(initResult(
					metaWithExtensions(Map.of("io.modelcontextprotocol/skills", Map.of("directoryRead", false)))));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isFalse();
		}

		@Test
		@Story("unrelated extensions ignored")
		@Severity(SeverityLevel.NORMAL)
		@Description("other extension identifiers do not trigger skills support")
		void present_unrelatedExtension_absent() {
			final SkillsExtensionSupport support = SkillsExtensionSupport
				.from(initResult(metaWithExtensions(Map.of("io.modelcontextprotocol/tasks", Map.of()))));

			assertThat(support.supported()).isFalse();
		}

		@Test
		@Story("raw canonical extensions map")
		@Severity(SeverityLevel.NORMAL)
		@Description("fromExtensions parses a pre-extracted capabilities.extensions map")
		void present_rawExtensionsMap_supported() {
			final SkillsExtensionSupport support = SkillsExtensionSupport
				.fromExtensions(Map.of("io.modelcontextprotocol/skills", Map.of("directoryRead", true)));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isTrue();
		}

	}

	@Nested
	@DisplayName("absent")
	class Absent {

		@Test
		@Story("no _meta")
		@Severity(SeverityLevel.CRITICAL)
		@Description("an initialize result without _meta yields ABSENT")
		void absent_noMeta() {
			assertThat(SkillsExtensionSupport.from(initResult(null))).isEqualTo(SkillsExtensionSupport.ABSENT);
		}

		@Test
		@Story("_meta without extensions")
		@Severity(SeverityLevel.NORMAL)
		@Description("_meta carrying other keys yields ABSENT")
		void absent_metaWithoutExtensions() {
			assertThat(SkillsExtensionSupport.from(initResult(Map.of("other", "value"))))
				.isEqualTo(SkillsExtensionSupport.ABSENT);
		}

		@Test
		@Story("null initialize result")
		@Severity(SeverityLevel.NORMAL)
		@Description("null initialize result yields ABSENT")
		void absent_nullInitializeResult() {
			assertThat(SkillsExtensionSupport.from(null)).isEqualTo(SkillsExtensionSupport.ABSENT);
		}

		@Test
		@Story("null extensions map")
		@Severity(SeverityLevel.NORMAL)
		@Description("fromExtensions(null) yields ABSENT")
		void absent_nullExtensions() {
			assertThat(SkillsExtensionSupport.fromExtensions(null)).isEqualTo(SkillsExtensionSupport.ABSENT);
		}

	}

	@Nested
	@DisplayName("malformed")
	class Malformed {

		@Test
		@Story("extensions is a string")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a non-map _meta.extensions yields ABSENT instead of failing")
		void malformed_extensionsString_absent() {
			final SkillsExtensionSupport support = SkillsExtensionSupport.from(initResult(Map.of("extensions", "x")));

			assertThat(support.supported()).isFalse();
		}

		@Test
		@Story("extension value is a string")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a non-map extension value yields ABSENT instead of failing")
		void malformed_stringValue_absent() {
			final SkillsExtensionSupport support = SkillsExtensionSupport
				.from(initResult(metaWithExtensions(Map.of("io.modelcontextprotocol/skills", "yes"))));

			assertThat(support.supported()).isFalse();
		}

		@Test
		@Story("extension value is null")
		@Severity(SeverityLevel.NORMAL)
		@Description("a null extension value yields ABSENT")
		void malformed_nullValue_absent() {
			final Map<String, Object> extensions = new HashMap<>();
			extensions.put("io.modelcontextprotocol/skills", null);

			final SkillsExtensionSupport support = SkillsExtensionSupport
				.from(initResult(metaWithExtensions(extensions)));

			assertThat(support.supported()).isFalse();
		}

		@Test
		@Story("directoryRead is a string")
		@Severity(SeverityLevel.NORMAL)
		@Description("a non-boolean directoryRead falls back to the default false")
		void malformed_directoryReadString_defaultsFalse() {
			final SkillsExtensionSupport support = SkillsExtensionSupport.from(initResult(
					metaWithExtensions(Map.of("io.modelcontextprotocol/skills", Map.of("directoryRead", "yes")))));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isFalse();
		}

	}

}
