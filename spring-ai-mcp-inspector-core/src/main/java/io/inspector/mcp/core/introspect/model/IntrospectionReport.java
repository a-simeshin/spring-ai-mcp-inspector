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

import java.util.List;

/**
 * Result of introspecting an {@code ApplicationContext}: every declared MCP tool and
 * resource with its provenance, plus a single top-level warning list (R-ELEMENT v11).
 *
 * <p>
 * When the context declares no tools and no resources the report carries exactly one
 * {@link WarningCode#NO_MCP_ELEMENTS} warning and never fails (HTTP 200 contract).
 *
 * @param tools declared tools ({@link McpElementKind#TOOL})
 * @param resources declared resources and resource templates
 * @param warnings top-level warnings
 * @author Artem Simeshin
 */
public record IntrospectionReport(List<McpElementInfo> tools, List<McpElementInfo> resources,
		List<SchemaWarning> warnings) {

}
