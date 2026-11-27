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

package io.inspector.mcp.core.proxy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared {@link SandboxCspSerializer}: the single Java CSP serializer
 * used by both stacks (PR #199 owner review, serializer dedup) and the single place
 * App-supplied domains are validated as strict CSP host-source expressions.
 */
class SandboxCspSerializerTests {

	@Test
	void isAllowedSourceExpression_acceptsHostSourceGrammar() {
		for (final String valid : List.of("example.com", "sub.example.com", "https://example.com", "http://example.com",
				"wss://realtime.service.com", "ws://api.example.com", "https://*.cloudflare.com", "*.cloudflare.com",
				"example.com:*", "https://example.com:8443", "https://example.com/path", "example.com/path/deep/x.js",
				"wss://example.com:8080/socket", "localhost", "127.0.0.1", "[::1]", "[2001:db8::1]")) {
			assertThat(SandboxCspSerializer.isAllowedSourceExpression(valid)).as("valid: %s", valid).isTrue();
		}
	}

	@Test
	void isAllowedSourceExpression_rejectsNonHostGrammar() {
		for (final String invalid : java.util.Arrays.asList("javascript:alert(1)", "data:text/html,x", "'self'",
				"'unsafe-eval'", "'unsafe-inline'", "'none'", "*", "https://evil.com; script-src 'unsafe-eval'",
				"https://evil.com javascript:alert(1)", "https://evil.com ", " https://evil.com", "", null,
				// Owner review PR #199 round 2 BLOCK 1: path-embedded separators WITHOUT
				// any
				// whitespace must be rejected by the grammar itself, not by the
				// whitespace rule.
				"example.com/;frame-ancestors", "cdn.example.com/;connect-src", "example.com/,x",
				"example.com/'unsafe-eval'", "wss://example.com/;frame-ancestors", "*.example.com/;connect-src")) {
			assertThat(SandboxCspSerializer.isAllowedSourceExpression(invalid)).as("invalid: %s", invalid).isFalse();
		}
	}

	@Test
	void serialize_dropsInvalidDomainsAndKeepsValidOnes() {
		final Map<String, Object> csp = Map.of("connectDomains", List.of("https://api.example.com",
				"https://evil.com; script-src 'unsafe-eval'", "'unsafe-eval'", "data:text/html,x"));
		final String policy = SandboxCspSerializer.serialize(csp);
		assertThat(policy).contains("connect-src https://api.example.com");
		assertThat(policy).doesNotContain("unsafe-eval").doesNotContain("data:");
		assertThat(policy).doesNotContain("evil.com");
	}

	@Test
	void serialize_allInvalidDomainsFallsBackToNone() {
		final Map<String, Object> csp = Map.of("connectDomains", List.of("javascript:alert(1)", "*"));
		assertThat(SandboxCspSerializer.serialize(csp)).contains("connect-src 'none'");
	}

	@Test
	void serialize_nullYieldsLockedBaseline() {
		final String policy = SandboxCspSerializer.serialize(null);
		assertThat(policy).contains("default-src 'none'");
		assertThat(policy).contains("connect-src 'none'");
		assertThat(policy).contains("frame-ancestors 'self'");
		assertThat(policy).contains("object-src 'none'");
	}

	@Test
	void serialize_nonStringItemsIgnored() {
		final Map<String, Object> csp = Map.of("connectDomains", List.of(42, true, "https://api.example.com"));
		assertThat(SandboxCspSerializer.serialize(csp)).contains("connect-src https://api.example.com");
	}

	@Test
	void serialize_rejectsDirectiveInjectionViaPathWithoutWhitespace() {
		// Owner review PR #199 round 2 BLOCK 1, exact payloads. Sources are joined with a
		// space, so a domain like "example.com/;frame-ancestors" used to split the
		// serialized
		// policy: the first frame-ancestors occurrence won and became "evil.com", letting
		// any
		// site frame /sandbox?csp=... Now the path grammar forbids ';', ',' and "'": the
		// injected source is dropped, the baseline frame-ancestors 'self' stands, and the
		// only
		// surviving occurrence of the second domain is the declared connect source.
		final Map<String, Object> framePayload = Map.of("connectDomains",
				List.of("example.com/;frame-ancestors", "evil.com"));
		final String framePolicy = SandboxCspSerializer.serialize(framePayload);
		assertThat(framePolicy).doesNotContain("frame-ancestors evil.com");
		assertThat(framePolicy).doesNotContain("example.com/;frame-ancestors");
		assertThat(framePolicy).contains("frame-ancestors 'self'");
		assertThat(framePolicy).contains("connect-src evil.com");

		// Second payload: resourceDomains feed script/style/img/font/media-src, so an
		// injected ";connect-src" used to land BEFORE the real "connect-src 'none'" and
		// win.
		final Map<String, Object> connectPayload = Map.of("resourceDomains",
				List.of("cdn.example.com/;connect-src", "evil.com"));
		final String connectPolicy = SandboxCspSerializer.serialize(connectPayload);
		assertThat(connectPolicy).doesNotContain("connect-src evil.com");
		assertThat(connectPolicy).doesNotContain("cdn.example.com/;connect-src");
		assertThat(connectPolicy).contains("connect-src 'none'");
		assertThat(connectPolicy).contains("img-src evil.com");
	}

	@Test
	void cspParamMaxLengthIs4096() {
		// The 4 KB cap must stay consistent across both stacks and the page.
		assertThat(SandboxCspSerializer.CSP_PARAM_MAX_LENGTH).isEqualTo(4096);
	}

}
