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

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProtocolModeDetector}.
 *
 * @author Artem Simeshin
 */
class ProtocolModeDetectorTests {

	@Test
	void detectFromHeaders_statelessVersion_returnsStateless() {
		final Map<String, String> headers = Map.of(ProtocolModeDetector.MCP_PROTOCOL_VERSION_HEADER,
				ProtocolModeDetector.STATELESS_PROTOCOL_VERSION);
		assertThat(ProtocolModeDetector.detectFromHeaders(headers)).isEqualTo(ProtocolMode.STATELESS);
	}

	@Test
	void detectFromHeaders_legacyVersion_returnsLegacy() {
		final Map<String, String> headers = Map.of(ProtocolModeDetector.MCP_PROTOCOL_VERSION_HEADER,
				ProtocolModeDetector.LEGACY_PROTOCOL_VERSION);
		assertThat(ProtocolModeDetector.detectFromHeaders(headers)).isEqualTo(ProtocolMode.LEGACY_SESSION);
	}

	@Test
	void detectFromHeaders_noHeaders_returnsLegacy() {
		assertThat(ProtocolModeDetector.detectFromHeaders(null)).isEqualTo(ProtocolMode.LEGACY_SESSION);
		assertThat(ProtocolModeDetector.detectFromHeaders(Map.of())).isEqualTo(ProtocolMode.LEGACY_SESSION);
	}

	@Test
	void detectFromHeaders_unrelatedHeader_returnsLegacy() {
		final Map<String, String> headers = Map.of("Authorization", "Bearer tok");
		assertThat(ProtocolModeDetector.detectFromHeaders(headers)).isEqualTo(ProtocolMode.LEGACY_SESSION);
	}

	@Test
	void detect_metaFallbackWithProtocolVersionAndNoSessionHeader_returnsStateless() {
		final Map<String, Object> meta = Map.of("protocolVersion", ProtocolModeDetector.STATELESS_PROTOCOL_VERSION);
		assertThat(ProtocolModeDetector.detect(Map.of(), meta)).isEqualTo(ProtocolMode.STATELESS);
	}

	@Test
	void detect_metaFallbackWithSessionHeader_returnsLegacy() {
		final Map<String, Object> meta = Map.of("protocolVersion", ProtocolModeDetector.STATELESS_PROTOCOL_VERSION);
		final Map<String, String> headers = Map.of(ProtocolModeDetector.MCP_SESSION_ID_HEADER, "sess-123");
		assertThat(ProtocolModeDetector.detect(headers, meta)).isEqualTo(ProtocolMode.LEGACY_SESSION);
	}

	@Test
	void detect_metaFallbackWithoutProtocolVersion_returnsLegacy() {
		final Map<String, Object> meta = Map.of("capabilities", Map.of());
		assertThat(ProtocolModeDetector.detect(Map.of(), meta)).isEqualTo(ProtocolMode.LEGACY_SESSION);
	}

	@Test
	void detect_metaFallbackNullMeta_returnsLegacy() {
		assertThat(ProtocolModeDetector.detect(Map.of(), null)).isEqualTo(ProtocolMode.LEGACY_SESSION);
	}

	@Test
	void detect_headerOverridesMeta_returnsStateless() {
		final Map<String, Object> meta = Map.of("protocolVersion", ProtocolModeDetector.LEGACY_PROTOCOL_VERSION);
		final Map<String, String> headers = Map.of(ProtocolModeDetector.MCP_PROTOCOL_VERSION_HEADER,
				ProtocolModeDetector.STATELESS_PROTOCOL_VERSION);
		assertThat(ProtocolModeDetector.detect(headers, meta)).isEqualTo(ProtocolMode.STATELESS);
	}

	@Test
	void detect_headerOverridesMeta_returnsLegacy() {
		final Map<String, Object> meta = Map.of("protocolVersion", ProtocolModeDetector.STATELESS_PROTOCOL_VERSION);
		final Map<String, String> headers = Map.of(ProtocolModeDetector.MCP_PROTOCOL_VERSION_HEADER,
				ProtocolModeDetector.LEGACY_PROTOCOL_VERSION);
		assertThat(ProtocolModeDetector.detect(headers, meta)).isEqualTo(ProtocolMode.LEGACY_SESSION);
	}

	@Test
	void getHeader_caseInsensitive() {
		final Map<String, String> headers = Map.of("mcp-protocol-version", "2026-07-28");
		assertThat(ProtocolModeDetector.getHeader(headers, "MCP-Protocol-Version")).isEqualTo("2026-07-28");
	}

	@Test
	void extractCapabilitySnapshot_validMeta_returnsSnapshot() {
		final Map<String, Object> meta = Map.of("protocolVersion", "2026-07-28", "clientInfo",
				Map.of("name", "test-client", "version", "1.0.0"), "capabilities",
				Map.of("tools", Map.of("listChanged", true)));
		final var snapshot = ProtocolModeDetector.extractCapabilitySnapshot(meta, "req-1", "http://localhost:8080/mcp");
		assertThat(snapshot).isPresent();
		assertThat(snapshot.get().protocolVersion()).isEqualTo("2026-07-28");
		assertThat(snapshot.get().clientName()).isEqualTo("test-client");
		assertThat(snapshot.get().clientVersion()).isEqualTo("1.0.0");
		assertThat(snapshot.get().capabilities()).containsKey("tools");
	}

	@Test
	void extractCapabilitySnapshot_nullMeta_returnsEmpty() {
		assertThat(ProtocolModeDetector.extractCapabilitySnapshot(null, "req-1", "http://localhost:8080/mcp"))
			.isEmpty();
	}

	@Test
	void extractCapabilitySnapshot_noProtocolVersion_returnsEmpty() {
		final Map<String, Object> meta = Map.of("capabilities", Map.of());
		assertThat(ProtocolModeDetector.extractCapabilitySnapshot(meta, "req-1", "http://localhost:8080/mcp"))
			.isEmpty();
	}

	@Test
	void extractCapabilitySnapshot_noClientInfo_returnsSnapshotWithNullClient() {
		final Map<String, Object> meta = Map.of("protocolVersion", "2026-07-28");
		final var snapshot = ProtocolModeDetector.extractCapabilitySnapshot(meta, "req-1", "http://localhost:8080/mcp");
		assertThat(snapshot).isPresent();
		assertThat(snapshot.get().clientName()).isNull();
		assertThat(snapshot.get().clientVersion()).isNull();
	}

	@Test
	void extractCapabilitySnapshot_noCapabilities_returnsSnapshotWithEmptyCapabilities() {
		final Map<String, Object> meta = Map.of("protocolVersion", "2026-07-28");
		final var snapshot = ProtocolModeDetector.extractCapabilitySnapshot(meta, "req-1", "http://localhost:8080/mcp");
		assertThat(snapshot).isPresent();
		assertThat(snapshot.get().capabilities()).isEmpty();
	}

}
