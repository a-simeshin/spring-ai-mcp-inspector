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

/**
 * A single warning attached to the introspection report.
 *
 * <p>
 * {@code path} follows RFC-6901 JSON Pointer with no dot and no {@code $} separator
 * (R-PATH v11): schema warnings point at the offending node inside the schema,
 * OUTSIDE_COMPONENT_SCAN and NO_MCP_ELEMENTS use {@code "$"}. {@code element} is the
 * exact element name the warning refers to (FQCN for OUTSIDE_COMPONENT_SCAN, empty string
 * for NO_MCP_ELEMENTS, R-ELEMENT v11).
 *
 * @param code the warning code
 * @param severity severity derived from {@link WarningCode#defaultSeverity()} (always
 * {@code "warning"} today)
 * @param element element the warning refers to
 * @param path RFC-6901 JSON Pointer of the offending node
 * @param message human-readable explanation
 * @author Artem Simeshin
 */
public record SchemaWarning(WarningCode code, String severity, String element, String path, String message) {

}
