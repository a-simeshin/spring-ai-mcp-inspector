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

package io.inspector.mcp.core.export;

import java.time.Instant;

/**
 * Immutable record of a single MCP tool call captured from the inspector History.
 *
 * @param toolName tool name as advertised by the MCP server (for example {@code echo})
 * @param method JSON-RPC method or tool invocation name (for example {@code tools/call})
 * @param arguments serialized JSON arguments passed to the tool, never {@code null}
 * @param result serialized JSON result returned by the tool, never {@code null}
 * @param timestamp ISO-8601 instant when the call was captured
 * @author Artem Simeshin
 */
public record ToolCallRecord(String toolName, String method, String arguments, String result, String timestamp) {

	public ToolCallRecord {
		if (toolName == null || toolName.isBlank()) {
			throw new IllegalArgumentException("toolName must not be blank");
		}
		if (method == null || method.isBlank()) {
			throw new IllegalArgumentException("method must not be blank");
		}
		if (arguments == null) {
			arguments = "{}";
		}
		if (result == null) {
			result = "{}";
		}
		if (timestamp == null || timestamp.isBlank()) {
			timestamp = Instant.now().toString();
		}
	}

}
