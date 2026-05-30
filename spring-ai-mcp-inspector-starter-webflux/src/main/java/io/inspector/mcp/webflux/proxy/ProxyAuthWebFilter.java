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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive bearer-token guard for {@code /mcp-inspector-api/**}.
 *
 * <p>
 * Accepts the token in {@code X-MCP-Proxy-Auth} (optionally with a {@code Bearer }
 * prefix) or as a {@code MCP_PROXY_AUTH_TOKEN} query parameter (browsers can't set custom
 * headers on {@code EventSource}).
 *
 * <p>
 * {@code /mcp-inspector-api/health} is allow-listed for unauthenticated monitoring.
 *
 * <p>
 * Skipped when {@code spring.ai.mcp.inspector.auth-enabled=false}.
 */
public class ProxyAuthWebFilter implements WebFilter, Ordered {

	private final McpInspectorProperties properties;

	private final InspectorAuthTokenProvider tokenProvider;

	private final int order;

	private final String proxyPrefix;

	public ProxyAuthWebFilter(McpInspectorProperties properties, InspectorAuthTokenProvider tokenProvider) {
		this(properties, tokenProvider, Ordered.HIGHEST_PRECEDENCE + 110);
	}

	public ProxyAuthWebFilter(McpInspectorProperties properties, InspectorAuthTokenProvider tokenProvider, int order) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.order = order;
		// Derive the proxy URL prefix once at construction time from the configured
		// path; falling back to the legacy default if no properties bean is wired
		// (defensive — in production the autoconfig always supplies one).
		this.proxyPrefix = (properties != null) ? properties.getProxyPath() + "/" : "/mcp-inspector-api/";
	}

	@Override
	public int getOrder() {
		return order;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (properties == null || !properties.isAuthEnabled()) {
			return chain.filter(exchange);
		}
		String path = exchange.getRequest().getURI().getPath();
		if (path == null || !path.startsWith(proxyPrefix)) {
			return chain.filter(exchange);
		}
		// Health is intentionally open.
		if (path.endsWith("/health")) {
			return chain.filter(exchange);
		}

		String presented = exchange.getRequest().getHeaders().getFirst(ProxyConstants.AUTH_HEADER);
		if (presented != null && presented.startsWith("Bearer ")) {
			presented = presented.substring(7);
		}
		if (presented == null || presented.isBlank()) {
			presented = exchange.getRequest().getQueryParams().getFirst(ProxyConstants.AUTH_QUERY_PARAM);
		}

		String expected = tokenProvider.token();
		if (presented == null || !constantTimeEquals(presented, expected)) {
			exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
			return exchange.getResponse().setComplete();
		}
		return chain.filter(exchange);
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		byte[] aa = a.getBytes(StandardCharsets.UTF_8);
		byte[] bb = b.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(aa, bb);
	}

}
