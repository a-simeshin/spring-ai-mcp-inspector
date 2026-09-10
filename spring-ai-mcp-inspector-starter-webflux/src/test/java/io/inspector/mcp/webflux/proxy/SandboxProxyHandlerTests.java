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
 * Unit tests for {@link SandboxProxyHandler}.
 */
class SandboxProxyHandlerTests {

	private final SandboxProxyHandler handler = new SandboxProxyHandler();

	@Test
	void serveSandboxReturnsHtmlWithDefaultCsp() {
		final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector-api/sandbox").build();
		final MockServerWebExchange exchange = MockServerWebExchange.from(request);
		final ServerRequest serverRequest = ServerRequest.create(exchange,
				java.util.List.of(new org.springframework.http.codec.HttpMessageReader[0]));

		final Mono<ServerResponse> result = this.handler.serveSandbox(serverRequest);

		StepVerifier.create(result).assertNext((response) -> {
			assertThat(response.statusCode().is2xxSuccessful()).isTrue();
			assertThat(response.headers().getContentType().toString()).contains(MediaType.TEXT_HTML_VALUE);
			assertThat(response.headers().getFirst("Content-Security-Policy")).contains("default-src 'none'");
			assertThat(response.headers().getCacheControl()).isEqualTo(CacheControl.noStore().getHeaderValue());
		}).verifyComplete();
	}

	@Test
	void serveSandboxWithValidCspParam_serializesJsonToHeader() {
		final String cspJson = "{\"connectDomains\":[\"https://api.example.com\"],\"resourceDomains\":[\"https://cdn.example.com\"]}";
		final MockServerHttpRequest request = MockServerHttpRequest
			.get("/mcp-inspector-api/sandbox?csp="
					+ java.net.URLEncoder.encode(cspJson, java.nio.charset.StandardCharsets.UTF_8))
			.build();
		final MockServerWebExchange exchange = MockServerWebExchange.from(request);
		final ServerRequest serverRequest = ServerRequest.create(exchange,
				java.util.List.of(new org.springframework.http.codec.HttpMessageReader[0]));

		// Parse csp directly and verify handler's serialization
		final String cspHeader = this.handler.parseAndSerializeCsp(cspJson);
		assertThat(cspHeader).contains("connect-src https://api.example.com");
		assertThat(cspHeader).contains("img-src https://cdn.example.com");
	}

	@Test
	void serveSandboxWithMalformedCspParam_returnsSafeDefault() {
		final String malformed = "not-json{";
		final String cspHeader = this.handler.parseAndSerializeCsp(malformed);
		// Should not echo raw value; should return safe default
		assertThat(cspHeader).doesNotContain("not-json{");
		assertThat(cspHeader).contains("default-src 'none'");
	}

	@Test
	void serveSandboxWithOversizeCspParam_returns400() {
		final String oversized = "x".repeat(4097);
		final MockServerHttpRequest request = MockServerHttpRequest.get("/mcp-inspector-api/sandbox?csp=" + oversized)
			.build();
		final MockServerWebExchange exchange = MockServerWebExchange.from(request);
		final ServerRequest serverRequest = ServerRequest.create(exchange,
				java.util.List.of(new org.springframework.http.codec.HttpMessageReader[0]));

		final Mono<ServerResponse> result = this.handler.serveSandbox(serverRequest);

		StepVerifier.create(result)
			.assertNext((response) -> assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
			.verifyComplete();
	}

	@Test
	void serveSandboxWithJavascriptOrigin_rejectsWithSafeDefault() {
		final String cspJson = "{\"connectDomains\":[\"javascript:alert(1)\"]}";
		final String cspHeader = this.handler.parseAndSerializeCsp(cspJson);
		assertThat(cspHeader).doesNotContain("javascript:alert");
		assertThat(cspHeader).contains("default-src 'none'");
	}

}
