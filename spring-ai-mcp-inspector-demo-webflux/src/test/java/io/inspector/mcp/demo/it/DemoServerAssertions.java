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

import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceContents;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP surface every reactive loopback transport has to deliver: the tool catalogue, a
 * resource body, and two tool calls that actually carry arguments in and results back.
 *
 * <p>
 * The three {@code WebFluxLoopback*IT} classes differ only in the transport they open, so
 * the assertions live here once. Listing tools alone proved little: it exercises a single
 * server-to-client response and never touches argument marshalling or the resources API,
 * which is where a transport swap actually breaks.
 */
final class DemoServerAssertions {

	/** {@code DemoResourcesProvider#greeting()}. */
	private static final String GREETING_URI = "demo://greeting";

	private static final String GREETING_BODY = "Hello from MCP demo";

	private DemoServerAssertions() {
	}

	/** {@code tools/list} advertises the demo tool catalogue. */
	static void assertListsDemoTools(McpSyncClient client) {
		ListToolsResult result = client.listTools();
		assertThat(result.tools()).extracting(tool -> tool.name()).contains("echo", "sum", "currentTime");
	}

	/** {@code resources/read} returns the static greeting body verbatim. */
	static void assertReadsGreetingResource(McpSyncClient client) {
		ReadResourceResult result = client.readResource(ReadResourceRequest.builder(GREETING_URI).build());

		assertThat(result.contents()).as("%s returned content", GREETING_URI).isNotEmpty();
		ResourceContents first = result.contents().get(0);
		assertThat(first).isInstanceOf(TextResourceContents.class);
		assertThat(((TextResourceContents) first).text()).as("greeting body").isEqualTo(GREETING_BODY);
	}

	/**
	 * {@code tools/call} carries arguments to the server and the computed result back:
	 * {@code echo} proves a string argument survives the round trip, {@code sum} proves
	 * the server ran real logic on two numeric arguments rather than echoing input.
	 */
	static void assertCallsDemoTools(McpSyncClient client) {
		assertThat(callToolText(client, "echo", Map.of("text", "reactive-loopback"))).as("echo returns its argument")
			.contains("reactive-loopback");

		assertThat(callToolText(client, "sum", Map.of("a", 41, "b", 1))).as("sum adds its arguments").contains("42");
	}

	/** Calls a tool and returns the concatenated text of every text fragment. */
	private static String callToolText(McpSyncClient client, String name, Map<String, Object> arguments) {
		CallToolResult result = client.callTool(CallToolRequest.builder(name).arguments(arguments).build());
		assertThat(result.isError()).as("tool %s did not error", name).isNotEqualTo(Boolean.TRUE);

		StringBuilder text = new StringBuilder();
		for (Content content : result.content()) {
			if (content instanceof TextContent fragment) {
				text.append(fragment.text());
			}
		}
		assertThat(text.length()).as("tool %s returned text content", name).isPositive();
		return text.toString();
	}

}
