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

/**
 * Unit tests for {@link SkillsExtensionSupport}.
 *
 * <p>
 * The parser works on the raw initialize result frame (the untyped {@code result} map),
 * because the canonical {@code capabilities.extensions} declaration is dropped by the
 * SDK's typed {@code ServerCapabilities} record before the typed model could see it.
 */
@Epic("MCP Inspector Core")
@Feature("SEP-2640 skills capability detection")
class SkillsExtensionSupportTests {

	private static final String EXT_ID = SkillsExtensionSupport.EXTENSION_ID;

	private static Map<String, Object> rawResult(final Map<String, Object> capabilities,
			final Map<String, Object> meta) {
		final Map<String, Object> result = new HashMap<>();
		if (capabilities != null) {
			result.put("capabilities", capabilities);
		}
		if (meta != null) {
			result.put("_meta", meta);
		}
		return result;
	}

	private static Map<String, Object> capabilitiesWith(final Map<String, Object> extensions) {
		final Map<String, Object> capabilities = new HashMap<>();
		capabilities.put("tools", Map.of());
		if (extensions != null) {
			capabilities.put("extensions", extensions);
		}
		return capabilities;
	}

	private static Map<String, Object> metaWith(final Map<String, Object> extensions) {
		final Map<String, Object> meta = new HashMap<>();
		if (extensions != null) {
			meta.put("extensions", extensions);
		}
		return meta;
	}

	@Nested
	@DisplayName("canonical capabilities.extensions")
	class Canonical {

		@Test
		@Story("canonical declaration with directoryRead")
		@Severity(SeverityLevel.CRITICAL)
		@Description("capabilities.extensions carries io.modelcontextprotocol/skills with directoryRead=true")
		void canonical_withDirectoryReadTrue_supportedAndDirectoryRead() {
			final SkillsExtensionSupport support = SkillsExtensionSupport.fromRawInitializeResult(
					rawResult(capabilitiesWith(Map.of(EXT_ID, Map.of("directoryRead", true))), null));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isTrue();
		}

		@Test
		@Story("canonical declaration with empty object")
		@Severity(SeverityLevel.CRITICAL)
		@Description("an empty extension object in the canonical location declares support without optional features")
		void canonical_withEmptyObject_supportedWithoutDirectoryRead() {
			final SkillsExtensionSupport support = SkillsExtensionSupport
				.fromRawInitializeResult(rawResult(capabilitiesWith(Map.of(EXT_ID, Map.of())), null));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isFalse();
		}

		@Test
		@Story("canonical declaration with directoryRead false")
		@Severity(SeverityLevel.NORMAL)
		@Description("explicit directoryRead=false in the canonical location keeps directoryRead off")
		void canonical_withDirectoryReadFalse_supportedWithoutDirectoryRead() {
			final SkillsExtensionSupport support = SkillsExtensionSupport.fromRawInitializeResult(
					rawResult(capabilitiesWith(Map.of(EXT_ID, Map.of("directoryRead", false))), null));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isFalse();
		}

		@Test
		@Story("unrelated extensions ignored")
		@Severity(SeverityLevel.NORMAL)
		@Description("other extension identifiers in the canonical map do not trigger skills support")
		void canonical_unrelatedExtension_absent() {
			final SkillsExtensionSupport support = SkillsExtensionSupport.fromRawInitializeResult(
					rawResult(capabilitiesWith(Map.of("io.modelcontextprotocol/tasks", Map.of())), null));

			assertThat(support.supported()).isFalse();
		}

