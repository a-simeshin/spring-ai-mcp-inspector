/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.it;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.inspector.mcp.demo.DemoApplication;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke ITs for the server-initiated MCP affordances added in this task: sampling
 * ({@code askLlm}), elicitation ({@code askUser}), and roots ({@code listMyRoots}).
 *
 * <p>
 * Each test brings up the demo server on the streamable-HTTP transport and builds a
 * hand-rolled {@link McpSyncClient} that wires the appropriate client capability
 * ({@code sampling} / {@code elicitation} / {@code roots}) plus the matching handler. The
 * {@link io.inspector.mcp.core.client.LoopbackMcpClientFactory} is not used here because
 * it does not expose the {@code roots(...)} builder hook we need.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
		"spring.ai.mcp.inspector.auth-enabled=false",
		"spring.autoconfigure.exclude="
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebFluxAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebFluxAutoConfiguration,"
				+ "io.inspector.mcp.webflux.McpInspectorWebFluxAutoConfiguration" })
class InteractiveToolsIT {

	@LocalServerPort
	private int port;

	@Test
	void toolsListContainsInteractiveTools() {
		try (McpSyncClient client = baseClient().build()) {
			client.initialize();
			ListToolsResult result = client.listTools();
			List<String> names = result.tools().stream().map(t -> t.name()).toList();
			assertThat(names).contains("askLlm", "askUser", "listMyRoots");
		}
	}

	@Test
	void listMyRootsReturnsClientAdvertisedRoots() {
		try (McpSyncClient client = baseClient().capabilities(ClientCapabilities.builder().roots(false).build())
			.roots(new Root("file:///tmp/demo-root-a", "alpha"), new Root("file:///tmp/demo-root-b", "beta"))
			.build()) {
			client.initialize();
			CallToolResult result = client.callTool(new CallToolRequest("listMyRoots", Map.of()));
			assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
			String text = firstText(result);
			assertThat(text).contains("file:///tmp/demo-root-a");
			assertThat(text).contains("file:///tmp/demo-root-b");
		}
	}

	@Test
	void askLlmReturnsSamplingHandlerResponse() {
		try (McpSyncClient client = baseClient().capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(req -> CreateMessageResult.builder()
				.role(Role.ASSISTANT)
				.content(new TextContent("the sky is blue because of Rayleigh scattering"))
				.model("stub-model")
				.build())
			.build()) {
			client.initialize();
			CallToolResult result = client
				.callTool(new CallToolRequest("askLlm", Map.of("question", "why is the sky blue?")));
			assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
			assertThat(firstText(result)).contains("Rayleigh scattering");
		}
	}

	@Test
	void askUserReturnsElicitationHandlerResponse() {
		try (McpSyncClient client = baseClient().capabilities(ClientCapabilities.builder().elicitation().build())
			// SDK 0.18.2 quirk: ElicitResult.Builder lacks an `action` setter; the
			// 2-arg record constructor is the cleanest path.
			.elicitation(req -> new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("answer", "blue")))
			.build()) {
			client.initialize();
			CallToolResult result = client
				.callTool(new CallToolRequest("askUser", Map.of("question", "what is your favorite color?")));
			assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
			assertThat(firstText(result)).contains("blue");
		}
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	private McpClient.SyncSpec baseClient() {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
			.builder("http://127.0.0.1:" + port)
			.endpoint("/mcp")
			.build();
		return McpClient.sync(transport)
			.requestTimeout(Duration.ofSeconds(10))
			.initializationTimeout(Duration.ofSeconds(10));
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
