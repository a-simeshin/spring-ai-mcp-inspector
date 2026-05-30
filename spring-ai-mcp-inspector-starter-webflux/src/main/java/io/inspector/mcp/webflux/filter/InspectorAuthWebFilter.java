/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux.filter;

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
 * Reactive auth filter for the inspector REST endpoints. Validates the
 * {@code X-MCP-Inspector-Auth} header (or {@code ?auth=} query parameter) against the
 * token resolved by {@link InspectorAuthTokenProvider} using a constant-time compare.
 *
 * <p>
 * Only requests starting with {@code /mcp-inspector/api/} are guarded — the UI
 * (index.html, JS, CSS) is served unauthenticated because the token is embedded into the
 * HTML template and the SSE endpoint uses {@code ?auth=}.
 */
public class InspectorAuthWebFilter implements WebFilter, Ordered {

	public static final String HEADER = "X-MCP-Inspector-Auth";

	public static final String QUERY_PARAM = "auth";

	private final McpInspectorProperties properties;

	private final InspectorAuthTokenProvider tokenProvider;

	private final int order;

	private final String apiPrefix;

	public InspectorAuthWebFilter(McpInspectorProperties properties, InspectorAuthTokenProvider tokenProvider) {
		this(properties, tokenProvider, Ordered.HIGHEST_PRECEDENCE + 100);
	}

	public InspectorAuthWebFilter(McpInspectorProperties properties, InspectorAuthTokenProvider tokenProvider,
			int order) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.order = order;
		// Derive the API URL prefix once at construction time from the configured
		// inspector path. The {@code McpInspectorProperties} bean must be present
		// — defensive fallback to the legacy default for tests that wire the
		// filter without a properties bean.
		this.apiPrefix = (properties != null) ? properties.getPath() + "/api/" : "/mcp-inspector/api/";
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
		if (path == null || !path.startsWith(apiPrefix)) {
			return chain.filter(exchange);
		}

		String expected = tokenProvider.token();
		String provided = exchange.getRequest().getHeaders().getFirst(HEADER);
		if (provided == null || provided.isBlank()) {
			provided = exchange.getRequest().getQueryParams().getFirst(QUERY_PARAM);
		}

		if (provided == null || !constantTimeEquals(provided, expected)) {
			exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
			return exchange.getResponse().setComplete();
		}
		return chain.filter(exchange);
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
		byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(aBytes, bBytes);
	}

}
