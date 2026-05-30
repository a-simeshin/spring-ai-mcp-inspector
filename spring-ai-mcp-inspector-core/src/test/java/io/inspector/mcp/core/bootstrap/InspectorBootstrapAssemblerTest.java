/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.bootstrap;

import java.util.List;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link InspectorBootstrapAssembler}. */
@Epic("MCP Inspector Core")
@Feature("Bootstrap assembler")
class InspectorBootstrapAssemblerTest {

	@Nested
	@DisplayName("assemble()")
	class Assemble {

		@Test
		@Story("Framework defaults")
		@Severity(SeverityLevel.CRITICAL)
		@Description("assemble() populates the framework-default fields from properties, auth and transport detector")
		void assemble_populatesFrameworkDefaults() {
			// given
			McpInspectorProperties properties = new McpInspectorProperties();
			properties.setPath("/mcp-inspector");

			InspectorAuthTokenProvider authTokenProvider = mock(InspectorAuthTokenProvider.class);
			when(authTokenProvider.token()).thenReturn("the-token");

			TransportDetector transportDetector = mock(TransportDetector.class);
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));

			InspectorBootstrapAssembler assembler = new InspectorBootstrapAssembler(properties, authTokenProvider,
					transportDetector, List.of());

			// when
			InspectorBootstrap bootstrap = assembler.assemble();

			// then
			assertThat(bootstrap.getAuthToken()).isEqualTo("the-token");
			assertThat(bootstrap.getProxyAddress()).isEqualTo("/mcp-inspector-api");
			assertThat(bootstrap.getDetectedTransport()).isEqualTo("streamable-http");
			assertThat(bootstrap.getDetectedUrl()).isEqualTo("/mcp");
		}

		@Test
		@Story("Customizer ordering")
		@Severity(SeverityLevel.NORMAL)
		@Description("assemble() applies customizers in list order so the last writer wins")
		void assemble_appliesCustomizersInListOrder() {
			// given
			InspectorBootstrapAssembler assembler = newAssemblerWithCustomizers(
					List.of(bootstrap -> bootstrap.setDefaultUrl("A"), bootstrap -> bootstrap.setDefaultUrl("B")));

			// when
			InspectorBootstrap bootstrap = assembler.assemble();

			// then
			assertThat(bootstrap.getDefaultUrl()).isEqualTo("B");
		}

		@Test
		@Story("No customizers")
		@Severity(SeverityLevel.NORMAL)
		@Description("assemble() returns framework defaults only when no customizers are registered")
		void assemble_emptyCustomizersList_returnsDefaultsOnly() {
			// given
			InspectorBootstrapAssembler assembler = newAssemblerWithCustomizers(List.of());

			// when
			InspectorBootstrap bootstrap = assembler.assemble();

			// then
			assertThat(bootstrap.getAuthToken()).isEqualTo("token");
			assertThat(bootstrap.getProxyAddress()).isEqualTo("/mcp-inspector-api");
			assertThat(bootstrap.getDetectedTransport()).isEqualTo("streamable-http");
			assertThat(bootstrap.getDetectedUrl()).isEqualTo("/mcp");
			assertThat(bootstrap.getDefaultUrl()).isNull();
			assertThat(bootstrap.getDefaultTransport()).isNull();
			assertThat(bootstrap.getDefaultHeaders()).isEmpty();
			assertThat(bootstrap.getServerEntries()).isEmpty();
			assertThat(bootstrap.getExtra()).isEmpty();
		}

		@Test
		@Story("Extra map escape hatch")
		@Severity(SeverityLevel.NORMAL)
		@Description("assemble() lets a customizer push arbitrary entries into the extra map")
		void assemble_customizerCanModifyExtraMap() {
			// given
			InspectorBootstrapAssembler assembler = newAssemblerWithCustomizers(
					List.of(bootstrap -> bootstrap.getExtra().put("foo", "bar")));

			// when
			InspectorBootstrap bootstrap = assembler.assemble();

			// then
			assertThat(bootstrap.getExtra()).containsEntry("foo", "bar");
		}

	}

	@Nested
	@DisplayName("transport-name mapping")
	class TransportNameMapping {

		@ParameterizedTest(name = "{0} -> {1}")
		@CsvSource({ "SSE,sse", "STREAMABLE,streamable-http", "STATELESS,streamable-http", "STDIO_NO_HTTP,stdio",
				"UNKNOWN," })
		@Story("Transport name mapping")
		@Severity(SeverityLevel.NORMAL)
		@Description("assemble() maps every detected TransportType onto the UI-expected identifier")
		void assemble_mapsEachTransportTypeToUiName(TransportType type, String expected) {
			// given
			String endpoint = "/mcp";
			InspectorBootstrapAssembler assembler = newAssemblerForTransport(type, endpoint);

			// when
			InspectorBootstrap bootstrap = assembler.assemble();

			// then
			assertThat(bootstrap.getDetectedTransport()).isEqualTo(expected == null ? "" : expected);
		}

		@Test
		@Story("Null endpoint")
		@Severity(SeverityLevel.NORMAL)
		@Description("assemble() maps a null detected endpoint to an empty detectedUrl string")
		void assemble_whenNullEndpoint_setsEmptyDetectedUrl() {
			// given
			InspectorBootstrapAssembler assembler = newAssemblerForTransport(TransportType.STDIO_NO_HTTP, null);

			// when
			InspectorBootstrap bootstrap = assembler.assemble();

			// then
			assertThat(bootstrap.getDetectedUrl()).isEmpty();
			assertThat(bootstrap.getDetectedTransport()).isEqualTo("stdio");
		}

	}

	private static InspectorBootstrapAssembler newAssemblerForTransport(TransportType type, String endpoint) {
		McpInspectorProperties properties = new McpInspectorProperties();
		properties.setPath("/mcp-inspector");

		InspectorAuthTokenProvider authTokenProvider = mock(InspectorAuthTokenProvider.class);
		when(authTokenProvider.token()).thenReturn("token");

		TransportDetector transportDetector = mock(TransportDetector.class);
		when(transportDetector.detect()).thenReturn(new DetectedTransport(type, endpoint, null, "WEBMVC"));

		return new InspectorBootstrapAssembler(properties, authTokenProvider, transportDetector, List.of());
	}

	private static InspectorBootstrapAssembler newAssemblerWithCustomizers(
			List<InspectorBootstrapCustomizer> customizers) {
		McpInspectorProperties properties = new McpInspectorProperties();
		properties.setPath("/mcp-inspector");

		InspectorAuthTokenProvider authTokenProvider = mock(InspectorAuthTokenProvider.class);
		when(authTokenProvider.token()).thenReturn("token");

		TransportDetector transportDetector = mock(TransportDetector.class);
		when(transportDetector.detect())
			.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));

		return new InspectorBootstrapAssembler(properties, authTokenProvider, transportDetector, customizers);
	}

}
