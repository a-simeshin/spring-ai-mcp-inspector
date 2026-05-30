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
 * Optional payload for {@code POST /mcp-inspector/api/connect} used by the "External
 * Target" tab. When {@code externalCommand} is {@code null}, the inspector connects to
 * the same JVM via the loopback factory.
 *
 * @param externalCommand shell-style command to spawn an external MCP stdio server;
 * {@code null} means "use loopback target"
 */
public record ConnectRequest(String externalCommand) {
}
