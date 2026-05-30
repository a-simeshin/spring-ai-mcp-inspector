/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.proxy;

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

/**
 * Unit tests for {@link ProxyAuthFilter}. Drives the filter through
 * {@link MockHttpServletRequest}/{@link MockFilterChain} and asserts the allow / deny
 * branches (header, Bearer prefix, query param, health bypass, auth-disabled).
 */
@Epic("WebMvc Inspector")
@Feature("ProxyAuthFilter")
class ProxyAuthFilterTest {

	private McpInspectorProperties properties;

	private ProxyAuthFilter filter;

	private String token;

	@BeforeEach
	void setUp() {
		properties = new McpInspectorProperties();
		properties.setAuthToken("proxy-token");
		InspectorAuthTokenProvider tokenProvider = new InspectorAuthTokenProvider(properties);
		token = tokenProvider.token();
		filter = new ProxyAuthFilter(properties, tokenProvider);
	}

	@Nested
	@DisplayName("doFilterInternal()")
	class DoFilterInternal {

		@Test
		@Story("Allow")
		@Severity(SeverityLevel.CRITICAL)
		@Description("passes the chain when the raw token is presented in the auth header")
		void doFilter_withValidRawTokenHeader_passesChain() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader(ProxyConstants.AUTH_HEADER, token);
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
		@Description("strips the Bearer prefix and passes the chain for a valid bearer token")
		void doFilter_withBearerPrefix_passesChain() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader(ProxyConstants.AUTH_HEADER, "Bearer " + token);
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
		@Description("accepts the token via the query-param fallback (EventSource cannot set headers)")
		void doFilter_withValidQueryParam_passesChain() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector-api/sse");
			request.setParameter(ProxyConstants.AUTH_QUERY_PARAM, token);
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
		void doFilter_withInvalidToken_returns401() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader(ProxyConstants.AUTH_HEADER, "wrong-token");
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
		@Description("returns 401 when no token is presented at all")
		void doFilter_withMissingToken_returns401() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
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
		@Description("the /health probe is allowed through without any token")
		void doFilter_forHealthPath_passesWithoutToken() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector-api/health");
			request.setRequestURI("/mcp-inspector-api/health");
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
			assertThat(chain.getRequest()).isNotNull();
		}

		@Test
		@Story("Bypass")
		@Severity(SeverityLevel.NORMAL)
		@Description("when auth is disabled every request is forwarded without checking the token")
		void doFilter_whenAuthDisabled_passesChain() throws Exception {
			// given
			properties.setAuthEnabled(false);
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
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
