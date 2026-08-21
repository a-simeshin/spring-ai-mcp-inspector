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
 * Reactive (WebFlux + Netty) variant of {@code LoopbackStatelessTransportIT}.
 *
 * <p>
 * Mandated by {@code ### Integration Layer (Java)} →
 * {@code WebFluxLoopbackStatelessIT#toolsListReactiveViaLoopbackStateless}.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("MCP Transports")
@Feature("Loopback Stateless (WebFlux)")
class WebFluxLoopbackStatelessIT {

	@Autowired
	private LoopbackMcpClientFactory loopbackFactory;

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@BeforeEach
	void connect() {
		this.client = loopbackFactory.forStateless("127.0.0.1", this.port, "/mcp");
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
	@DisplayName("listTools via the reactive loopback stateless client returns the demo tools")
	@Story("Reactive loopback stateless tools/list")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo server on the reactive (WebFlux + Netty) stack over stateless and verifies a real "
			+ "loopback inspector client can list the expected demo tools (echo, sum, currentTime).")
	void listTools_viaReactiveLoopbackStateless_returnsDemoTools() {
		DemoServerAssertions.assertListsDemoTools(this.client);
	}

	@Test
	@DisplayName("readResource via the reactive loopback stateless client returns the greeting body")
	@Story("Reactive loopback stateless resources/read")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Reads demo://greeting over the reactive (WebFlux + Netty) stateless transport and verifies the "
			+ "resource body arrives intact: the resources API travels the same transport as tools/list.")
	void readResource_viaReactiveLoopbackStateless_returnsGreetingBody() {
		DemoServerAssertions.assertReadsGreetingResource(this.client);
	}

	@Test
	@DisplayName("callTool via the reactive loopback stateless client round-trips arguments and results")
	@Story("Reactive loopback stateless tools/call")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Calls echo and sum over the reactive (WebFlux + Netty) stateless transport and verifies the "
			+ "arguments reach the server and the computed results come back. Listing tools alone never "
			+ "exercises argument marshalling.")
	void callTool_viaReactiveLoopbackStateless_roundTripsArgumentsAndResults() {
		DemoServerAssertions.assertCallsDemoTools(this.client);
	}

}
