/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux.it;

import java.time.Instant;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class TestToolsProvider {

	@McpTool(name = "echo", description = "Echo back input text")
	public String echo(@McpToolParam(description = "text", required = true) String text) {
		return text;
	}

	@McpTool(name = "sum", description = "Sum two integers")
	public int sum(@McpToolParam(description = "a", required = true) int a,
			@McpToolParam(description = "b", required = true) int b) {
		return a + b;
	}

	@McpTool(name = "currentTime", description = "Current time in ISO-8601")
	public String currentTime() {
		return Instant.now().toString();
	}

}
