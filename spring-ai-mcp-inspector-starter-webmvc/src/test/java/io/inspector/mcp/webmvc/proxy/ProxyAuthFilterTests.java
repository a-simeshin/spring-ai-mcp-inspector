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

package io.inspector.mcp.webmvc.proxy;

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

/**
 * Unit tests for {@link ProxyAuthFilter}. Drives the filter through
 * {@link MockHttpServletRequest}/{@link MockFilterChain} and asserts the allow / deny
 * branches (header, Bearer prefix, query param, health bypass, auth-disabled).
 */
@Epic("WebMvc Inspector")
@Feature("ProxyAuthFilter")
class ProxyAuthFilterTests {

	private McpInspectorProperties properties;

	private ProxyAuthFilter filter;

	private String token;

	@BeforeEach
	void setUp() {
		this.properties = new McpInspectorProperties();
		this.properties.setAuthToken("proxy-token");
		final InspectorAuthTokenProvider tokenProvider = new InspectorAuthTokenProvider(this.properties);
		this.token = tokenProvider.token();
		this.filter = new ProxyAuthFilter(this.properties, tokenProvider);
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
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader(ProxyConstants.AUTH_HEADER, ProxyAuthFilterTests.this.token);
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			ProxyAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader(ProxyConstants.AUTH_HEADER, "Bearer " + ProxyAuthFilterTests.this.token);
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			ProxyAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector-api/sse");
			request.setParameter(ProxyConstants.AUTH_QUERY_PARAM, ProxyAuthFilterTests.this.token);
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			ProxyAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			request.addHeader(ProxyConstants.AUTH_HEADER, "wrong-token");
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			ProxyAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			ProxyAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector-api/health");
			request.setRequestURI("/mcp-inspector-api/health");
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			ProxyAuthFilterTests.this.filter.doFilter(request, response, chain);

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
			ProxyAuthFilterTests.this.properties.setAuthEnabled(false);
			final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp-inspector-api/mcp");
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			ProxyAuthFilterTests.this.filter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
			assertThat(chain.getRequest()).isNotNull();
		}

	}

}
