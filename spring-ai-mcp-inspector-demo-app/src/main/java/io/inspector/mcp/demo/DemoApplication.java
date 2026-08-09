package io.inspector.mcp.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Demo MCP server application.
 *
 * <p>
 * This module carries the demo itself and no web stack, so the transport comes from
 * whichever module you build on top of it — {@code spring-ai-mcp-inspector-demo-webmvc}
 * for the servlet stack, {@code spring-ai-mcp-inspector-demo-webflux} for the reactive
 * one. Spring profiles ({@code sse}, {@code streamable}, {@code stateless},
 * {@code stdio}) then pick the MCP endpoint style.
 *
 * <p>
 * Tools, resources and prompts are discovered automatically by the Spring AI MCP server
 * starter via {@code @McpTool}, {@code @McpResource} and {@code @McpPrompt} annotations
 * on {@code @Component}-managed beans.
 */
@SpringBootApplication
@EnableScheduling
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

}
