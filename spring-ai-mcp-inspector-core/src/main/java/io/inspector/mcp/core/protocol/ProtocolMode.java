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

/**
 * Operating mode of the MCP protocol detected for the current connection.
 *
 * <p>
 * {@link #LEGACY_SESSION} is the 2025-11-25 and earlier behaviour: an explicit
 * {@code initialize}/{@code initialized} handshake establishes a session, and the
 * {@code Mcp-Session-Id} header is carried on every request.
 *
 * <p>
 * {@link #STATELESS} is the 2026-07-28 rewrite: SEP-2575 removes the handshake, SEP-2567
 * removes the {@code Mcp-Session-Id} header, and capabilities, {@code protocolVersion}
 * and {@code clientInfo} travel in {@code _meta} on every request. The
 * {@code MCP-Protocol-Version} header carries the version.
 *
 * @author Artem Simeshin
 */
public enum ProtocolMode {

	/** Classic session-based MCP protocol (2025-11-25 and earlier). */
	LEGACY_SESSION,

	/** Stateless MCP protocol (2026-07-28 and later). */
	STATELESS

}
