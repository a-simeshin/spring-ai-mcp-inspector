/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.proxy;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.transport.TransportType;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ProxyTransportFactory}. */
@Epic("MCP Inspector Core")
@Feature("Proxy transport factory")
class ProxyTransportFactoryTest {

	private final ProxyTransportFactory factory = new ProxyTransportFactory(new ObjectMapper());

	@Nested
	@DisplayName("openSse()")
	class OpenSse {

		@Test
		@Story("Build SSE transport")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() builds an HttpClientSseClientTransport for a URI with host, port and path")
		void openSse_withFullUri_buildsSseTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = factory.openSse(sseUri);

			// then
			assertThat(transport).isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Default endpoint branch")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() falls back to /sse and omits the port when the URI has no path or port")
		void openSse_withNoPathAndNoPort_buildsTransportWithDefaults() {
			// given
			final URI sseUri = URI.create("http://localhost");

			// when
			final McpClientTransport transport = factory.openSse(sseUri);

			// then
			assertThat(transport).isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() rejects a null URI")
		void openSse_withNullUri_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> factory.openSse(null)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("sseUri");
		}

	}

	@Nested
	@DisplayName("openStreamable()")
	class OpenStreamable {

		@Test
		@Story("Build streamable transport")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openStreamable() builds an HttpClientStreamableHttpTransport for a URI with host, port and path")
		void openStreamable_withFullUri_buildsStreamableTransport() {
			// given
			final URI mcpUri = URI.create("https://example.com:9443/mcp");

			// when
			final McpClientTransport transport = factory.openStreamable(mcpUri);

			// then
			assertThat(transport).isInstanceOf(HttpClientStreamableHttpTransport.class);
		}

		@Test
		@Story("Default endpoint branch")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStreamable() falls back to /mcp when the URI has no path")
		void openStreamable_withNoPath_buildsTransportWithDefaultEndpoint() {
			// given
			final URI mcpUri = URI.create("http://localhost:8080");

			// when
			final McpClientTransport transport = factory.openStreamable(mcpUri);

			// then
			assertThat(transport).isInstanceOf(HttpClientStreamableHttpTransport.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStreamable() rejects a null URI")
		void openStreamable_withNullUri_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> factory.openStreamable(null)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("mcpUri");
		}

	}

	@Nested
	@DisplayName("openStdio()")
	class OpenStdio {

		@Test
		@Story("Build stdio transport")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openStdio() builds a StdioClientTransport for a command with arguments and env")
		void openStdio_withCommandArgsAndEnv_buildsStdioTransport() {
			// given
			final List<String> command = List.of("node", "server.js", "--flag");
			final Map<String, String> env = Map.of("FOO", "bar");

			// when
			final McpClientTransport transport = factory.openStdio(command, env);

			// then
			assertThat(transport).isInstanceOf(StdioClientTransport.class);
		}

		@Test
		@Story("Build stdio transport")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() builds a transport for a bare command with no args and no env")
		void openStdio_withSingleCommandAndNoEnv_buildsStdioTransport() {
			// given
			final List<String> command = List.of("server");

			// when
			final McpClientTransport transport = factory.openStdio(command, null);

			// then
			assertThat(transport).isInstanceOf(StdioClientTransport.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() rejects a null command")
		void openStdio_withNullCommand_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> factory.openStdio(null, Map.of())).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("command");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() rejects an empty command list")
		void openStdio_withEmptyCommand_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> factory.openStdio(List.of(), Map.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("command");
		}

	}

	@Nested
	@DisplayName("default constructor")
	class DefaultConstructor {

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("the no-arg constructor builds a usable factory with a default ObjectMapper")
		void noArgConstructor_buildsUsableFactory() {
			// given
			final ProxyTransportFactory defaultFactory = new ProxyTransportFactory();

			// when
			final McpClientTransport transport = defaultFactory.openStreamable(URI.create("http://localhost:8080/mcp"));

			// then
			assertThat(transport).isInstanceOf(HttpClientStreamableHttpTransport.class);
		}

	}

	@Nested
	@DisplayName("TransportType enum")
	class TransportTypeEnum {

		@Test
		@Story("Counted enum guard")
		@Severity(SeverityLevel.MINOR)
		@Description("TransportType exposes the documented flavours via values()/valueOf()")
		void transportType_valuesAndValueOf_expectedConstants() {
			// when
			final TransportType[] values = TransportType.values();

			// then
			assertThat(values).containsExactly(TransportType.SSE, TransportType.STREAMABLE, TransportType.STATELESS,
					TransportType.STDIO_NO_HTTP, TransportType.UNKNOWN);
			assertThat(TransportType.valueOf("STREAMABLE")).isEqualTo(TransportType.STREAMABLE);
		}

	}

}
