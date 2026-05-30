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
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link InspectorAuthWebFilter}. */
@Epic("MCP Inspector WebFlux")
@Feature("InspectorAuthWebFilter reactive token guard")
class InspectorAuthWebFilterTest {

	private McpInspectorProperties properties;

	private InspectorAuthTokenProvider tokenProvider;

	private InspectorAuthWebFilter filter;

	private String token;

	@BeforeEach
	void setUp() {
		properties = new McpInspectorProperties();
		properties.setAuthToken("fixed-test-token");
		tokenProvider = new InspectorAuthTokenProvider(properties);
		token = tokenProvider.token();
		filter = new InspectorAuthWebFilter(properties, tokenProvider);
	}

	@Nested
	@DisplayName("filter()")
	class Filter {

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.CRITICAL)
		@Description("filter() passes a request carrying the valid token in the auth header")
		void filter_withValidHeaderToken_passesThrough() {
			// given
			MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
				.header(InspectorAuthWebFilter.HEADER, token)
				.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
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
		@Description("filter() accepts the valid token supplied as the auth query parameter")
		void filter_withValidQueryParamToken_passesThrough() {
			// given
			MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/events?auth=" + token)
				.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Short-circuit branches")
		@Severity(SeverityLevel.CRITICAL)
		@Description("filter() rejects an invalid token with 401 and does not invoke the chain")
		void filter_withInvalidToken_returns401AndShortCircuits() {
			// given
			MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
				.header(InspectorAuthWebFilter.HEADER, "wrong")
				.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
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
		@Description("filter() rejects a request with no token at all with 401 and short-circuits")
		void filter_withMissingToken_returns401AndShortCircuits() {
			// given
			MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
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
		@Description("filter() skips non-/api/ paths (e.g. the SPA index) without inspecting any token")
		void filter_withNonApiPath_passesThrough() {
			// given
			MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/index.html").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() passes through unconditionally when auth is disabled")
		void filter_whenAuthDisabled_passesThrough() {
			// given
			properties.setAuthEnabled(false);
			MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() passes through unconditionally when constructed with no properties bean (properties == null branch)")
		void filter_whenPropertiesNull_passesThrough() {
			// given — defensive construction with a null properties bean
			InspectorAuthWebFilter nullPropsFilter = new InspectorAuthWebFilter(null, tokenProvider);
			MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
				.header(InspectorAuthWebFilter.HEADER, "irrelevant")
				.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
			ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(nullPropsFilter.filter(exchange, chain)).verifyComplete();

			// then — null properties short-circuits the auth check entirely
			assertThat(chain.invoked).isTrue();
			assertThat(exchange.getResponse().getStatusCode()).isNull();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() ignores a blank header and falls back to the query parameter token (provided isBlank branch)")
		void filter_withBlankHeaderButValidQueryToken_passesThrough() {
			// given — blank header forces the fallback to the ?auth= query parameter
			MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/events?auth=" + token)
				.header(InspectorAuthWebFilter.HEADER, "   ")
				.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
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
			// given — a provider that resolves a null token, so the compare's b == null
			// branch is exercised against a present, non-null presented token
			InspectorAuthTokenProvider nullTokenProvider = org.mockito.Mockito.mock(InspectorAuthTokenProvider.class);
			org.mockito.Mockito.when(nullTokenProvider.token()).thenReturn(null);
			InspectorAuthWebFilter nullTokenFilter = new InspectorAuthWebFilter(properties, nullTokenProvider);
			MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
				.header(InspectorAuthWebFilter.HEADER, "some-presented-token")
				.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);
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
		public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange) {
			invoked = true;
			return Mono.empty();
		}

	}

}
