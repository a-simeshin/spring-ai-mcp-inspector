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

package io.inspector.mcp.webmvc.auth;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.inspector.mcp.core.auth.OwnerTokenCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ServletSessionOwnerResolver} (D8) — cookie read, codec delegation
 * and re-mint.
 */
@Epic("WebMvc Inspector")
@Feature("ServletSessionOwnerResolver")
class ServletSessionOwnerResolverTests {

	private final OwnerTokenCodec codec = new OwnerTokenCodec();

	private final ServletSessionOwnerResolver resolver = new ServletSessionOwnerResolver(this.codec);

	private MockHttpServletRequest requestWithCookie(final String value) {
		final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
		if (value != null) {
			request.setCookies(new Cookie(OwnerTokenCodec.COOKIE_NAME, value));
		}
		return request;
	}

	@Nested
	@DisplayName("resolve()")
	class Resolve {

		@Test
		@Story("Valid cookie")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() delegates to the codec and returns the embedded owner id without re-minting")
		void resolve_validCookie_returnsOwnerIdAndNoSetCookie() {
			// given
			final String token = ServletSessionOwnerResolverTests.this.codec.mint("owner-valid",
					java.time.Instant.now());
			final MockHttpServletRequest request = requestWithCookie(token);
			final MockHttpServletResponse response = new MockHttpServletResponse();

			// when
			final String ownerId = ServletSessionOwnerResolverTests.this.resolver.resolve(request, response);

			// then
			assertThat(ownerId).isEqualTo("owner-valid");
			assertThat(response.getHeaderNames()).doesNotContain("Set-Cookie");
		}

		@Test
		@Story("Absent cookie")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() mints a fresh signed token for an absent cookie and adds HttpOnly/Lax/Path=/ Set-Cookie")
		void resolve_absentCookie_mintsFreshOwner() {
			// given
			final MockHttpServletRequest request = requestWithCookie(null);
			final MockHttpServletResponse response = new MockHttpServletResponse();

			// when
			final String ownerId = ServletSessionOwnerResolverTests.this.resolver.resolve(request, response);

			// then
			assertThat(ownerId).isNotBlank();
			final String setCookie = response.getHeader("Set-Cookie");
			assertThat(setCookie).contains(OwnerTokenCodec.COOKIE_NAME + "=");
			assertThat(setCookie).contains("HttpOnly");
			assertThat(setCookie).contains("SameSite=Lax");
			assertThat(setCookie).contains("Path=/");
			// the minted cookie validates back to the same owner
			final String token = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
			assertThat(ServletSessionOwnerResolverTests.this.codec.validate(token)).contains(ownerId);
		}

		@Test
		@Story("Forged cookie")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() re-mints a NEW owner for a forged (bad-HMAC) cookie — the old scope is NOT inherited")
		void resolve_forgedCookie_reMintsNewOwner() {
			// given — structurally valid shape, unsigned garbage
			final MockHttpServletRequest request = requestWithCookie("owner-victim.1750000000.deadbeef");
			final MockHttpServletResponse response = new MockHttpServletResponse();

			// when
			final String ownerId = ServletSessionOwnerResolverTests.this.resolver.resolve(request, response);

			// then
			assertThat(ownerId).isNotEqualTo("owner-victim");
			assertThat(response.getHeader("Set-Cookie")).contains(OwnerTokenCodec.COOKIE_NAME + "=");
		}

		@Test
		@Story("Expired cookie")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() re-mints a NEW owner for an expired signed cookie")
		void resolve_expiredCookie_reMintsNewOwner() {
			// given — minted 25h ago (TTL is 24h)
			final String expired = ServletSessionOwnerResolverTests.this.codec.mint("owner-old",
					java.time.Instant.now().minusSeconds(25 * 3600));
			final MockHttpServletRequest request = requestWithCookie(expired);
			final MockHttpServletResponse response = new MockHttpServletResponse();

			// when
			final String ownerId = ServletSessionOwnerResolverTests.this.resolver.resolve(request, response);

			// then
			assertThat(ownerId).isNotEqualTo("owner-old");
			assertThat(response.getHeader("Set-Cookie")).isNotNull();
		}

		@Test
		@Story("Null safety")
		@Severity(SeverityLevel.NORMAL)
		@Description("resolve() still mints an owner when the request has no cookies at all")
		void resolve_noCookiesArray_mintsFreshOwner() {
			// given
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector/api/config");
			final MockHttpServletResponse response = new MockHttpServletResponse();

			// when
			final String ownerId = ServletSessionOwnerResolverTests.this.resolver.resolve(request, response);

			// then
			assertThat(ownerId).isNotBlank();
			assertThat(response.getHeader("Set-Cookie")).isNotNull();
		}

	}

}
