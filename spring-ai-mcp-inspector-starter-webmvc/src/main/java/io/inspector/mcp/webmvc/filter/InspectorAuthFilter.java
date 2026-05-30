/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.filter;

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
 */
public class InspectorAuthFilter extends OncePerRequestFilter {

	public static final String AUTH_HEADER = "X-MCP-Inspector-Auth";

	public static final String AUTH_QUERY_PARAM = "auth";

	private final McpInspectorProperties properties;

	private final InspectorAuthTokenProvider tokenProvider;

	public InspectorAuthFilter(McpInspectorProperties properties, InspectorAuthTokenProvider tokenProvider) {
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

		String presented = request.getHeader(AUTH_HEADER);
		if (presented == null || presented.isBlank()) {
			presented = request.getParameter(AUTH_QUERY_PARAM);
		}

		String expected = tokenProvider.token();
		if (presented == null || !constantTimeEquals(presented, expected)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing inspector auth token");
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
