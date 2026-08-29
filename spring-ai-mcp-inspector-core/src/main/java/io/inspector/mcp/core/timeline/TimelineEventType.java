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
 * Discriminator for the kind of event that was recorded on the timeline.
 *
 * <p>
 * Each value corresponds to a JSON-RPC or stream event category emitted by the
 * {@link McpTrafficRecorder}.
 *
 * @author Artem Simeshin
 */
public enum TimelineEventType {

	/**
	 * A JSON-RPC request sent from the browser to the target MCP server.
	 */
	MCP_JSONRPC_REQUEST("mcp.jsonrpc.request"),

	/**
	 * A JSON-RPC response (success or error) received from the target MCP server.
	 */
	MCP_JSONRPC_RESPONSE("mcp.jsonrpc.response"),

	/**
	 * A JSON-RPC notification (no {@code id}) sent or received.
	 */
	MCP_JSONRPC_NOTIFICATION("mcp.jsonrpc.notification"),

	/**
	 * A streamable HTTP streaming event (e.g. SSE chunk).
	 */
	MCP_STREAM_EVENT("mcp.stream.event");

	private final String wireName;

	TimelineEventType(final String wireName) {
		this.wireName = wireName;
	}

	/**
	 * Returns the wire-level name used in the event payload.
	 * @return the wire name (never {@code null})
	 */
	public String wireName() {
		return this.wireName;
	}

}
