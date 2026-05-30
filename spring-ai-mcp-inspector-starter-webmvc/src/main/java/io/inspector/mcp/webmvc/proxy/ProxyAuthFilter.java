/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.proxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

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
 * {@code /mcp-inspector-api/health} is intentionally <em>not</em> protected so
 * load-balancer probes can hit it without credentials.
 *
 * <p>
 * Skipped entirely when {@code spring.ai.mcp.inspector.auth-enabled=false}.
 */
public class ProxyAuthFilter extends OncePerRequestFilter {

	private final McpInspectorProperties properties;

	private final InspectorAuthTokenProvider tokenProvider;

	public ProxyAuthFilter(McpInspectorProperties properties, InspectorAuthTokenProvider tokenProvider) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		if (!properties.isAuthEnabled()) {
			chain.doFilter(request, response);
			return;
		}

		String path = request.getRequestURI();
		if (path != null && path.endsWith("/health")) {
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

		String expected = tokenProvider.token();
		if (presented == null || !constantTimeEquals(presented, expected)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing MCP proxy auth token");
			return;
		}
		chain.doFilter(request, response);
	}

	private static boolean constantTimeEquals(String a, String b) {
		byte[] aa = a.getBytes(StandardCharsets.UTF_8);
		byte[] bb = b.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(aa, bb);
	}

}
