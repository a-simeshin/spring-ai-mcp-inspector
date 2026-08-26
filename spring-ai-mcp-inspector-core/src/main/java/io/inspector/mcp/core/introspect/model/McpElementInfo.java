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

package io.inspector.mcp.core.introspect.model;

import java.util.Map;

/**
 * A single tool or resource reported by the introspection.
 *
 * <p>
 * Tools carry {@code inputSchema} / {@code outputSchema} (both nullable); resources and
 * resource templates carry {@code uri} (the URI, or the URI template for
 * {@link McpElementKind#RESOURCE_TEMPLATE}) and {@code mimeType}. {@code source} is
 * always non-null (R-SOURCE v8).
 *
 * @param kind element discriminator
 * @param name element name (annotation name, or the method name when the annotation has
 * no name, R-NAME-FALLBACK)
 * @param description human-readable description, or {@code null}
 * @param inputSchema JSON-schema of the tool input, or {@code null} for resources
 * @param outputSchema JSON-schema of the tool output, or {@code null} for resources
 * @param uri resource URI / URI template, or {@code null} for tools
 * @param mimeType resource mime type, or {@code null} for tools
 * @param source provenance of the element, never {@code null}
 * @author Artem Simeshin
 */
public record McpElementInfo(McpElementKind kind, String name, String description, Map<String, Object> inputSchema,
		Map<String, Object> outputSchema, String uri, String mimeType, SourceInfo source) {

}
