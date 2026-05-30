/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux.router;

import java.net.URI;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.webflux.proxy.ProxyHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * Functional endpoint registration for the inspector UI / API.
 *
 * <p>
 * Mirrors the WebMVC starter's controller surface so the React UI is identical across
 * stacks.
 *
 * <p>
 * All routes are built from {@link McpInspectorProperties#getPath()} /
 * {@link McpInspectorProperties#getProxyPath()} at bean-construction time. The functional
 * router has no SpEL — paths are concatenated as plain strings here.
 *
 * <p>
 * OAuth callback paths {@code /oauth/callback} and {@code /oauth/callback/debug} stay
 * hardcoded — the upstream React bundle checks them as literals against
 * {@code window.location.pathname}, so the backend must serve them at the top-level URL
 * regardless of the inspector's configured prefix.
 */
@Configuration(proxyBeanMethods = false)
public class InspectorRouterConfig {

	private static final ClassPathResource UI_ROOT = new ClassPathResource("mcp-inspector-bundle/");

	@Bean
	public RouterFunction<ServerResponse> inspectorRouter(InspectorHandler handler, McpInspectorProperties properties) {
		String basePath = properties.getPath();
		String apiPath = basePath + "/api";
		URI indexRedirect = URI.create(basePath + "/index.html");
		return route(GET(basePath), req -> ServerResponse.temporaryRedirect(indexRedirect).build())
			.andRoute(GET(basePath + "/"), req -> ServerResponse.temporaryRedirect(indexRedirect).build())
			.andRoute(GET(basePath + "/index.html"), handler::index)
			// Top-level OAuth callback routes serve the same templated SPA so the
			// upstream React client's App.tsx pathname checks
			// (=== "/oauth/callback" / "/oauth/callback/debug") can claim the URL.
			// These paths intentionally stay hardcoded — see class javadoc.
			.andRoute(GET("/oauth/callback"), handler::index)
			.andRoute(GET("/oauth/callback/debug"), handler::index)
			// Typed bootstrap config endpoint. Sits at ${path}/config (outside
			// ${path}/api/*) so it is intentionally not behind the inspector
			// auth filter — it has to deliver the auth token to the SPA.
			.andRoute(GET(basePath + "/config"), handler::serveConfig)
			.andRoute(GET(apiPath + "/config"), handler::config)
			.andRoute(POST(apiPath + "/connect"), handler::connect)
			.andRoute(POST(apiPath + "/jsonrpc"), handler::jsonRpc)
			.andRoute(POST(apiPath + "/jsonrpc/respond"), handler::respond)
			.andRoute(GET(apiPath + "/events"), handler::events)
			.andRoute(GET(apiPath + "/roots"), handler::getRoots)
			.andRoute(PUT(apiPath + "/roots"), handler::putRoots)
			.andRoute(POST(apiPath + "/oauth/initiate"), handler::oauthInitiate)
			.andRoute(GET(apiPath + "/oauth/callback"), handler::oauthCallback)
			.andRoute(DELETE(apiPath + "/session/{id}"), handler::deleteSession)
			.and(RouterFunctions.resources(basePath + "/**", UI_ROOT));
	}

	/**
	 * Upstream-compatible proxy routes. Lives on a sibling prefix ({@code path + "-api"})
	 * to keep the v1 inspector contract intact.
	 */
	@Bean
	public RouterFunction<ServerResponse> inspectorProxyRouter(ProxyHandler proxy, McpInspectorProperties properties) {
		String proxyBase = properties.getProxyPath();
		return route(GET(proxyBase + "/health"), proxy::health).andRoute(GET(proxyBase + "/config"), proxy::config)
			.andRoute(POST(proxyBase + "/fetch"), proxy::fetch)
			.andRoute(GET(proxyBase + "/sse"), proxy::openSse)
			.andRoute(GET(proxyBase + "/stdio"), proxy::openStdio)
			.andRoute(POST(proxyBase + "/message"), proxy::postMessage)
			.andRoute(POST(proxyBase + "/mcp"), proxy::postMcp)
			.andRoute(GET(proxyBase + "/mcp"), proxy::getMcp)
			.andRoute(DELETE(proxyBase + "/mcp"), proxy::deleteMcp);
	}

}
