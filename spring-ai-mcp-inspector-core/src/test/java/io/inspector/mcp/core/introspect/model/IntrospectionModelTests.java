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

package io.inspector.mcp.core.introspect.model;

import java.util.List;
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

/** Unit tests for the introspection model records and the kind enum. */
@Epic("MCP Inspector Core")
@Feature("Introspection model")
class IntrospectionModelTests {

	private static final SourceInfo REFLECTED_SOURCE = new SourceInfo("myBean", "com.example.MyBean", "myTool",
			"org.springframework.ai.mcp.annotation.McpTool", true);

	@Nested
	@DisplayName("McpElementKind")
	class McpElementKindTests {

		@Test
		@Story("Tool and resource kinds")
		@Severity(SeverityLevel.NORMAL)
		@Description("the contract exposes TOOL, RESOURCE and RESOURCE_TEMPLATE only (R-SCOPE v13 drops prompts/completes)")
		void kind_enum_containsContractKinds() {
			// when & then
			assertThat(McpElementKind.values()).containsExactly(McpElementKind.TOOL, McpElementKind.RESOURCE,
					McpElementKind.RESOURCE_TEMPLATE);
		}

	}

	@Nested
	@DisplayName("SourceInfo")
	class SourceInfoTests {

		@Test
		@Story("Programmatic source shape")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a spec-only element carries registered=true with null fields, never a null object (R-SOURCE v8)")
		void sourceInfo_programmatic_hasNullFieldsAndRegisteredTrue() {
			// given
			final SourceInfo source = new SourceInfo(null, null, null, null, true);
			// then
			assertThat(source.beanName()).isNull();
			assertThat(source.beanClass()).isNull();
			assertThat(source.method()).isNull();
			assertThat(source.annotation()).isNull();
			assertThat(source.registered()).isTrue();
			assertThat(source).isEqualTo(new SourceInfo(null, null, null, null, true));
			assertThat(source.hashCode()).isEqualTo(new SourceInfo(null, null, null, null, true).hashCode());
			assertThat(source.toString()).contains("registered=true");
		}

		@Test
		@Story("Reflected source shape")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a reflected element carries bean/method/annotation with registered=true")
		void sourceInfo_reflected_exposesBeanAndMethod() {
			// when & then
			assertThat(REFLECTED_SOURCE.beanName()).isEqualTo("myBean");
			assertThat(REFLECTED_SOURCE.beanClass()).isEqualTo("com.example.MyBean");
			assertThat(REFLECTED_SOURCE.method()).isEqualTo("myTool");
			assertThat(REFLECTED_SOURCE.annotation()).isEqualTo("org.springframework.ai.mcp.annotation.McpTool");
			assertThat(REFLECTED_SOURCE.registered()).isTrue();
		}

	}

	@Nested
	@DisplayName("SchemaWarning")
	class SchemaWarningTests {

