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
package io.inspector.mcp.demo.it;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitUrlRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import io.inspector.mcp.demo.DemoApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@code authorizeViaUrl} demo tool, which exercises
 * <em>url-mode</em> MCP elicitation ({@code elicitation/create} with {@code mode:"url"}).
 *
 * <p>
 * The suite boots the demo server on the streamable-HTTP transport (webmvc stack) and
 * builds a hand-rolled {@link McpSyncClient} that advertises url-mode elicitation via
 * {@link ClientCapabilities.Builder#elicitation(boolean, boolean)} and wires a
 * {@link McpClient.SyncSpec#urlElicitation} handler. Two scenarios are verified:
 * <ol>
 * <li><strong>Accept</strong> — handler returns {@link ElicitResult.Action#ACCEPT}; tool
 * result must contain {@code "user accepted and returned from"}.</li>
 * <li><strong>Decline</strong> — handler returns {@link ElicitResult.Action#DECLINE};
 * tool result must contain {@code "user decline"}.</li>
 * </ol>
 *
 * <p>
 * The test pattern mirrors {@link InteractiveToolsIT}: hand-rolled {@link McpSyncClient},
 * same Spring Boot harness, same {@code STREAMABLE} protocol property, same webflux
 * autoconfigure exclusions.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("MCP Interactive Tools")
@Feature("URL-mode elicitation")
class AuthorizeViaUrlIT {

	private static final String AUTH_URL = "https://oauth.example.com/authorize?x=1";

	@LocalServerPort
	private int port;

	@Nested
	@DisplayName("authorizeViaUrl() — ACCEPT")
	class AcceptVariant {

		@Test
		@DisplayName("authorizeViaUrl returns accepted message when client handler returns ACCEPT")
		@Story("URL elicitation — accept flow")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Verifies that authorizeViaUrl surfaces 'user accepted and returned from' "
				+ "when the url-mode elicitation handler returns ACCEPT.")
		void authorizeViaUrl_whenHandlerAccepts_returnsAcceptedMessage() {
			// given — client advertises url-mode only (form=false, url=true)
			try (McpSyncClient client = urlClient(req -> {
				assertThat(req.url()).isEqualTo(AUTH_URL);
				assertThat(req.message()).isNotBlank();
				return new ElicitResult(ElicitResult.Action.ACCEPT, null);
			})) {
				// when
				client.initialize();
				final CallToolResult result = client
					.callTool(new CallToolRequest("authorizeViaUrl", Map.of("authUrl", AUTH_URL)));

				// then
				assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
				assertThat(firstText(result)).contains("user accepted and returned from");
			}
		}

	}

	@Nested
	@DisplayName("authorizeViaUrl() — DECLINE")
	class DeclineVariant {

		@Test
		@DisplayName("authorizeViaUrl returns decline message when client handler returns DECLINE")
		@Story("URL elicitation — decline flow")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Verifies that authorizeViaUrl surfaces 'user decline' "
				+ "when the url-mode elicitation handler returns DECLINE.")
		void authorizeViaUrl_whenHandlerDeclines_returnsDeclineMessage() {
			// given — client advertises url-mode only (form=false, url=true)
			try (McpSyncClient client = urlClient(req -> new ElicitResult(ElicitResult.Action.DECLINE, null))) {
				// when
				client.initialize();
				final CallToolResult result = client
					.callTool(new CallToolRequest("authorizeViaUrl", Map.of("authUrl", AUTH_URL)));

				// then
				assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
				assertThat(firstText(result)).contains("user decline");
			}
		}

	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	/**
	 * Builds a {@link McpSyncClient} connected to the test server via streamable-HTTP
	 * that advertises <em>url-mode</em> elicitation and wires the supplied handler.
	 * @param handler called by the SDK when the server issues an
	 * {@code elicitation/create?mode=url} request
	 * @return a ready-to-{@link McpSyncClient#initialize() initialize} client
	 */
	private McpSyncClient urlClient(final java.util.function.Function<ElicitUrlRequest, ElicitResult> handler) {
		final HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
			.builder("http://127.0.0.1:" + port)
			.endpoint("/mcp")
			.build();
		return McpClient.sync(transport)
			.requestTimeout(Duration.ofSeconds(10))
			.initializationTimeout(Duration.ofSeconds(10))
			// advertise url-mode elicitation (form=false, url=true)
			.capabilities(ClientCapabilities.builder().elicitation(false, true).build())
			.urlElicitation(handler)
			.build();
	}

	private static String firstText(final CallToolResult result) {
		final List<Content> contents = result.content();
		if (contents == null || contents.isEmpty()) {
			return "";
		}
		final Content first = contents.get(0);
		if (first instanceof TextContent text) {
			return text.text();
		}
		return String.valueOf(first);
	}

}
