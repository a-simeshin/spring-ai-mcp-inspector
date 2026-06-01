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

package io.inspector.mcp.webmvc.filter;

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

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link InspectorAuthFilter}. */
@Epic("WebMvc Inspector")
@Feature("InspectorAuthFilter")
class InspectorAuthFilterTests {

	private McpInspectorProperties properties;

	private InspectorAuthTokenProvider tokenProvider;

	private InspectorAuthFilter filter;

	private String token;

	@BeforeEach
	void setUp() {
		this.properties = new McpInspectorProperties();
		this.properties.setAuthToken("fixed-token-for-tests");
		this.tokenProvider = new InspectorAuthTokenProvider(this.properties);
		this.token = this.tokenProvider.token();
		this.filter = new InspectorAuthFilter(this.properties, this.tokenProvider);
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
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			request.addHeader(InspectorAuthFilter.AUTH_HEADER, InspectorAuthFilterTests.this.token);
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			InspectorAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/events");
			request.setParameter(InspectorAuthFilter.AUTH_QUERY_PARAM, InspectorAuthFilterTests.this.token);
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			InspectorAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			request.addHeader(InspectorAuthFilter.AUTH_HEADER, "wrong-token");
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			InspectorAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			InspectorAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			InspectorAuthFilterTests.this.properties.setAuthEnabled(false);
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			InspectorAuthFilterTests.this.filter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
			assertThat(chain.getRequest()).isNotNull();
		}

	}

}