		@Test
		@Story("Warning carries code, severity, element, path, message")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the outside-component-scan shape: element = FQCN, path = \"$\" (R-PATH v11, R-ELEMENT v11)")
		void schemaWarning_outsideComponentScan_exposesContractFields() {
			// given
			final SchemaWarning warning = new SchemaWarning(WarningCode.OUTSIDE_COMPONENT_SCAN,
					WarningCode.OUTSIDE_COMPONENT_SCAN.defaultSeverity(), "com.example.OutsideTool", "$", "message");
			// then
			assertThat(warning.code()).isEqualTo(WarningCode.OUTSIDE_COMPONENT_SCAN);
			assertThat(warning.severity()).isEqualTo("warning");
			assertThat(warning.element()).isEqualTo("com.example.OutsideTool");
			assertThat(warning.path()).isEqualTo("$");
			assertThat(warning.message()).isEqualTo("message");
			assertThat(warning).isEqualTo(new SchemaWarning(WarningCode.OUTSIDE_COMPONENT_SCAN, "warning",
					"com.example.OutsideTool", "$", "message"));
			assertThat(warning.hashCode()).isEqualTo(new SchemaWarning(WarningCode.OUTSIDE_COMPONENT_SCAN, "warning",
					"com.example.OutsideTool", "$", "message")
				.hashCode());
			assertThat(warning.toString()).contains("code=OUTSIDE_COMPONENT_SCAN");
		}

	}

	@Nested
	@DisplayName("McpElementInfo")
	class McpElementInfoTests {

		@Test
		@Story("Tool element shape")
		@Severity(SeverityLevel.NORMAL)
		@Description("a tool carries name, description and schemas; uri and mimeType are null")
		void elementInfo_tool_exposesContractFields() {
			// given
			final Map<String, Object> inputSchema = Map.of("type", "object");
			final Map<String, Object> outputSchema = Map.of("type", "object");
			final McpElementInfo tool = new McpElementInfo(McpElementKind.TOOL, "echo", "Echo", inputSchema,
					outputSchema, null, null, REFLECTED_SOURCE);
			// then
			assertThat(tool.kind()).isEqualTo(McpElementKind.TOOL);
			assertThat(tool.name()).isEqualTo("echo");
			assertThat(tool.description()).isEqualTo("Echo");
			assertThat(tool.inputSchema()).isEqualTo(inputSchema);
			assertThat(tool.outputSchema()).isEqualTo(outputSchema);
			assertThat(tool.uri()).isNull();
			assertThat(tool.mimeType()).isNull();
			assertThat(tool.source()).isEqualTo(REFLECTED_SOURCE);
		}

		@Test
		@Story("Resource element shape")
		@Severity(SeverityLevel.NORMAL)
		@Description("a resource carries uri and mimeType; schemas are null")
		void elementInfo_resource_exposesContractFields() {
			// given
			final McpElementInfo resource = new McpElementInfo(McpElementKind.RESOURCE, "greeting", "A greeting", null,
					null, "demo://greeting", "text/plain", REFLECTED_SOURCE);
			// then
			assertThat(resource.kind()).isEqualTo(McpElementKind.RESOURCE);
			assertThat(resource.uri()).isEqualTo("demo://greeting");
			assertThat(resource.mimeType()).isEqualTo("text/plain");
			assertThat(resource.inputSchema()).isNull();
			assertThat(resource.outputSchema()).isNull();
			assertThat(resource).isEqualTo(new McpElementInfo(McpElementKind.RESOURCE, "greeting", "A greeting", null,
					null, "demo://greeting", "text/plain", REFLECTED_SOURCE));
			assertThat(resource.hashCode()).isEqualTo(new McpElementInfo(McpElementKind.RESOURCE, "greeting",
					"A greeting", null, null, "demo://greeting", "text/plain", REFLECTED_SOURCE)
				.hashCode());
			assertThat(resource.toString()).contains("uri=demo://greeting");
		}

	}

	@Nested
	@DisplayName("IntrospectionReport")
	class IntrospectionReportTests {

		@Test
		@Story("Report carries tools, resources and warnings")
		@Severity(SeverityLevel.NORMAL)
		@Description("the report exposes the three contract lists")
		void report_exposesContractLists() {
			// given
			final McpElementInfo tool = new McpElementInfo(McpElementKind.TOOL, "echo", null, Map.of(), null, null,
					null, REFLECTED_SOURCE);
			final SchemaWarning warning = new SchemaWarning(WarningCode.NO_MCP_ELEMENTS,
					WarningCode.NO_MCP_ELEMENTS.defaultSeverity(), "", "$", "none");
			// when
			final IntrospectionReport report = new IntrospectionReport(List.of(tool), List.of(), List.of(warning));
			// then
			assertThat(report.tools()).containsExactly(tool);
			assertThat(report.resources()).isEmpty();
			assertThat(report.warnings()).containsExactly(warning);
			assertThat(report).isEqualTo(new IntrospectionReport(List.of(tool), List.of(), List.of(warning)));
			assertThat(report.hashCode())
				.isEqualTo(new IntrospectionReport(List.of(tool), List.of(), List.of(warning)).hashCode());
			assertThat(report.toString()).contains("tools=");
		}

	}

}
