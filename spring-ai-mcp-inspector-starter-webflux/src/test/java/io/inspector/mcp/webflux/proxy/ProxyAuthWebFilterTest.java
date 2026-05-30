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

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;
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
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ProxyAuthWebFilter}. */
@Epic("MCP Inspector WebFlux")
@Feature("ProxyAuthWebFilter reactive bearer guard")
class ProxyAuthWebFilterTest {

	private McpInspectorProperties properties;

	private InspectorAuthTokenProvider tokenProvider;

	private ProxyAuthWebFilter filter;

	private String token;

	@BeforeEach
	void setUp() {
		properties = new McpInspectorProperties();
		properties.setAuthToken("fixed-proxy-token");
		tokenProvider = new InspectorAuthTokenProvider(properties);
		token = tokenProvider.token();
		filter = new ProxyAuthWebFilter(properties, tokenProvider);
	}

	@Nested
	@DisplayName("getOrder()")
	class Order {

		@Test
		@Story("Ordering")
		@Severity(SeverityLevel.MINOR)
		@Description("getOrder() returns the high-precedence order so the proxy guard runs early")
		void getOrder_default_returnsHighPrecedenceOrder() {
			// when & then
			assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 110);
		}

	}

	@Nested
	@DisplayName("filter()")
	class Filter {

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() passes through when auth is disabled, without inspecting any token")
		void filter_whenAuthDisabled_passesThrough() {
			// given
			properties.setAuthEnabled(false);
			MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.post("/mcp-inspector-api/mcp").build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() ignores requests outside the proxy prefix and lets them through")
		void filter_whenPathOutsideProxyPrefix_passesThrough() {
			// given
			MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.get("/some/other/path").build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() allow-lists the unauthenticated /health endpoint")
		void filter_whenHealthEndpoint_passesThroughWithoutToken() {
			// given
			MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.get("/mcp-inspector-api/health").build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.CRITICAL)
		@Description("filter() passes a request carrying the valid token in the X-MCP-Proxy-Auth header (Bearer prefix)")
		void filter_withValidBearerHeader_passesThrough() {
			// given
			MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
					.header(ProxyConstants.AUTH_HEADER, "Bearer " + token)
					.build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
			assertThat(exchange.getResponse().getStatusCode()).isNull();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() accepts the valid token supplied as the MCP_PROXY_AUTH_TOKEN query parameter")
		void filter_withValidQueryToken_passesThrough() {
			// given
			MockServerWebExchange exchange = MockServerWebExchange.from(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?" + ProxyConstants.AUTH_QUERY_PARAM + "=" + token)
						.build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Short-circuit branches")
		@Severity(SeverityLevel.CRITICAL)
		@Description("filter() rejects a request with a wrong token: sets 401 and does not invoke the chain")
		void filter_withWrongToken_returns401AndShortCircuits() {
			// given
			MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
					.header(ProxyConstants.AUTH_HEADER, "totally-wrong")
					.build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isFalse();
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@Story("Short-circuit branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() rejects a request with no token at all: sets 401 and short-circuits")
		void filter_withMissingToken_returns401AndShortCircuits() {
			// given
			MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.post("/mcp-inspector-api/mcp").build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isFalse();
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() passes through unconditionally when constructed with no properties bean (properties == null branch)")
		void filter_whenPropertiesNull_passesThrough() {
			// given — defensive construction with a null properties bean
			ProxyAuthWebFilter nullPropsFilter = new ProxyAuthWebFilter(null, tokenProvider);
			MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.post("/mcp-inspector-api/mcp").build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(nullPropsFilter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
			assertThat(exchange.getResponse().getStatusCode()).isNull();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() ignores a blank header and falls back to the query parameter token (presented isBlank branch)")
		void filter_withBlankHeaderButValidQueryToken_passesThrough() {
			// given — a blank header value forces the fall-back to the query parameter
			MockServerWebExchange exchange = MockServerWebExchange.from(
					MockServerHttpRequest.get("/mcp-inspector-api/sse?" + ProxyConstants.AUTH_QUERY_PARAM + "=" + token)
						.header(ProxyConstants.AUTH_HEADER, "   ")
						.build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
			assertThat(exchange.getResponse().getStatusCode()).isNull();
		}

		@Test
		@Story("Short-circuit branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() rejects with 401 when the expected token is null (constantTimeEquals b == null branch)")
		void filter_whenExpectedTokenNull_returns401() {
			// given — a provider that resolves a null token
			InspectorAuthTokenProvider nullTokenProvider = org.mockito.Mockito.mock(InspectorAuthTokenProvider.class);
			org.mockito.Mockito.when(nullTokenProvider.token()).thenReturn(null);
			ProxyAuthWebFilter nullTokenFilter = new ProxyAuthWebFilter(properties, nullTokenProvider);
			MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.post("/mcp-inspector-api/mcp")
					.header(ProxyConstants.AUTH_HEADER, "Bearer presented")
					.build());
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(nullTokenFilter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isFalse();
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

	}

	private static final class ChainSpy implements WebFilterChain {

		boolean invoked;

		@Override
		public Mono<Void> filter(ServerWebExchange exchange) {
			invoked = true;
			return Mono.empty();
		}

	}

}
