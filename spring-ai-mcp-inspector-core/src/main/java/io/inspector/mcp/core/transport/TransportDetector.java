/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.transport;

import java.util.Locale;

import org.springframework.core.env.Environment;

/**
 * Reads the Spring {@link Environment} to figure out which MCP transport / stack the host
 * application is using. Web-stack-agnostic — does not depend on any servlet or reactor
 * types.
 *
 * <p>
 * Heuristics:
 *
 * <ul>
 * <li>If {@code spring.ai.mcp.server.stdio=true} <em>and</em>
 * {@code spring.main.web-application-type=NONE} →
 * {@link TransportType#STDIO_NO_HTTP}.</li>
 * <li>Otherwise {@code spring.ai.mcp.server.protocol} (SSE / STREAMABLE / STATELESS) is
 * used.</li>
 * <li>If neither stdio nor protocol is set, falls back to
 * {@link TransportType#UNKNOWN}.</li>
 * </ul>
 */
public class TransportDetector {

	public static final String PROP_PROTOCOL = "spring.ai.mcp.server.protocol";

	public static final String PROP_STDIO = "spring.ai.mcp.server.stdio";

	public static final String PROP_WEB_APP_TYPE = "spring.main.web-application-type";

	public static final String PROP_SSE_ENDPOINT = "spring.ai.mcp.server.sse-endpoint";

	public static final String PROP_SSE_MESSAGE_ENDPOINT = "spring.ai.mcp.server.sse-message-endpoint";

	public static final String PROP_MCP_ENDPOINT = "spring.ai.mcp.server.mcp-endpoint";

	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	public static final String DEFAULT_SSE_MESSAGE_ENDPOINT = "/mcp/message";

	public static final String DEFAULT_MCP_ENDPOINT = "/mcp";

	public static final String STACK_WEBMVC = "WEBMVC";

	public static final String STACK_WEBFLUX = "WEBFLUX";

	public static final String STACK_STDIO = "STDIO";

	private final Environment environment;

	public TransportDetector(Environment environment) {
		this.environment = environment;
	}

	/**
	 * Performs detection.
	 * @return populated {@link DetectedTransport}; never {@code null}
	 */
	public DetectedTransport detect() {
		boolean stdio = environment.getProperty(PROP_STDIO, Boolean.class, Boolean.FALSE);
		String webAppType = normalize(environment.getProperty(PROP_WEB_APP_TYPE));

		if (stdio && "NONE".equals(webAppType)) {
			return new DetectedTransport(TransportType.STDIO_NO_HTTP, null, null, STACK_STDIO);
		}

		String stack = "REACTIVE".equals(webAppType) ? STACK_WEBFLUX : STACK_WEBMVC;

		String protocolRaw = environment.getProperty(PROP_PROTOCOL);
		String protocol = normalize(protocolRaw);

		if (protocol == null || protocol.isEmpty()) {
			return new DetectedTransport(TransportType.UNKNOWN, null, null, stack);
		}

		return switch (protocol) {
			case "SSE" -> new DetectedTransport(TransportType.SSE,
					environment.getProperty(PROP_SSE_ENDPOINT, DEFAULT_SSE_ENDPOINT),
					environment.getProperty(PROP_SSE_MESSAGE_ENDPOINT, DEFAULT_SSE_MESSAGE_ENDPOINT), stack);
			case "STREAMABLE" -> new DetectedTransport(TransportType.STREAMABLE,
					environment.getProperty(PROP_MCP_ENDPOINT, DEFAULT_MCP_ENDPOINT), null, stack);
			case "STATELESS" -> new DetectedTransport(TransportType.STATELESS,
					environment.getProperty(PROP_MCP_ENDPOINT, DEFAULT_MCP_ENDPOINT), null, stack);
			default -> new DetectedTransport(TransportType.UNKNOWN, null, null, stack);
		};
	}

	private static String normalize(String value) {
		return (value == null) ? null : value.trim().toUpperCase(Locale.ROOT);
	}

}
