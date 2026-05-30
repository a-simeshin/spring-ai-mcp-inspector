/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux.proxy;

/**
 * Shared constants for the upstream-proxy reactive handlers. Mirrors the webmvc-side
 * {@code ProxyConstants}; duplicated to keep the two starters dependency-free of each
 * other.
 */
public final class ProxyConstants {

	private ProxyConstants() {
	}

	/**
	 * Documentation marker for the proxy path. The real value is bound from
	 * {@code spring.ai.mcp.inspector.path} (with a {@code -api} suffix) and exposed via
	 * {@link io.inspector.mcp.core.config.McpInspectorProperties#getProxyPath()}.
	 * @deprecated Use
	 * {@link io.inspector.mcp.core.config.McpInspectorProperties#getProxyPath()}.
	 */
	@Deprecated
	public static final String BASE = "/mcp-inspector-api";

	public static final String AUTH_HEADER = "X-MCP-Proxy-Auth";

	public static final String AUTH_QUERY_PARAM = "MCP_PROXY_AUTH_TOKEN";

	public static final String MCP_SESSION_ID_HEADER = "mcp-session-id";

}
