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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link InspectorAuthWebFilter}. */
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

	@Test
	void passesValidToken() {
		MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
			.header(InspectorAuthWebFilter.HEADER, token)
			.build();
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		ChainSpy chain = new ChainSpy();

		StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

		assertThat(chain.invoked).isTrue();
		assertThat(exchange.getResponse().getStatusCode()).isNull();
	}

	@Test
	void passesValidTokenInQueryParam() {
		MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/events?auth=" + token).build();
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		ChainSpy chain = new ChainSpy();

		StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

		assertThat(chain.invoked).isTrue();
	}

	@Test
	void rejectsInvalidToken() {
		MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
			.header(InspectorAuthWebFilter.HEADER, "wrong")
			.build();
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		ChainSpy chain = new ChainSpy();

		StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

		assertThat(chain.invoked).isFalse();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void rejectsMissingToken() {
		MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config").build();
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		ChainSpy chain = new ChainSpy();

		StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

		assertThat(chain.invoked).isFalse();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void skipsNonApiPaths() {
		MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/index.html").build();
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		ChainSpy chain = new ChainSpy();

		StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

		assertThat(chain.invoked).isTrue();
	}

	@Test
	void skipsAuthWhenDisabled() {
		properties.setAuthEnabled(false);
		MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config").build();
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		ChainSpy chain = new ChainSpy();

		StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

		assertThat(chain.invoked).isTrue();
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
