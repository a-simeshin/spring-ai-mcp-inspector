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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SandboxProxyController}. CSP assertions parse the policy and
 * check the EFFECTIVE directive (first occurrence wins under CSP3 parsing), never
 * substring presence: a concatenated second policy would satisfy a substring check while
 * the browser silently ignores it (PR #199 owner review, BLOCK finding 2).
 */
class SandboxProxyControllerTests {

	/**
	 * App fixture declaring a connect domain, script/image resource domain, and frames.
	 */
	private static final String CSP_JSON = "{\"connectDomains\":[\"https://api.example.com\",\"https://ws.example.com\"],"
			+ "\"resourceDomains\":[\"https://cdn.example.com\"],\"frameDomains\":[\"https://frames.example.com\"],"
			+ "\"baseUriDomains\":[\"https://base.example.com\"]}";

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

	@Test
	void parseAndSerializeCsp_null_returnsBaselinePolicy() {
		final Map<String, String> effective = effectiveDirectives(SandboxProxyController.parseAndSerializeCsp(null));
		assertThat(effective.get("default-src")).isEqualTo("'none'");
		assertThat(effective.get("script-src")).isEqualTo("'self' 'unsafe-inline'");
		assertThat(effective.get("style-src")).isEqualTo("'self' 'unsafe-inline'");
		assertThat(effective.get("connect-src")).isEqualTo("'none'");
		assertThat(effective.get("img-src")).isEqualTo("'none'");
		assertThat(effective.get("font-src")).isEqualTo("'none'");
		assertThat(effective.get("media-src")).isEqualTo("'none'");
		assertThat(effective.get("frame-src")).isEqualTo("'self'");
	}

	@Test
	void parseAndSerializeCsp_blank_returnsBaselinePolicy() {
		final Map<String, String> effective = effectiveDirectives(SandboxProxyController.parseAndSerializeCsp(""));
		assertThat(effective.get("connect-src")).isEqualTo("'none'");
	}

	@Test
	void parseAndSerializeCsp_appDomains_effectiveDirectivesCarryThem() {
		final String policy = SandboxProxyController.parseAndSerializeCsp(CSP_JSON);
		final Map<String, String> effective = effectiveDirectives(policy);
		// connect-src equals exactly the App-declared domain set, replacing the 'none'
		// baseline: this is the directive the browser enforces.
		assertThat(effective.get("connect-src")).isEqualTo("https://api.example.com https://ws.example.com");
		// resourceDomains broaden the SAME
		// script-src/style-src/img-src/font-src/media-src.
		assertThat(effective.get("script-src")).isEqualTo("'self' 'unsafe-inline' https://cdn.example.com");
		assertThat(effective.get("style-src")).isEqualTo("'self' 'unsafe-inline' https://cdn.example.com");
		assertThat(effective.get("img-src")).isEqualTo("https://cdn.example.com");
		assertThat(effective.get("font-src")).isEqualTo("https://cdn.example.com");
		assertThat(effective.get("media-src")).isEqualTo("https://cdn.example.com");
		assertThat(effective.get("frame-src")).isEqualTo("'self' https://frames.example.com");
		assertThat(effective.get("base-uri")).isEqualTo("https://base.example.com");
	}

	@Test
	void parseAndSerializeCsp_appDomains_noDirectiveAppearsTwice() {
		// A second, concatenated policy would be ignored by CSP3 repeated-directive
		// parsing, so the merged policy must emit every directive exactly once.
		final Map<String, Long> counts = directiveCounts(SandboxProxyController.parseAndSerializeCsp(CSP_JSON));
		assertThat(counts).allSatisfy((name, count) -> assertThat(count).as("directive %s", name).isEqualTo(1L));
		assertThat(counts.keySet()).contains("connect-src", "script-src", "style-src", "img-src", "font-src",
				"media-src", "frame-src", "base-uri", "object-src", "form-action", "frame-ancestors");
	}

	@Test
	void parseAndSerializeCsp_everyPolicyPath_objectSrcNoneAndNoDuplicates() {
		// SEP-1865 draft, "Sandbox proxy", item 5: object-src 'none' everywhere. Every
		// code path (baseline, malformed JSON, dangerous origin, merged App policy)
		// must emit it once and duplicate no directive.
		final List<String> params = Arrays.asList(null, "", "not-json{",
				"{\"connectDomains\":[\"javascript:alert(1)\"]}",
				"{\"frameDomains\":[\"data:text/html,<script>alert(1)</script>\"]}", CSP_JSON);
		for (final String param : params) {
			final String policy = SandboxProxyController.parseAndSerializeCsp(param);
			final Map<String, String> effective = effectiveDirectives(policy);
			assertThat(effective.get("object-src")).as("object-src for param %s", param).isEqualTo("'none'");
			assertThat(directiveCounts(policy)).as("directive counts for param %s", param)
				.allSatisfy((name, count) -> assertThat(count).as("directive %s", name).isEqualTo(1L));
		}
	}

	@Test
	void parseAndSerializeCsp_defaultAndErrorPaths_carryFrameAncestorsSelf() {
		// frame-ancestors 'self' stops any other origin from framing the sandbox
		// endpoint; required on every path that returns the page baseline.
		final List<String> params = Arrays.asList(null, "not-json{", "{\"connectDomains\":[\"javascript:alert(1)\"]}",
				CSP_JSON);
		for (final String param : params) {
			final Map<String, String> effective = effectiveDirectives(
					SandboxProxyController.parseAndSerializeCsp(param));
			assertThat(effective.get("frame-ancestors")).as("frame-ancestors for param %s", param).isEqualTo("'self'");
		}
	}

	@Test
	void parseAndSerializeCsp_malformedJson_returnsBaselineWithoutEcho() {
		final String policy = SandboxProxyController.parseAndSerializeCsp("not-json{");
		// Should not echo raw value; should return the page baseline
		assertThat(policy).doesNotContain("not-json{");
		assertThat(effectiveDirectives(policy).get("default-src")).isEqualTo("'none'");
	}

	@Test
	void parseAndSerializeCsp_javascriptOrigin_rejected() {
		final String policy = SandboxProxyController
			.parseAndSerializeCsp("{\"connectDomains\":[\"javascript:alert(1)\"]}");
		assertThat(policy).doesNotContain("javascript:alert");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo("'none'");
	}

	@Test
	void parseAndSerializeCsp_dataOrigin_rejected() {
		final String policy = SandboxProxyController
			.parseAndSerializeCsp("{\"frameDomains\":[\"data:text/html,<script>alert(1)</script>\"]}");
		assertThat(policy).doesNotContain("data:text/html");
		assertThat(effectiveDirectives(policy).get("frame-src")).isEqualTo("'self'");
	}

	@Test
	void parseAndSerializeCsp_separatorAndWhitespaceValues_droppedNotEchoed() {
		// PR #199 owner review: values with ';' or whitespace must never be joined
		// into the header; only the valid host-source entries survive.
		final String policy = SandboxProxyController.parseAndSerializeCsp(
				"{\"connectDomains\":[\"https://api.example.com\",\"https://evil.com; script-src 'unsafe-eval'\"]}");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo("https://api.example.com");
		assertThat(policy).doesNotContain("unsafe-eval");
	}

	@Test
	void parseAndSerializeCsp_quotedKeywordValue_dropped() {
		final String policy = SandboxProxyController
			.parseAndSerializeCsp("{\"connectDomains\":[\"'unsafe-eval'\",\"https://api.example.com\"]}");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo("https://api.example.com");
		assertThat(policy).doesNotContain("'unsafe-eval' https");
	}

	@Test
	void parseAndSerializeCsp_allInvalidValues_fallBackToBaseline() {
		final String policy = SandboxProxyController
			.parseAndSerializeCsp("{\"connectDomains\":[\"data:text/html,x\",\"*, 'unsafe-eval'\"]}");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo("'none'");
	}

	@Test
	void parseAndSerializeCsp_validHostSourceGrammar_accepted() {
		// Scheme, port, path, localhost, IPv4 and bracketed IPv6 literals are all
		// valid host-source expressions.
		final String policy = SandboxProxyController.parseAndSerializeCsp(
				"{\"connectDomains\":[\"api.example.com\",\"https://api.example.com:8443\",\"http://api.example.com/v1/api\","
						+ "\"localhost\",\"127.0.0.1\",\"[::1]\"]}");
		assertThat(effectiveDirectives(policy).get("connect-src")).isEqualTo(
				"api.example.com https://api.example.com:8443 http://api.example.com/v1/api localhost 127.0.0.1 [::1]");
	}

	@Test
	void serveSandboxWithOversizeCspParam_returns400() throws Exception {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 4097; i++) {
			sb.append("x");
		}
		final ResponseEntity<String> response = new SandboxProxyController().serveSandbox(sb.toString());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("CSP parameter exceeds maximum length");
	}

}
