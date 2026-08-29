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

package io.inspector.mcp.core.timeline;

/**
 * Discriminator for the kind of event captured on the timeline.
 *
 * <p>
 * Each value maps to a distinct set of populated fields in {@link TimelineEvent}. Only
 * {@link #APP_LOG} carries log-level, logger-name, thread-name, message and throwable
 * fields; the MCP-related types carry request/response/error payloads.
 *
 * @author Artem Simeshin
 */
public enum TimelineEventType {

	/** An outgoing JSON-RPC request sent to the MCP server. */
	MCP_JSONRPC_REQUEST,

	/** A JSON-RPC response received from the MCP server. */
	MCP_JSONRPC_RESPONSE,

	/** A JSON-RPC notification received from the MCP server. */
	MCP_JSONRPC_NOTIFICATION,

	/** A streaming event (e.g. SSE chunk) from the MCP server. */
	MCP_STREAM_EVENT,

	/** An application log entry emitted by the host JVM. */
	APP_LOG

}
