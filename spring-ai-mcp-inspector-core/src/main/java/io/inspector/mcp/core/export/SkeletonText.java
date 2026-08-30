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

package io.inspector.mcp.core.export;

/**
 * Helpers for emitting string literals and comment-safe text into generated Java sources.
 *
 * @author Artem Simeshin
 */
final class SkeletonText {

	/**
	 * Maximum number of literal characters kept on one line of generated source before
	 * the literal wraps onto a {@code +} continuation line.
	 */
	private static final int MAX_LITERAL_WIDTH = 60;

	private SkeletonText() {
	}

	/**
	 * Renders {@code raw} as a Java string literal for generated sources: backslashes,
	 * quotes and control characters are escaped, and long content wraps over {@code +}
	 * continuation lines so generated lines stay readable.
	 * @param raw the raw string value
	 * @return a literal expression, for example {@code "{\"a\":1}"} or a multi-line
	 * concatenation of such literals
	 */
	static String stringLiteral(final String raw) {
		final StringBuilder literal = new StringBuilder("\"");
		int width = 0;
		for (int i = 0; i < raw.length(); i++) {
			final String unit = escapeUnit(raw.charAt(i));
			if (width > 0 && width + unit.length() > MAX_LITERAL_WIDTH) {
				literal.append("\"\n\t\t+ \"");
				width = 0;
			}
			literal.append(unit);
			width += unit.length();
		}
		return literal.append("\"").toString();
	}

	/**
	 * Makes a value safe for a single-line comment or Javadoc line in generated sources:
	 * control characters become spaces and block-comment terminators are neutralised.
	 * @param raw the raw value
	 * @return a value safe to interpolate into a single comment line
	 */
	static String commentSafe(final String raw) {
		return raw.replaceAll("\\p{Cntrl}", " ").replace("*/", "* /");
	}

	private static String escapeUnit(final char candidate) {
		return switch (candidate) {
			case '\\' -> "\\\\";
			case '"' -> "\\\"";
			case '\n' -> "\\n";
			case '\r' -> "\\r";
			case '\t' -> "\\t";
			default -> (candidate < 0x20) ? String.format("\\u%04x", (int) candidate) : String.valueOf(candidate);
		};
	}

}
