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

package io.inspector.mcp.webflux.it;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Reactive parallel of {@code McpIntrospectionEndpointIT}: real HTTP against a booted
 * {@code TestMcpServerApp} (WebFlux) with the introspection fixtures registered as
 * {@code List<...>} spec beans (R10). Same scenarios as the WebMvc IT.
 */
@Epic("MCP Inspector WebFlux")
@Feature("Introspection endpoint (integration)")
@AutoConfigureWebTestClient
@SpringBootTest(
		classes = { TestMcpServerApp.class, IncompatibleSchemaToolsConfiguration.class,
				ExtendedSpecificationsConfiguration.class, AsyncSpecificationsConfiguration.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-intro", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false",
				"spring.application.name=mcp-inspector-itest-flux-intro" })
class McpIntrospectionWebFluxEndpointIT {

	private static final String MCP_TOOL_FQCN = "org.springframework.ai.mcp.annotation.McpTool";

	private static final String MCP_RESOURCE_FQCN = "org.springframework.ai.mcp.annotation.McpResource";

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private JsonMapper objectMapper;

	@Test
	@DisplayName("GET /introspection returns 200 with tools carrying bean/method sources")
	@Story("Introspection endpoint")
	@Severity(SeverityLevel.CRITICAL)
	@Description("echo/sum/currentTime are reported with a non-null source mapping to TestToolsProvider methods")
	void introspectionEndpoint_returns200AndSource() {
		// when
		final JsonNode body = getReport();
		// then
		assertThat(body.has("tools")).isTrue();
		assertThat(body.has("resources")).isTrue();
		assertThat(body.has("warnings")).isTrue();
		final JsonNode echo = toolByName(body, "echo");
		assertThat(echo.path("kind").asText()).isEqualTo("TOOL");
		assertThat(echo.path("name").asText()).isEqualTo("echo");
		assertThat(echo.path("inputSchema").isObject()).isTrue();
		assertSource(echo, "testToolsProvider", TestToolsProvider.class.getName(), "echo", MCP_TOOL_FQCN);
		final JsonNode sum = toolByName(body, "sum");
		assertSource(sum, "testToolsProvider", TestToolsProvider.class.getName(), "sum", MCP_TOOL_FQCN);
		final JsonNode currentTime = toolByName(body, "currentTime");
		assertSource(currentTime, "testToolsProvider", TestToolsProvider.class.getName(), "currentTime", MCP_TOOL_FQCN);
	}

	@Test
	@DisplayName("GET /introspection reports a registered @McpResource with its bean source")
	@Story("Resources")
	@Severity(SeverityLevel.CRITICAL)
	@Description("the @McpResource greeting is reported with kind RESOURCE and source mapping to TestResourcesProvider.greeting (R9)")
	void resourceRegistered_returnsSource() {
		// when
		final JsonNode body = getReport();
		// then
		final JsonNode greeting = resourceByName(body, "greeting");
		assertThat(greeting.path("kind").asText()).isEqualTo("RESOURCE");
		assertThat(greeting.path("uri").asText()).isEqualTo("inspector://greeting");
		assertThat(greeting.path("mimeType").asText()).isEqualTo("text/plain");
		assertSource(greeting, "testResourcesProvider", TestResourcesProvider.class.getName(), "greeting",
				MCP_RESOURCE_FQCN);
	}

	@Test
	@DisplayName("GET /introspection classifies an annotated URI-template resource as RESOURCE_TEMPLATE")
	@Story("Resources")
	@Severity(SeverityLevel.CRITICAL)
	@Description("the @McpResource fileTemplate with uri inspector://files/{id} is reported as RESOURCE_TEMPLATE with its source (R-TEMPLATE v11)")
	void annotationResourceTemplate_returnsSource() {
		// when
		final JsonNode body = getReport();
		// then
		final JsonNode fileTemplate = resourceByName(body, "fileTemplate");
		assertThat(fileTemplate.path("kind").asText()).isEqualTo("RESOURCE_TEMPLATE");
		assertThat(fileTemplate.path("uri").asText()).isEqualTo("inspector://files/{id}");
		assertSource(fileTemplate, "testResourcesProvider", TestResourcesProvider.class.getName(), "fileTemplate",
				MCP_RESOURCE_FQCN);
	}

