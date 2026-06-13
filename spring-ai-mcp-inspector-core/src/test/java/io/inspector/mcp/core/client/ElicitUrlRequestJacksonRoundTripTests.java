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

package io.inspector.mcp.core.client;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson-3 round-trip tests for url-mode elicitation types.
 *
 * <p>
 * Uses the same {@code tools.jackson.databind.json.JsonMapper} the inspector starters use
 * (created with {@code new JsonMapper()}) to verify that:
 * <ul>
 * <li>{@link McpSchema.ElicitUrlRequest} serialises with a {@code "mode"} field equal to
 * {@code "url"} and includes {@code "url"} and {@code "elicitationId"} fields.</li>
 * <li>{@link McpSchema.ElicitResult} deserialises from a minimal
 * {@code {"action":"accept"}} JSON string to an instance with {@code action()==ACCEPT}
 * and {@code content()==null}.</li>
 * </ul>
 *
 * <p>
 * This test is the single gate that confirms the Jackson-3 / {@code com.fasterxml}
 * annotation bridge works correctly for the url-mode elicitation feature.
 *
 * @author Artem Simeshin
 */
@Epic("MCP Inspector Core")
@Feature("Url-mode elicitation Jackson round-trip")
class ElicitUrlRequestJacksonRoundTripTests {

	/**
	 * The same mapper the starters create: {@code new JsonMapper()} from
	 * {@code tools.jackson.databind.json}. Jackson-3 still reads
	 * {@code com.fasterxml.jackson.annotation.*} through its built-in annotation bridge,
	 * so {@code @JsonTypeInfo} / {@code @JsonSubTypes} on {@code ElicitRequest} resolve
	 * correctly.
	 */
	private final JsonMapper mapper = new JsonMapper();

	@Nested
	@DisplayName("ElicitUrlRequest serialisation")
	class Serialisation {

		@Test
		@Story("Mode field")
		@Severity(SeverityLevel.BLOCKER)
		@Description("valueToTree on ElicitUrlRequest emits mode=url, url, and elicitationId fields")
		void valueToTree_emitsModeUrlAndElicitationId() {
			// given
			final McpSchema.ElicitUrlRequest request = McpSchema.ElicitUrlRequest.builder("m", "https://x/y", "id123")
				.build();

			// when
			final JsonNode node = ElicitUrlRequestJacksonRoundTripTests.this.mapper.valueToTree(request);

			// then – mode field must be "url"
			assertThat(node.get("mode")).as("mode node must be present").isNotNull();
			assertThat(node.get("mode").asText()).as("mode must equal \"url\"").isEqualTo("url");

			// url field must be present
			assertThat(node.get("url")).as("url node must be present").isNotNull();
			assertThat(node.get("url").asText()).isEqualTo("https://x/y");

			// elicitationId field must be present
			assertThat(node.get("elicitationId")).as("elicitationId node must be present").isNotNull();
			assertThat(node.get("elicitationId").asText()).isEqualTo("id123");
		}

	}

	@Nested
	@DisplayName("ElicitResult deserialisation")
	class Deserialisation {

		@Test
		@Story("Accept action")
		@Severity(SeverityLevel.BLOCKER)
		@Description("treeToValue of {\"action\":\"accept\"} into ElicitResult yields ACCEPT with null content")
		void treeToValue_acceptJson_yieldsAcceptWithNullContent() throws Exception {
			// given
			final JsonNode node = ElicitUrlRequestJacksonRoundTripTests.this.mapper.readTree("{\"action\":\"accept\"}");

			// when
			final McpSchema.ElicitResult result = ElicitUrlRequestJacksonRoundTripTests.this.mapper.treeToValue(node,
					McpSchema.ElicitResult.class);

			// then
			assertThat(result.action()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
			assertThat(result.content()).isNull();
		}

	}

}
