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
 * A raw JSON-RPC envelope relayed from the inspector UI to the loopback MCP client.
 *
 * @param jsonrpc JSON-RPC version (e.g. {@code "2.0"})
 * @param id request id; may be a number or string in JSON, so typed as {@link Object}
 * @param method MCP method name (e.g. {@code "tools/list"})
 * @param params request params; arbitrary JSON, typed as {@link Object}
 */
public record JsonRpcRelay(String jsonrpc, Object id, String method, Object params) {
}
