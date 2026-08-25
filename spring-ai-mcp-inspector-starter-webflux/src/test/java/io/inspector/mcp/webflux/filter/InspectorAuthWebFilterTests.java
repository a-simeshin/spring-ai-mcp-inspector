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

package io.inspector.mcp.webflux.filter;

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
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.auth.OwnerTokenCodec;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.webflux.auth.InspectorSessionAttributes;
import io.inspector.mcp.webflux.auth.ReactiveSessionOwnerResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Unit tests for {@link InspectorAuthWebFilter}. */
@Epic("MCP Inspector WebFlux")
@Feature("InspectorAuthWebFilter reactive token guard")
class InspectorAuthWebFilterTests {

	private McpInspectorProperties properties;

	private InspectorAuthTokenProvider tokenProvider;

	private InspectorAuthWebFilter filter;

	private String token;

	@BeforeEach
	void setUp() {
		this.properties = new McpInspectorProperties();
		this.properties.setAuthToken("fixed-test-token");
		this.tokenProvider = new InspectorAuthTokenProvider(this.properties);
		this.token = this.tokenProvider.token();
		this.filter = new InspectorAuthWebFilter(this.properties, this.tokenProvider);
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
			final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
				.header(InspectorAuthWebFilter.HEADER, InspectorAuthWebFilterTests.this.token)
				.build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(InspectorAuthWebFilterTests.this.filter.filter(exchange, chain)).verifyComplete();

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
			final MockServerHttpRequest request = MockServerHttpRequest
				.get("/mcp-inspector/api/events?auth=" + InspectorAuthWebFilterTests.this.token)
				.build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(InspectorAuthWebFilterTests.this.filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Short-circuit branches")
		@Severity(SeverityLevel.CRITICAL)
		@Description("filter() rejects an invalid token with 401 and does not invoke the chain")
		void filter_withInvalidToken_returns401AndShortCircuits() {
			// given
			final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
				.header(InspectorAuthWebFilter.HEADER, "wrong")
				.build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(InspectorAuthWebFilterTests.this.filter.filter(exchange, chain)).verifyComplete();

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
			final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config").build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(InspectorAuthWebFilterTests.this.filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isFalse();
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@Story("Short-circuit branches")
		@Severity(SeverityLevel.BLOCKER)
		@Description("filter() still guards the REST API when the application runs under a base path or forwarded prefix")
		void filter_underBasePathWithoutToken_returns401() {
			// given — the prefix lives in contextPath, so the raw URI path is
			// /app/mcp-inspector/api/connect while the application-relative path is not
			final MockServerHttpRequest request = MockServerHttpRequest.post("/app/mcp-inspector/api/connect")
				.contextPath("/app")
				.build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(InspectorAuthWebFilterTests.this.filter.filter(exchange, chain)).verifyComplete();

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
			final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/index.html").build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(InspectorAuthWebFilterTests.this.filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() passes through unconditionally when auth is disabled")
		void filter_whenAuthDisabled_passesThrough() {
			// given
			InspectorAuthWebFilterTests.this.properties.setAuthEnabled(false);
			final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config").build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(InspectorAuthWebFilterTests.this.filter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
		}

		@Test
		@Story("Allow branches")
		@Severity(SeverityLevel.NORMAL)
		@Description("filter() passes through unconditionally when constructed with no properties bean (properties == null branch)")
		void filter_whenPropertiesNull_passesThrough() {
			// given — defensive construction with a null properties bean
			final InspectorAuthWebFilter nullPropsFilter = new InspectorAuthWebFilter(null,
					InspectorAuthWebFilterTests.this.tokenProvider);
			final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
				.header(InspectorAuthWebFilter.HEADER, "irrelevant")
				.build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

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
			final MockServerHttpRequest request = MockServerHttpRequest
				.get("/mcp-inspector/api/events?auth=" + InspectorAuthWebFilterTests.this.token)
				.header(InspectorAuthWebFilter.HEADER, "   ")
				.build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(InspectorAuthWebFilterTests.this.filter.filter(exchange, chain)).verifyComplete();

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
			final InspectorAuthTokenProvider nullTokenProvider = mock(InspectorAuthTokenProvider.class);
			given(nullTokenProvider.token()).willReturn(null);
			final InspectorAuthWebFilter nullTokenFilter = new InspectorAuthWebFilter(
					InspectorAuthWebFilterTests.this.properties, nullTokenProvider);
			final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector/api/config")
				.header(InspectorAuthWebFilter.HEADER, "some-presented-token")
				.build();
			final MockServerWebExchange exchange = MockServerWebExchange.from(request);
			final ChainSpy chain = new ChainSpy();

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
		public Mono<Void> filter(final org.springframework.web.server.ServerWebExchange exchange) {
			this.invoked = true;
			return Mono.empty();
		}

	}

	@Nested
	@DisplayName("session-owner cookie (D8)")
	class SessionOwner {

		private InspectorAuthWebFilter ownerFilter;

		private OwnerTokenCodec codec;

		@BeforeEach
		void setUp() {
			InspectorAuthWebFilterTests.this.properties.setAuthToken("fixed-test-token");
			this.codec = new OwnerTokenCodec();
			this.ownerFilter = new InspectorAuthWebFilter(InspectorAuthWebFilterTests.this.properties,
					InspectorAuthWebFilterTests.this.tokenProvider, Ordered.HIGHEST_PRECEDENCE + 100,
					new ReactiveSessionOwnerResolver(this.codec));
		}

		private MockServerHttpRequest requestWithCookie(final String value) {
			final MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/mcp-inspector/api/config")
				.header(InspectorAuthWebFilter.HEADER, InspectorAuthWebFilterTests.this.token);
			if (value != null) {
				builder.cookie(new HttpCookie(OwnerTokenCodec.COOKIE_NAME, value));
			}
			return builder.build();
		}

		@Test
		@Story("Owner mint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a first request with a valid auth token and NO cookie passes and mints a signed Set-Cookie (never 401)")
		void firstRequestWithoutCookie_mintsOwnerCookie() {
			// given
			final MockServerWebExchange exchange = MockServerWebExchange.from(requestWithCookie(null));
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(this.ownerFilter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(chain.invoked).isTrue();
			assertThat(exchange.getResponse().getStatusCode()).isNull();
			final ResponseCookie cookie = exchange.getResponse().getCookies().getFirst(OwnerTokenCodec.COOKIE_NAME);
			assertThat(cookie).isNotNull();
			assertThat(cookie.isHttpOnly()).isTrue();
			assertThat(cookie.getSameSite()).isEqualTo("Lax");
			final Object attribute = exchange.getAttribute(InspectorSessionAttributes.OWNER_ID);
			assertThat(attribute).isInstanceOf(String.class);
			assertThat(this.codec.validate(cookie.getValue())).contains((String) attribute);
		}

		@Test
		@Story("Owner parse")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a valid signed cookie is reused — same owner, no re-mint")
		void validCookie_isReusedWithoutReMint() {
			// given
			final String token = this.codec.mint("owner-stable", java.time.Instant.now());
			final MockServerWebExchange exchange = MockServerWebExchange.from(requestWithCookie(token));
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(this.ownerFilter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat((Object) exchange.getAttribute(InspectorSessionAttributes.OWNER_ID)).isEqualTo("owner-stable");
			assertThat(exchange.getResponse().getCookies()).isEmpty();
		}

		@Test
		@Story("Owner re-mint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a forged cookie is treated as absent — a NEW owner is minted (old scope NOT inherited)")
		void forgedCookie_reMintsNewOwner() {
			// given — structurally valid but unsigned
			final MockServerWebExchange exchange = MockServerWebExchange
				.from(requestWithCookie("owner-victim.1750000000.deadbeef"));
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(this.ownerFilter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat((Object) exchange.getAttribute(InspectorSessionAttributes.OWNER_ID))
				.isNotEqualTo("owner-victim");
			assertThat(exchange.getResponse().getCookies().getFirst(OwnerTokenCodec.COOKIE_NAME)).isNotNull();
		}

		@Test
		@Story("Owner re-mint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("an expired signed cookie is re-minted to a new owner")
		void expiredCookie_reMintsNewOwner() {
			// given
			final String expired = this.codec.mint("owner-old", java.time.Instant.now().minusSeconds(25 * 3600));
			final MockServerWebExchange exchange = MockServerWebExchange.from(requestWithCookie(expired));
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(this.ownerFilter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat((Object) exchange.getAttribute(InspectorSessionAttributes.OWNER_ID)).isNotEqualTo("owner-old");
			assertThat(exchange.getResponse().getCookies().getFirst(OwnerTokenCodec.COOKIE_NAME)).isNotNull();
		}

		@Test
		@Story("Guard first")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a request that FAILS the X-MCP-Inspector-Auth guard gets 401 and NO cookie is minted")
		void failedGuard_doesNotMintCookie() {
			// given
			final MockServerWebExchange exchange = MockServerWebExchange
				.from(MockServerHttpRequest.get("/mcp-inspector/api/config")
					.header(InspectorAuthWebFilter.HEADER, "wrong-token"));
			final ChainSpy chain = new ChainSpy();

			// when
			StepVerifier.create(this.ownerFilter.filter(exchange, chain)).verifyComplete();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(chain.invoked).isFalse();
			assertThat(exchange.getResponse().getCookies()).isEmpty();
			assertThat((Object) exchange.getAttribute(InspectorSessionAttributes.OWNER_ID)).isNull();
		}

	}

}
