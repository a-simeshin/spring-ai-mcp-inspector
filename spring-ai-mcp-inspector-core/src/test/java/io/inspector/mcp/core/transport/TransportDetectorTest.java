/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.transport;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link TransportDetector}. */
@Epic("MCP Inspector Core")
@Feature("Transport detector")
class TransportDetectorTest {

	@Nested
	@DisplayName("detect()")
	class Detect {

		@Test
		@Story("SSE detection")
		@Severity(SeverityLevel.CRITICAL)
		@Description("detect() resolves the SSE transport with its sse and message endpoints from the protocol property")
		void detect_whenSseProtocol_detectsSseWithEndpoints() {
			// given
			MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE");

			// when
			DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.SSE);
			assertThat(detected.endpoint()).isEqualTo("/sse");
			assertThat(detected.messageEndpoint()).isEqualTo("/mcp/message");
			assertThat(detected.stack()).isEqualTo(TransportDetector.STACK_WEBMVC);
		}

		@Test
		@Story("Streamable detection")
		@Severity(SeverityLevel.CRITICAL)
		@Description("detect() resolves the streamable transport with /mcp endpoint and no message endpoint")
		void detect_whenStreamableProtocol_detectsStreamable() {
			// given
			MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "STREAMABLE");

			// when
			DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.STREAMABLE);
			assertThat(detected.endpoint()).isEqualTo("/mcp");
			assertThat(detected.messageEndpoint()).isNull();
		}

		@Test
		@Story("Stateless detection")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() resolves the stateless transport with /mcp endpoint")
		void detect_whenStatelessProtocol_detectsStateless() {
			// given
			MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "STATELESS");

			// when
			DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.STATELESS);
			assertThat(detected.endpoint()).isEqualTo("/mcp");
		}

		@Test
		@Story("Stdio detection")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() resolves a pure-stdio server with no HTTP endpoint and the stdio stack")
		void detect_whenStdioNoHttp_detectsStdioStack() {
			// given
			MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_STDIO, "true")
				.withProperty(TransportDetector.PROP_WEB_APP_TYPE, "NONE");

			// when
			DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.STDIO_NO_HTTP);
			assertThat(detected.stack()).isEqualTo(TransportDetector.STACK_STDIO);
			assertThat(detected.endpoint()).isNull();
		}

		@Test
		@Story("Custom endpoints")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() honours custom SSE and message endpoint overrides")
		void detect_whenCustomSseEndpoints_honoursOverrides() {
			// given
			MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE")
				.withProperty(TransportDetector.PROP_SSE_ENDPOINT, "/custom-sse")
				.withProperty(TransportDetector.PROP_SSE_MESSAGE_ENDPOINT, "/custom-msg");

			// when
			DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.endpoint()).isEqualTo("/custom-sse");
			assertThat(detected.messageEndpoint()).isEqualTo("/custom-msg");
		}

		@Test
		@Story("Custom endpoints")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() honours a custom streamable MCP endpoint override")
		void detect_whenCustomMcpEndpoint_honoursOverride() {
			// given
			MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "STREAMABLE")
				.withProperty(TransportDetector.PROP_MCP_ENDPOINT, "/custom-mcp");

			// when
			DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.endpoint()).isEqualTo("/custom-mcp");
		}

		@Test
		@Story("Reactive stack")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() resolves the WebFlux stack when the web application type is reactive")
		void detect_whenReactiveWebAppType_detectsWebfluxStack() {
			// given
			MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE")
				.withProperty(TransportDetector.PROP_WEB_APP_TYPE, "REACTIVE");

			// when
			DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.stack()).isEqualTo(TransportDetector.STACK_WEBFLUX);
		}

		@Test
		@Story("Unknown transport")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() returns UNKNOWN when no protocol property is present")
		void detect_whenNoProtocol_returnsUnknown() {
			// given
			MockEnvironment env = new MockEnvironment();

			// when
			DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.UNKNOWN);
		}

	}

}
