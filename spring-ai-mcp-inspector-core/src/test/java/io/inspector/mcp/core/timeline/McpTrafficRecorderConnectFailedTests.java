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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link McpTrafficRecorder#recordConnectFailed} (issue #250): the
 * {@code MCP_CONNECT_FAILED} timeline event payload.
 */
@DisplayName("McpTrafficRecorder recordConnectFailed")
class McpTrafficRecorderConnectFailedTests {

	@Test
	@DisplayName("recordConnectFailed appends an MCP_CONNECT_FAILED event with the full payload")
	void appendsConnectFailedEvent() {
		final BoundedTimelineService timeline = new BoundedTimelineService(10);
		final McpTrafficRecorder recorder = new McpTrafficRecorder(timeline);

		recorder.recordConnectFailed("session-1", "not_found", "sse_only", List.of("/mcp", "/sse"), "alive",
				"http://127.0.0.1:9999/mcp");

		final List<TimelineEvent> events = timeline.query(TimelineQuery.all());
		assertThat(events).hasSize(1);
		final TimelineEvent event = events.get(0);
		assertThat(event.type()).isEqualTo(TimelineEventType.MCP_CONNECT_FAILED);
		assertThat(event.sessionId()).isEqualTo("session-1");
		assertThat(event.payload()).isNotNull();
		assertThat(event.payload().path("reason").asText()).isEqualTo("not_found");
		assertThat(event.payload().path("hintKind").asText()).isEqualTo("sse_only");
		assertThat(event.payload().path("probedPaths").size()).isEqualTo(2);
		assertThat(event.payload().path("probedPaths").get(0).asText()).isEqualTo("/mcp");
		assertThat(event.payload().path("probeOutcome").asText()).isEqualTo("alive");
		assertThat(event.payload().path("url").asText()).isEqualTo("http://127.0.0.1:9999/mcp");
	}

	@Test
	@DisplayName("hintKind null is serialized as null, not omitted")
	void nullHintKindSerialized() {
		final BoundedTimelineService timeline = new BoundedTimelineService(10);
		final McpTrafficRecorder recorder = new McpTrafficRecorder(timeline);

		recorder.recordConnectFailed(null, "unknown", null, List.of(), "timeout", "http://127.0.0.1:9999/mcp");

		final List<TimelineEvent> events = timeline.query(TimelineQuery.all());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).payload().has("hintKind")).isTrue();
		assertThat(events.get(0).payload().path("hintKind").isNull()).isTrue();
		assertThat(events.get(0).payload().path("probedPaths").size()).isEqualTo(0);
		assertThat(events.get(0).payload().path("probeOutcome").asText()).isEqualTo("timeout");
	}

}
