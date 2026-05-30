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

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link InspectorAuthFilter}. */
class InspectorAuthFilterTest {

	private McpInspectorProperties properties;

	private InspectorAuthTokenProvider tokenProvider;

	private InspectorAuthFilter filter;

	private String token;

	@BeforeEach
	void setUp() {
		properties = new McpInspectorProperties();
		properties.setAuthToken("fixed-token-for-tests");
		tokenProvider = new InspectorAuthTokenProvider(properties);
		token = tokenProvider.token();
		filter = new InspectorAuthFilter(properties, tokenProvider);
	}

	@Test
	void passesValidToken() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
		request.addHeader(InspectorAuthFilter.AUTH_HEADER, token);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void passesValidTokenInQueryParam() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/events");
		request.setParameter(InspectorAuthFilter.AUTH_QUERY_PARAM, token);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void rejectsInvalidToken() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
		request.addHeader(InspectorAuthFilter.AUTH_HEADER, "wrong-token");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void rejectsMissingToken() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void skipsAuthWhenDisabled() throws Exception {
		properties.setAuthEnabled(false);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
		assertThat(chain.getRequest()).isNotNull();
	}

}
