package io.inspector.mcp.demo.prompts;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * Demo MCP prompts exposed by the server.
 *
 * <p>
 * Each method annotated with {@link McpPrompt} is automatically registered by the Spring
 * AI MCP server annotation scanner.
 */
@Component
public class DemoPromptsProvider {

	@McpPrompt(name = "greeting", description = "Generate a personalized greeting message")
	public GetPromptResult greeting(@McpArg(name = "name", description = "User's name", required = true) String name) {
		String message = "Hello, " + name + "!";
		return new GetPromptResult("Greeting", List.of(new PromptMessage(Role.ASSISTANT, new TextContent(message))));
	}

}
