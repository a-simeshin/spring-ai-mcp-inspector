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

package io.inspector.mcp.webflux.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import io.inspector.mcp.core.auth.OwnerTokenCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReactiveSessionOwnerResolver} (D8) — cookie read, codec
 * delegation and re-mint.
 */
@Epic("MCP Inspector WebFlux")
@Feature("ReactiveSessionOwnerResolver")
class ReactiveSessionOwnerResolverTests {

	private final OwnerTokenCodec codec = new OwnerTokenCodec();

	private final ReactiveSessionOwnerResolver resolver = new ReactiveSessionOwnerResolver(this.codec);

	@Nested
	@DisplayName("resolve()")
	class Resolve {

		@Test
		@Story("Valid cookie")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() delegates to the codec and returns the embedded owner id without re-minting")
		void resolve_validCookie_returnsOwnerIdAndNoSetCookie() {
			// given
			final String token = ReactiveSessionOwnerResolverTests.this.codec.mint("owner-valid");
			final MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.get("/mcp-inspector/api/config")
					.cookie(new HttpCookie(OwnerTokenCodec.COOKIE_NAME, token)));

			// when
			final String ownerId = ReactiveSessionOwnerResolverTests.this.resolver.resolve(exchange);

			// then
			assertThat(ownerId).isEqualTo("owner-valid");
			assertThat(exchange.getResponse().getCookies()).isEmpty();
		}

		@Test
		@Story("Absent cookie")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() mints a fresh signed token for an absent cookie and adds a HttpOnly/Lax/Path=/ cookie")
		void resolve_absentCookie_mintsFreshOwner() {
			// given
			final MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.get("/mcp-inspector/api/config"));

			// when
			final String ownerId = ReactiveSessionOwnerResolverTests.this.resolver.resolve(exchange);

			// then
			assertThat(ownerId).isNotBlank();
			final ResponseCookie cookie = exchange.getResponse().getCookies().getFirst(OwnerTokenCodec.COOKIE_NAME);
			assertThat(cookie).isNotNull();
			assertThat(cookie.isHttpOnly()).isTrue();
			assertThat(cookie.getSameSite()).isEqualTo("Lax");
			assertThat(cookie.getPath()).isEqualTo("/");
			assertThat(ReactiveSessionOwnerResolverTests.this.codec.validate(cookie.getValue())).contains(ownerId);
		}

		@Test
		@Story("Forged cookie")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() re-mints a NEW owner for a forged (bad-HMAC) cookie — old scope NOT inherited")
		void resolve_forgedCookie_reMintsNewOwner() {
			// given — structurally valid shape, unsigned garbage
			final MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.get("/mcp-inspector/api/config")
					.cookie(new HttpCookie(OwnerTokenCodec.COOKIE_NAME, "owner-victim.1750000000.deadbeef")));

			// when
			final String ownerId = ReactiveSessionOwnerResolverTests.this.resolver.resolve(exchange);

			// then
			assertThat(ownerId).isNotEqualTo("owner-victim");
			assertThat(exchange.getResponse().getCookies().getFirst(OwnerTokenCodec.COOKIE_NAME)).isNotNull();
		}

		@Test
		@Story("Expired cookie")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() re-mints a NEW owner for an expired signed cookie")
		void resolve_expiredCookie_reMintsNewOwner() {
			// given — minted 25h ago (TTL is 24h)
			final OwnerTokenCodec pastClockCodec = new OwnerTokenCodec(
					Clock.fixed(Instant.now().minusSeconds(25 * 3600), ZoneOffset.UTC));
			final String expired = pastClockCodec.mint("owner-old");
			final MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.get("/mcp-inspector/api/config")
					.cookie(new HttpCookie(OwnerTokenCodec.COOKIE_NAME, expired)));

			// when
			final String ownerId = ReactiveSessionOwnerResolverTests.this.resolver.resolve(exchange);

			// then
			assertThat(ownerId).isNotEqualTo("owner-old");
			assertThat(exchange.getResponse().getCookies().getFirst(OwnerTokenCodec.COOKIE_NAME)).isNotNull();
		}

	}

}
