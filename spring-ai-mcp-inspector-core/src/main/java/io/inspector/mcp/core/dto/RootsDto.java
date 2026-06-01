/*
 * Copyright 2025-present the original author or authors.
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

package io.inspector.mcp.core.dto;

import java.util.List;

/**
 * Envelope for {@code GET /mcp-inspector/api/roots} and the body of
 * {@code PUT /mcp-inspector/api/roots}.
 *
 * @param roots ordered list of roots advertised to the MCP server
 * @author Artem Simeshin
 */
public record RootsDto(List<RootDto> roots) {
}
