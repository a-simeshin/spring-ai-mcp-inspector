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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.auth.OwnerTokenCodec;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.webmvc.auth.InspectorSessionAttributes;
import io.inspector.mcp.webmvc.auth.ServletSessionOwnerResolver;

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

	@Nested
	@DisplayName("session-owner cookie (D8)")
	class SessionOwner {

		private InspectorAuthFilter ownerFilter;

		private OwnerTokenCodec codec;

		@BeforeEach
		void setUp() {
			InspectorAuthFilterTests.this.properties.setAuthToken("fixed-token-for-tests");
			this.codec = new OwnerTokenCodec();
			InspectorAuthFilterTests.this.filter = new InspectorAuthFilter(InspectorAuthFilterTests.this.properties,
					InspectorAuthFilterTests.this.tokenProvider, new ServletSessionOwnerResolver(this.codec));
			this.ownerFilter = InspectorAuthFilterTests.this.filter;
		}

		private MockHttpServletRequest requestWithCookie(final String value) {
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			request.addHeader(InspectorAuthFilter.AUTH_HEADER, InspectorAuthFilterTests.this.token);
			if (value != null) {
				request.setCookies(new Cookie(OwnerTokenCodec.COOKIE_NAME, value));
			}
			return request;
		}

		@Test
		@Story("Owner mint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a first request with a valid auth token and NO cookie passes and mints a signed Set-Cookie (never 401)")
		void firstRequestWithoutCookie_mintsOwnerCookie() throws Exception {
			// given
			final MockHttpServletRequest request = requestWithCookie(null);
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			this.ownerFilter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
			assertThat(chain.getRequest()).isNotNull();
			final String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
			assertThat(setCookie).contains(OwnerTokenCodec.COOKIE_NAME + "=")
				.contains("HttpOnly")
				.contains("SameSite=Lax");
			final Object attribute = request.getAttribute(InspectorSessionAttributes.OWNER_ID);
			assertThat(attribute).isInstanceOf(String.class);
			final String token = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
			assertThat(this.codec.validate(token)).contains((String) attribute);
		}

		@Test
		@Story("Owner parse")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a valid signed cookie is reused — same owner, no re-mint")
		void validCookie_isReusedWithoutReMint() throws Exception {
			// given
			final String token = this.codec.mint("owner-stable", java.time.Instant.now());
			final MockHttpServletRequest request = requestWithCookie(token);
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			this.ownerFilter.doFilter(request, response, chain);

			// then
			assertThat(request.getAttribute(InspectorSessionAttributes.OWNER_ID)).isEqualTo("owner-stable");
			assertThat(response.getHeaderNames()).doesNotContain(HttpHeaders.SET_COOKIE);
		}

		@Test
		@Story("Owner re-mint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a forged cookie is treated as absent — a NEW owner is minted (old scope NOT inherited)")
		void forgedCookie_reMintsNewOwner() throws Exception {
			// given — structurally valid but unsigned
			final MockHttpServletRequest request = requestWithCookie("owner-victim.1750000000.deadbeef");
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			this.ownerFilter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
			final Object attribute = request.getAttribute(InspectorSessionAttributes.OWNER_ID);
			assertThat(attribute).isNotEqualTo("owner-victim");
			assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNotNull();
		}

		@Test
		@Story("Owner re-mint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("an expired signed cookie is re-minted to a new owner")
		void expiredCookie_reMintsNewOwner() throws Exception {
			// given
			final String expired = this.codec.mint("owner-old", java.time.Instant.now().minusSeconds(25 * 3600));
			final MockHttpServletRequest request = requestWithCookie(expired);
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			this.ownerFilter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
			assertThat(request.getAttribute(InspectorSessionAttributes.OWNER_ID)).isNotEqualTo("owner-old");
			assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNotNull();
		}

		@Test
		@Story("Guard first")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a request that FAILS the X-MCP-Inspector-Auth guard gets 401 and NO cookie is minted")
		void failedGuard_doesNotMintCookie() throws Exception {
			// given
			final MockHttpServletRequest request = requestWithCookie(null);
			request.removeHeader(InspectorAuthFilter.AUTH_HEADER);
			request.addHeader(InspectorAuthFilter.AUTH_HEADER, "wrong");
			final MockHttpServletResponse response = new MockHttpServletResponse();
			final MockFilterChain chain = new MockFilterChain();

			// when
			this.ownerFilter.doFilter(request, response, chain);

			// then
			assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
			assertThat(chain.getRequest()).isNull();
			assertThat(response.getHeaderNames()).doesNotContain(HttpHeaders.SET_COOKIE);
			assertThat(request.getAttribute(InspectorSessionAttributes.OWNER_ID)).isNull();
		}

	}

}