		@Test
		@Story("canonical wins over legacy _meta")
		@Severity(SeverityLevel.CRITICAL)
		@Description("when both locations declare the extension, the canonical declaration wins")
		void canonicalWinsOverMeta() {
			final SkillsExtensionSupport support = SkillsExtensionSupport
				.fromRawInitializeResult(rawResult(capabilitiesWith(Map.of(EXT_ID, Map.of("directoryRead", true))),
						metaWith(Map.of(EXT_ID, Map.of("directoryRead", false)))));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).as("canonical capabilities.extensions must win over _meta.extensions")
				.isTrue();
		}

	}

	@Nested
	@DisplayName("legacy _meta.extensions fallback")
	class LegacyMeta {

		@Test
		@Story("legacy declaration in _meta")
		@Severity(SeverityLevel.NORMAL)
		@Description("_meta.extensions still declares support when the canonical location is absent")
		void meta_withExtension_supported() {
			final SkillsExtensionSupport support = SkillsExtensionSupport.fromRawInitializeResult(
					rawResult(capabilitiesWith(null), metaWith(Map.of(EXT_ID, Map.of("directoryRead", true)))));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isTrue();
		}

		@Test
		@Story("_meta without extensions")
		@Severity(SeverityLevel.NORMAL)
		@Description("_meta carrying other keys yields ABSENT")
		void meta_withoutExtensions_absent() {
			final SkillsExtensionSupport support = SkillsExtensionSupport
				.fromRawInitializeResult(rawResult(capabilitiesWith(null), Map.of("other", "value")));

			assertThat(support).isEqualTo(SkillsExtensionSupport.ABSENT);
		}

	}

	@Nested
	@DisplayName("absent")
	class Absent {

		@Test
		@Story("no capabilities and no _meta")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a raw result without capabilities or _meta yields ABSENT")
		void absent_emptyResult() {
			assertThat(SkillsExtensionSupport.fromRawInitializeResult(new HashMap<>()))
				.isEqualTo(SkillsExtensionSupport.ABSENT);
		}

		@Test
		@Story("null raw result")
		@Severity(SeverityLevel.NORMAL)
		@Description("null raw initialize result yields ABSENT")
		void absent_nullRawResult() {
			assertThat(SkillsExtensionSupport.fromRawInitializeResult(null)).isEqualTo(SkillsExtensionSupport.ABSENT);
		}

		@Test
		@Story("null extensions map")
		@Severity(SeverityLevel.NORMAL)
		@Description("fromExtensions(null) yields ABSENT")
		void absent_nullExtensions() {
			assertThat(SkillsExtensionSupport.fromExtensions(null)).isEqualTo(SkillsExtensionSupport.ABSENT);
		}

		@Test
		@Story("capabilities without extensions key")
		@Severity(SeverityLevel.NORMAL)
		@Description("capabilities that carry no extensions key at all yield ABSENT")
		void absent_capabilitiesWithoutExtensions() {
			final SkillsExtensionSupport support = SkillsExtensionSupport
				.fromRawInitializeResult(rawResult(Map.of("tools", Map.of()), null));

			assertThat(support.supported()).isFalse();
		}

	}

	@Nested
	@DisplayName("malformed")
	class Malformed {

		@Test
		@Story("extensions is a string")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a non-map capabilities.extensions yields ABSENT instead of failing")
		void malformed_extensionsString_absent() {
			final Map<String, Object> capabilities = new HashMap<>();
			capabilities.put("tools", Map.of());
			capabilities.put("extensions", "broken");

			final SkillsExtensionSupport support = SkillsExtensionSupport
				.fromRawInitializeResult(rawResult(capabilities, null));

			assertThat(support.supported()).isFalse();
		}

		@Test
		@Story("extension value is a string")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a non-map extension value yields ABSENT instead of failing")
		void malformed_stringValue_absent() {
			final SkillsExtensionSupport support = SkillsExtensionSupport
				.fromRawInitializeResult(rawResult(capabilitiesWith(Map.of(EXT_ID, "yes")), null));

			assertThat(support.supported()).isFalse();
		}

		@Test
		@Story("extension value is null")
		@Severity(SeverityLevel.NORMAL)
		@Description("a null extension value yields ABSENT")
		void malformed_nullValue_absent() {
			final Map<String, Object> extensions = new HashMap<>();
			extensions.put(EXT_ID, null);

			final SkillsExtensionSupport support = SkillsExtensionSupport
				.fromRawInitializeResult(rawResult(capabilitiesWith(extensions), null));

			assertThat(support.supported()).isFalse();
		}

		@Test
		@Story("directoryRead is a string")
		@Severity(SeverityLevel.NORMAL)
		@Description("a non-boolean directoryRead falls back to the default false")
		void malformed_directoryReadString_defaultsFalse() {
			final SkillsExtensionSupport support = SkillsExtensionSupport.fromRawInitializeResult(
					rawResult(capabilitiesWith(Map.of(EXT_ID, Map.of("directoryRead", "yes"))), null));

			assertThat(support.supported()).isTrue();
			assertThat(support.directoryRead()).isFalse();
		}

		@Test
		@Story("capabilities is a string")
		@Severity(SeverityLevel.NORMAL)
		@Description("a non-map capabilities value yields ABSENT instead of failing")
		void malformed_capabilitiesString_absent() {
			final Map<String, Object> result = new HashMap<>();
			result.put("capabilities", "broken");

			final SkillsExtensionSupport support = SkillsExtensionSupport.fromRawInitializeResult(result);

			assertThat(support.supported()).isFalse();
		}

	}

}
