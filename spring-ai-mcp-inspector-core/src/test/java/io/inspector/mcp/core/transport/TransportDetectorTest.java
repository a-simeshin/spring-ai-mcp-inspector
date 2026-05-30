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

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link TransportDetector}. */
class TransportDetectorTest {

	@Test
	void detectsSseWhenSseProtocolProperty() {
		MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE");

		DetectedTransport detected = new TransportDetector(env).detect();

		assertThat(detected.type()).isEqualTo(TransportType.SSE);
		assertThat(detected.endpoint()).isEqualTo("/sse");
		assertThat(detected.messageEndpoint()).isEqualTo("/mcp/message");
		assertThat(detected.stack()).isEqualTo(TransportDetector.STACK_WEBMVC);
	}

	@Test
	void detectsStreamable() {
		MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "STREAMABLE");

		DetectedTransport detected = new TransportDetector(env).detect();

		assertThat(detected.type()).isEqualTo(TransportType.STREAMABLE);
		assertThat(detected.endpoint()).isEqualTo("/mcp");
		assertThat(detected.messageEndpoint()).isNull();
	}

	@Test
	void detectsStateless() {
		MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "STATELESS");

		DetectedTransport detected = new TransportDetector(env).detect();

		assertThat(detected.type()).isEqualTo(TransportType.STATELESS);
		assertThat(detected.endpoint()).isEqualTo("/mcp");
	}

	@Test
	void detectsStdioNoHttp() {
		MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_STDIO, "true")
			.withProperty(TransportDetector.PROP_WEB_APP_TYPE, "NONE");

		DetectedTransport detected = new TransportDetector(env).detect();

		assertThat(detected.type()).isEqualTo(TransportType.STDIO_NO_HTTP);
		assertThat(detected.stack()).isEqualTo(TransportDetector.STACK_STDIO);
		assertThat(detected.endpoint()).isNull();
	}

	@Test
	void respectsCustomSseEndpoint() {
		MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE")
			.withProperty(TransportDetector.PROP_SSE_ENDPOINT, "/custom-sse")
			.withProperty(TransportDetector.PROP_SSE_MESSAGE_ENDPOINT, "/custom-msg");

		DetectedTransport detected = new TransportDetector(env).detect();

		assertThat(detected.endpoint()).isEqualTo("/custom-sse");
		assertThat(detected.messageEndpoint()).isEqualTo("/custom-msg");
	}

	@Test
	void respectsCustomMcpEndpoint() {
		MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "STREAMABLE")
			.withProperty(TransportDetector.PROP_MCP_ENDPOINT, "/custom-mcp");

		DetectedTransport detected = new TransportDetector(env).detect();

		assertThat(detected.endpoint()).isEqualTo("/custom-mcp");
	}

	@Test
	void detectsReactiveStack() {
		MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE")
			.withProperty(TransportDetector.PROP_WEB_APP_TYPE, "REACTIVE");

		DetectedTransport detected = new TransportDetector(env).detect();

		assertThat(detected.stack()).isEqualTo(TransportDetector.STACK_WEBFLUX);
	}

	@Test
	void returnsUnknownWhenNoProtocol() {
		MockEnvironment env = new MockEnvironment();

		DetectedTransport detected = new TransportDetector(env).detect();

		assertThat(detected.type()).isEqualTo(TransportType.UNKNOWN);
	}

}
