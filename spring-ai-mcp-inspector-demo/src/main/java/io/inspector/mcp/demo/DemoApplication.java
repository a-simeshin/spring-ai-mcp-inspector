package io.inspector.mcp.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Demo MCP server application.
 *
 * <p>
 * Showcases the Spring AI MCP Inspector across multiple Maven profiles ({@code webmvc},
 * {@code webflux}, {@code stdio-only}) and Spring profiles ({@code sse},
 * {@code streamable}, {@code stateless}, {@code stdio}).
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
