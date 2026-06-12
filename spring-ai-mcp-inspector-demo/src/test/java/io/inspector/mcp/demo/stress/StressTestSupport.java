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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Shared helpers for the {@code *IT.java} stress / big-data integration tests under
 * {@code io.inspector.mcp.demo.stress}.
 *
 * <p>
 * All methods are deliberately allocation-light so we can fan them out across 100s of
 * concurrent invocations without skewing wall-time measurements.
 */
final class StressTestSupport {

	private StressTestSupport() {
	}

	/**
	 * Common autoconfig-exclude list used by the WebMVC-stack stress ITs.
	 *
	 * <p>
	 * Declared as a true compile-time constant ({@code String} concatenation of literals)
	 * so it can be referenced from {@code @TestPropertySource} annotation values, which
	 * require constant expressions.
	 */
	static final String WEBMVC_EXCLUDES = "org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebFluxAutoConfiguration,"
			+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration,"
			+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebFluxAutoConfiguration,"
			+ "io.inspector.mcp.webflux.McpInspectorWebFluxAutoConfiguration";

	/**
	 * Single-shot {@code tools/call} that returns the concatenated text of every
	 * {@link TextContent} content fragment in the result.
	 *
	 * <p>
	 * Empirically MCP SDK 0.18.2 + mcp-annotations 0.9 does not bind arguments supplied
	 * to {@code CallToolRequest(String, Map)} when invoked from the loopback client —
	 * every parameter arrives at the server as {@code null}. The JSON-string constructor
	 * (which routes through {@link JacksonMcpJsonMapper}) binds correctly, so we render
	 * the {@code Map} to a JSON string ourselves and use the JSON-string ctor.
	 *
	 * <p>
	 * Documented as a backend bug in the run report. The user-facing inspector UI is
	 * unaffected — its proxy controller hands the raw JSON args through verbatim, never
	 * building a Java {@code Map} first.
	 */
	static String callToolText(McpSyncClient client, String name, Map<String, Object> args) {
		CallToolResult result = client.callTool(buildRequest(name, args));
		if (Boolean.TRUE.equals(result.isError())) {
			throw new IllegalStateException("tool " + name + " returned isError=true: " + result);
		}
		StringBuilder out = new StringBuilder();
		for (Content content : result.content()) {
			if (content instanceof TextContent text) {
				out.append(text.text());
			}
		}
		return out.toString();
	}

	/**
	 * Same as {@link #callToolText} but returns the raw {@link CallToolResult} so callers
	 * can inspect structured / image / embedded fragments.
	 */
	static CallToolResult callTool(McpSyncClient client, String name, Map<String, Object> args) {
		return client.callTool(buildRequest(name, args));
	}

	/** Builds the request via the JSON-string ctor — see {@link #callToolText}. */
	static CallToolRequest buildRequest(String name, Map<String, Object> args) {
		Map<String, Object> argMap = (args == null) ? new LinkedHashMap<>() : args;
		try {
			String json = JSON.writeValueAsString(argMap);
			return new CallToolRequest(MCP_JSON, name, json);
		}
		catch (Exception ex) {
			throw new IllegalStateException("failed to serialize args for tool " + name, ex);
		}
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final JacksonMcpJsonMapper MCP_JSON = new JacksonMcpJsonMapper(JSON);

	/**
	 * Boots a fresh {@link io.inspector.mcp.demo.DemoApplication} on a random port using
	 * the proven {@link org.springframework.boot.builder.SpringApplicationBuilder}
	 * pattern from {@code InspectorE2ETest#startApp} / {@code ProxyAppHarness}.
	 *
	 * <p>
	 * This is the only reliable way to get the {@code /mcp-inspector-api/*} controllers
	 * mapped — a plain {@code @SpringBootTest(classes =
	 * DemoApplication.class, webEnvironment = RANDOM_PORT)} autoconfigures loopback
	 * factory beans but does <em>not</em> hand them through to the MVC request-mapping
	 * handler (empirical: {@code /mcp-inspector-api/health} returns 404 from such a
	 * context).
	 * @param protocol one of {@code SSE}, {@code STREAMABLE}, {@code STATELESS}
	 */
	static org.springframework.context.ConfigurableApplicationContext startWebmvc(String protocol) {
		String exclude = WEBMVC_EXCLUDES;
		String[] args = new String[] { "--server.port=0", "--spring.main.web-application-type=servlet",
				"--spring.ai.mcp.server.protocol=" + protocol.toUpperCase(),
				"--spring.ai.mcp.inspector.auth-enabled=false", "--spring.autoconfigure.exclude=" + exclude, };
		return new org.springframework.boot.builder.SpringApplicationBuilder(
				io.inspector.mcp.demo.DemoApplication.class)
			.web(org.springframework.boot.WebApplicationType.SERVLET)
			.run(args);
	}

	/** Extracts the dynamically allocated server port from a started context. */
	static int port(org.springframework.context.ConfigurableApplicationContext ctx) {
		return ((org.springframework.boot.web.server.context.WebServerApplicationContext) ctx).getWebServer().getPort();
	}

	/** Convenience: best-effort close that never throws (used in {@code @AfterEach}). */
	static void quietClose(McpSyncClient client) {
		if (client == null) {
			return;
		}
		try {
			client.close();
		}
		catch (Exception ignored) {
			/* best-effort */
		}
	}

}
