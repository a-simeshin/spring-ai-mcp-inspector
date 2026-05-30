/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.proxy;

import java.util.Map;

import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProxyConfigController}. Asserts the {@code /config} body shape
 * and the transport-name / server-url mapping branches.
 */
@Epic("WebMvc Inspector")
@Feature("ProxyConfigController")
class ProxyConfigControllerTest {

	private TransportDetector transportDetector;

	private InspectorServerPortHolder portHolder;

	private ProxyConfigController controller;

	@BeforeEach
	void setUp() {
		transportDetector = mock(TransportDetector.class);
		portHolder = mock(InspectorServerPortHolder.class);
		controller = new ProxyConfigController(transportDetector, portHolder);
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
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));
			when(portHolder.port()).thenReturn(8123);

			// when
			Map<String, Object> body = controller.config();

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
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.SSE, "/sse", "/mcp/message", "WEBMVC"));
			when(portHolder.port()).thenReturn(9000);

			// when
			Map<String, Object> body = controller.config();

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
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STDIO_NO_HTTP, null, null, "STDIO"));
			when(portHolder.port()).thenReturn(9000);

			// when
			Map<String, Object> body = controller.config();

			// then
			assertThat(body).containsEntry("defaultTransport", "stdio").containsEntry("defaultServerUrl", "");
		}

		@Test
		@Story("Config defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() maps UNKNOWN to empty transport and empty server url")
		void config_withUnknownTransport_returnsEmpties() {
			// given
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.UNKNOWN, null, null, "WEBMVC"));
			when(portHolder.port()).thenReturn(9000);

			// when
			Map<String, Object> body = controller.config();

			// then
			assertThat(body).containsEntry("defaultTransport", "").containsEntry("defaultServerUrl", "");
		}

		@Test
		@Story("Config defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() returns an empty server url when the port has not yet been resolved")
		void config_withUnresolvedPort_returnsEmptyServerUrl() {
			// given
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));
			when(portHolder.port()).thenReturn(0);

			// when
			Map<String, Object> body = controller.config();

			// then
			assertThat(body).containsEntry("defaultServerUrl", "");
		}

		@Test
		@Story("Config defaults")
		@Severity(SeverityLevel.MINOR)
		@Description("config() falls back to /mcp when a streamable transport reports a blank endpoint")
		void config_withBlankEndpoint_fallsBackToMcp() {
			// given
			when(transportDetector.detect())
				.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "", null, "WEBMVC"));
			when(portHolder.port()).thenReturn(7777);

			// when
			Map<String, Object> body = controller.config();

			// then
			assertThat(body).containsEntry("defaultServerUrl", "http://localhost:7777/mcp");
		}

	}

}
