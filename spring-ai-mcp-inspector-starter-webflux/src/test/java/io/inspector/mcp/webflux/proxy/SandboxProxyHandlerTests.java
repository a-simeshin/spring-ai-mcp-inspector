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

package io.inspector.mcp.webflux.proxy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SandboxProxyHandler}. CSP assertions parse the policy and check
 * the EFFECTIVE directive (first occurrence wins under CSP3 parsing), never substring
 * presence: a concatenated second policy would satisfy a substring check while the
 * browser silently ignores it (PR #199 owner review, BLOCK finding 2).
 */
class SandboxProxyHandlerTests {

	private final SandboxProxyHandler handler = new SandboxProxyHandler();

	/** App fixture declaring a connect domain and a script/image resource domain. */
	private static final String CSP_JSON = "{\"connectDomains\":[\"https://api.example.com\"],"
			+ "\"resourceDomains\":[\"https://cdn.example.com\"]}";

	/**
	 * Parse a serialized policy into its effective directives, mirroring CSP3 parsing:
	 * the FIRST occurrence of a repeated directive wins, later ones are ignored.
	 */
	private static Map<String, String> effectiveDirectives(final String policy) {
		final Map<String, String> directives = new LinkedHashMap<>();
		for (final String token : policy.split(";")) {
			final String trimmed = token.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			final String[] nameAndValue = trimmed.split("\\s+", 2);
			directives.putIfAbsent(nameAndValue[0], (nameAndValue.length > 1) ? nameAndValue[1] : "");
		}
		return directives;
	}

	/** Occurrence count of every directive name in the serialized policy. */
	private static Map<String, Long> directiveCounts(final String policy) {
		final Map<String, Long> counts = new LinkedHashMap<>();
		for (final String token : policy.split(";")) {
			final String trimmed = token.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			final String name = trimmed.split("\\s+", 2)[0];
			counts.merge(name, 1L, Long::sum);
		}
		return counts;
	}

	private ServerRequest requestWithCsp(final String cspJson) {
		final String query = (cspJson != null)
				? "?csp=" + java.net.URLEncoder.encode(cspJson, java.nio.charset.StandardCharsets.UTF_8) : "";
		final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector-api/sandbox" + query).build();
		final MockServerWebExchange exchange = MockServerWebExchange.from(request);
		return ServerRequest.create(exchange,
				java.util.List.of(new org.springframework.http.codec.HttpMessageReader[0]));
	}

	@Test
	void serveSandboxReturnsHtmlWithBaselinePolicy() {
		final Mono<ServerResponse> result = this.handler.serveSandbox(requestWithCsp(null));

		StepVerifier.create(result).assertNext((response) -> {
			assertThat(response.statusCode().is2xxSuccessful()).isTrue();
			assertThat(response.headers().getContentType().toString()).contains(MediaType.TEXT_HTML_VALUE);
			final Map<String, String> effective = effectiveDirectives(
					response.headers().getFirst("Content-Security-Policy"));
			assertThat(effective.get("default-src")).isEqualTo("'none'");
			assertThat(effective.get("connect-src")).isEqualTo("'none'");
			assertThat(effective.get("frame-src")).isEqualTo("'self'");
			assertThat(effective.get("object-src")).isEqualTo("'none'");
			assertThat(response.headers().getCacheControl()).isEqualTo(CacheControl.noStore().getHeaderValue());
		}).verifyComplete();
	}

	@Test
	void serveSandboxAppDomains_effectiveDirectivesCarryThem() {
		final String policy = this.handler.parseAndSerializeCsp(CSP_JSON);
		final Map<String, String> effective = effectiveDirectives(policy);
		// connect-src equals exactly the App-declared domain set, replacing the 'none'
		// baseline: this is the directive the browser enforces.
		assertThat(effective.get("connect-src")).isEqualTo("https://api.example.com");
		// resourceDomains broaden the SAME script-src/img-src, not a second copy.
		assertThat(effective.get("script-src")).isEqualTo("'self' 'unsafe-inline' https://cdn.example.com");
		assertThat(effective.get("img-src")).isEqualTo("https://cdn.example.com");
	}

	@Test
	void serveSandboxAppDomains_noDirectiveAppearsTwice() {
		final Map<String, Long> counts = directiveCounts(this.handler.parseAndSerializeCsp(CSP_JSON));
		assertThat(counts).allSatisfy((name, count) -> assertThat(count).as("directive %s", name).isEqualTo(1L));
		assertThat(counts.keySet()).contains("connect-src", "script-src", "style-src", "img-src", "font-src",
				"media-src", "frame-src", "base-uri", "object-src", "form-action", "frame-ancestors");
	}

	@Test
	void serveSandboxEveryPolicyPath_objectSrcNoneAndNoDuplicates() {
		final List<String> params = Arrays.asList(null, "", "not-json{",
				"{\"connectDomains\":[\"javascript:alert(1)\"]}",
				"{\"frameDomains\":[\"data:text/html,<script>alert(1)</script>\"]}", CSP_JSON);
		for (final String param : params) {
			final String policy = this.handler.parseAndSerializeCsp(param);
			final Map<String, String> effective = effectiveDirectives(policy);
			assertThat(effective.get("object-src")).as("object-src for param %s", param).isEqualTo("'none'");
			assertThat(effective.get("frame-ancestors")).as("frame-ancestors for param %s", param).isEqualTo("'self'");
			assertThat(directiveCounts(policy)).as("directive counts for param %s", param)
				.allSatisfy((name, count) -> assertThat(count).as("directive %s", name).isEqualTo(1L));
		}
	}

	@Test
	void serveSandboxWithMalformedCspParam_returnsBaselineWithoutEcho() {
		final String policy = this.handler.parseAndSerializeCsp("not-json{");
		assertThat(policy).doesNotContain("not-json{");
		assertThat(effectiveDirectives(policy).get("default-src")).isEqualTo("'none'");
	}

	@Test
	void serveSandboxWithOversizeCspParam_returns400() {
		final String oversized = "x".repeat(4097);
		final Mono<ServerResponse> result = this.handler.serveSandbox(requestWithCsp(oversized));

		StepVerifier.create(result)
			.assertNext((response) -> assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
			.verifyComplete();
	}

	@Test
	void serveSandboxWithJavascriptOrigin_rejectsWithBaseline() {
		final String policy = this.handler.parseAndSerializeCsp("{\"connectDomains\":[\"javascript:alert(1)\"]}");
		assertThat(policy).doesNotContain("javascript:alert");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo("'none'");
	}

	@Test
	void parseAndSerializeCsp_separatorAndWhitespaceValues_droppedNotEchoed() {
		// PR #199 owner review: values with ';' or whitespace must never be joined
		// into the header; only the valid host-source entries survive.
		final String policy = this.handler.parseAndSerializeCsp(
				"{\"connectDomains\":[\"https://api.example.com\",\"https://evil.com; script-src 'unsafe-eval'\"]}");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo("https://api.example.com");
		assertThat(policy).doesNotContain("unsafe-eval");
	}

	@Test
	void parseAndSerializeCsp_quotedKeywordValue_dropped() {
		final String policy = this.handler
			.parseAndSerializeCsp("{\"connectDomains\":[\"'unsafe-eval'\",\"https://api.example.com\"]}");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo("https://api.example.com");
	}

	@Test
	void parseAndSerializeCsp_allInvalidValues_fallBackToBaseline() {
		final String policy = this.handler
			.parseAndSerializeCsp("{\"connectDomains\":[\"data:text/html,x\",\"*, 'unsafe-eval'\"]}");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo("'none'");
	}

	@Test
	void parseAndSerializeCsp_validHostSourceGrammar_accepted() {
		// Scheme, port, path, localhost, IPv4 and bracketed IPv6 literals are all
		// valid host-source expressions.
		final String policy = this.handler.parseAndSerializeCsp(
				"{\"connectDomains\":[\"api.example.com\",\"https://api.example.com:8443\",\"http://api.example.com/v1/api\","
						+ "\"localhost\",\"127.0.0.1\",\"[::1]\"]}");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo(
				"api.example.com https://api.example.com:8443 http://api.example.com/v1/api localhost 127.0.0.1 [::1]");
	}

}
