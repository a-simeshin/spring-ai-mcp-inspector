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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.demo.DemoApplication;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive (WebFlux + Netty) variant of {@code LoopbackSseTransportIT}. Nothing here
 * selects the web stack: this module's classpath carries WebFlux and Netty and nothing
 * else, so Spring Boot deduces the reactive application type on its own.
 *
 * <p>
 * Mandated by {@code ### Integration Layer (Java)} →
 * {@code WebFluxLoopbackSseIT#toolsListReactiveViaLoopbackSse}.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("MCP Transports")
@Feature("Loopback SSE (WebFlux)")
class WebFluxLoopbackSseIT {

	@Autowired
	private LoopbackMcpClientFactory loopbackFactory;

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@BeforeEach
	void connect() {
		client = loopbackFactory.forSse("127.0.0.1", port, "/sse");
		client.initialize();
	}

	@AfterEach
	void disconnect() {
		if (client == null) {
			return;
		}
		try {
			client.close();
		}
		catch (Exception ignored) {
			/* best-effort */
		}
	}

	@Test
	@DisplayName("listTools via the reactive loopback SSE client returns the demo tools")
	@Story("Reactive loopback SSE tools/list")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo server on the reactive (WebFlux + Netty) stack over SSE "
			+ "and verifies a real loopback inspector client can list the expected demo tools (echo, sum, currentTime).")
	void listTools_viaReactiveLoopbackSse_returnsDemoTools() {
		// when
		ListToolsResult result = client.listTools();
		List<String> names = result.tools().stream().map(t -> t.name()).toList();

		// then
		assertThat(names).contains("echo", "sum", "currentTime");
	}

	@Test
	@DisplayName("readResource via the reactive loopback SSE client returns the demo greeting payload")
	@Story("Reactive loopback SSE resources/read")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Reads demo://greeting over the reactive loopback SSE transport and asserts the exact text "
			+ "DemoResourcesProvider serves, so a transport that returns an empty or truncated body still fails.")
	void readResource_viaReactiveLoopbackSse_returnsGreetingPayload() {
		// when
		ReadResourceResult result = client.readResource(new ReadResourceRequest("demo://greeting"));

		// then
		assertThat(result.contents()).as("resources/read returned at least one fragment").isNotEmpty();
		assertThat(result.contents().get(0)).isInstanceOf(TextResourceContents.class);
		TextResourceContents contents = (TextResourceContents) result.contents().get(0);
		assertThat(contents.uri()).isEqualTo("demo://greeting");
		assertThat(contents.text()).isEqualTo("Hello from MCP demo");
	}

	@Test
	@DisplayName("callTool via the reactive loopback SSE client executes currentTime")
	@Story("Reactive loopback SSE tools/call")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Calls the argument-less currentTime tool over the reactive loopback SSE transport and asserts "
			+ "the returned text parses as an ISO-8601 instant produced during the call, proving the server really "
			+ "executed the tool rather than echoing an empty result.")
	void callTool_viaReactiveLoopbackSse_returnsFreshInstant() {
		// given
		// currentTime takes no arguments on purpose: this asserts transport plumbing,
		// not the SDK's argument binding, which the servlet-side ITs already cover.
		Instant before = Instant.now().minusSeconds(60);

		// when
		CallToolResult result = client.callTool(new CallToolRequest("currentTime", Map.of()));

		// then
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(result.content()).as("tools/call returned at least one content fragment").isNotEmpty();
		assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
		String text = ((TextContent) result.content().get(0)).text();
		assertThat(Instant.parse(text)).as("currentTime returns a fresh ISO-8601 instant").isAfter(before);
	}

}
