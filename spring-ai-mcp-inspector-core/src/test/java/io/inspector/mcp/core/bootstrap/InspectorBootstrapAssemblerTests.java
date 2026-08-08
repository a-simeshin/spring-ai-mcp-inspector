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

package io.inspector.mcp.core.bootstrap;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Unit tests for {@link InspectorBootstrapAssembler}. */
@Epic("MCP Inspector Core")
@Feature("Bootstrap assembler")
class InspectorBootstrapAssemblerTests {

	private static InspectorBootstrapAssembler newAssemblerForTransport(final TransportType type,
			final String endpoint) {
		final McpInspectorProperties properties = new McpInspectorProperties();
		properties.setPath("/mcp-inspector");

		final InspectorAuthTokenProvider authTokenProvider = mock(InspectorAuthTokenProvider.class);
		given(authTokenProvider.token()).willReturn("token");

		final TransportDetector transportDetector = mock(TransportDetector.class);
		given(transportDetector.detect()).willReturn(new DetectedTransport(type, endpoint, null, "WEBMVC"));

		return new InspectorBootstrapAssembler(properties, authTokenProvider, transportDetector, List.of());
	}

	private static InspectorBootstrapAssembler newAssemblerWithCustomizers(
			final List<InspectorBootstrapCustomizer> customizers) {
		final McpInspectorProperties properties = new McpInspectorProperties();
		properties.setPath("/mcp-inspector");

		final InspectorAuthTokenProvider authTokenProvider = mock(InspectorAuthTokenProvider.class);
		given(authTokenProvider.token()).willReturn("token");

		final TransportDetector transportDetector = mock(TransportDetector.class);
		given(transportDetector.detect())
			.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));

		return new InspectorBootstrapAssembler(properties, authTokenProvider, transportDetector, customizers);
	}

	@Nested
	@DisplayName("assemble()")
	class Assemble {

		@Test
		@Story("Framework defaults")
		@Severity(SeverityLevel.CRITICAL)
		@Description("assemble() populates the framework-default fields from properties, auth and transport detector")
		void assemble_populatesFrameworkDefaults() {
			// given
			final McpInspectorProperties properties = new McpInspectorProperties();
			properties.setPath("/mcp-inspector");

			final InspectorAuthTokenProvider authTokenProvider = mock(InspectorAuthTokenProvider.class);
			given(authTokenProvider.token()).willReturn("the-token");

			final TransportDetector transportDetector = mock(TransportDetector.class);
			given(transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));

			final InspectorBootstrapAssembler assembler = new InspectorBootstrapAssembler(properties, authTokenProvider,
					transportDetector, List.of());

			// when
			final InspectorBootstrap bootstrap = assembler.assemble();

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
			final InspectorBootstrapAssembler assembler = newAssemblerWithCustomizers(
					List.of((bootstrap) -> bootstrap.setDefaultUrl("A"), (bootstrap) -> bootstrap.setDefaultUrl("B")));

			// when
			final InspectorBootstrap bootstrap = assembler.assemble();

			// then
			assertThat(bootstrap.getDefaultUrl()).isEqualTo("B");
		}

		@Test
		@Story("No customizers")
		@Severity(SeverityLevel.NORMAL)
		@Description("assemble() returns framework defaults only when no customizers are registered")
		void assemble_emptyCustomizersList_returnsDefaultsOnly() {
			// given
			final InspectorBootstrapAssembler assembler = newAssemblerWithCustomizers(List.of());

			// when
			final InspectorBootstrap bootstrap = assembler.assemble();

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
			final InspectorBootstrapAssembler assembler = newAssemblerWithCustomizers(
					List.of((bootstrap) -> bootstrap.getExtra().put("foo", "bar")));

			// when
			final InspectorBootstrap bootstrap = assembler.assemble();

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
		void assemble_mapsEachTransportTypeToUiName(final TransportType type, final String expected) {
			// given
			final String endpoint = "/mcp";
			final InspectorBootstrapAssembler assembler = newAssemblerForTransport(type, endpoint);

			// when
			final InspectorBootstrap bootstrap = assembler.assemble();

			// then
			assertThat(bootstrap.getDetectedTransport()).isEqualTo((expected != null) ? expected : "");
		}

		@Test
		@Story("Null endpoint")
		@Severity(SeverityLevel.NORMAL)
		@Description("assemble() maps a null detected endpoint to an empty detectedUrl string")
		void assemble_whenNullEndpoint_setsEmptyDetectedUrl() {
			// given
			final InspectorBootstrapAssembler assembler = newAssemblerForTransport(TransportType.STDIO_NO_HTTP, null);

			// when
			final InspectorBootstrap bootstrap = assembler.assemble();

			// then
			assertThat(bootstrap.getDetectedUrl()).isEmpty();
			assertThat(bootstrap.getDetectedTransport()).isEqualTo("stdio");
		}

	}

	@Nested
	@DisplayName("assemble(requestPrefix)")
	class AssembleWithPrefix {

		@Test
		@Story("Request prefix")
		@Severity(SeverityLevel.CRITICAL)
		@Description("assemble(prefix) prefixes the proxy address but leaves the already-prefixed detected URL alone")
		void assemble_withPrefix_prefixesProxyAddressOnly() {
			// given
			final InspectorBootstrapAssembler assembler = newAssemblerForTransport(TransportType.STREAMABLE,
					"/app/mcp");

			// when
			final InspectorBootstrap bootstrap = assembler.assemble("/app");

			// then
			assertThat(bootstrap.getProxyAddress()).isEqualTo("/app/mcp-inspector-api");
			assertThat(bootstrap.getDetectedUrl()).isEqualTo("/app/mcp");
		}

		@Test
		@Story("Request prefix")
		@Severity(SeverityLevel.NORMAL)
		@Description("A blank, null or root request prefix leaves the proxy address unprefixed")
		void assemble_withoutPrefix_keepsProxyAddress() {
			// given
			final InspectorBootstrapAssembler assembler = newAssemblerForTransport(TransportType.STREAMABLE, "/mcp");

			// when & then
			assertThat(assembler.assemble("").getProxyAddress()).isEqualTo("/mcp-inspector-api");
			assertThat(assembler.assemble((String) null).getProxyAddress()).isEqualTo("/mcp-inspector-api");
			assertThat(assembler.assemble("/").getProxyAddress()).isEqualTo("/mcp-inspector-api");
			assertThat(assembler.assemble("app/").getProxyAddress()).isEqualTo("/app/mcp-inspector-api");
		}

	}

}
