/*
 * Copyright 2026 the original author or authors.
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

package io.inspector.mcp.webmvc.proxy;

/**
 * Shared constants for the upstream-proxy controllers.
 *
 * <p>
 * Endpoint layout (kept in lock-step with the upstream
 * {@code modelcontextprotocol/inspector} server, minus the {@code /sandbox} route):
 *
 * <ul>
 * <li>{@code GET    /mcp-inspector-api/sse} — open a session against an SSE / Streamable
 * / Stdio target</li>
 * <li>{@code POST   /mcp-inspector-api/message} — push a frame into the open SSE
 * session</li>
 * <li>{@code POST/GET/DELETE /mcp-inspector-api/mcp} — Streamable-HTTP transport</li>
 * <li>{@code GET    /mcp-inspector-api/stdio} — open a session against a stdio
 * target</li>
 * <li>{@code GET    /mcp-inspector-api/config} — defaults for the client form</li>
 * <li>{@code GET    /mcp-inspector-api/health} — liveness probe (no auth)</li>
 * <li>{@code POST   /mcp-inspector-api/fetch} — generic outbound HTTP fetch</li>
 * </ul>
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
	 *
	 * <p>
	 * This constant is kept purely for grep-ability and javadoc cross-linking; it must
	 * <strong>not</strong> be used to build request mappings, filter URL patterns, or any
	 * other runtime-effective path string. Production code reads the path from
	 * {@code McpInspectorProperties} or via SpEL placeholders of the form
	 * {@code "${spring.ai.mcp.inspector.path}-api"}.
	 * @since 0.1.0
	 * @deprecated Read the path from {@code McpInspectorProperties#getProxyPath()} or use
	 * the SpEL placeholder {@code "${spring.ai.mcp.inspector.path}-api"} in annotations.
	 */
	@Deprecated(since = "0.1.0")
	public static final String BASE = "/mcp-inspector-api";

	/**
	 * Auth header expected on every protected request. Upstream uses
	 * {@code Bearer <token>}.
	 */
	public static final String AUTH_HEADER = "X-MCP-Proxy-Auth";

	/** Auth query-param alias (EventSource cannot set headers). */
	public static final String AUTH_QUERY_PARAM = "MCP_PROXY_AUTH_TOKEN";

	/** {@code mcp-session-id} response header for Streamable-HTTP. */
	public static final String MCP_SESSION_ID_HEADER = "mcp-session-id";

}
