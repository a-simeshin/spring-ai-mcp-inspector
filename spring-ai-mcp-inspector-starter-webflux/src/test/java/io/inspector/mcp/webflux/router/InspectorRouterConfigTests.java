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

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.webflux.proxy.ProxyHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InspectorRouterConfig}. Asserts that the functional routing
 * predicates match (or reject) request paths derived from the configured
 * {@link McpInspectorProperties#getPath()} /
 * {@link McpInspectorProperties#getProxyPath()}.
 */
@Epic("MCP Inspector WebFlux")
@Feature("InspectorRouterConfig functional routing")
class InspectorRouterConfigTests {

	private static final HandlerStrategies STRATEGIES = HandlerStrategies.withDefaults();

	private InspectorRouterConfig config;

	private InspectorHandler handler;

	private ProxyHandler proxy;

	@BeforeEach
	void setUp() {
		this.config = new InspectorRouterConfig();
		this.handler = mock(InspectorHandler.class);
		this.proxy = mock(ProxyHandler.class);
		given(this.handler.config(any())).willReturn(ServerResponse.ok().build());
		given(this.handler.index(any())).willReturn(ServerResponse.ok().build());
		given(this.handler.connect(any())).willReturn(ServerResponse.ok().build());
		given(this.proxy.health(any())).willReturn(ServerResponse.ok().build());
		given(this.proxy.config(any())).willReturn(ServerResponse.ok().build());
	}

	private static McpInspectorProperties properties(final String path) {
		final McpInspectorProperties properties = new McpInspectorProperties();
		properties.setPath(path);
		return properties;
	}

	private static ServerRequest request(final HttpMethod method, final String uri) {
		final MockServerHttpRequest mock = MockServerHttpRequest.method(method, uri).build();
		final MockServerWebExchange exchange = MockServerWebExchange.from(mock);
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	private static ServerRequest requestWithContextPath(final HttpMethod method, final String uri,
			final String contextPath) {
		final MockServerHttpRequest mock = MockServerHttpRequest.method(method, uri).contextPath(contextPath).build();
		final MockServerWebExchange exchange = MockServerWebExchange.from(mock);
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	private static boolean matches(final RouterFunction<ServerResponse> router, final ServerRequest request) {
		return router.route(request).blockOptional().isPresent();
	}

	@Nested
	@DisplayName("inspectorRouter()")
	class InspectorRouter {

		@Test
		@Story("Routing predicates")
		@Severity(SeverityLevel.CRITICAL)
		@Description("inspectorRouter routes GET ${path}/api/config to the config handler at the default prefix")
		void configRoute_matchesConfiguredPath() {
			// given
			final RouterFunction<ServerResponse> router = InspectorRouterConfigTests.this.config
				.inspectorRouter(InspectorRouterConfigTests.this.handler, properties("/mcp-inspector"));

			// when
			final boolean matched = matches(router, request(HttpMethod.GET, "/mcp-inspector/api/config"));

			// then
			assertThat(matched).isTrue();
		}

		@Test
		@Story("Routing predicates")
		@Severity(SeverityLevel.NORMAL)
		@Description("inspectorRouter re-mounts every route under a custom path prefix")
		void apiRoutes_matchUnderCustomPrefix() {
			// given
			final RouterFunction<ServerResponse> router = InspectorRouterConfigTests.this.config
				.inspectorRouter(InspectorRouterConfigTests.this.handler, properties("/custom"));

			// when & then
			assertThat(matches(router, request(HttpMethod.GET, "/custom/api/config"))).isTrue();
			assertThat(matches(router, request(HttpMethod.POST, "/custom/api/connect"))).isTrue();
			assertThat(matches(router, request(HttpMethod.GET, "/custom/index.html"))).isTrue();
			assertThat(matches(router, request(HttpMethod.GET, "/custom/config"))).isTrue();
		}

		@Test
		@Story("Routing predicates")
		@Severity(SeverityLevel.NORMAL)
		@Description("inspectorRouter does NOT match the default prefix once a custom path is configured")
		void defaultPrefix_doesNotMatchAfterRemount() {
			// given
			final RouterFunction<ServerResponse> router = InspectorRouterConfigTests.this.config
				.inspectorRouter(InspectorRouterConfigTests.this.handler, properties("/custom"));

			// when
			final boolean matched = matches(router, request(HttpMethod.GET, "/mcp-inspector/api/config"));

			// then
			assertThat(matched).isFalse();
		}

		@Test
		@Story("Routing predicates")
		@Severity(SeverityLevel.NORMAL)
		@Description("inspectorRouter keeps the top-level /oauth/callback routes regardless of the configured prefix")
		void oauthCallbackRoutes_matchAtTopLevel() {
			// given
			final RouterFunction<ServerResponse> router = InspectorRouterConfigTests.this.config
				.inspectorRouter(InspectorRouterConfigTests.this.handler, properties("/custom"));

			// when & then
			assertThat(matches(router, request(HttpMethod.GET, "/oauth/callback"))).isTrue();
			assertThat(matches(router, request(HttpMethod.GET, "/oauth/callback/debug"))).isTrue();
		}

		@Test
		@Story("Base path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The root redirect prefixes Location with the request's base path — the framework does not do it for a manual header")
		void rootRedirect_underBasePath_prefixesLocation() {
			// given
			final RouterFunction<ServerResponse> router = InspectorRouterConfigTests.this.config
				.inspectorRouter(InspectorRouterConfigTests.this.handler, properties("/mcp-inspector"));
			final ServerRequest request = requestWithContextPath(HttpMethod.GET, "/app/mcp-inspector", "/app");

			// when
			final ServerResponse response = router.route(request).flatMap((handler) -> handler.handle(request)).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.headers().getLocation()).hasToString("/app/mcp-inspector/index.html");
		}

		@Test
		@Story("Base path")
		@Severity(SeverityLevel.NORMAL)
		@Description("The root redirect stays unprefixed for a root-mounted application")
		void rootRedirect_withoutBasePath_keepsPlainLocation() {
			// given
			final RouterFunction<ServerResponse> router = InspectorRouterConfigTests.this.config
				.inspectorRouter(InspectorRouterConfigTests.this.handler, properties("/mcp-inspector"));
			final ServerRequest request = request(HttpMethod.GET, "/mcp-inspector/");

			// when
			final ServerResponse response = router.route(request).flatMap((handler) -> handler.handle(request)).block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.headers().getLocation()).hasToString("/mcp-inspector/index.html");
		}

		@Test
		@Story("Routing predicates")
		@Severity(SeverityLevel.MINOR)
		@Description("inspectorRouter does not match a method/path that is not registered")
		void unregisteredPath_doesNotMatch() {
			// given
			final RouterFunction<ServerResponse> router = InspectorRouterConfigTests.this.config
				.inspectorRouter(InspectorRouterConfigTests.this.handler, properties("/mcp-inspector"));

			// when
			final boolean matched = matches(router, request(HttpMethod.POST, "/mcp-inspector/api/config"));

			// then — config is a GET route only, POST must not match
			assertThat(matched).isFalse();
		}

	}

	@Nested
	@DisplayName("inspectorProxyRouter()")
	class ProxyRouter {

		@Test
		@Story("Proxy routing predicates")
		@Severity(SeverityLevel.CRITICAL)
		@Description("inspectorProxyRouter mounts the proxy routes on the derived ${path}-api prefix")
		void proxyRoutes_matchDerivedProxyPrefix() {
			// given
			final RouterFunction<ServerResponse> router = InspectorRouterConfigTests.this.config
				.inspectorProxyRouter(InspectorRouterConfigTests.this.proxy, properties("/mcp-inspector"));

			// when & then
			assertThat(matches(router, request(HttpMethod.GET, "/mcp-inspector-api/health"))).isTrue();
			assertThat(matches(router, request(HttpMethod.GET, "/mcp-inspector-api/config"))).isTrue();
			assertThat(matches(router, request(HttpMethod.POST, "/mcp-inspector-api/mcp"))).isTrue();
			assertThat(matches(router, request(HttpMethod.DELETE, "/mcp-inspector-api/mcp"))).isTrue();
		}

		@Test
		@Story("Proxy routing predicates")
		@Severity(SeverityLevel.MINOR)
		@Description("inspectorProxyRouter does not match a path outside the proxy prefix")
		void nonProxyPath_doesNotMatch() {
			// given
			final RouterFunction<ServerResponse> router = InspectorRouterConfigTests.this.config
				.inspectorProxyRouter(InspectorRouterConfigTests.this.proxy, properties("/mcp-inspector"));

			// when
			final boolean matched = matches(router, request(HttpMethod.GET, "/unrelated/health"));

			// then
			assertThat(matched).isFalse();
		}

	}

}
