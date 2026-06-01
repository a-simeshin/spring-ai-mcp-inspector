/*
 * Copyright 2025-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.inspector.mcp.webflux.proxy;

/**
 * Shared constants for the upstream-proxy reactive handlers. Mirrors the webmvc-side
 * {@code ProxyConstants}; duplicated to keep the two starters dependency-free of each
 * other.
 *
 * @author Artem Simeshin
 */
public final class ProxyConstants {

	private ProxyConstants() {
	}

	/**
	 * Documentation marker for the proxy path. The real value is bound from
	 * {@code spring.ai.mcp.inspector.path} (with a {@code -api} suffix) and exposed via
	 * {@link io.inspector.mcp.core.config.McpInspectorProperties#getProxyPath()}.
	 * @since 1.0
	 * @deprecated Use
	 * {@link io.inspector.mcp.core.config.McpInspectorProperties#getProxyPath()}.
	 */
	@Deprecated(since = "1.0")
	public static final String BASE = "/mcp-inspector-api";

	/** Header name carrying the proxy authentication token. */
	public static final String AUTH_HEADER = "X-MCP-Proxy-Auth";

	/** Query parameter carrying the proxy authentication token for SSE clients. */
	public static final String AUTH_QUERY_PARAM = "MCP_PROXY_AUTH_TOKEN";

	/** Header name used to correlate Streamable-HTTP sessions. */
	public static final String MCP_SESSION_ID_HEADER = "mcp-session-id";

}
