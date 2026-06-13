package io.inspector.mcp.demo;

import java.util.Locale;

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
		// Pin the JVM default locale to English BEFORE Spring (and the MCP SDK) start.
		//
		// MCP SDK 2.0 validates tool inputs via
		// io.modelcontextprotocol.util.ToolInputValidator,
		// which delegates to the jackson3 DefaultJsonSchemaValidator. That validator
		// calls
		// networknt's Schema.validate(JsonNode) without an ExecutionContext/locale, so
		// networknt's
		// ResourceBundleMessageSource resolves ValidationMessage text using
		// Locale.getDefault().
		// On a machine whose default locale is Russian, networknt picks
		// jsv-messages_ru.properties
		// and validation errors surface in Russian (shown verbatim in the inspector UI).
		//
		// The SDK exposes no hook to set the validator locale, so the cleanest fix at the
		// demo
		// level is to force the process-wide default locale to English up front. This
		// makes
		// validation messages (and any other Locale.getDefault()-driven text)
		// locale-neutral.
		Locale.setDefault(Locale.ENGLISH);
		SpringApplication.run(DemoApplication.class, args);
	}

}
