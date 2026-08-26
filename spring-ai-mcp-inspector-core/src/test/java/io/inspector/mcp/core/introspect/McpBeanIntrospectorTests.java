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

package io.inspector.mcp.core.introspect;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
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
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import reactor.core.publisher.Mono;

import io.inspector.mcp.core.introspect.imported.ImportedConfig;
import io.inspector.mcp.core.introspect.imported.ImportedOutsideTool;
import io.inspector.mcp.core.introspect.model.IntrospectionReport;
import io.inspector.mcp.core.introspect.model.McpElementInfo;
import io.inspector.mcp.core.introspect.model.McpElementKind;
import io.inspector.mcp.core.introspect.model.SchemaWarning;
import io.inspector.mcp.core.introspect.model.SourceInfo;
import io.inspector.mcp.core.introspect.model.WarningCode;
import io.inspector.mcp.core.introspect.outside.resources.OutsideMcpResource;
import io.inspector.mcp.core.introspect.outside.tools.OutsideMcpTool;
import io.inspector.mcp.core.introspect.scanroots.ScanRootApplication;
import io.inspector.mcp.core.introspect.scanroots.ScanRootOutsideTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Unit tests for {@link McpBeanIntrospector}. */
@Epic("MCP Inspector Core")
@Feature("MCP bean introspection")
class McpBeanIntrospectorTests {

	private static final String MCP_TOOL_FQCN = "org.springframework.ai.mcp.annotation.McpTool";

	private static final String MCP_RESOURCE_FQCN = "org.springframework.ai.mcp.annotation.McpResource";

	private static String sourceMethod(final McpElementInfo tool) {
		return tool.source().method();
	}

	private static void assertProgrammaticSource(final McpElementInfo element) {
		assertThat(element.source()).isEqualTo(new SourceInfo(null, null, null, null, true));
	}

	private static McpServerFeatures.SyncToolSpecification syncToolSpec(final String name,
			final Map<String, Object> inputSchema) {
		return syncToolSpec(name, "Tool " + name, inputSchema, null);
	}

