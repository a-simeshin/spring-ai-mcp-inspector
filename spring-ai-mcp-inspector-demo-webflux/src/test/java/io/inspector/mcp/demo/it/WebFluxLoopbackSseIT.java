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

import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.demo.DemoApplication;

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

	@Test
	@DisplayName("listTools via the reactive loopback SSE client returns the demo tools")
	@Story("Reactive loopback SSE tools/list")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo server on the reactive (WebFlux + Netty) stack over SSE and verifies a real "
			+ "loopback inspector client can list the expected demo tools (echo, sum, currentTime).")
	void listTools_viaReactiveLoopbackSse_returnsDemoTools() {
		// given
		McpSyncClient client = loopbackFactory.forSse("127.0.0.1", port, "/sse");
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
