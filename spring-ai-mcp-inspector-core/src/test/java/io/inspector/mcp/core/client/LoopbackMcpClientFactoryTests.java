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

package io.inspector.mcp.core.client;

import io.modelcontextprotocol.client.McpSyncClient;
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

/**
 * Unit tests for {@link LoopbackMcpClientFactory}.
 *
 * <p>
 * These tests verify that the factory constructs {@link McpSyncClient} instances without
 * throwing — they do not initiate any actual transport connection. Real connectivity is
 * covered by {@code LoopbackSseTransportIT} / streamable / stateless.
 */
@Epic("MCP Inspector Core")
@Feature("Loopback MCP client factory")
class LoopbackMcpClientFactoryTests {

	private final LoopbackMcpClientFactory factory = new LoopbackMcpClientFactory();

	private static void closeQuietly(final McpSyncClient client) {
		try {
			client.close();
		}
		catch (final Exception ignored) {
			/* best-effort cleanup */
		}
	}

	@Nested
	@DisplayName("forStreamable()")
	class ForStreamable {

		@Test
		@Story("Build streamable client")
		@Severity(SeverityLevel.CRITICAL)
		@Description("forStreamable() builds a client for a host/port and explicit endpoint")
		void forStreamable_withMessageEndpoint_buildsClient() {
			// given / when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStreamable("localhost", 8080,
					"/mcp");

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

		@Test
		@Story("Default endpoint")
		@Severity(SeverityLevel.NORMAL)
		@Description("forStreamable() defaults the endpoint to /mcp when none is supplied")
		void forStreamable_withNullEndpoint_defaultsToSlashMcp() {
			// given / when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStreamable("127.0.0.1", 8080,
					null);

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

	}

	@Nested
	@DisplayName("forSse()")
	class ForSse {

		@Test
		@Story("Build SSE client")
		@Severity(SeverityLevel.CRITICAL)
		@Description("forSse() builds a client for a host/port with sse and message endpoints")
		void forSse_withMessageEndpoint_buildsClient() {
			// given / when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forSse("localhost", 8080, "",
					"/mcp/message");

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

	}

	@Nested
	@DisplayName("forStateless()")
	class ForStateless {

		@Test
		@Story("Build stateless client")
		@Severity(SeverityLevel.CRITICAL)
		@Description("forStateless() builds a client for a host/port and endpoint")
		void forStateless_withEndpoint_buildsClient() {
			// given / when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStateless("127.0.0.1", 9090,
					"/mcp");

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

		@Test
		@Story("Build stateless client")
		@Severity(SeverityLevel.NORMAL)
		@Description("forStateless() defaults the endpoint to /mcp when none is supplied")
		void forStateless_withNullEndpoint_defaultsToSlashMcp() {
			// given / when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStateless("127.0.0.1", 9090,
					null);

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

	}

	@Nested
	@DisplayName("inspector client handlers")
	class WithHandlers {

		private static final InspectorClientHandlers BOTH = new InspectorClientHandlers((req) -> null, (req) -> null,
				null);

		@Test
		@Story("Wire SSE handlers")
		@Severity(SeverityLevel.NORMAL)
		@Description("forSse() wires sampling and elicitation handlers on the client when supplied")
		void forSse_withHandlers_buildsClient() {
			// given / when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forSse("localhost", 8080, "/base",
					"/mcp/message", BOTH);

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

		@Test
		@Story("Wire streamable handlers")
		@Severity(SeverityLevel.NORMAL)
		@Description("forStreamable() wires sampling and elicitation handlers on the client when supplied")
		void forStreamable_withHandlers_buildsClient() {
			// given / when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStreamable("localhost", 8080,
					"/mcp", BOTH);

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

		@Test
		@Story("Wire stateless handlers")
		@Severity(SeverityLevel.NORMAL)
		@Description("forStateless() wires sampling and elicitation handlers on the client when supplied")
		void forStateless_withHandlers_buildsClient() {
			// given / when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStateless("localhost", 8080,
					"/mcp", BOTH);

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