	@Test
	@DisplayName("GET /introspection yields exactly six external/unresolved/union warnings for the incompatible fixture")
	@Story("Schema compatibility warnings")
	@Severity(SeverityLevel.CRITICAL)
	@Description("the incompatibleSchemaTool fixture produces EXTERNAL_REF/UNRESOLVED_REF/UNION_TYPE on input AND output, with exact RFC-6901 paths, all severity=warning, all element=incompatibleSchemaTool (R-OUTPUT-FIXTURE v11)")
	void incompatibleSchemaFixture_yieldsExternalUnresolvedUnionWarnings() {
		// given
		final JsonNode body = getReport();
		assertThat(toolByName(body, "incompatibleSchemaTool").path("inputSchema").isObject()).isTrue();
		assertThat(toolByName(body, "incompatibleSchemaTool").path("outputSchema").isObject()).isTrue();
		// when
		final List<Object> actual = new ArrayList<>();
		for (final JsonNode warning : body.path("warnings")) {
			if ("incompatibleSchemaTool".equals(warning.path("element").asText())) {
				actual.add(tuple(warning.path("code").asText(), warning.path("path").asText(),
						warning.path("severity").asText(), warning.path("element").asText()));
			}
		}
		// then — exactly six warnings for the fixture tool, exact code/path/severity
		assertThat(actual).hasSize(6)
			.containsExactlyInAnyOrder(
					tuple("EXTERNAL_REF", "inputSchema/properties/payload/$ref", "warning", "incompatibleSchemaTool"),
					tuple("UNRESOLVED_REF", "inputSchema/properties/missing/$ref", "warning", "incompatibleSchemaTool"),
					tuple("UNION_TYPE", "inputSchema/properties/choice/anyOf", "warning", "incompatibleSchemaTool"),
					tuple("EXTERNAL_REF", "outputSchema/properties/payload/$ref", "warning", "incompatibleSchemaTool"),
					tuple("UNRESOLVED_REF", "outputSchema/properties/missing/$ref", "warning",
							"incompatibleSchemaTool"),
					tuple("UNION_TYPE", "outputSchema/properties/choice/anyOf", "warning", "incompatibleSchemaTool"));
	}

	@Test
	@DisplayName("GET /introspection reports stateless List beans with a programmatic source")
	@Story("Spec registry")
	@Severity(SeverityLevel.CRITICAL)
	@Description("the declared List<McpStatelessServerFeatures.SyncToolSpecification> and SyncResourceSpecification beans are reported with registered=true and null provenance (R-STATELESS v10)")
	void statelessDeclaredListBeans_reported() {
		// when
		final JsonNode body = getReport();
		// then
		final JsonNode statelessTool = toolByName(body, "statelessTool");
		assertProgrammaticSource(statelessTool);
		final JsonNode statelessResource = resourceByName(body, "statelessResource");
		assertThat(statelessResource.path("kind").asText()).isEqualTo("RESOURCE");
		assertProgrammaticSource(statelessResource);
	}

	@Test
	@DisplayName("GET /introspection reports stateful and stateless async List beans")
	@Story("Spec registry")
	@Severity(SeverityLevel.CRITICAL)
	@Description("List<AsyncToolSpecification> and List<McpStatelessServerFeatures.AsyncToolSpecification> beans are both reported with a non-null source (R-ASYNC v13)")
	void asyncDeclaredListBeans_reported() {
		// when
		final JsonNode body = getReport();
		// then
		final JsonNode asyncTool = toolByName(body, "asyncTool");
		assertThat(asyncTool.path("kind").asText()).isEqualTo("TOOL");
		assertProgrammaticSource(asyncTool);
		final JsonNode asyncStatelessTool = toolByName(body, "asyncStatelessTool");
		assertThat(asyncStatelessTool.path("kind").asText()).isEqualTo("TOOL");
		assertProgrammaticSource(asyncStatelessTool);
	}

