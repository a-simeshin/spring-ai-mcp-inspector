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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link InspectorBootstrapAssembler}. */
class InspectorBootstrapAssemblerTest {

	@Test
	void assemble_populatesFrameworkDefaults() {
		McpInspectorProperties properties = new McpInspectorProperties();
		properties.setPath("/mcp-inspector");

		InspectorAuthTokenProvider authTokenProvider = mock(InspectorAuthTokenProvider.class);
		when(authTokenProvider.token()).thenReturn("the-token");

		TransportDetector transportDetector = mock(TransportDetector.class);
		when(transportDetector.detect())
			.thenReturn(new DetectedTransport(TransportType.STREAMABLE, "/mcp", null, "WEBMVC"));

		InspectorBootstrapAssembler assembler = new InspectorBootstrapAssembler(properties, authTokenProvider,
				transportDetector, List.of());

		InspectorBootstrap bootstrap = assembler.assemble();

		assertThat(bootstrap.getAuthToken()).isEqualTo("the-token");
		assertThat(bootstrap.getProxyAddress()).isEqualTo("/mcp-inspector-api");
		assertThat(bootstrap.getDetectedTransport()).isEqualTo("streamable-http");
		assertThat(bootstrap.getDetectedUrl()).isEqualTo("/mcp");
	}

	@Test
	void assemble_appliesCustomizersInListOrder() {
		InspectorBootstrapAssembler assembler = newAssemblerWithCustomizers(
				List.of(bootstrap -> bootstrap.setDefaultUrl("A"), bootstrap -> bootstrap.setDefaultUrl("B")));

		InspectorBootstrap bootstrap = assembler.assemble();

		assertThat(bootstrap.getDefaultUrl()).isEqualTo("B");
	}

	@Test
	void assemble_emptyCustomizersList_returnsDefaultsOnly() {
		InspectorBootstrapAssembler assembler = newAssemblerWithCustomizers(List.of());

		InspectorBootstrap bootstrap = assembler.assemble();

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
	void assemble_customizerCanModifyExtraMap() {
		InspectorBootstrapAssembler assembler = newAssemblerWithCustomizers(
				List.of(bootstrap -> bootstrap.getExtra().put("foo", "bar")));

		InspectorBootstrap bootstrap = assembler.assemble();

		assertThat(bootstrap.getExtra()).containsEntry("foo", "bar");
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
