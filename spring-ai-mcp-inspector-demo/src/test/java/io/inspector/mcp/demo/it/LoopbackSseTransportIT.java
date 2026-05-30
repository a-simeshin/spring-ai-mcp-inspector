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

import java.util.List;

import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.demo.DemoApplication;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bring up the demo MCP server over <strong>SSE</strong> and exercise a real loopback
 * inspector client end-to-end via {@link LoopbackMcpClientFactory#forSse}.
 *
 * <p>
 * Mandated by {@code ### Integration Layer (Java)} →
 * {@code LoopbackSseTransportIT#toolsListViaLoopbackSse}.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.inspector.auth-enabled=false",
		// Force servlet stack: with both webmvc and webflux MCP starters on the test
		// classpath,
		// we must exclude the webflux autoconfig so the WebMvc SSE transport gets
		// registered.
		"spring.autoconfigure.exclude="
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebFluxAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebFluxAutoConfiguration,"
				+ "io.inspector.mcp.webflux.McpInspectorWebFluxAutoConfiguration" })
@Epic("MCP Transports")
@Feature("Loopback SSE")
class LoopbackSseTransportIT {

	@Autowired
	private LoopbackMcpClientFactory loopbackFactory;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("listTools via the loopback SSE client returns the demo tools")
	@Story("Loopback SSE tools/list")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo server over the SSE transport and verifies a real loopback inspector "
			+ "client can list the expected demo tools (echo, sum, currentTime).")
	void listTools_viaLoopbackSse_returnsDemoTools() {
		// given
		McpSyncClient client = loopbackFactory.forSse("127.0.0.1", port, "", "/mcp/message");
		try {
			// when
			client.initialize();
			ListToolsResult result = client.listTools();
			List<String> names = result.tools().stream().map(t -> t.name()).toList();

			// then
			assertThat(names).contains("echo", "sum", "currentTime");
		}
		finally {
			try {
				client.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
		}
	}

}
