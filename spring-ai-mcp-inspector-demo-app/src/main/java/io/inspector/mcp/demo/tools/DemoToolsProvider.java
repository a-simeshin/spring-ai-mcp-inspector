package io.inspector.mcp.demo.tools;

import java.time.Instant;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Demo MCP tools exposed by the server.
 *
 * <p>
 * Each method annotated with {@link McpTool} is automatically registered by the Spring AI
 * MCP server annotation scanner.
 */
@Component
public class DemoToolsProvider {

	@McpTool(name = "echo", description = "Echo back input text",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public String echo(@McpToolParam(description = "text to echo", required = true) String text) {
		return text;
	}

	@McpTool(name = "sum", description = "Sum two integers",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public int sum(@McpToolParam(description = "first addend", required = true) int a,
			@McpToolParam(description = "second addend", required = true) int b) {
		return a + b;
	}

	@McpTool(name = "currentTime", description = "Current time in ISO-8601 (UTC)",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public String currentTime() {
		return Instant.now().toString();
	}

}