	@Test
	@DisplayName("GET /introspection falls back to the method name for unnamed annotations")
	@Story("Spec registry")
	@Severity(SeverityLevel.CRITICAL)
	@Description("an @McpTool and an @McpResource without a name attribute are reported with source.method equal to the method name (R-NAME-FALLBACK v13)")
	void unnamedToolAndResource_reportMethodNameSource() {
		// when
		final JsonNode body = getReport();
		// then
		final JsonNode unnamedTool = toolByName(body, "unnamedTool");
		assertSource(unnamedTool, "testResourcesProvider", TestResourcesProvider.class.getName(), "unnamedTool",
				MCP_TOOL_FQCN);
		final JsonNode unnamedResource = resourceByName(body, "unnamedResource");
		assertThat(unnamedResource.path("kind").asText()).isEqualTo("RESOURCE");
		assertThat(unnamedResource.path("uri").asText()).isEqualTo("inspector://unnamed");
		assertSource(unnamedResource, "testResourcesProvider", TestResourcesProvider.class.getName(), "unnamedResource",
				MCP_RESOURCE_FQCN);
	}

	@Test
	@DisplayName("GET /introspection warns about annotated classes outside the component scan")
	@Story("Schema compatibility warnings")
	@Severity(SeverityLevel.CRITICAL)
	@Description("UnregisteredMcpTools and UnregisteredMcpResources live in the scan root but are not beans, so OUTSIDE_COMPONENT_SCAN warnings are emitted for both (R-OUTSIDE-RESOURCE v11)")
	void outsideComponentScan_warning() {
		// when
		final JsonNode body = getReport();
		// then
		final List<Object> actual = new ArrayList<>();
		for (final JsonNode warning : body.path("warnings")) {
			actual.add(tuple(warning.path("code").asText(), warning.path("element").asText(),
					warning.path("severity").asText(), warning.path("path").asText()));
		}
		assertThat(actual).contains(
				tuple("OUTSIDE_COMPONENT_SCAN", UnregisteredMcpTools.class.getName(), "warning", "$"),
				tuple("OUTSIDE_COMPONENT_SCAN", UnregisteredMcpResources.class.getName(), "warning", "$"));
	}

	private JsonNode getReport() {
		final AtomicReference<byte[]> bodyRef = new AtomicReference<>();
		// when
		this.webTestClient.get()
			.uri("/mcp-inspector/api/introspection")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith("application/json")
			.expectBody()
			.consumeWith((result) -> bodyRef.set(result.getResponseBody()));
		// then
		assertThat(bodyRef.get()).isNotNull();
		try {
			return this.objectMapper.readTree(bodyRef.get());
		}
		catch (final Exception ex) {
			throw new AssertionError("introspection response is not valid JSON", ex);
		}
	}

	private JsonNode toolByName(final JsonNode body, final String name) {
		return elementByName(body.path("tools"), name);
	}

	private JsonNode resourceByName(final JsonNode body, final String name) {
		return elementByName(body.path("resources"), name);
	}

	private static JsonNode elementByName(final JsonNode elements, final String name) {
		for (final JsonNode element : elements) {
			if (name.equals(element.path("name").asText())) {
				return element;
			}
		}
		throw new AssertionError("element not found in report: " + name);
	}

	private static void assertSource(final JsonNode element, final String beanName, final String beanClass,
			final String method, final String annotation) {
		final JsonNode source = element.path("source");
		assertThat(source.isObject()).as("source is present").isTrue();
		assertThat(source.path("registered").asBoolean()).isTrue();
		assertThat(source.path("beanName").asText()).isEqualTo(beanName);
		assertThat(source.path("beanClass").asText()).isEqualTo(beanClass);
		assertThat(source.path("method").asText()).isEqualTo(method);
		assertThat(source.path("annotation").asText()).isEqualTo(annotation);
	}

	private static void assertProgrammaticSource(final JsonNode element) {
		final JsonNode source = element.path("source");
		assertThat(source.isObject()).as("source is present").isTrue();
		assertThat(source.path("registered").asBoolean()).isTrue();
		assertThat(source.path("beanName").isNull()).isTrue();
		assertThat(source.path("beanClass").isNull()).isTrue();
		assertThat(source.path("method").isNull()).isTrue();
		assertThat(source.path("annotation").isNull()).isTrue();
	}

}