	private static McpServerFeatures.SyncToolSpecification syncToolSpec(final String name, final String description,
			final Map<String, Object> inputSchema, final Map<String, Object> outputSchema) {
		final McpSchema.Tool tool = McpSchema.Tool.builder(name, inputSchema)
			.description(description)
			.outputSchema(outputSchema)
			.build();
		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> toolResult())
			.build();
	}

	private static McpServerFeatures.AsyncToolSpecification asyncToolSpec(final String name,
			final Map<String, Object> inputSchema) {
		final McpSchema.Tool tool = McpSchema.Tool.builder(name, inputSchema).description("Tool " + name).build();
		return McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> Mono.just(toolResult()))
			.build();
	}

	private static McpStatelessServerFeatures.SyncToolSpecification statelessSyncToolSpec(final String name,
			final Map<String, Object> inputSchema) {
		final McpSchema.Tool tool = McpSchema.Tool.builder(name, inputSchema).description("Tool " + name).build();
		return McpStatelessServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((transportContext, request) -> toolResult())
			.build();
	}

	private static McpStatelessServerFeatures.AsyncToolSpecification asyncStatelessToolSpec(final String name,
			final Map<String, Object> inputSchema) {
		final McpSchema.Tool tool = McpSchema.Tool.builder(name, inputSchema).description("Tool " + name).build();
		return McpStatelessServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((transportContext, request) -> Mono.just(toolResult()))
			.build();
	}

	private static McpSchema.CallToolResult toolResult() {
		return McpSchema.CallToolResult.builder(List.of(new McpSchema.TextContent("ok"))).build();
	}

	// ------------------------------------------------------------------

	@Nested
	@DisplayName("spec registry read")
	class SpecRegistryRead {

		@Test
		@Story("Schemas come from the registered spec registry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("inputSchema/outputSchema are read from the List<SyncToolSpecification> bean, never regenerated")
		void schemaReadFromRegisteredToolSpec() {
			// given
			final Map<String, Object> inputSchema = Map.of("type", "object", "required", List.of("message"));
			final Map<String, Object> outputSchema = Map.of("type", "object");
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					SchemaToolConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.tools()).singleElement().satisfies((tool) -> {
				assertThat(tool.name()).isEqualTo("schemaTool");
				assertThat(tool.inputSchema()).isEqualTo(inputSchema);
				assertThat(tool.outputSchema()).isEqualTo(outputSchema);
				assertThat(tool.description()).isEqualTo("Tool with schema");
			});
		}

		@Test
		@Story("Stateless sync tool specs are read")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a declared List<McpStatelessServerFeatures.SyncToolSpecification> bean is reported with a programmatic source")
		void statelessToolSpec_read() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					StatelessSyncToolConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.tools()).extracting(McpElementInfo::name).containsExactly("statelessTool");
			assertThat(report.tools()).singleElement().satisfies(McpBeanIntrospectorTests::assertProgrammaticSource);
		}

		@Test
		@Story("Stateful async tool specs are read")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a declared List<McpServerFeatures.AsyncToolSpecification> bean is reported (R-ASYNC v13)")
		void asyncToolSpec_read() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					AsyncToolConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.tools()).extracting(McpElementInfo::name).containsExactly("asyncTool");
			assertThat(report.tools()).singleElement().satisfies(McpBeanIntrospectorTests::assertProgrammaticSource);
		}

		@Test
		@Story("Stateful async resource template specs are read")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a declared List<McpServerFeatures.AsyncResourceTemplateSpecification> bean is reported as RESOURCE_TEMPLATE (R-ASYNC v13)")
		void asyncResourceTemplateSpec_read() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					AsyncResourceTemplateConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.resources()).singleElement().satisfies((resource) -> {
				assertThat(resource.kind()).isEqualTo(McpElementKind.RESOURCE_TEMPLATE);
				assertThat(resource.name()).isEqualTo("asyncTemplate");
				assertThat(resource.uri()).isEqualTo("tmpl://async/{id}");
				assertProgrammaticSource(resource);
			});
		}

		@Test
		@Story("Stateless async tool specs are read")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a declared List<McpStatelessServerFeatures.AsyncToolSpecification> bean is reported (R-ASYNC v13)")
		void asyncStatelessToolSpec_read() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					AsyncStatelessToolConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.tools()).extracting(McpElementInfo::name).containsExactly("asyncStatelessTool");
			assertThat(report.tools()).singleElement().satisfies(McpBeanIntrospectorTests::assertProgrammaticSource);
		}

		@Test
		@Story("Direct spec beans are not reported")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a direct (non-List) @Bean SyncToolSpecification is not a registry element and is NOT reported (R10)")
		void directSpecBean_notReported() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					DirectSpecConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.tools()).extracting(McpElementInfo::name).containsExactly("validTool");
		}

	}

	@Nested
	@DisplayName("bean mapping")
	class BeanMapping {

		@Test
		@Story("Tools map to their declaring bean and method")
		@Severity(SeverityLevel.CRITICAL)
		@Description("echo/sum/currentTime are reported with source {beanName, beanClass, method, annotation FQCN, registered=true}")
		void toolToBeanMapping_echoSumCurrentTime() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					EchoSumCurrentTimeConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.tools()).extracting(McpElementInfo::name, McpBeanIntrospectorTests::sourceMethod)
				.containsExactlyInAnyOrder(tuple("echo", "echo"), tuple("sum", "sum"),
						tuple("currentTime", "currentTime"));
			assertThat(report.tools()).allSatisfy((tool) -> {
				assertThat(tool.source().registered()).isTrue();
				assertThat(tool.source().beanName()).isEqualTo("toolsProvider");
				assertThat(tool.source().beanClass()).isEqualTo(EchoSumCurrentTimeProvider.class.getName());
				assertThat(tool.source().annotation()).isEqualTo(MCP_TOOL_FQCN);
				assertThat(tool.inputSchema()).isNotNull();
			});
		}

		@Test
		@Story("Resources map to their declaring bean and method")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a registered @McpResource is reported with its bean/method source (R9)")
		void resourceToBeanMapping() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					GreetingResourceConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.resources()).singleElement().satisfies((resource) -> {
				assertThat(resource.kind()).isEqualTo(McpElementKind.RESOURCE);
				assertThat(resource.name()).isEqualTo("demo-greeting");
				assertThat(resource.uri()).isEqualTo("demo://greeting");
				assertThat(resource.mimeType()).isEqualTo("text/plain");
				assertThat(resource.source()).isEqualTo(new SourceInfo("greetingProvider",
						GreetingProvider.class.getName(), "greeting", MCP_RESOURCE_FQCN, true));
			});
		}

		@Test
		@Story("Resource templates map to their declaring bean and method")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a registered @McpResource with a URI template maps to its bean/method and is a RESOURCE_TEMPLATE")
		void resourceTemplateToBeanMapping() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					FileTemplateConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.resources()).singleElement().satisfies((resource) -> {
				assertThat(resource.kind()).isEqualTo(McpElementKind.RESOURCE_TEMPLATE);
				assertThat(resource.name()).isEqualTo("file");
				assertThat(resource.uri()).isEqualTo("tmpl://files/{id}");
				assertThat(resource.source().beanName()).isEqualTo("filesProvider");
				assertThat(resource.source().method()).isEqualTo("file");
				assertThat(resource.source().annotation()).isEqualTo(MCP_RESOURCE_FQCN);
			});
		}

		@Test
		@Story("URI templates classify resources as RESOURCE_TEMPLATE")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a resource spec whose uri contains {..} is classified RESOURCE_TEMPLATE (R-TEMPLATE v11)")
		void annotationResourceTemplate_classifiedAsResourceTemplate() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					UriTemplateResourceConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.resources()).singleElement().satisfies((resource) -> {
				assertThat(resource.kind()).isEqualTo(McpElementKind.RESOURCE_TEMPLATE);
				assertThat(resource.uri()).isEqualTo("tmpl://items/{id}");
				assertThat(resource.name()).isEqualTo("itemsTemplate");
			});
		}

		@Test
		@Story("Unnamed tools fall back to the method name")
		@Severity(SeverityLevel.CRITICAL)
		@Description("@McpTool without name is reported under method.getName() (R-NAME-FALLBACK v13)")
		void unnamedTool_nameFallsBackToMethod() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					UnnamedToolConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.tools()).singleElement().satisfies((tool) -> {
				assertThat(tool.name()).isEqualTo("unnamedTool");
				assertThat(tool.source().method()).isEqualTo("unnamedTool");
			});
		}

		@Test
		@Story("Unnamed resources fall back to the method name")
		@Severity(SeverityLevel.CRITICAL)
		@Description("@McpResource without name is reported under method.getName(), uri always from the annotation (R-NAME-FALLBACK v13)")
		void unnamedResource_nameFallsBackToMethod() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					UnnamedResourceConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.resources()).singleElement().satisfies((resource) -> {
				assertThat(resource.name()).isEqualTo("unnamedResource");
				assertThat(resource.uri()).isEqualTo("demo://unnamed");
				assertThat(resource.source().method()).isEqualTo("unnamedResource");
			});
		}

	}

	@Nested
	@DisplayName("outside component scan")
	class OutsideComponentScan {

		@Test
		@Story("Classes with @McpTool outside component scan are warned")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a class with a method-level @McpTool inside a scan root that is not a bean yields OUTSIDE_COMPONENT_SCAN")
		void outsideComponentScan_warning() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					ToolScanConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.warnings()).anySatisfy((warning) -> {
				assertThat(warning.code()).isEqualTo(WarningCode.OUTSIDE_COMPONENT_SCAN);
				assertThat(warning.severity()).isEqualTo("warning");
				assertThat(warning.path()).isEqualTo("$");
				assertThat(warning.element()).isEqualTo(OutsideMcpTool.class.getName());
			});
		}

		@Test
		@Story("Classes with @McpResource outside component scan are warned")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the OUTSIDE_COMPONENT_SCAN detection covers @McpResource too (R-OUTSIDE-RESOURCE v11)")
		void outsideComponentScan_resourceWarning() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					ResourceScanConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.warnings()).anySatisfy((warning) -> {
				assertThat(warning.code()).isEqualTo(WarningCode.OUTSIDE_COMPONENT_SCAN);
				assertThat(warning.element()).isEqualTo(OutsideMcpResource.class.getName());
			});
		}

		@Test
		@Story("Default @SpringBootApplication root resolves to its package")
		@Severity(SeverityLevel.CRITICAL)
		@Description("@SpringBootApplication without basePackages resolves the scan root to the declaring class package (R-SCAN v12)")
		void outsideComponentScan_defaultSpringBootApplication() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					ScanRootApplication.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.warnings()).extracting(SchemaWarning::element)
				.contains(ScanRootOutsideTool.class.getName());
			assertThat(report.warnings()).anySatisfy((warning) -> {
				assertThat(warning.code()).isEqualTo(WarningCode.OUTSIDE_COMPONENT_SCAN);
				assertThat(warning.path()).isEqualTo("$");
				assertThat(warning.severity()).isEqualTo("warning");
			});
		}

		@Test
		@Story("Multiple @ComponentScan roots are all resolved")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a @ComponentScans container contributes every base package as a scan root (R-SCAN v12)")
		void outsideComponentScan_multipleComponentScans() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					MultiScanConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.warnings()).extracting(SchemaWarning::element)
				.contains(ScanRootOutsideTool.class.getName(), ImportedOutsideTool.class.getName());
		}

		@Test
		@Story("@Import-ed configurations contribute scan roots")
		@Severity(SeverityLevel.CRITICAL)
		@Description("scan roots of classes imported via @Import are collected (R-SCAN v12)")
		void outsideComponentScan_importedConfig() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					ImportingConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.warnings()).extracting(SchemaWarning::element)
				.contains(ImportedOutsideTool.class.getName());
		}

		@Test
		@Story("No scan root means no scan and no warning")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a context without any @ComponentScan/@SpringBootApplication skips classpath scanning entirely (R-SCAN v12)")
		void outsideComponentScan_noRoot() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					PlainBeansConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.warnings()).isEmpty();
		}

	}

	@Nested
	@DisplayName("report")
	class Report {

		@Test
		@Story("Safe schemas carry no warnings")
		@Severity(SeverityLevel.NORMAL)
		@Description("a context with plain tools and resources produces zero warnings and no NO_MCP_ELEMENTS")
		void safeSchema_noWarnings() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SafeConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.warnings()).isEmpty();
			assertThat(report.tools()).isNotEmpty();
			assertThat(report.resources()).isNotEmpty();
		}

		@Test
		@Story("Empty context reports NO_MCP_ELEMENTS")
		@Severity(SeverityLevel.CRITICAL)
		@Description("an empty context yields empty tools/resources and exactly one NO_MCP_ELEMENTS warning")
		void emptyContext_noMcpElements() {
			// given
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
			context.refresh();
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(report.tools()).isEmpty();
			assertThat(report.resources()).isEmpty();
			assertThat(report.warnings()).singleElement().satisfies((warning) -> {
				assertThat(warning.code()).isEqualTo(WarningCode.NO_MCP_ELEMENTS);
				assertThat(warning.severity()).isEqualTo("warning");
				assertThat(warning.element()).isEmpty();
				assertThat(warning.path()).isEqualTo("$");
			});
		}

		@Test
		@Story("Failing registry beans degrade, never fail the report")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a lazy List<SyncToolSpecification> bean that throws is requested, skipped, and the valid tool is retained (R-DEGRADE v13)")
		void failingBean_degradesNotFailsReport() {
			// given
			DegradationConfig.REQUESTED.set(false);
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					DegradationConfig.class);
			// when
			final IntrospectionReport report = new McpBeanIntrospector().introspect(context);
			// then
			assertThat(DegradationConfig.REQUESTED).isTrue();
			assertThat(report.tools()).extracting(McpElementInfo::name).containsExactly("validTool");
		}

	}

	// ------------------------------------------------------------------

	@Configuration
	static class SchemaToolConfig {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
			final Map<String, Object> inputSchema = Map.of("type", "object", "required", List.of("message"));
			final Map<String, Object> outputSchema = Map.of("type", "object");
			return List.of(syncToolSpec("schemaTool", "Tool with schema", inputSchema, outputSchema));
		}

	}

	@Configuration
	static class StatelessSyncToolConfig {

		@Bean
		List<McpStatelessServerFeatures.SyncToolSpecification> toolSpecs() {
			return List.of(statelessSyncToolSpec("statelessTool", Map.of("type", "object")));
		}

	}

	@Configuration
	static class AsyncToolConfig {

		@Bean
		List<McpServerFeatures.AsyncToolSpecification> toolSpecs() {
			return List.of(asyncToolSpec("asyncTool", Map.of("type", "object")));
		}

	}

	@Configuration
	static class AsyncResourceTemplateConfig {

		@Bean
		List<McpServerFeatures.AsyncResourceTemplateSpecification> templateSpecs() {
			return List.of(
					new McpServerFeatures.AsyncResourceTemplateSpecification(
							McpSchema.ResourceTemplate.builder("tmpl://async/{id}", "asyncTemplate")
								.description("Async template")
								.build(),
							(exchange, request) -> Mono.just(new McpSchema.ReadResourceResult(List.of()))));
		}

	}

	@Configuration
	static class AsyncStatelessToolConfig {

		@Bean
		List<McpStatelessServerFeatures.AsyncToolSpecification> toolSpecs() {
			return List.of(asyncStatelessToolSpec("asyncStatelessTool", Map.of("type", "object")));
		}

	}

	@Configuration
	static class DirectSpecConfig {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> validSpecs() {
			return List.of(syncToolSpec("validTool", Map.of("type", "object")));
		}

		@Bean
		McpServerFeatures.SyncToolSpecification directSpec() {
			return syncToolSpec("directTool", Map.of("type", "object"));
		}

	}

	@Configuration
	static class EchoSumCurrentTimeConfig {

		@Bean
		EchoSumCurrentTimeProvider toolsProvider() {
			return new EchoSumCurrentTimeProvider();
		}

		@Bean
		List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
			return List.of(syncToolSpec("echo", Map.of("type", "object")),
					syncToolSpec("sum", Map.of("type", "object")),
					syncToolSpec("currentTime", Map.of("type", "object")));
		}

	}

	static class EchoSumCurrentTimeProvider {

		@McpTool(name = "echo")
		String echo(final String message) {
			return message;
		}

		@McpTool
		String sum(final int a, final int b) {
			return String.valueOf(a + b);
		}

		@McpTool(name = "currentTime")
		String currentTime() {
			return "now";
		}

	}

	@Configuration
	static class GreetingResourceConfig {

		@Bean
		GreetingProvider greetingProvider() {
			return new GreetingProvider();
		}

		@Bean
		List<McpServerFeatures.SyncResourceSpecification> resourceSpecs() {
			return List.of(new McpServerFeatures.SyncResourceSpecification(
					McpSchema.Resource.builder("demo://greeting", "demo-greeting")
						.description("A greeting")
						.mimeType("text/plain")
						.build(),
					(exchange, request) -> new McpSchema.ReadResourceResult(
							List.of(new McpSchema.TextResourceContents("demo://greeting", "text/plain", "hello")))));
		}

	}

	static class GreetingProvider {

		@McpResource(uri = "demo://greeting", name = "demo-greeting", description = "A greeting")
		String greeting() {
			return "hello";
		}

	}

	@Configuration
	static class FileTemplateConfig {

		@Bean
		FilesProvider filesProvider() {
			return new FilesProvider();
		}

		@Bean
		List<McpServerFeatures.SyncResourceTemplateSpecification> templateSpecs() {
			return List.of(new McpServerFeatures.SyncResourceTemplateSpecification(
					McpSchema.ResourceTemplate.builder("tmpl://files/{id}", "file").description("A file").build(),
					(exchange, request) -> new McpSchema.ReadResourceResult(List.of())));
		}

	}

	static class FilesProvider {

		@McpResource(uri = "tmpl://files/{id}", name = "file", description = "A file")
		String file(final String id) {
			return id;
		}

	}

	@Configuration
	static class UriTemplateResourceConfig {

		@Bean
		List<McpServerFeatures.SyncResourceSpecification> resourceSpecs() {
			return List.of(new McpServerFeatures.SyncResourceSpecification(
					McpSchema.Resource.builder("tmpl://items/{id}", "itemsTemplate").build(),
					(exchange, request) -> new McpSchema.ReadResourceResult(List.of())));
		}

	}

	@Configuration
	static class UnnamedToolConfig {

		@Bean
		UnnamedToolProvider unnamedToolProvider() {
			return new UnnamedToolProvider();
		}

		@Bean
		List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
			return List.of(syncToolSpec("unnamedTool", Map.of("type", "object")));
		}

	}

	static class UnnamedToolProvider {

		@McpTool
		String unnamedTool(final String input) {
			return input;
		}

	}

	@Configuration
	static class UnnamedResourceConfig {

		@Bean
		UnnamedResourceProvider unnamedResourceProvider() {
			return new UnnamedResourceProvider();
		}

		@Bean
		List<McpServerFeatures.SyncResourceSpecification> resourceSpecs() {
			return List.of(new McpServerFeatures.SyncResourceSpecification(
					McpSchema.Resource.builder("demo://unnamed", "unnamedResource").build(),
					(exchange, request) -> new McpSchema.ReadResourceResult(List.of())));
		}

	}

	static class UnnamedResourceProvider {

		@McpResource(uri = "demo://unnamed")
		String unnamedResource() {
			return "hi";
		}

	}

	@Configuration
	@ComponentScan(basePackages = "io.inspector.mcp.core.introspect.outside.tools")
	static class ToolScanConfig {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
			return List.of(syncToolSpec("scanTool", Map.of("type", "object")));
		}

	}

	@Configuration
	@ComponentScan(basePackages = "io.inspector.mcp.core.introspect.outside.resources")
	static class ResourceScanConfig {

		@Bean
		List<McpServerFeatures.SyncResourceSpecification> resourceSpecs() {
			return List.of(new McpServerFeatures.SyncResourceSpecification(
					McpSchema.Resource.builder("demo://scanned", "scannedResource").build(),
					(exchange, request) -> new McpSchema.ReadResourceResult(List.of())));
		}

	}

	@ComponentScans({ @ComponentScan(basePackages = "io.inspector.mcp.core.introspect.scanroots"),
			@ComponentScan(basePackages = "io.inspector.mcp.core.introspect.imported") })
	@Configuration
	static class MultiScanConfig {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
			return List.of(syncToolSpec("multiTool", Map.of("type", "object")));
		}

	}

	@Configuration
	@Import(ImportedConfig.class)
	static class ImportingConfig {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
			return List.of(syncToolSpec("importingTool", Map.of("type", "object")));
		}

	}

	@Configuration
	static class PlainBeansConfig {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
			return List.of(syncToolSpec("plainTool", Map.of("type", "object")));
		}

	}

	@Configuration
	static class SafeConfig {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
			return List.of(syncToolSpec("safeTool", Map.of("type", "object")));
		}

		@Bean
		List<McpServerFeatures.SyncResourceSpecification> resourceSpecs() {
			return List.of(new McpServerFeatures.SyncResourceSpecification(
					McpSchema.Resource.builder("demo://safe", "safeResource").build(),
					(exchange, request) -> new McpSchema.ReadResourceResult(List.of())));
		}

	}

	@Configuration
	static class DegradationConfig {

		static final AtomicBoolean REQUESTED = new AtomicBoolean(false);

		@Bean
		List<McpServerFeatures.SyncToolSpecification> validSpecs() {
			return List.of(syncToolSpec("validTool", Map.of("type", "object")));
		}

		@Bean
		@Lazy
		List<McpServerFeatures.SyncToolSpecification> failingSpecs() {
			DegradationConfig.REQUESTED.set(true);
			throw new IllegalStateException("introspection-degrade-fixture");
		}

	}

}
