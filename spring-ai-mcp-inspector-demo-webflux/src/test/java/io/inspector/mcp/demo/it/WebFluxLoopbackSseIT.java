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

import io.modelcontextprotocol.client.McpSyncClient;
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

import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.demo.DemoApplication;

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
		this.client = loopbackFactory.forSse("127.0.0.1", this.port, "/sse");
		this.client.initialize();
	}

	@AfterEach
	void disconnect() {
		if (this.client == null) {
			return;
		}
		try {
			this.client.close();
		}
		catch (Exception ignored) {
			/* best-effort */
		}
	}

	@Test
	@DisplayName("listTools via the reactive loopback SSE client returns the demo tools")
	@Story("Reactive loopback SSE tools/list")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo server on the reactive (WebFlux + Netty) stack over SSE and verifies a real "
			+ "loopback inspector client can list the expected demo tools (echo, sum, currentTime).")
	void listTools_viaReactiveLoopbackSse_returnsDemoTools() {
		DemoServerAssertions.assertListsDemoTools(this.client);
	}

	@Test
	@DisplayName("readResource via the reactive loopback SSE client returns the greeting body")
	@Story("Reactive loopback SSE resources/read")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Reads demo://greeting over the reactive (WebFlux + Netty) SSE transport and verifies the "
			+ "resource body arrives intact: the resources API travels the same transport as tools/list.")
	void readResource_viaReactiveLoopbackSse_returnsGreetingBody() {
		DemoServerAssertions.assertReadsGreetingResource(this.client);
	}

	@Test
	@DisplayName("callTool via the reactive loopback SSE client round-trips arguments and results")
	@Story("Reactive loopback SSE tools/call")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Calls echo and sum over the reactive (WebFlux + Netty) SSE transport and verifies the "
			+ "arguments reach the server and the computed results come back. Listing tools alone never "
			+ "exercises argument marshalling.")
	void callTool_viaReactiveLoopbackSse_roundTripsArgumentsAndResults() {
		DemoServerAssertions.assertCallsDemoTools(this.client);
	}

}
