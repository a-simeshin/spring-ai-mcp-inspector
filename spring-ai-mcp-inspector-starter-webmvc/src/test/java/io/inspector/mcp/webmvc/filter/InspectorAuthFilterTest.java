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
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link InspectorAuthFilter}. */
@Epic("WebMvc Inspector")
@Feature("InspectorAuthFilter")
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

	@Nested
	@DisplayName("doFilter()")
	class DoFilter {

		@Test
		@Story("Allow")
		@Severity(SeverityLevel.CRITICAL)
		@Description("passes the chain when a valid token is presented in the auth header")
		void passesValidToken() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			request.addHeader(InspectorAuthFilter.AUTH_HEADER, token);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
			assertThat(chain.getRequest()).isNotNull();
		}

		@Test
		@Story("Allow")
		@Severity(SeverityLevel.NORMAL)
		@Description("accepts the token via the query-param fallback")
		void passesValidTokenInQueryParam() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/events");
			request.setParameter(InspectorAuthFilter.AUTH_QUERY_PARAM, token);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
			assertThat(chain.getRequest()).isNotNull();
		}

		@Test
		@Story("Deny")
		@Severity(SeverityLevel.CRITICAL)
		@Description("returns 401 and does not call the chain when the token is wrong")
		void rejectsInvalidToken() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			request.addHeader(InspectorAuthFilter.AUTH_HEADER, "wrong-token");
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
			assertThat(chain.getRequest()).isNull();
		}

		@Test
		@Story("Deny")
		@Severity(SeverityLevel.NORMAL)
		@Description("returns 401 when no token is presented")
		void rejectsMissingToken() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
			assertThat(chain.getRequest()).isNull();
		}

		@Test
		@Story("Bypass")
		@Severity(SeverityLevel.NORMAL)
		@Description("forwards every request without checking the token when auth is disabled")
		void skipsAuthWhenDisabled() throws Exception {
			// given
			properties.setAuthEnabled(false);
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
			assertThat(chain.getRequest()).isNotNull();
		}

	}

}
