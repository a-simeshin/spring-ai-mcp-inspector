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

package io.inspector.mcp.webflux.router;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.webflux.proxy.ProxyHandler;

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
 *
 * @author Artem Simeshin
 */
@Configuration(proxyBeanMethods = false)
public class InspectorRouterConfig {

	private static final ClassPathResource UI_ROOT = new ClassPathResource("mcp-inspector-bundle/");

	/**
	 * Cache policy for everything the resource route serves. Only content-hashed bundle
	 * assets (plus {@code mcp.svg}) get here — {@code index.html} is claimed by the route
	 * above and served no-store, so a long max-age cannot pin a stale entry point.
	 */
	private static final CacheControl ASSET_CACHE_CONTROL = CacheControl.maxAge(7, TimeUnit.DAYS);

	@Bean
	public RouterFunction<ServerResponse> inspectorRouter(final InspectorHandler handler,
			final McpInspectorProperties properties) {
		final String basePath = properties.getPath();
		final String apiPath = basePath + "/api";
		final String indexPath = basePath + "/index.html";
		return route(GET(basePath), (req) -> ServerResponse.temporaryRedirect(indexRedirect(req, indexPath)).build())
			.andRoute(GET(basePath + "/"),
					(req) -> ServerResponse.temporaryRedirect(indexRedirect(req, indexPath)).build())
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
			.andRoute(GET(apiPath + "/introspection"), handler::introspection)
			.andRoute(POST(apiPath + "/connect"), handler::connect)
			.andRoute(POST(apiPath + "/jsonrpc"), handler::jsonRpc)
			.andRoute(POST(apiPath + "/jsonrpc/respond"), handler::respond)
			.andRoute(GET(apiPath + "/events"), handler::events)
			.andRoute(GET(apiPath + "/roots"), handler::getRoots)
			.andRoute(PUT(apiPath + "/roots"), handler::putRoots)
			.andRoute(POST(apiPath + "/oauth/initiate"), handler::oauthInitiate)
			.andRoute(GET(apiPath + "/oauth/callback"), handler::oauthCallback)
			.andRoute(DELETE(apiPath + "/session/{id}"), handler::deleteSession)
			.and(RouterFunctions.resources(basePath + "/**", UI_ROOT,
					(resource, headers) -> headers.setCacheControl(ASSET_CACHE_CONTROL)));
	}

	/**
	 * Builds the {@code index.html} redirect target for a request. A manually set
	 * {@code Location} is not base-path-prepended by the framework, so the request's
	 * context path (WebFlux base path or reverse-proxy prefix) is prepended here.
	 * @param request the incoming request
	 * @param indexPath the inspector-relative index path
	 * @return the absolute-path redirect target
	 */
	private static URI indexRedirect(final ServerRequest request, final String indexPath) {
		final String contextPath = request.requestPath().contextPath().value();
		return URI.create(("/".equals(contextPath) ? "" : contextPath) + indexPath);
	}

	/**
	 * Upstream-compatible proxy routes. Lives on a sibling prefix ({@code path + "-api"})
	 * to keep the v1 inspector contract intact.
	 * @param proxy the proxy handler bean
	 * @param properties the inspector configuration properties
	 * @return the router function for proxy endpoints
	 */
	@Bean
	public RouterFunction<ServerResponse> inspectorProxyRouter(final ProxyHandler proxy,
			final McpInspectorProperties properties) {
		final String proxyBase = properties.getProxyPath();
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
