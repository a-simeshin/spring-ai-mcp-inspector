/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.dto;

import java.util.List;

/**
 * Envelope for {@code GET /mcp-inspector/api/roots} and the body of
 * {@code PUT /mcp-inspector/api/roots}.
 *
 * @param roots ordered list of roots advertised to the MCP server
 */
public record RootsDto(List<RootDto> roots) {
}
