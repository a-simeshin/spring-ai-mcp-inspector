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

import java.util.Map;

/**
 * Returned by {@code GET /mcp-inspector/api/config}. Describes the detected MCP server
 * and supplies the auth token plus capability summary to the UI.
 *
 * @param transport detected transport name (e.g. {@code SSE})
 * @param endpoint primary endpoint path
 * @param messageEndpoint SSE-only message endpoint path; may be {@code null}
 * @param stack {@code WEBMVC} / {@code WEBFLUX} / {@code STDIO}
 * @param serverName MCP server {@code Implementation.name}
 * @param version MCP server {@code Implementation.version}
 * @param authToken bearer token the UI must send back on every API call
 * @param capabilities raw {@code ServerCapabilities} as a Jackson-friendly map
 */
public record ConfigDto(String transport, String endpoint, String messageEndpoint, String stack, String serverName,
		String version, String authToken, Map<String, Object> capabilities) {
}
