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

package io.inspector.mcp.core.proxy;

import java.net.URI;
import java.util.List;
import java.util.Map;

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
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.transport.TransportType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ProxyTransportFactory}. */
@Epic("MCP Inspector Core")
@Feature("Proxy transport factory")
class ProxyTransportFactoryTests {

	private final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());

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
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openSse(sseUri);

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
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openSse(sseUri);

			// then
			assertThat(transport).isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() rejects a null URI")
		void openSse_withNullUri_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> ProxyTransportFactoryTests.this.factory.openSse(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("sseUri");
		}

		@Test
		@Story("Header forwarding — auth only")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() three-arg overload with an authorization value and no custom headers "
				+ "returns a non-null SSE transport")
		void openSse_withAuthorizationOnly_returnsSseTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openSse(sseUri,
					"Bearer tok-123", null);

			// then
			assertThat(transport).isNotNull().isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Header forwarding — custom headers only")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() three-arg overload with no authorization but with custom headers "
				+ "returns a non-null SSE transport")
		void openSse_withCustomHeadersOnly_returnsSseTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openSse(sseUri, null,
					Map.of("X-Tenant", "acme"));

			// then
			assertThat(transport).isNotNull().isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Header forwarding — auth and custom headers")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() three-arg overload with both authorization and custom headers "
				+ "returns a non-null SSE transport")
		void openSse_withAuthAndCustomHeaders_returnsSseTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openSse(sseUri,
					"Bearer tok-abc", Map.of("X-Tenant", "acme"));

			// then
			assertThat(transport).isNotNull().isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Header forwarding — both null/empty")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() three-arg overload with null authorization and empty custom headers "
				+ "behaves like the single-arg overload and returns a non-null SSE transport")
		void openSse_withNullAuthAndEmptyCustomHeaders_returnsSseTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openSse(sseUri, null,
					Map.of());

			// then
			assertThat(transport).isNotNull().isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Restricted header swallowed")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() does not propagate an IllegalArgumentException caused by a restricted "
				+ "custom header name — the transport is still returned")
		void openSse_withRestrictedCustomHeaderName_doesNotThrow() {
			// given — "host" is a restricted header in Java's HttpClient
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when & then — must not throw
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openSse(sseUri, null,
					Map.of("host", "evil.example.com"));

			assertThat(transport).isNotNull().isInstanceOf(HttpClientSseClientTransport.class);
		}

	}

	@Nested
	@DisplayName("buildSse()")
	class BuildSse {

		@Test
		@Story("Build SSE transport with preflight wrapper")
		@Severity(SeverityLevel.CRITICAL)
		@Description("buildSse() returns an SsePreflightTransport wrapping an HttpClientSseClientTransport")
		void buildSse_withFullUri_wrapsInPreflightTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.buildSse(sseUri);

			// then
			assertThat(transport).isInstanceOf(SsePreflightTransport.class);
			assertThat(((SsePreflightTransport) transport).unwrap()).isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("buildSse() rejects a null URI")
		void buildSse_withNullUri_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> ProxyTransportFactoryTests.this.factory.buildSse(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("sseUri");
		}

		@Test
		@Story("Normalization")
		@Severity(SeverityLevel.CRITICAL)
		@Description("buildSse() with a URI that has no path normalizes to /sse in the preflight")
		void buildSse_withNoPath_normalizesToSse() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.buildSse(sseUri);

			// then
			assertThat(transport).isInstanceOf(SsePreflightTransport.class);
			assertThat(((SsePreflightTransport) transport).unwrap()).isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Header forwarding -- auth only")
		@Severity(SeverityLevel.CRITICAL)
		@Description("buildSse() three-arg overload with an authorization value and no custom headers "
				+ "returns a non-null SsePreflightTransport")
		void buildSse_withAuthorizationOnly_returnsSsePreflightTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.buildSse(sseUri,
					"Bearer tok-123", null);

			// then
			assertThat(transport).isNotNull().isInstanceOf(SsePreflightTransport.class);
			assertThat(((SsePreflightTransport) transport).unwrap()).isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Header forwarding -- custom headers only")
		@Severity(SeverityLevel.CRITICAL)
		@Description("buildSse() three-arg overload with no authorization but with custom headers "
				+ "returns a non-null SsePreflightTransport")
		void buildSse_withCustomHeadersOnly_returnsSsePreflightTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.buildSse(sseUri, null,
					Map.of("X-Tenant", "acme"));

			// then
			assertThat(transport).isNotNull().isInstanceOf(SsePreflightTransport.class);
			assertThat(((SsePreflightTransport) transport).unwrap()).isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Header forwarding -- auth and custom headers")
		@Severity(SeverityLevel.CRITICAL)
		@Description("buildSse() three-arg overload with both authorization and custom headers "
				+ "returns a non-null SsePreflightTransport")
		void buildSse_withAuthAndCustomHeaders_returnsSsePreflightTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.buildSse(sseUri,
					"Bearer tok-abc", Map.of("X-Tenant", "acme"));

			// then
			assertThat(transport).isNotNull().isInstanceOf(SsePreflightTransport.class);
			assertThat(((SsePreflightTransport) transport).unwrap()).isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Header forwarding -- both null/empty")
		@Severity(SeverityLevel.NORMAL)
		@Description("buildSse() three-arg overload with null authorization and empty custom headers "
				+ "returns a non-null SsePreflightTransport")
		void buildSse_withNullAuthAndEmptyCustomHeaders_returnsSsePreflightTransport() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.buildSse(sseUri, null,
					Map.of());

			// then
			assertThat(transport).isNotNull().isInstanceOf(SsePreflightTransport.class);
			assertThat(((SsePreflightTransport) transport).unwrap()).isInstanceOf(HttpClientSseClientTransport.class);
		}

		@Test
		@Story("Restricted header swallowed")
		@Severity(SeverityLevel.NORMAL)
		@Description("buildSse() does not propagate an IllegalArgumentException caused by a restricted "
				+ "custom header name -- the transport is still returned")
		void buildSse_withRestrictedCustomHeaderName_doesNotThrow() {
			// given
			final URI sseUri = URI.create("http://127.0.0.1:8080/sse");

			// when & then -- must not throw
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.buildSse(sseUri, null,
					Map.of("host", "evil.example.com"));

			assertThat(transport).isNotNull().isInstanceOf(SsePreflightTransport.class);
			assertThat(((SsePreflightTransport) transport).unwrap()).isInstanceOf(HttpClientSseClientTransport.class);
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
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openStreamable(mcpUri);

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
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openStreamable(mcpUri);

			// then
			assertThat(transport).isInstanceOf(HttpClientStreamableHttpTransport.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStreamable() rejects a null URI")
		void openStreamable_withNullUri_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> ProxyTransportFactoryTests.this.factory.openStreamable(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("mcpUri");
		}

		@Test
		@Story("Header forwarding — auth only")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openStreamable() three-arg overload with an authorization value and no custom headers "
				+ "returns a non-null streamable transport")
		void openStreamable_withAuthorizationOnly_returnsStreamableTransport() {
			// given
			final URI mcpUri = URI.create("http://127.0.0.1:8080/mcp");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openStreamable(mcpUri,
					"Bearer tok-123", null);

			// then
			assertThat(transport).isNotNull().isInstanceOf(HttpClientStreamableHttpTransport.class);
		}

		@Test
		@Story("Header forwarding — custom headers only")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openStreamable() three-arg overload with no authorization but with custom headers "
				+ "returns a non-null streamable transport")
		void openStreamable_withCustomHeadersOnly_returnsStreamableTransport() {
			// given
			final URI mcpUri = URI.create("http://127.0.0.1:8080/mcp");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openStreamable(mcpUri, null,
					Map.of("X-Tenant", "acme"));

			// then
			assertThat(transport).isNotNull().isInstanceOf(HttpClientStreamableHttpTransport.class);
		}

		@Test
		@Story("Header forwarding — auth and custom headers")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openStreamable() three-arg overload with both authorization and custom headers "
				+ "returns a non-null streamable transport")
		void openStreamable_withAuthAndCustomHeaders_returnsStreamableTransport() {
			// given
			final URI mcpUri = URI.create("http://127.0.0.1:8080/mcp");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openStreamable(mcpUri,
					"Bearer tok-abc", Map.of("X-Tenant", "acme"));

			// then
			assertThat(transport).isNotNull().isInstanceOf(HttpClientStreamableHttpTransport.class);
		}

		@Test
		@Story("Header forwarding — both null/empty")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStreamable() three-arg overload with null authorization and empty custom headers "
				+ "behaves like the single-arg overload and returns a non-null streamable transport")
		void openStreamable_withNullAuthAndEmptyCustomHeaders_returnsStreamableTransport() {
			// given
			final URI mcpUri = URI.create("http://127.0.0.1:8080/mcp");

			// when
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openStreamable(mcpUri, null,
					Map.of());

			// then
			assertThat(transport).isNotNull().isInstanceOf(HttpClientStreamableHttpTransport.class);
		}

		@Test
		@Story("Restricted header swallowed")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStreamable() does not propagate an IllegalArgumentException caused by a restricted "
				+ "custom header name — the transport is still returned")
		void openStreamable_withRestrictedCustomHeaderName_doesNotThrow() {
			// given — "host" is a restricted header in Java's HttpClient
			final URI mcpUri = URI.create("http://127.0.0.1:8080/mcp");

			// when & then — must not throw
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openStreamable(mcpUri, null,
					Map.of("host", "evil.example.com"));

			assertThat(transport).isNotNull().isInstanceOf(HttpClientStreamableHttpTransport.class);
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
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openStdio(command, env);

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
			final McpClientTransport transport = ProxyTransportFactoryTests.this.factory.openStdio(command, null);

			// then
			assertThat(transport).isInstanceOf(StdioClientTransport.class);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() rejects a null command")
		void openStdio_withNullCommand_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> ProxyTransportFactoryTests.this.factory.openStdio(null, Map.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("command");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() rejects an empty command list")
		void openStdio_withEmptyCommand_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> ProxyTransportFactoryTests.this.factory.openStdio(List.of(), Map.of()))
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
		@Description("the no-arg constructor builds a usable factory with a default JsonMapper")
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
