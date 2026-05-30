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

import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.demo.DemoApplication;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static io.inspector.mcp.demo.stress.StressTestSupport.WEBMVC_EXCLUDES;

/** Internal arg-binding debug — not for failsafe; flip Disabled to investigate. */
@Disabled("debug helper — kept for re-investigation when the -parameters bug lands a fix; see run report")
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.inspector.auth-enabled=false",
				"spring.autoconfigure.exclude=" + WEBMVC_EXCLUDES, "logging.level.io.modelcontextprotocol=DEBUG" })
class _DebugArgBindingTestDisabled {

	@Autowired
	LoopbackMcpClientFactory loopbackFactory;

	@LocalServerPort
	int port;

	@Test
	void probe() {
		McpSyncClient c = loopbackFactory.forStreamable("127.0.0.1", port, "/mcp");
		try {
			c.initialize();
			Map<String, Object> args = new LinkedHashMap<>();
			args.put("text", "HELLO-PROBE");
			CallToolResult r = c.callTool(new CallToolRequest("echo", args));
			System.out.println("[probe-map] " + r);
			r = c.callTool(StressTestSupport.buildRequest("echo", args));
			System.out.println("[probe-json] " + r);
			args.clear();
			args.put("a", 7);
			args.put("b", 3);
			r = c.callTool(new CallToolRequest("sum", args));
			System.out.println("[probe-sum] " + r);
		}
		finally {
			try {
				c.close();
			}
			catch (Exception ignored) {
				/* nop */ }
		}
	}

}