		@Test
		@Story("Sampling-only handler")
		@Severity(SeverityLevel.MINOR)
		@Description("forStreamable() wires only the sampling handler when elicitation is absent")
		void forStreamable_withSamplingOnly_buildsClient() {
			// given
			final InspectorClientHandlers samplingOnly = new InspectorClientHandlers((req) -> null, null, null);

			// when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStreamable("localhost", 8080,
					"/mcp", samplingOnly);

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

		@Test
		@Story("Default host fallback")
		@Severity(SeverityLevel.MINOR)
		@Description("forSse() falls back to 127.0.0.1 when the host is blank and uses the root base path")
		void forSse_withBlankHostAndRootBase_buildsClient() {
			// given / when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forSse(" ", 8080, "/",
					"/mcp/message", InspectorClientHandlers.none());

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

	}

	/**
	 * Branch-matrix tests for {@code applyHandlers}: exercises the
	 * {@code if(hasForm)/if(hasUrl)/if(hasForm||hasUrl)} conditional paths so the new
	 * url-mode conditionals achieve the required line/branch coverage gate.
	 */
	@Nested
	@DisplayName("applyHandlers branch matrix")
	class ApplyHandlersBranchMatrix {

		@Test
		@Story("Form-only elicitation branch")
		@Severity(SeverityLevel.CRITICAL)
		@Description("applyHandlers enters only the hasForm branch when form handler is set and url handler is null")
		void formOnly_buildsClientAndAdvertisesFormCapability() {
			// given – form handler wired, url handler absent
			final InspectorClientHandlers formOnly = new InspectorClientHandlers(null, (req) -> null, null);

			// when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStreamable("localhost", 8080,
					"/mcp", formOnly);

			// then – client builds without error (branch: hasForm=true, hasUrl=false)
			assertThat(client).isNotNull();
			final io.modelcontextprotocol.spec.McpSchema.ClientCapabilities caps = client.getClientCapabilities();
			assertThat(caps).isNotNull();
			assertThat(caps.elicitation()).isNotNull();
			assertThat(caps.elicitation().form()).isNotNull();
			assertThat(caps.elicitation().url()).isNull();
			closeQuietly(client);
		}

		@Test
		@Story("Url-only elicitation branch")
		@Severity(SeverityLevel.CRITICAL)
		@Description("applyHandlers enters only the hasUrl branch when url handler is set and form handler is null")
		void urlOnly_buildsClientAndAdvertisesUrlCapability() {
			// given – url handler wired, form handler absent
			final InspectorClientHandlers urlOnly = new InspectorClientHandlers(null, null, (req) -> null);

			// when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStreamable("localhost", 8080,
					"/mcp", urlOnly);

			// then – client builds without error (branch: hasForm=false, hasUrl=true)
			assertThat(client).isNotNull();
			final io.modelcontextprotocol.spec.McpSchema.ClientCapabilities caps = client.getClientCapabilities();
			assertThat(caps).isNotNull();
			assertThat(caps.elicitation()).isNotNull();
			assertThat(caps.elicitation().url()).isNotNull();
			assertThat(caps.elicitation().form()).isNull();
			closeQuietly(client);
		}

		@Test
		@Story("Form+url elicitation branch")
		@Severity(SeverityLevel.CRITICAL)
		@Description("applyHandlers enters both hasForm and hasUrl branches when both handlers are set")
		void formAndUrl_buildsClientAndAdvertisesBothCapabilities() {
			// given – both handlers wired
			final InspectorClientHandlers both = new InspectorClientHandlers(null, (req) -> null, (req) -> null);

			// when
			final McpSyncClient client = LoopbackMcpClientFactoryTests.this.factory.forStreamable("localhost", 8080,
					"/mcp", both);

			// then – client builds without error (branch: hasForm=true, hasUrl=true)
			assertThat(client).isNotNull();
			final io.modelcontextprotocol.spec.McpSchema.ClientCapabilities caps = client.getClientCapabilities();
			assertThat(caps).isNotNull();
			assertThat(caps.elicitation()).isNotNull();
			assertThat(caps.elicitation().form()).isNotNull();
			assertThat(caps.elicitation().url()).isNotNull();
			closeQuietly(client);
		}

	}

}
