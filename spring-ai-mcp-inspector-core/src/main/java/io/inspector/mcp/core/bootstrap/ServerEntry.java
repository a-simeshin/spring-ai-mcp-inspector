/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.bootstrap;

import java.util.Map;

/**
 * Pre-populated MCP server entry the inspector UI will offer in its picker. Carried as
 * part of the {@link InspectorBootstrap} payload.
 *
 * <p>
 * The canonical constructor defensively copies (and null-coalesces) the {@code headers}
 * map so callers cannot mutate it post-construction.
 *
 * @param name human-readable label (never {@code null})
 * @param url server URL (never {@code null})
 * @param type transport type identifier ({@code "sse"}, {@code "streamable-http"}, ...)
 * @param headers per-request headers map (defensive-copied; never {@code null} after
 * construction)
 */
public record ServerEntry(String name, String url, String type, Map<String, String> headers) {

	public ServerEntry {
		headers = headers == null ? Map.of() : Map.copyOf(headers);
	}
}
