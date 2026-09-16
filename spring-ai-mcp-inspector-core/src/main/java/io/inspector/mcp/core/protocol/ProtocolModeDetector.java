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
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Detects the {@link ProtocolMode} from HTTP headers and JSON-RPC {@code _meta} payloads.
 *
 * <p>
 * The detection order is:
 * <ol>
 * <li><b>Header-based:</b> if the {@code MCP-Protocol-Version} header equals
 * {@code "2026-07-28"}, the mode is {@link ProtocolMode#STATELESS}.</li>
 * <li><b>Meta fallback:</b> if the {@code MCP-Protocol-Version} header is absent but the
 * JSON-RPC request/response {@code _meta} contains a {@code "protocolVersion"} field AND
 * no {@code Mcp-Session-Id} header is present, the mode is
 * {@link ProtocolMode#STATELESS}.</li>
 * <li><b>Default:</b> everything else is {@link ProtocolMode#LEGACY_SESSION}.</li>
 * </ol>
 *
 * <p>
 * This class also provides a convenience method
 * {@link #extractCapabilitySnapshot(Map, String, String)} that builds a
 * {@link CapabilitySnapshot} from {@code _meta} data, suitable for storing the latest
 * protocol state on a session.
 *
 * @author Artem Simeshin
 */
public final class ProtocolModeDetector {

	/**
	 * The MCP protocol version header name.
	 */
	public static final String MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

	/**
	 * The legacy session header name.
	 */
	public static final String MCP_SESSION_ID_HEADER = "Mcp-Session-Id";

	/**
	 * The stateless protocol version string.
	 */
	public static final String STATELESS_PROTOCOL_VERSION = "2026-07-28";

	/**
	 * The legacy protocol version string.
	 */
	public static final String LEGACY_PROTOCOL_VERSION = "2025-11-25";

	private ProtocolModeDetector() {
	}

	/**
	 * Detects the {@link ProtocolMode} from HTTP headers alone. This is the primary
	 * detection path.
	 * <p>
	 * Checks the {@code MCP-Protocol-Version} header. If it equals {@code "2026-07-28"},
	 * returns {@link ProtocolMode#STATELESS}. Otherwise returns
	 * {@link ProtocolMode#LEGACY_SESSION}.
	 * <p>
	 * Callers that also have access to the JSON-RPC {@code _meta} should use
	 * {@link #detect(Map, Map)} instead, which applies the meta fallback when the header
	 * is absent.
	 * @param headers the HTTP headers (case-insensitive lookup); may be {@code null} or
	 * empty
	 * @return the detected mode, never {@code null}
	 */
	public static ProtocolMode detectFromHeaders(final Map<String, String> headers) {
		if (headers == null || headers.isEmpty()) {
			return ProtocolMode.LEGACY_SESSION;
		}
		final String protocolVersion = headers.get(MCP_PROTOCOL_VERSION_HEADER);
		if (STATELESS_PROTOCOL_VERSION.equals(protocolVersion)) {
			return ProtocolMode.STATELESS;
		}
		return ProtocolMode.LEGACY_SESSION;
	}

	/**
	 * Detects the {@link ProtocolMode} from HTTP headers and an optional JSON-RPC
	 * {@code _meta} payload.
	 * <p>
	 * First tries header-based detection ({@link #detectFromHeaders(Map)}). If the header
	 * is absent or does not indicate stateless mode, and a {@code _meta} payload is
	 * provided that contains a {@code "protocolVersion"} key, and no
	 * {@code Mcp-Session-Id} header is present, returns {@link ProtocolMode#STATELESS}.
	 * Otherwise returns {@link ProtocolMode#LEGACY_SESSION}.
	 * @param headers the HTTP headers; may be {@code null} or empty
	 * @param meta the JSON-RPC {@code _meta} payload as a map; may be {@code null} or
	 * empty
	 * @return the detected mode, never {@code null}
	 */
	public static ProtocolMode detect(final Map<String, String> headers, final Map<String, Object> meta) {
		// Primary: header-based detection
		final String headerVersion = getHeader(headers, MCP_PROTOCOL_VERSION_HEADER);
		if (STATELESS_PROTOCOL_VERSION.equals(headerVersion)) {
			return ProtocolMode.STATELESS;
		}
		// Fallback: _meta-based detection only when the version header is completely
		// absent
		if (headerVersion == null && hasNoSessionHeader(headers) && hasProtocolVersionInMeta(meta)) {
			return ProtocolMode.STATELESS;
		}
		return ProtocolMode.LEGACY_SESSION;
	}

	/**
	 * Extracts a {@link CapabilitySnapshot} from a JSON-RPC {@code _meta} payload.
	 * <p>
	 * Returns {@link Optional#empty()} when {@code meta} is {@code null} or does not
	 * contain a recognizable protocol version.
	 * @param meta the JSON-RPC {@code _meta} payload as a map; may be {@code null}
	 * @param sourceRequestId the JSON-RPC request id from which this snapshot was
	 * extracted; may be {@code null}
	 * @param serverUrl the target MCP server URL; may be {@code null}
	 * @return a {@link CapabilitySnapshot} if {@code _meta} contains a protocol version,
	 * or {@link Optional#empty()} otherwise
	 */
	@SuppressWarnings("unchecked")
	public static Optional<CapabilitySnapshot> extractCapabilitySnapshot(final Map<String, Object> meta,
			final String sourceRequestId, final String serverUrl) {
		if (meta == null || meta.isEmpty()) {
			return Optional.empty();
		}
		final Object versionObj = meta.get("protocolVersion");
		if (!(versionObj instanceof final String protocolVersion) || protocolVersion.isBlank()) {
			return Optional.empty();
		}
		String clientName = null;
		String clientVersion = null;
		final Object clientInfoObj = meta.get("clientInfo");
		if (clientInfoObj instanceof final Map<?, ?> clientInfo) {
			final Object nameObj = clientInfo.get("name");
			if (nameObj instanceof final String name) {
				clientName = name;
			}
			final Object verObj = clientInfo.get("version");
			if (verObj instanceof final String ver) {
				clientVersion = ver;
			}
		}
		Map<String, Object> capabilities = Collections.emptyMap();
		final Object capsObj = meta.get("capabilities");
		if (capsObj instanceof final Map<?, ?> caps) {
			capabilities = (Map<String, Object>) caps;
		}
		return Optional.of(new CapabilitySnapshot(protocolVersion, clientName, clientVersion, capabilities,
				sourceRequestId, Instant.now(), serverUrl));
	}

	/**
	 * Gets a header value by case-insensitive lookup.
	 * @param headers the HTTP headers; may be {@code null}
	 * @param name the header name to look up
	 * @return the header value, or {@code null} if not present
	 */
	static String getHeader(final Map<String, String> headers, final String name) {
		if (headers == null) {
			return null;
		}
		final String direct = headers.get(name);
		if (direct != null) {
			return direct;
		}
		// Case-insensitive fallback: some HTTP frameworks normalize headers
		for (final Map.Entry<String, String> entry : headers.entrySet()) {
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Returns {@code true} when no {@code Mcp-Session-Id} header is present.
	 * @param headers the HTTP headers; may be {@code null}
	 * @return {@code true} when no session header is present
	 */
	private static boolean hasNoSessionHeader(final Map<String, String> headers) {
		return getHeader(headers, MCP_SESSION_ID_HEADER) == null;
	}

	/**
	 * Returns {@code true} when {@code meta} is non-null, non-empty and contains a
	 * non-blank {@code "protocolVersion"} key.
	 * @param meta the JSON-RPC {@code _meta} payload; may be {@code null}
	 * @return {@code true} when {@code meta} contains a non-blank {@code protocolVersion}
	 */
	private static boolean hasProtocolVersionInMeta(final Map<String, Object> meta) {
		if (meta == null || meta.isEmpty()) {
			return false;
		}
		final Object versionObj = meta.get("protocolVersion");
		return versionObj instanceof final String version && !version.isBlank();
	}

}
