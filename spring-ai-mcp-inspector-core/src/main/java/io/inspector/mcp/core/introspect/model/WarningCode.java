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
 * Codes of the warnings an introspection report can carry.
 *
 * <p>
 * All codes map to severity {@code "warning"} via {@link #defaultSeverity()}; the
 * {@code "error"} severity is reserved for future use.
 *
 * @author Artem Simeshin
 */
public enum WarningCode {

	/** Schema references a {@code $ref} pointing outside the document. */
	EXTERNAL_REF,

	/** Schema references a local {@code $ref} that does not resolve. */
	UNRESOLVED_REF,

	/** Schema declares a union type ({@code anyOf}/{@code oneOf}). */
	UNION_TYPE,

	/** Class declares {@code @McpTool}/{@code @McpResource} methods but is not a bean. */
	OUTSIDE_COMPONENT_SCAN,

	/** The application context declares no MCP tools or resources. */
	NO_MCP_ELEMENTS;

	/**
	 * Default severity for this code. All codes are {@code "warning"} today; {@code
	 * "error"} is reserved and unused.
	 * @return the severity string
	 */
	public String defaultSeverity() {
		return "warning";
	}

}
