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

/**
 * UI-visible MCP root entry. Mirrors the wire shape of
 * {@code io.modelcontextprotocol.spec.McpSchema$Root} but is decoupled from the MCP SDK
 * so the JSON contract is stable.
 *
 * @param uri root URI (e.g. {@code file:///workspace})
 * @param name display name; may be {@code null}
 */
public record RootDto(String uri, String name) {
}
