/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.stress;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.demo.DemoApplication;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
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

import static io.inspector.mcp.demo.stress.StressTestSupport.quietClose;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress: {@code deepJson(depth=50)} produces a 50-level-nested JSON object — the proxy +
 * SDK must hand it back well-formed and parseable.
 *
 * <p>
 * The {@code deepJson} tool returns a {@code Map<String,Object>} from its Java signature.
 * The MCP annotation framework surfaces it either as {@code structuredContent} or as a
 * serialized JSON {@link io.modelcontextprotocol.spec.McpSchema.TextContent} — we accept
 * whichever is present and assert depth = 50 against the parsed tree.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("Stress & Scale")
@Feature("Deep JSON rendering")
class DeepJsonRenderingIT {

	private static final int EXPECTED_DEPTH = 50;

	private final ObjectMapper mapper = new ObjectMapper();

	@Autowired
	private LoopbackMcpClientFactory loopbackFactory;

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@BeforeEach
	void connect() {
		client = loopbackFactory.forStreamable("127.0.0.1", port, "/mcp");
		client.initialize();
	}

	@AfterEach
	void disconnect() {
		quietClose(client);
	}

	@Test
	@Story("Nested object round-trip")
	@Severity(SeverityLevel.NORMAL)
	@Description("Verifies deepJson output round-trips over the proxy as a well-formed, parseable JSON tree")
	@DisplayName("deepJson returns a parseable nested object")
	void deepJson_overProxy_returnsParseableNestedObject() throws Exception {
		// given
		// `depth` binds by name: the root pom compiles with -parameters, which
		// mcp-annotations needs to see declared parameter names instead of arg0.
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("depth", EXPECTED_DEPTH);

		// when
		CallToolResult result = client.callTool(StressTestSupport.buildRequest("deepJson", args));

		// then
		assertThat(result.isError()).as("deepJson must succeed when depth is supplied").isNotEqualTo(true);

		JsonNode root = resolveTree(result);
		assertThat(root).as("deepJson must yield a JSON object").isNotNull();
		assertThat(root.isObject()).as("deepJson returned a parseable JSON object").isTrue();
		assertThat(measureDepth(root)).as("the whole nested tree survives the proxy round-trip")
			.isEqualTo(EXPECTED_DEPTH);
	}

	/**
	 * Locate the JSON tree in either {@code structuredContent} or the first TextContent
	 * fragment. Order matters: structured wins so we don't depend on the TextContent
	 * serialization choosing a particular formatting.
	 */
	private JsonNode resolveTree(CallToolResult result) throws Exception {
		Object structured = result.structuredContent();
		if (structured != null) {
			return mapper.valueToTree(structured);
		}
		// Fall back to TextContent — concatenate all text fragments and parse.
		StringBuilder text = new StringBuilder();
		for (var c : result.content()) {
			if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent t) {
				text.append(t.text());
			}
		}
		assertThat(text.length()).as("no TextContent in deepJson result").isPositive();
		return mapper.readTree(text.toString());
	}

	/** Walk the {@code "a"} chain — provider always uses field name {@code a}. */
	private static int measureDepth(JsonNode node) {
		int d = 0;
		JsonNode cursor = node;
		while (cursor != null && cursor.isObject() && cursor.has("a")) {
			d++;
			cursor = cursor.get("a");
		}
		return d;
	}

}
