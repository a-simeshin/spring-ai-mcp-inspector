/*
 * Copyright 2025-present the original author or authors.
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

package io.inspector.mcp.webmvc.proxy;

import java.util.Map;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ProxyConfigController}. Asserts the {@code /config} body shape
 * and the transport-name / server-url mapping branches.
 */
@Epic("WebMvc Inspector")
@Feature("ProxyConfigController")
class ProxyConfigControllerTests {

	private TransportDetector transportDetector;

	private InspectorServerPortHolder portHolder;

	private ProxyConfigController controller;

	@BeforeEach
	void setUp() {
		this.transportDetector = mock(TransportDetector.class);
		this.portHolder = mock(InspectorServerPortHolder.class);
		this.controller = new ProxyConfigController(this.transportDetector, this.portHolder);
	}

	@Nested
	@DisplayName("config()")
	class Config {

		@Test
		@Story("Config defaults")
		@Severity(SeverityLevel.CRITICAL)
		@Description("config() returns streamable-http and a server url for a STREAMABLE transport")
		void config_withStreamableTransport_returnsStreamableDefaults() {
			// given
			given(ProxyConfigControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));
			given(ProxyConfigControllerTests.this.portHolder.port()).willReturn(8123);

			// when
			final Map<String, Object> body = ProxyConfigControllerTests.this.controller.config();

			// then
			assertThat(body).containsEntry("defaultEnvironment", Map.of())
				.containsEntry("defaultCommand", "")
				.containsEntry("defaultArgs", "")
				.containsEntry("defaultTransport", "streamable-http")
				.containsEntry("defaultServerUrl", "http://localhost:8123/mcp");
		}

		@Test
		@Story("Config defaults")
		@Severity(SeverityLevel.NORMAL)
		@Description("config() maps an SSE transport to defaultTransport=sse")
		void config_withSseTransport_returnsSseDefault() {
			// given
			given(ProxyConfigControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBMVC"));
			given(ProxyConfigControllerTests.this.portHolder.port()).willReturn(9000);

			// when
			final Map<String, Object> body = ProxyConfigControllerTests.this.controller.config();

			// then
			assertThat(body).containsEntry("defaultTransport", "sse")
				.containsEntry("defaultServerUrl", "http://localhost:9000/sse");
		}

		@Test
		@Story("Config defaults")
		@Severity(SeverityLevel.NORMAL)
		@Description("config() maps STDIO_NO_HTTP to defaultTransport=stdio and an empty server url")
		void config_withStdioTransport_returnsEmptyServerUrl() {
			// given
			given(ProxyConfigControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STDIO_NO_HTTP, null, null, "STDIO"));
			given(ProxyConfigControllerTests.this.portHolder.port()).willReturn(9000);

			// when
			final Map<String, Object> body = ProxyConfigControllerTests.this.controller.config();

			// then
			assertThat(body).containsEntry("defaultTransport", "stdio").containsEntry("defaultServerUrl", "");
		}

		@Test
		@Story("Config defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() maps UNKNOWN to empty transport and empty server url")
		void config_withUnknownTransport_returnsEmpties() {
			// given
			given(ProxyConfigControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.UNKNOWN, null, null, "WEBMVC"));
			given(ProxyConfigControllerTests.this.portHolder.port()).willReturn(9000);

			// when
			final Map<String, Object> body = ProxyConfigControllerTests.this.controller.config();

			// then
			assertThat(body).containsEntry("defaultTransport", "").containsEntry("defaultServerUrl", "");
		}

		@Test
		@Story("Config defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() returns an empty server url when the port has not yet been resolved")
		void config_withUnresolvedPort_returnsEmptyServerUrl() {
			// given
			given(ProxyConfigControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));
			given(ProxyConfigControllerTests.this.portHolder.port()).willReturn(0);

			// when
			final Map<String, Object> body = ProxyConfigControllerTests.this.controller.config();

			// then
			assertThat(body).containsEntry("defaultServerUrl", "");
		}

		@Test
		@Story("Config defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() falls back to /mcp when a streamable transport reports a blank endpoint")
		void config_withBlankEndpoint_fallsBackToMcp() {
			// given
			given(ProxyConfigControllerTests.this.transportDetector.detect())
				.willReturn(new DetectedTransport(TransportType.STREAMABLE, "", null, "WEBMVC"));
			given(ProxyConfigControllerTests.this.portHolder.port()).willReturn(7777);

			// when
			final Map<String, Object> body = ProxyConfigControllerTests.this.controller.config();

			// then
			assertThat(body).containsEntry("defaultServerUrl", "http://localhost:7777/mcp");
		}

	}

}
