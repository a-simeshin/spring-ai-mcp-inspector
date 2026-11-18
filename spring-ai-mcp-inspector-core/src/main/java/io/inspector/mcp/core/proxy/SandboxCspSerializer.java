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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Single serializer for sandbox-proxy Content-Security-Policy values, shared by the
 * WebMVC controller and the WebFlux handler. The TypeScript counterpart is the sandbox
 * page's inline serializer ({@code upstream-client/public/sandbox-proxy.html}): the page
 * is served standalone, without the bundle, so it cannot import a shared module.
 *
 * <p>
 * Serializes a McpUiResourceCsp object into ONE effective policy: the page baseline
 * merged at the directive level with the App-declared domains. Every directive is emitted
 * at most once (CSP3 ignores repeated directives, keeping only the first occurrence), so
 * the App domains in this policy are the ones the browser actually enforces. A null csp
 * yields the page baseline with every network-fetch directive locked to 'none'.
 *
 * <p>
 * App-supplied domain values are validated as strict CSP host-source expressions by
 * {@link #isAllowedSourceExpression(String)} before they can enter a policy: only the
 * host-source grammar passes, so values containing ';', whitespace, scheme sources like
 * {@code javascript:}/{@code data:}, quoted keywords like {@code 'unsafe-eval'}, or any
 * other non-host grammar never reach the serialized header. A csp object that contains no
 * valid domain at all is treated as absent (page baseline), and an invalid value never
 * removes an earlier valid one from the policy.
 *
 * @author Artem Simeshin
 */
public final class SandboxCspSerializer {

	/**
	 * Maximum accepted length of the {@code csp} query parameter (4 KB), enforced by both
	 * stacks (400 on overflow). The page's inline serializer enforces the same cap on the
	 * {@code params.csp} value of the sandbox-resource-ready message (page baseline on
	 * overflow), so the limit is identical everywhere a policy can enter the page.
	 */
	public static final int CSP_PARAM_MAX_LENGTH = 4096;

	/**
	 * Strict CSP host-source grammar: an optional scheme ({@code https://},
	 * {@code http://}, {@code wss://} or {@code ws://}), a host that is a dotted host
	 * name (optionally behind a leading {@code *.} wildcard label), {@code localhost}, or
	 * an IP literal, an optional port ({@code :8443} or {@code :*}), and an optional
	 * path. The path character set deliberately excludes the policy separators
	 * {@code ';'}, {@code ','} and {@code '''}: a value like
	 * {@code example.com/;frame-ancestors} (no whitespace anywhere) must never pass, it
	 * would split the serialized policy and inject a directive (owner review PR #199,
	 * round 2, BLOCK 1). Everything else is rejected: quoted keywords ({@code 'self'},
	 * {@code 'unsafe-eval'}), nonce/hash sources, {@code data:}/{@code javascript:} and
	 * any other bare scheme, and values with whitespace.
	 */
	private static final Pattern HOST_SOURCE = Pattern.compile(
			"^(?:(?:https|http|wss|ws)://)?(?:(?:\\*\\.)?(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}"
					+ "|localhost" + "|(?:\\d{1,3}\\.){3}\\d{1,3}" + "|\\[[0-9a-fA-F:]+\\])"
					+ "(?::(?:\\d{1,5}|\\*))?(?:/[\\w\\-.~/!$&()*+=:@%]*)?$");

	private SandboxCspSerializer() {
	}

	/**
	 * Serialize a McpUiResourceCsp object into ONE effective policy for the sandbox proxy
	 * response. Domain values that are not strict host-source expressions are dropped
	 * from the policy before serialization.
	 * @param csp the parsed McpUiResourceCsp object, or null for the page baseline
	 * @return the serialized policy with exactly one occurrence of every directive
	 */
	public static String serialize(final Map<String, Object> csp) {
		final Map<String, Object> config = (csp != null) ? csp : Map.of();
		final List<String> connectDomains = sanitizedDomains(config, "connectDomains");
		final List<String> resourceDomains = sanitizedDomains(config, "resourceDomains");
		final List<String> frameDomains = sanitizedDomains(config, "frameDomains");
		final List<String> baseUriDomains = sanitizedDomains(config, "baseUriDomains");

		final List<String> parts = new ArrayList<>();
		parts.add("default-src 'none'");

		// The page's own relay script and style are inline, and the srcdoc guest
		// inherits this policy, so its inline scripts need 'unsafe-inline' too. App
		// resourceDomains broaden the SAME directive instead of a second, ignored
		// script-src.
		if (resourceDomains.isEmpty()) {
			parts.add("script-src 'self' 'unsafe-inline'");
			parts.add("style-src 'self' 'unsafe-inline'");
			parts.add("img-src 'none'");
			parts.add("font-src 'none'");
			parts.add("media-src 'none'");
		}
		else {
			final String joined = String.join(" ", resourceDomains);
			parts.add("script-src 'self' 'unsafe-inline' " + joined);
			parts.add("style-src 'self' 'unsafe-inline' " + joined);
			parts.add("img-src " + joined);
			parts.add("font-src " + joined);
			parts.add("media-src " + joined);
		}

		// connect-src: App-declared domains only, or 'none' when the App declares
		// none (the page itself performs no fetches).
		if (connectDomains.isEmpty()) {
			parts.add("connect-src 'none'");
		}
		else {
			parts.add("connect-src " + String.join(" ", connectDomains));
		}

		// frame-src keeps 'self': the page must stay able to host its own srcdoc
		// view iframe regardless of the App frameDomains.
		if (frameDomains.isEmpty()) {
			parts.add("frame-src 'self'");
		}
		else {
			parts.add("frame-src 'self' " + String.join(" ", frameDomains));
		}

		if (baseUriDomains.isEmpty()) {
			parts.add("base-uri 'none'");
		}
		else {
			parts.add("base-uri " + String.join(" ", baseUriDomains));
		}

		// SEP-1865 draft, "Sandbox proxy", item 5: object-src 'none' everywhere.
		parts.add("object-src 'none'");
		parts.add("form-action 'none'");
		parts.add("frame-ancestors 'self'");

		return String.join("; ", parts);
	}

	/**
	 * Validate an App-supplied domain value as a strict CSP host-source expression
	 * (host-source grammar only). Rejected: values containing a policy separator or
	 * whitespace, scheme sources ({@code javascript:}, {@code data:}), quoted keywords
	 * ({@code 'self'}, {@code 'unsafe-eval'}), and empty values.
	 * @param value the raw domain value from the App's McpUiResourceCsp
	 * @return true when the value is a strict host-source expression
	 */
	public static boolean isAllowedSourceExpression(final String value) {
		return value != null && !value.isEmpty() && HOST_SOURCE.matcher(value).matches();
	}

	/**
	 * Extract a sanitized string list for a McpUiResourceCsp key: non-list values and
	 * non-string items are ignored, and items that are not strict host-source expressions
	 * are dropped so they can never enter the serialized policy.
	 * @param csp the parsed McpUiResourceCsp object, or null when absent
	 * @param key the McpUiResourceCsp field name to read
	 * @return the valid host-source expressions from that field, never null
	 */
	static List<String> sanitizedDomains(final Map<String, Object> csp, final String key) {
		final Object value = (csp != null) ? csp.get(key) : null;
		if (!(value instanceof List<?> rawList)) {
			return Collections.emptyList();
		}
		final List<String> result = new ArrayList<>();
		for (final Object item : rawList) {
			if (item instanceof String s && isAllowedSourceExpression(s)) {
				result.add(s);
			}
		}
		return result;
	}

}
