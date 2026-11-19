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

package io.inspector.mcp.core.timeline;

import java.util.List;

/**
 * Redacts connection targets before they reach timeline payloads, the live registry or
 * log output. Client URLs routinely embed credentials as userinfo
 * ({@code https://user:pass@host/mcp}) or query tokens ({@code /sse?token=abc123}), and
 * stdio commands may embed secret flags; none of these may appear verbatim in captured
 * data.
 *
 * <p>
 * URL targets: the userinfo is replaced with {@code ***} ({@code scheme://***@host}) and
 * the query string is dropped entirely, regardless of whether a scheme is present (a
 * scheme-less {@code host/sse?token=x} still loses its query). Host and path are kept.
 *
 * <p>
 * stdio targets: only {@code argv[0]} and flag names are shown, never values. A command
 * {@code npx -y server --api-key=SECRET token} is reported as {@code npx -y --api-key};
 * argument values are dropped without being inspected, so no secret is ever echoed.
 *
 * <p>
 * All methods are idempotent: sanitizing an already sanitized value returns it unchanged.
 *
 * @author Artem Simeshin
 */
public final class TargetSanitizer {

	/** Replacement shown in place of credentials. */
	public static final String REDACTED = "***";

	private TargetSanitizer() {
	}

	/**
	 * Sanitizes a URL-like target: userinfo credentials are replaced with {@code ***} and
	 * the query string is dropped entirely; host and path are kept. The query is stripped
	 * even when the value carries no {@code scheme://} prefix. Values without userinfo or
	 * query pass through unchanged, which makes the method safe to apply to already
	 * sanitized input and to plain stdio command strings.
	 * @param rawTarget the raw target, may be {@code null}
	 * @return the sanitized target, or the input unchanged when it is {@code null} or
	 * empty
	 */
	public static String sanitizeTarget(final String rawTarget) {
		if (rawTarget == null || rawTarget.isEmpty()) {
			return rawTarget;
		}
		String sanitized = rawTarget;
		final int queryIndex = sanitized.indexOf('?');
		if (queryIndex >= 0) {
			sanitized = sanitized.substring(0, queryIndex);
		}
		final int schemeIndex = sanitized.indexOf("://");
		if (schemeIndex >= 0) {
			final int pathIndex = sanitized.indexOf('/', schemeIndex + 3);
			final int authorityEnd = (pathIndex >= 0) ? pathIndex : sanitized.length();
			final int atIndex = sanitized.indexOf('@', schemeIndex + 3);
			if (atIndex >= 0 && atIndex < authorityEnd) {
				sanitized = sanitized.substring(0, schemeIndex + 3) + REDACTED + sanitized.substring(atIndex);
			}
		}
		return sanitized;
	}

	/**
	 * Sanitizes a stdio command line: only {@code argv[0]} and flag names survive,
	 * argument values are dropped. A flag carrying an inline value
	 * ({@code --api-key=SECRET}) is reduced to the flag name; a separate value token
	 * ({@code --api-key SECRET}) is dropped because it does not start with {@code -}.
	 * @param command the executable, may be {@code null}
	 * @param args the raw arguments, may be {@code null} or empty
	 * @return the sanitized command line, or the input when the command is {@code null}
	 * or empty
	 */
	public static String sanitizeCommand(final String command, final List<String> args) {
		if (command == null || command.isEmpty()) {
			return command;
		}
		if (args == null || args.isEmpty()) {
			return command;
		}
		final StringBuilder sb = new StringBuilder(command);
		for (final String arg : args) {
			if (arg == null || !arg.startsWith("-")) {
				continue;
			}
			sb.append(' ');
			final int equalsIndex = arg.indexOf('=');
			sb.append((equalsIndex >= 0) ? arg.substring(0, equalsIndex) : arg);
		}
		return sb.toString();
	}

}
