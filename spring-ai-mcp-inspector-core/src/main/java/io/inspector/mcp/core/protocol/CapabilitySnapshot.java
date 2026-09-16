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

package io.inspector.mcp.core.protocol;

import java.time.Instant;
import java.util.Map;

/**
 * Snapshot of the protocol capability data extracted from a JSON-RPC request or
 * response's {@code _meta} property (stateless protocol) or from the
 * {@code InitializeResult} (legacy session protocol).
 *
 * <p>
 * In the 2026-07-28 stateless protocol the server's capabilities, {@code protocolVersion}
 * and {@code clientInfo} travel inside {@code _meta} on every request instead of in a
 * dedicated {@code initialize} handshake. This record captures the latest such snapshot
 * for the UI to display and for proxy-internal routing decisions.
 *
 * @param protocolVersion the MCP protocol version string, e.g. {@code "2026-07-28"} or
 * {@code "2025-11-25"}
 * @param clientName the name of the MCP client implementation extracted from
 * {@code _meta.clientInfo.name}; may be {@code null}
 * @param clientVersion the version of the MCP client implementation extracted from
 * {@code _meta.clientInfo.version}; may be {@code null}
 * @param capabilities the server's capabilities as a raw map; never {@code null}
 * @param sourceRequestId the JSON-RPC request id from which this snapshot was extracted;
 * may be {@code null}
 * @param receivedAt the instant at which this snapshot was captured; never {@code null}
 * @param serverUrl the target MCP server URL; may be {@code null}
 * @author Artem Simeshin
 */
public record CapabilitySnapshot(String protocolVersion, String clientName, String clientVersion,
		Map<String, Object> capabilities, String sourceRequestId, Instant receivedAt, String serverUrl) {

	/**
	 * Compact constructor that defends against null {@code receivedAt} and
	 * {@code capabilities}.
	 */
	public CapabilitySnapshot {
		if (receivedAt == null) {
			receivedAt = Instant.now();
		}
		if (capabilities == null) {
			capabilities = Map.of();
		}
	}

}
