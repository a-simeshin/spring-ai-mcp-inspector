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
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link JsonRpcRelay} Jackson serialization. */
@Epic("MCP Inspector Core")
@Feature("JSON-RPC relay DTO")
class JsonRpcRelayDtoTests {

	private final JsonMapper mapper = new JsonMapper();

	@Nested
	@DisplayName("Jackson round-trip")
	class RoundTrip {

		@Test
		@Story("Full payload")
		@Severity(SeverityLevel.CRITICAL)
		@Description("JsonRpcRelay serializes and deserializes all fields including params via Jackson")
		void roundTrip_withFullPayload_preservesAllFields() throws Exception {
			// given
			final JsonRpcRelay original = new JsonRpcRelay("2.0", 42, "tools/list", Map.of("foo", "bar"));

			// when
			final String json = JsonRpcRelayDtoTests.this.mapper.writeValueAsString(original);

			// then
			assertThat(json).contains("\"jsonrpc\":\"2.0\"");
			assertThat(json).contains("\"method\":\"tools/list\"");
			assertThat(json).contains("\"id\":42");

			final JsonRpcRelay roundTripped = JsonRpcRelayDtoTests.this.mapper.readValue(json, JsonRpcRelay.class);
			assertThat(roundTripped.jsonrpc()).isEqualTo("2.0");
			assertThat(roundTripped.method()).isEqualTo("tools/list");
			assertThat(roundTripped.id()).isEqualTo(42);
			assertThat(roundTripped.params()).isInstanceOf(Map.class);
		}

		@Test
		@Story("Null fields")
		@Severity(SeverityLevel.NORMAL)
		@Description("JsonRpcRelay round-trips null id and params without error")
		void roundTrip_withNullFields_preservesNulls() throws Exception {
			// given
			final JsonRpcRelay original = new JsonRpcRelay("2.0", null, "ping", null);

			// when
			final String json = JsonRpcRelayDtoTests.this.mapper.writeValueAsString(original);
			final JsonRpcRelay roundTripped = JsonRpcRelayDtoTests.this.mapper.readValue(json, JsonRpcRelay.class);

			// then
			assertThat(roundTripped.method()).isEqualTo("ping");
			assertThat(roundTripped.id()).isNull();
			assertThat(roundTripped.params()).isNull();
		}

	}

}
