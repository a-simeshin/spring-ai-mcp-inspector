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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for
 * {@link McpTrafficRecorder#recordConnectFailed(String, String, String, List, String, String)}
 * (issue #250): the wire payload shape of the recorded {@code MCP_CONNECT_FAILED} event
 * and the MDC hygiene of the append.
 */
@DisplayName("McpTrafficRecorder connect-failed recording")
class McpTrafficRecorderConnectFailedTests {

	private CaptureTimelineService timeline;

	private McpTrafficRecorder recorder;

	@BeforeEach
	void setUp() {
		this.timeline = new CaptureTimelineService();
		this.recorder = new McpTrafficRecorder(this.timeline);
	}

	@AfterEach
	void tearDown() {
		MDC.clear();
	}

	@Test
	@DisplayName("recordConnectFailed appends one MCP_CONNECT_FAILED event with the probe fields")
	void recordsConnectFailedEventWithProbeFields() {
		this.recorder.recordConnectFailed("s-1", "not_found", "sse_only", List.of("/mcp", "/sse"), "alive",
				"http://127.0.0.1:9/mcp");
		final var events = this.timeline.query(TimelineQuery.all());
		assertThat(events).hasSize(1);
		final var event = events.get(0);
		assertThat(event.type()).isEqualTo(TimelineEventType.MCP_CONNECT_FAILED);
		assertThat(event.sessionId()).isEqualTo("s-1");
		assertThat(event.correlationId()).isNotBlank();
		assertThat(event.payload().path("reason").asText()).isEqualTo("not_found");
		assertThat(event.payload().path("hintKind").asText()).isEqualTo("sse_only");
		assertThat(event.payload().path("probedPaths").toString()).isEqualTo("[\"/mcp\",\"/sse\"]");
		assertThat(event.payload().path("probeOutcome").asText()).isEqualTo("alive");
		assertThat(event.payload().path("url").asText()).isEqualTo("http://127.0.0.1:9/mcp");
	}

	@Test
	@DisplayName("a throwing timeline keeps the caller's MDC value intact")
	void throwingTimelineKeepsMdcIntact() {
		MDC.put("mcp.correlationId", "prior-value");
		final McpTrafficRecorder broken = new McpTrafficRecorder(new ThrowingTimelineService());
		assertThatThrownBy(
				() -> broken.recordConnectFailed("s-1", "unknown", null, List.of(), "not_run", "http://up/mcp"))
			.isInstanceOf(IllegalStateException.class);
		assertThat(MDC.get("mcp.correlationId")).isEqualTo("prior-value");
	}

	/** In-memory timeline capture for assertions. */
	static final class CaptureTimelineService implements TimelineService {

		private final List<TimelineEvent> events = new ArrayList<>();

		@Override
		public void append(final TimelineEvent event) {
			this.events.add(event);
		}

		@Override
		public List<TimelineEvent> query(final TimelineQuery query) {
			return List.copyOf(this.events);
		}

		@Override
		public void clear() {
			this.events.clear();
		}

	}

	/** Timeline stub whose append always fails. */
	static final class ThrowingTimelineService implements TimelineService {

		@Override
		public void append(final TimelineEvent event) {
			throw new IllegalStateException("timeline broken");
		}

		@Override
		public List<TimelineEvent> query(final TimelineQuery query) {
			return List.of();
		}

		@Override
		public void clear() {
		}

	}

}
