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

package io.inspector.mcp.webmvc.filter;

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
 * Servlet filter that guards all {@code /mcp-inspector/api/**} routes with a bearer-style
 * token.
 *
 * <p>
 * The token may be supplied either via the {@code X-MCP-Inspector-Auth} header (preferred
 * for JSON/REST calls) or as the {@code auth} query parameter (required by
 * {@code EventSource}, which cannot set custom headers).
 *
 * <p>
 * Static resources (the UI itself) and the templated {@code index.html} are intentionally
 * <strong>not</strong> protected — the browser has to load them in order to obtain the
 * token in the first place.
 *
 * @author Artem Simeshin
 */
public class InspectorAuthFilter extends OncePerRequestFilter {

	/** Auth header name accepted on JSON/REST calls. */
	public static final String AUTH_HEADER = "X-MCP-Inspector-Auth";

	/** Auth query-parameter accepted for {@code EventSource} (cannot set headers). */
	public static final String AUTH_QUERY_PARAM = "auth";

	private final McpInspectorProperties properties;

	private final InspectorAuthTokenProvider tokenProvider;

	public InspectorAuthFilter(final McpInspectorProperties properties,
			final InspectorAuthTokenProvider tokenProvider) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
	}

	@Override
	protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
			final FilterChain chain) throws ServletException, IOException {

		if (!this.properties.isAuthEnabled()) {
			chain.doFilter(request, response);
			return;
		}

		String presented = request.getHeader(AUTH_HEADER);
		if (presented == null || presented.isBlank()) {
			presented = request.getParameter(AUTH_QUERY_PARAM);
		}

		final String expected = this.tokenProvider.token();
		if (presented == null || !constantTimeEquals(presented, expected)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing inspector auth token");
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
