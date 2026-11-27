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
import java.time.Instant;
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
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import io.inspector.mcp.demo.DemoApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive-stack pin for the server-initiated interactive tools (issue #377): a demo tool
 * body must never run on a Netty event loop, where the SDK's blocking
 * {@code createElicitation} call is rejected and {@code tools/call} settles with an error
 * before the user gets to answer.
 *
 * <p>
 * Mirrors the servlet-stack {@code InteractiveToolsIT} askUser case, but the client
 * handler DELAYS its answer: a server that keeps the call pending until the elicitation
 * comes back must finish late and carry the answer, while a server that settles
 * immediately fails this test with the error text in the assertion message.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("MCP Interactive Tools")
@Feature("Elicitation (WebFlux)")
class WebFluxInteractiveToolsIT {

	/**
	 * Must comfortably exceed the delayed handler below, so the answer always arrives.
	 */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

	/** How long the client handler sits on the elicitation before answering. */
	private static final Duration HANDLER_DELAY = Duration.ofMillis(800);

	/** Lower bound proving the call was kept pending, not settled instantly. */
	private static final Duration PENDING_FLOOR = Duration.ofMillis(500);

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("askUser stays pending until the delayed elicitation answer arrives")
	@Story("Elicitation")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Calls askUser with a client handler that delays its answer by 800ms and asserts the "
			+ "tools/call stays pending for at least that long, then surfaces the answer. An immediate "
			+ "error reply means the blocking elicitation ran on the event loop (issue #377)")
	void askUser_staysPending_untilDelayedAnswerArrives() {
		// given
		try (McpSyncClient client = baseClient().capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(req -> {
				try {
					Thread.sleep(HANDLER_DELAY.toMillis());
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("delayed elicitation handler interrupted", interrupted);
				}
				return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("answer", "blue"));
			})
			.build()) {
			client.initialize();

			// when
			Instant t0 = Instant.now();
			CallToolResult result = client
				.callTool(new CallToolRequest("askUser", Map.of("question", "what is your favorite color?")));
			Duration elapsed = Duration.between(t0, Instant.now());

			System.err.printf("[DIAG] askUser elapsed=%d ms resultText=%s%n", elapsed.toMillis(), firstText(result));
			// then
			assertThat(elapsed)
				.as("askUser must stay pending until the elicitation is answered (>= %s), not settle instantly",
						PENDING_FLOOR)
				.isGreaterThanOrEqualTo(PENDING_FLOOR);
			// A broken run settles instantly with the guidance/error string; the
			// answer text is the positive pin that the elicitation round-trip happened.
			assertThat(firstText(result))
				.as("askUser must return the client handler's answer, not a guidance/error string")
				.contains("blue");
		}
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	private McpClient.SyncSpec baseClient() {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
			.builder("http://127.0.0.1:" + this.port)
			.endpoint("/mcp")
			.build();
		return McpClient.sync(transport).requestTimeout(REQUEST_TIMEOUT).initializationTimeout(Duration.ofSeconds(10));
	}

	private static String firstText(CallToolResult result) {
		List<Content> contents = result.content();
		if (contents == null || contents.isEmpty()) {
			return "";
		}
		Content first = contents.get(0);
		if (first instanceof TextContent text) {
			return text.text();
		}
		return String.valueOf(first);
	}

}
