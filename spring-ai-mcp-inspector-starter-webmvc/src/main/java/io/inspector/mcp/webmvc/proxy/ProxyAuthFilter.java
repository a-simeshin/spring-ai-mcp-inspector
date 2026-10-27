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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;

/**
 * Bearer-token guard for {@code /mcp-inspector-api/**}.
 *
 * <p>
 * Upstream sends the token in {@code X-MCP-Proxy-Auth: Bearer <token>}; we accept both
 * that form and the raw token (no prefix) for tolerance.
 *
 * <p>
 * Browsers cannot set custom headers on {@code EventSource} requests, so we also accept a
 * {@code ?MCP_PROXY_AUTH_TOKEN=<token>} query parameter as a fallback (the upstream UI
 * does the same).
 *
 * <p>
 * {@code /mcp-inspector-api/health} and {@code /mcp-inspector-api/sandbox} are
 * intentionally <em>not</em> protected: load-balancer probes hit health without
 * credentials, and the sandbox proxy page is loaded into an iframe whose document carries
 * no headers. Both are matched by exact path, never by suffix.
 *
 * <p>
 * Skipped entirely when {@code spring.ai.mcp.inspector.auth-enabled=false}.
 *
 * @author Artem Simeshin
 */
public class ProxyAuthFilter extends OncePerRequestFilter {

	private final McpInspectorProperties properties;

	private final InspectorAuthTokenProvider tokenProvider;

	/**
	 * Exact paths of the intentionally open endpoints (health probe and sandbox proxy
	 * page), derived from the configured inspector path. Compared for equality, not by
	 * suffix: a suffix match would also exempt any other endpoint that merely ends with
	 * /health or /sandbox.
	 */
	private final String healthPath;

	private final String sandboxPath;

	public ProxyAuthFilter(final McpInspectorProperties properties, final InspectorAuthTokenProvider tokenProvider) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.healthPath = (properties != null) ? properties.getProxyPath() + "/health" : "/mcp-inspector-api/health";
		this.sandboxPath = (properties != null) ? properties.getProxyPath() + "/sandbox" : "/mcp-inspector-api/sandbox";
	}

	@Override
	protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
			final FilterChain chain) throws ServletException, IOException {

		if (!this.properties.isAuthEnabled()) {
			chain.doFilter(request, response);
			return;
		}

		// CORS preflight requests carry no credentials; let them pass so the CORS
		// layer can answer the preflight instead of rejecting it with 401.
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			chain.doFilter(request, response);
			return;
		}

		final String path = request.getRequestURI();
		if (this.healthPath.equals(path) || this.sandboxPath.equals(path)) {
			chain.doFilter(request, response);
			return;
		}

		String presented = request.getHeader(ProxyConstants.AUTH_HEADER);
		if (presented != null && presented.startsWith("Bearer ")) {
			presented = presented.substring(7);
		}
		if (presented == null || presented.isBlank()) {
			presented = request.getParameter(ProxyConstants.AUTH_QUERY_PARAM);
		}

		final String expected = this.tokenProvider.token();
		if (presented == null || !constantTimeEquals(presented, expected)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing MCP proxy auth token");
			return;
		}
		chain.doFilter(request, response);
	}

	private static boolean constantTimeEquals(final String a, final String b) {
		final byte[] aa = a.getBytes(StandardCharsets.UTF_8);
		final byte[] bb = b.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(aa, bb);
	}

}
