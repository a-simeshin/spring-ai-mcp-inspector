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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive (WebFlux + Netty) variant of {@code LoopbackStatelessTransportIT}.
 *
 * <p>
 * Mandated by {@code ### Integration Layer (Java)} →
 * {@code WebFluxLoopbackStatelessIT#toolsListReactiveViaLoopbackStateless}.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.main.web-application-type=reactive",
		"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.inspector.auth-enabled=false",
		"spring.autoconfigure.exclude="
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebMvcAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebMvcAutoConfiguration,"
				+ "io.inspector.mcp.webmvc.McpInspectorWebMvcAutoConfiguration" })
class WebFluxLoopbackStatelessIT {

	@Autowired
	private LoopbackMcpClientFactory loopbackFactory;

	@LocalServerPort
	private int port;

	@Test
	void toolsListReactiveViaLoopbackStateless() {
		McpSyncClient client = loopbackFactory.forStateless("127.0.0.1", port, "/mcp");
		try {
			client.initialize();
			ListToolsResult result = client.listTools();
			List<String> names = result.tools().stream().map(t -> t.name()).toList();
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
