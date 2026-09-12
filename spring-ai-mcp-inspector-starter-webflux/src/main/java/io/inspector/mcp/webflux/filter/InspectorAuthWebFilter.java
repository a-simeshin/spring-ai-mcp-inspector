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

package io.inspector.mcp.webflux.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.webflux.auth.InspectorSessionAttributes;
import io.inspector.mcp.webflux.auth.ReactiveSessionOwnerResolver;

/**
 * Reactive auth filter for the inspector REST endpoints. Validates the
 * {@code X-MCP-Inspector-Auth} header (or {@code ?auth=} query parameter) against the
 * token resolved by {@link InspectorAuthTokenProvider} using a constant-time compare.
 *
 * <p>
 * After the guard passes, the filter resolves the signed session-owner cookie
 * ({@code MCP_INSPECTOR_SESSION}) via {@link ReactiveSessionOwnerResolver} — minting a
 * fresh signed token when absent/forged/expired — and stashes the validated
 * {@code ownerId} as the {@code OWNER_ID} exchange attribute (D8). A request that FAILS
 * the {@code X-MCP-Inspector-Auth} guard is rejected with 401/403 BEFORE any cookie is
 * minted.
 *
 * <p>
 * Only requests starting with {@code /mcp-inspector/api/} are guarded — the UI
 * (index.html, JS, CSS) is served unauthenticated because the token is embedded into the
 * HTML template and the SSE endpoint uses {@code ?auth=}.
 *
 * @author Artem Simeshin
 */
public class InspectorAuthWebFilter implements WebFilter, Ordered {

	/** Header name carrying the inspector authentication token. */
	public static final String HEADER = "X-MCP-Inspector-Auth";

	/** Query parameter carrying the inspector authentication token for SSE clients. */
	public static final String QUERY_PARAM = "auth";

	private final McpInspectorProperties properties;

	private final InspectorAuthTokenProvider tokenProvider;

	private final ReactiveSessionOwnerResolver sessionOwnerResolver;

	private final int order;

	private final String apiPrefix;

	public InspectorAuthWebFilter(final McpInspectorProperties properties,
			final InspectorAuthTokenProvider tokenProvider) {
		this(properties, tokenProvider, Ordered.HIGHEST_PRECEDENCE + 100, null);
	}

	public InspectorAuthWebFilter(final McpInspectorProperties properties,
			final InspectorAuthTokenProvider tokenProvider, final int order) {
		this(properties, tokenProvider, order, null);
	}

	public InspectorAuthWebFilter(final McpInspectorProperties properties,
			final InspectorAuthTokenProvider tokenProvider, final int order,
			final ReactiveSessionOwnerResolver sessionOwnerResolver) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.sessionOwnerResolver = sessionOwnerResolver;
		this.order = order;
		// Derive the API URL prefix once at construction time from the configured
		// inspector path. The {@code McpInspectorProperties} bean must be present
		// — defensive fallback to the legacy default for tests that wire the
		// filter without a properties bean.
		this.apiPrefix = (properties != null) ? properties.getPath() + "/api/" : "/mcp-inspector/api/";
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
		if (this.properties == null || !this.properties.isAuthEnabled()) {
			return chain.filter(exchange);
		}
		// Application-relative path, never the raw URI: under spring.webflux.base-path
		// or an X-Forwarded-Prefix the prefix lives in contextPath, and matching the
		// raw path against the unprefixed API prefix would leave the API open.
		final String path = exchange.getRequest().getPath().pathWithinApplication().value();
		if (!path.startsWith(this.apiPrefix)) {
			return chain.filter(exchange);
		}
		// CORS preflight requests carry no credentials; let them pass so the CORS
		// layer can answer the preflight instead of rejecting it with 401.
		if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
			return chain.filter(exchange);
		}

		final String expected = this.tokenProvider.token();
		String provided = exchange.getRequest().getHeaders().getFirst(HEADER);
		if (provided == null || provided.isBlank()) {
			provided = exchange.getRequest().getQueryParams().getFirst(QUERY_PARAM);
		}

		if (provided == null || !constantTimeEquals(provided, expected)) {
			exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
			return exchange.getResponse().setComplete();
		}
		// D8: after the inspector guard passes the session owner is ALWAYS established
		// (mint/parse the signed cookie); there is no "missing owner → 401".
		if (this.sessionOwnerResolver != null) {
			exchange.getAttributes()
				.put(InspectorSessionAttributes.OWNER_ID, this.sessionOwnerResolver.resolve(exchange));
		}
		return chain.filter(exchange);
	}

	private static boolean constantTimeEquals(final String a, final String b) {
		if (a == null || b == null) {
			return false;
		}
		final byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
		final byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(aBytes, bBytes);
	}

}
