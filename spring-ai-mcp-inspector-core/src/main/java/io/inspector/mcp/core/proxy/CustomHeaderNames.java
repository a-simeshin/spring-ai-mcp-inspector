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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the {@code x-custom-auth-headers} request header that names the custom headers
 * whose inbound values should be forwarded verbatim to the upstream MCP server.
 *
 * <p>
 * Wire contract: a JSON array of strings, exactly as the inspector UI sends it
 * ({@code JSON.stringify(names)} in the vendored client's {@code useConnection}). The
 * proxy used to split the raw value on commas, so every name the UI sent arrived wrapped
 * in JSON brackets and quotes, the lookups found nothing, and the custom headers were
 * silently dropped.
 *
 * <p>
 * Parsing is fail-fast: a header that is present but not a JSON array of strings is a
 * client bug, and the callers translate the {@link IllegalArgumentException} into an
 * explicit 400 response instead of silently forwarding no custom headers.
 *
 * @author Artem Simeshin
 */
public final class CustomHeaderNames {

	/** The request header carrying the JSON array of custom header names. */
	public static final String HEADER = "x-custom-auth-headers";

	private static final JsonMapper MAPPER = new JsonMapper();

	private CustomHeaderNames() {
	}

	/**
	 * Parses the raw {@code x-custom-auth-headers} value into header names.
	 * @param named the raw header value, {@code null} or blank when the client sends no
	 * custom headers
	 * @return the parsed names in wire order (blank entries skipped), empty when the
	 * header is absent or blank
	 * @throws IllegalArgumentException when the value is present but is not a JSON array
	 * of strings (the message names the offending shape, never a header value)
	 */
	public static List<String> parse(final String named) {
		if (named == null || named.isBlank()) {
			return List.of();
		}
		final JsonNode root;
		try {
			root = MAPPER.readTree(named);
		}
		catch (final JacksonException ex) {
			throw new IllegalArgumentException(
					"x-custom-auth-headers must be a JSON array of header names, but is not valid JSON", ex);
		}
		if (!root.isArray()) {
			throw new IllegalArgumentException(
					"x-custom-auth-headers must be a JSON array of header names, but is " + nodeKind(root));
		}
		final List<String> names = new ArrayList<>();
		for (final JsonNode element : root) {
			if (!element.isTextual()) {
				throw new IllegalArgumentException(
						"x-custom-auth-headers must be a JSON array of header names, but contains "
								+ nodeKind(element));
			}
			final String name = element.asText().trim();
			if (!name.isEmpty()) {
				names.add(name);
			}
		}
		return names;
	}

	private static String nodeKind(final JsonNode node) {
		if (node.isObject()) {
			return "an object";
		}
		if (node.isTextual()) {
			return "a string";
		}
		if (node.isNumber()) {
			return "a number";
		}
		if (node.isBoolean()) {
			return "a boolean";
		}
		if (node.isNull()) {
			return "null";
		}
		return "not a string";
	}

	/**
	 * Resolves the inbound value of every parsed custom header name.
	 * @param named the raw {@code x-custom-auth-headers} value
	 * @param valueLookup maps a parsed header name to its inbound value, returning
	 * {@code null} when the request does not carry that header
	 * @return a map of custom header name to inbound value (never {@code null}); names
	 * whose lookup returns {@code null} are omitted
	 * @throws IllegalArgumentException see {@link #parse(String)}
	 */
	public static Map<String, String> resolve(final String named, final UnaryOperator<String> valueLookup) {
		final List<String> names = parse(named);
		if (names.isEmpty()) {
			return Map.of();
		}
		final Map<String, String> out = new LinkedHashMap<>();
		for (final String name : names) {
			final String value = valueLookup.apply(name);
			if (value != null) {
				out.put(name, value);
			}
		}
		return out;
	}

}
