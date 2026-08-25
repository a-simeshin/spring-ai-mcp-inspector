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

package io.inspector.mcp.core.introspect.check;

import java.util.List;

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

import io.inspector.mcp.core.introspect.model.SchemaWarning;
import io.inspector.mcp.core.introspect.model.WarningCode;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link JsonSchemaCompatibilityChecker}. */
@Epic("MCP Inspector Core")
@Feature("JSON-schema compatibility warnings")
class JsonSchemaCompatibilityCheckerTests {

	private final JsonSchemaCompatibilityChecker checker = new JsonSchemaCompatibilityChecker();

	private final JsonMapper objectMapper = new JsonMapper();

	private JsonNode schema(final String json) throws Exception {
		return this.objectMapper.readTree(json);
	}

	@Nested
	@DisplayName("external $ref")
	class ExternalRef {

		@Test
		@Story("A $ref pointing outside the document is reported")
		@Severity(SeverityLevel.CRITICAL)
		@Description("$ref not starting with '#/' yields EXTERNAL_REF with the exact RFC-6901 path")
		void externalRef_warned() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "type": "object",
					  "properties": {
					    "payload": { "$ref": "https://example.com/schemas/payload" }
					  }
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "inputSchema");
			// then
			assertThat(warnings).hasSize(1);
			assertThat(warnings.get(0).code()).isEqualTo(WarningCode.EXTERNAL_REF);
			assertThat(warnings.get(0).severity()).isEqualTo(WarningCode.EXTERNAL_REF.defaultSeverity());
			assertThat(warnings.get(0).element()).isEqualTo("echoTool");
			assertThat(warnings.get(0).path()).isEqualTo("inputSchema/properties/payload/$ref");
			assertThat(warnings.get(0).message()).isNotBlank();
		}

	}

	@Nested
	@DisplayName("unresolved local $ref")
	class UnresolvedRef {

		@Test
		@Story("A local $ref with no target is reported")
		@Severity(SeverityLevel.CRITICAL)
		@Description("local '#/...' $ref without a matching node in the document yields UNRESOLVED_REF")
		void unresolvedRef_warned() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "type": "object",
					  "properties": {
					    "payload": { "$ref": "#/definitions/missing" }
					  }
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "inputSchema");
			// then
			assertThat(warnings).hasSize(1);
			assertThat(warnings.get(0).code()).isEqualTo(WarningCode.UNRESOLVED_REF);
			assertThat(warnings.get(0).severity()).isEqualTo(WarningCode.UNRESOLVED_REF.defaultSeverity());
			assertThat(warnings.get(0).element()).isEqualTo("echoTool");
			assertThat(warnings.get(0).path()).isEqualTo("inputSchema/properties/payload/$ref");
			assertThat(warnings.get(0).message()).isNotBlank();
		}

	}

	@Nested
	@DisplayName("union types")
	class UnionType {

		@Test
		@Story("anyOf unions are reported, nested in objects and arrays")
		@Severity(SeverityLevel.CRITICAL)
		@Description("UNION_TYPE fires for every anyOf node; recursion covers nested objects and array items with exact RFC-6901 paths")
		void unionType_anyOf_warned() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "type": "object",
					  "properties": {
					    "choice": { "anyOf": [ { "type": "string" }, { "type": "number" } ] },
					    "rows": {
					      "type": "array",
					      "items": { "anyOf": [ { "type": "string" }, { "type": "integer" } ] }
					    }
					  }
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "inputSchema");
			// then
			assertThat(warnings).extracting(SchemaWarning::code).containsOnly(WarningCode.UNION_TYPE);
			assertThat(warnings).extracting(SchemaWarning::path)
				.containsExactly("inputSchema/properties/choice/anyOf", "inputSchema/properties/rows/items/anyOf");
			assertThat(warnings).extracting(SchemaWarning::element).containsOnly("echoTool");
		}

		@Test
		@Story("oneOf unions are reported")
		@Severity(SeverityLevel.CRITICAL)
		@Description("oneOf node yields UNION_TYPE with the exact RFC-6901 path")
		void unionType_oneOf_warned() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "type": "object",
					  "properties": {
					    "choice": { "oneOf": [ { "type": "string" }, { "type": "number" } ] }
					  }
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "inputSchema");
			// then
			assertThat(warnings).hasSize(1);
			assertThat(warnings.get(0).code()).isEqualTo(WarningCode.UNION_TYPE);
			assertThat(warnings.get(0).severity()).isEqualTo(WarningCode.UNION_TYPE.defaultSeverity());
			assertThat(warnings.get(0).element()).isEqualTo("echoTool");
			assertThat(warnings.get(0).path()).isEqualTo("inputSchema/properties/choice/oneOf");
		}

	}

	@Nested
	@DisplayName("output schema")
	class OutputSchema {

		@Test
		@Story("The outputSchema prefix is applied")
		@Severity(SeverityLevel.CRITICAL)
		@Description("check with pathPrefix 'outputSchema' yields paths prefixed accordingly (R-OUTPUT v8)")
		void outputSchema_externalRef_warned() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "type": "object",
					  "properties": {
					    "result": { "$ref": "https://example.com/schemas/result" }
					  }
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "outputSchema");
			// then
			assertThat(warnings).hasSize(1);
			assertThat(warnings.get(0).code()).isEqualTo(WarningCode.EXTERNAL_REF);
			assertThat(warnings.get(0).path()).isEqualTo("outputSchema/properties/result/$ref");
		}

	}

	@Nested
	@DisplayName("safe schema")
	class SafeSchema {

		@Test
		@Story("A schema without $ref or unions yields no warnings")
		@Severity(SeverityLevel.NORMAL)
		@Description("objects, arrays and scalar properties without $ref/anyOf/oneOf are reported as compatible")
		void safeSchema_noWarnings() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "type": "object",
					  "properties": {
					    "name": { "type": "string" },
					    "tags": { "type": "array", "items": { "type": "string" } },
					    "meta": { "type": "object", "properties": { "ok": { "type": "boolean" } } }
					  },
					  "required": [ "name" ]
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "inputSchema");
			// then
			assertThat(warnings).isEmpty();
		}

	}

	@Nested
	@DisplayName("resolved local $ref")
	class ResolvedRef {

		@Test
		@Story("A local $ref that resolves is not reported")
		@Severity(SeverityLevel.NORMAL)
		@Description("a '#/...' $ref with a target in the document (also nested) yields no warning")
		void nestedRef_resolved_noWarning() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "properties": {
					    "a": { "$ref": "#/definitions/foo" }
					  },
					  "definitions": {
					    "foo": { "type": "string" }
					  }
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "inputSchema");
			// then
			assertThat(warnings).isEmpty();
		}

	}

	@Nested
	@DisplayName("oversized array index")
	class OversizedArrayIndex {

		@Test
		@Story("An array index beyond int range is unresolved, not an exception")
		@Severity(SeverityLevel.CRITICAL)
		@Description("RFC-6901 array token '999999999999' must yield UNRESOLVED_REF instead of throwing NumberFormatException")
		void oversizedArrayIndex_unresolved_warned() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "type": "object",
					  "properties": {
					    "payload": { "$ref": "#/definitions/arr/999999999999" }
					  },
					  "definitions": {
					    "arr": [ 1, 2 ]
					  }
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "inputSchema");
			// then
			assertThat(warnings).hasSize(1);
			assertThat(warnings.get(0).code()).isEqualTo(WarningCode.UNRESOLVED_REF);
			assertThat(warnings.get(0).severity()).isEqualTo(WarningCode.UNRESOLVED_REF.defaultSeverity());
			assertThat(warnings.get(0).element()).isEqualTo("echoTool");
			assertThat(warnings.get(0).path()).isEqualTo("inputSchema/properties/payload/$ref");
			assertThat(warnings.get(0).message()).isNotBlank();
		}

	}

	@Nested
	@DisplayName("trailing empty token")
	class TrailingEmptyToken {

		@Test
		@Story("A ref ending with '/' targets the empty member and stays unresolved")
		@Severity(SeverityLevel.CRITICAL)
		@Description("RFC-6901 pointer '#/definitions/foo/' must not be collapsed to '#/definitions/foo'; missing '' member yields UNRESOLVED_REF")
		void refEndingWithSlash_unresolved_warned() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "type": "object",
					  "properties": {
					    "payload": { "$ref": "#/definitions/foo/" }
					  },
					  "definitions": {
					    "foo": { "type": "object" }
					  }
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "inputSchema");
			// then
			assertThat(warnings).hasSize(1);
			assertThat(warnings.get(0).code()).isEqualTo(WarningCode.UNRESOLVED_REF);
			assertThat(warnings.get(0).severity()).isEqualTo(WarningCode.UNRESOLVED_REF.defaultSeverity());
			assertThat(warnings.get(0).element()).isEqualTo("echoTool");
			assertThat(warnings.get(0).path()).isEqualTo("inputSchema/properties/payload/$ref");
			assertThat(warnings.get(0).message()).isNotBlank();
		}

	}

	@Nested
	@DisplayName("$ref with union siblings")
	class RefWithUnionSiblings {

		@Test
		@Story("A $ref with sibling anyOf reports both constructs")
		@Severity(SeverityLevel.CRITICAL)
		@Description("$ref plus sibling anyOf yields EXTERNAL_REF and UNION_TYPE with exact RFC-6901 paths")
		void refWithUnionSiblings_bothWarned() throws Exception {
			// given
			final JsonNode schema = schema("""
					{
					  "type": "object",
					  "properties": {
					    "payload": {
					      "$ref": "https://example.com/schemas/base",
					      "anyOf": [ { "type": "string" }, { "type": "number" } ]
					    }
					  }
					}
					""");
			// when
			final List<SchemaWarning> warnings = JsonSchemaCompatibilityCheckerTests.this.checker.check("echoTool",
					schema, "inputSchema");
			// then
			assertThat(warnings).hasSize(2);
			assertThat(warnings).extracting(SchemaWarning::code)
				.containsExactly(WarningCode.EXTERNAL_REF, WarningCode.UNION_TYPE);
			assertThat(warnings).extracting(SchemaWarning::path)
				.containsExactly("inputSchema/properties/payload/$ref", "inputSchema/properties/payload/anyOf");
			assertThat(warnings).extracting(SchemaWarning::severity).containsOnly("warning");
		}

	}

}
