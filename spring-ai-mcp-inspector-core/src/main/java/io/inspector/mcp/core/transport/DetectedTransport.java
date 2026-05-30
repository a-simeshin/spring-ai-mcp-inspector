/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.transport;

/**
 * Result of MCP transport auto-detection.
 *
 * @param type detected transport type
 * @param endpoint primary MCP endpoint path (nullable for
 * {@link TransportType#STDIO_NO_HTTP})
 * @param messageEndpoint message endpoint path (only populated for
 * {@link TransportType#SSE})
 * @param stack web stack label: {@code WEBMVC} / {@code WEBFLUX} / {@code STDIO}
 */
public record DetectedTransport(TransportType type, String endpoint, String messageEndpoint, String stack) {
}
