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

package io.inspector.mcp.core.proxy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@code x-custom-auth-headers} wire contract: a JSON array of header
 * names, exactly as the inspector UI sends it ({@code JSON.stringify(names)}).
 *
 * @author Artem Simeshin
 */
class CustomHeaderNamesTests {

	@Test
	@DisplayName("parse: absent or blank value yields no names")
	void parse_absentOrBlank_yieldsNoNames() {
		assertThat(CustomHeaderNames.parse(null)).isEmpty();
		assertThat(CustomHeaderNames.parse("")).isEmpty();
		assertThat(CustomHeaderNames.parse("   ")).isEmpty();
	}

	@Test
	@DisplayName("parse: JSON array of names is parsed in wire order, blanks trimmed away")
	void parse_jsonArray_yieldsNamesInOrder() {
		assertThat(CustomHeaderNames.parse("[\"X-Tenant\", \"X-Trace\"]")).containsExactly("X-Tenant", "X-Trace");
	}

	@Test
	@DisplayName("parse: blank entries inside the array are skipped, surrounding blanks trimmed")
	void parse_blankEntries_skipped() {
		assertThat(CustomHeaderNames.parse("[\" X-Tenant \", \"\", \"  \", \"X-Trace\"]")).containsExactly("X-Tenant",
				"X-Trace");
	}

	@Test
	@DisplayName("parse: the legacy comma-separated format is rejected, not silently emptied")
	void parse_commaSeparated_rejected() {
		// This is the exact payload the old split(',') parser accepted and the UI never
		// sent: pinning the rejection keeps the wire contract single-format.
		assertThatThrownBy(() -> CustomHeaderNames.parse("X-Tenant, X-Trace"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("x-custom-auth-headers must be a JSON array of header names");
	}

	@Test
	@DisplayName("parse: non-array JSON is rejected with the offending shape in the message")
	void parse_nonArrayJson_rejected() {
		assertThatThrownBy(() -> CustomHeaderNames.parse("{\"names\":[\"X-Tenant\"]}"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must be a JSON array of header names")
			.hasMessageContaining("an object");
		assertThatThrownBy(() -> CustomHeaderNames.parse("\"X-Tenant\"")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("a string");
	}

	@Test
	@DisplayName("parse: non-string array elements are rejected")
	void parse_nonStringElements_rejected() {
		assertThatThrownBy(() -> CustomHeaderNames.parse("[\"X-Tenant\", 42]"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("a number");
		assertThatThrownBy(() -> CustomHeaderNames.parse("[null]")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("null");
	}

	@Test
	@DisplayName("parse: broken JSON is rejected as invalid JSON")
	void parse_brokenJson_rejected() {
		assertThatThrownBy(() -> CustomHeaderNames.parse("[\"X-Tenant\"")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not valid JSON");
	}

	@Test
	@DisplayName("parse: the message never carries header values")
	void parse_messageNeverCarriesValues() {
		// Header VALUES are secrets; only the offending SHAPE may appear in the message.
		assertThatThrownBy(() -> CustomHeaderNames.parse("[\"X-Tenant\", 42]"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("secret");
	}

	@Test
	@DisplayName("resolve: only names whose inbound value is present survive")
	void resolve_dropsAbsentNames() {
		final Map<String, String> inbound = Map.of("X-Tenant", "acme");
		final Map<String, String> resolved = CustomHeaderNames.resolve("[\"X-Tenant\", \"X-Absent\"]", inbound::get);
		assertThat(resolved).containsExactly(Map.entry("X-Tenant", "acme"));
	}

	@Test
	@DisplayName("resolve: empty payload resolves to an empty map")
	void resolve_emptyPayload_emptyMap() {
		assertThat(CustomHeaderNames.resolve(null, (name) -> "v")).isEmpty();
		assertThat(CustomHeaderNames.resolve("  ", (name) -> "v")).isEmpty();
		assertThat(CustomHeaderNames.resolve("[]", (name) -> "v")).isEmpty();
	}

	@Test
	@DisplayName("resolve: values with commas and blanks round-trip untouched")
	void resolve_valuesWithCommasUntouched() {
		final String value = "a, b , c";
		final Map<String, String> resolved = CustomHeaderNames.resolve("[\"X-Tenant\"]", (name) -> value);
		assertThat(resolved).containsExactly(Map.entry("X-Tenant", value));
	}

	@Test
	@DisplayName("HEADER constant names the wire header")
	void headerConstant() {
		assertThat(CustomHeaderNames.HEADER).isEqualTo("x-custom-auth-headers");
	}

}
