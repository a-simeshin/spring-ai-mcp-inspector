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

package io.inspector.mcp.core.introspect.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.util.Assert;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.introspect.model.SchemaWarning;
import io.inspector.mcp.core.introspect.model.WarningCode;

/**
 * Detects JSON-schema constructs that are known to break MCP clients.
 *
 * <p>
 * Walks a schema recursively and reports {@code $ref} pointers that are external or
 * unresolvable inside the document, plus {@code anyOf}/{@code oneOf} union types. Warning
 * paths are RFC-6901 JSON Pointers prefixed with the schema section (R-PATH v11), e.g.
 * {@code inputSchema/properties/payload/$ref}.
 *
 * @author Artem Simeshin
 */
public class JsonSchemaCompatibilityChecker {

	/**
	 * Checks a schema for client-incompatible constructs.
	 * @param elementName name of the tool/resource the schema belongs to
	 * @param schema the schema document to walk
	 * @param pathPrefix prefix for warning paths ({@code "inputSchema"} or
	 * {@code "outputSchema"})
	 * @return the detected warnings, empty when the schema is compatible
	 */
	public List<SchemaWarning> check(final String elementName, final JsonNode schema, final String pathPrefix) {
		Assert.notNull(schema, "schema cannot be null");
		Assert.notNull(pathPrefix, "pathPrefix cannot be null");
		final List<SchemaWarning> warnings = new ArrayList<>();
		walk(elementName, schema, schema, pathPrefix, "", warnings);
		return warnings;
	}

	private void walk(final String elementName, final JsonNode root, final JsonNode node, final String pathPrefix,
			final String pointer, final List<SchemaWarning> warnings) {
		if (node.isObject()) {
			walkObject(elementName, root, (ObjectNode) node, pathPrefix, pointer, warnings);
		}
		else if (node.isArray()) {
			final ArrayNode array = (ArrayNode) node;
			for (int i = 0; i < array.size(); i++) {
				walk(elementName, root, array.get(i), pathPrefix, pointer + "/" + i, warnings);
			}
		}
	}

	private void walkObject(final String elementName, final JsonNode root, final ObjectNode object,
			final String pathPrefix, final String pointer, final List<SchemaWarning> warnings) {
		final JsonNode ref = object.get("$ref");
		if (ref != null) {
			checkRef(elementName, root, ref, pathPrefix, pointer, warnings);
		}
		for (final Map.Entry<String, JsonNode> property : object.properties()) {
			final String key = property.getKey();
			if ("anyOf".equals(key) || "oneOf".equals(key)) {
				warnings.add(warning(WarningCode.UNION_TYPE, elementName, pathPrefix + pointer + "/" + key,
						"Schema declares a union type (" + key + ") that some MCP clients do not support"));
			}
			walk(elementName, root, property.getValue(), pathPrefix, pointer + "/" + escape(key), warnings);
		}
	}

	private void checkRef(final String elementName, final JsonNode root, final JsonNode ref, final String pathPrefix,
			final String pointer, final List<SchemaWarning> warnings) {
		if (!ref.isTextual()) {
			return;
		}
		final String refValue = ref.asText();
		if (!refValue.startsWith("#/")) {
			warnings.add(warning(WarningCode.EXTERNAL_REF, elementName, pathPrefix + pointer + "/$ref",
					"Schema references an external $ref that MCP clients may not resolve: " + refValue));
		}
		else if (!resolves(root, refValue)) {
			warnings.add(warning(WarningCode.UNRESOLVED_REF, elementName, pathPrefix + pointer + "/$ref",
					"Schema references a local $ref with no target in the document: " + refValue));
		}
	}

	private static boolean resolves(final JsonNode root, final String ref) {
		JsonNode current = root;
		for (final String segment : ref.substring(2).split("/", -1)) {
			current = resolveSegment(current, unescape(segment));
			if (current == null) {
				return false;
			}
		}
		return true;
	}

	private static JsonNode resolveSegment(final JsonNode node, final String segment) {
		if (node.isArray()) {
			if (segment.isEmpty() || segment.length() > 10 || !segment.chars().allMatch(Character::isDigit)) {
				return null;
			}
			final long index = Long.parseLong(segment);
			return (index <= Integer.MAX_VALUE) ? node.get((int) index) : null;
		}
		return node.get(segment);
	}

	private static SchemaWarning warning(final WarningCode code, final String elementName, final String pointer,
			final String message) {
		return new SchemaWarning(code, code.defaultSeverity(), elementName, pointer, message);
	}

	private static String escape(final String segment) {
		return segment.replace("~", "~0").replace("/", "~1");
	}

	private static String unescape(final String segment) {
		return segment.replace("~1", "/").replace("~0", "~");
	}

}
