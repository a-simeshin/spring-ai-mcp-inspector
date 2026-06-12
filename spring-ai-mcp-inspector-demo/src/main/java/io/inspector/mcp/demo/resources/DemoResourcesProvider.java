package io.inspector.mcp.demo.resources;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/**
 * Demo MCP resources exposed by the server.
 *
 * <p>
 * Each method annotated with {@link McpResource} is automatically registered by the
 * Spring AI MCP server annotation scanner.
 */
@Component
public class DemoResourcesProvider {

	/**
	 * Static greeting resource available under the {@code demo://greeting} URI.
	 *
	 * <p>
	 * The {@code key} path variable is captured from the URI template; for the fixed
	 * {@code demo://greeting} URI it is not used, but the parameter is kept so the URI
	 * template binding stays explicit.
	 */
	@McpResource(uri = "demo://greeting", name = "demo-greeting",
			description = "Static greeting message exposed via MCP resources API")
	public String greeting() {
		return "Hello from MCP demo";
	}

}
