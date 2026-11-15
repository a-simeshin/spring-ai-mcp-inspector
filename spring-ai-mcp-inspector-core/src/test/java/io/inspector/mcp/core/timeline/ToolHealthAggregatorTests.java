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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ToolHealthAggregator}.
 *
 * <p>
 * Events are built in the exact wire shape recorded by {@link McpTrafficRecorder}: a raw
 * JSON-RPC request frame under {@code payload}, correlated to its response by
 * {@code correlationId}.
 */
class ToolHealthAggregatorTests {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final Instant BASE = Instant.parse("2026-11-15T12:00:00Z");

	private static TimelineEvent requestEvent(final String correlationId, final String tool, final Instant timestamp) {
		final ObjectNode payload = MAPPER.createObjectNode()
			.put("jsonrpc", "2.0")
			.put("id", 1)
			.put("method", "tools/call");
		if (tool != null) {
			payload.putObject("params").put("name", tool);
		}
		return new TimelineEvent(UUID.randomUUID().toString(), correlationId, "s-1",
				TimelineEventType.MCP_JSONRPC_REQUEST, timestamp, payload);
	}

	private static TimelineEvent responseWithResult(final String correlationId, final boolean isError,
			final Instant timestamp) {
		final ObjectNode payload = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1);
		final ObjectNode result = payload.putObject("result");
		result.putArray("content").addObject().put("type", "text").put("text", "done");
		result.put("isError", isError);
		return new TimelineEvent(UUID.randomUUID().toString(), correlationId, "s-1",
				TimelineEventType.MCP_JSONRPC_RESPONSE, timestamp, payload);
	}

	private static TimelineEvent jsonRpcErrorEvent(final String correlationId, final Instant timestamp) {
		final ObjectNode payload = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1);
		payload.putObject("error").put("code", -32603).put("message", "internal error");
		return new TimelineEvent(UUID.randomUUID().toString(), correlationId, "s-1",
				TimelineEventType.MCP_JSONRPC_RESPONSE, timestamp, payload);
	}

	private static TimelineEvent nonToolCallEvent(final Instant timestamp) {
		final ObjectNode payload = MAPPER.createObjectNode()
			.put("jsonrpc", "2.0")
			.put("id", 2)
			.put("method", "initialize");
		return new TimelineEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "s-1",
				TimelineEventType.MCP_JSONRPC_REQUEST, timestamp, payload);
	}

	private static Map<String, ToolHealthStat> byTool(final List<ToolHealthStat> stats) {
		return stats.stream().collect(Collectors.toMap(ToolHealthStat::method, Function.identity()));
	}

	@Nested
	@DisplayName("computeToolHealth")
	class ComputeToolHealth {

		@Test
		@DisplayName("returns an empty list for an empty buffer")
		void returnsEmptyForEmptyInput() {
			assertThat(ToolHealthAggregator.computeToolHealth(List.of())).isEmpty();
		}

		@Test
		@DisplayName("returns an empty list for null input")
		void returnsEmptyForNullInput() {
			assertThat(ToolHealthAggregator.computeToolHealth(null)).isEmpty();
		}

		@Test
		@DisplayName("ignores events that are not tools/call requests")
		void ignoresNonToolCallEvents() {
			assertThat(ToolHealthAggregator.computeToolHealth(List.of(nonToolCallEvent(BASE)))).isEmpty();
		}

		@Test
		@DisplayName("computes counts and errorRate correctly on a mixed set")
		void mixedSetCountsAndErrorRate() {
			// given: alpha: ok(100ms), tool error(200ms), json-rpc error(300ms),
			// unanswered; beta: two ok calls (10ms, 30ms). Newest first, as
			// BoundedTimelineService.query returns.
			final List<TimelineEvent> events = List.of(requestEvent("a4", "alpha", BASE.plusMillis(400)),
					requestEvent("b2", "beta", BASE.plusMillis(330)), requestEvent("a3", "alpha", BASE.plusMillis(300)),
					jsonRpcErrorEvent("a3", BASE.plusMillis(600)), requestEvent("b1", "beta", BASE.plusMillis(100)),
					responseWithResult("b1", false, BASE.plusMillis(110)),
					requestEvent("a2", "alpha", BASE.plusMillis(200)),
					responseWithResult("a2", true, BASE.plusMillis(400)),
					requestEvent("a1", "alpha", BASE.plusMillis(100)),
					responseWithResult("a1", false, BASE.plusMillis(200)),
					responseWithResult("b2", false, BASE.plusMillis(360)));
			// when
			final List<ToolHealthStat> stats = ToolHealthAggregator.computeToolHealth(events);
			// then
			assertThat(stats).hasSize(2);
			assertThat(stats).extracting(ToolHealthStat::method).containsExactly("alpha", "beta");
			final Map<String, ToolHealthStat> byName = byTool(stats);
			final ToolHealthStat alpha = byName.get("alpha");
			assertThat(alpha.calls()).isEqualTo(4);
			assertThat(alpha.errors()).isEqualTo(3);
			assertThat(alpha.noResponse()).isEqualTo(1);
			assertThat(alpha.errorRate()).isEqualTo(0.75);
			// answered durations: 100, 200, 300 -> nearest-rank p50 = 200, p95 = 300
			assertThat(alpha.p50Ms()).isEqualTo(200);
			assertThat(alpha.p95Ms()).isEqualTo(300);
			// most recent alpha call (a4) went unanswered
			assertThat(alpha.lastIsError()).isTrue();
			assertThat(alpha.lastCallAt()).isEqualTo(BASE.plusMillis(400).toEpochMilli());
			final ToolHealthStat beta = byName.get("beta");
			assertThat(beta.calls()).isEqualTo(2);
			assertThat(beta.errors()).isZero();
			assertThat(beta.noResponse()).isZero();
			assertThat(beta.errorRate()).isZero();
			// answered durations: 10, 30 -> nearest-rank p50 = 10, p95 = 30
			assertThat(beta.p50Ms()).isEqualTo(10);
			assertThat(beta.p95Ms()).isEqualTo(30);
			assertThat(beta.lastIsError()).isFalse();
			assertThat(beta.lastCallAt()).isEqualTo(BASE.plusMillis(330).toEpochMilli());
		}

		@Test
		@DisplayName("p50 and p95 match the manual nearest-rank calculation")
		void nearestRankPercentiles() {
			// given: four answered calls with durations 10, 20, 30, 40.
			// nearest-rank: p50 -> ceil(0.50 * 4) = 2 -> 20; p95 -> ceil(0.95 * 4) = 4
			// -> 40.
			final List<TimelineEvent> events = List.of(requestEvent("g4", "gamma", BASE.plusMillis(400)),
					responseWithResult("g4", false, BASE.plusMillis(440)),
					requestEvent("g3", "gamma", BASE.plusMillis(300)),
					responseWithResult("g3", false, BASE.plusMillis(330)),
					requestEvent("g2", "gamma", BASE.plusMillis(200)),
					responseWithResult("g2", false, BASE.plusMillis(220)),
					requestEvent("g1", "gamma", BASE.plusMillis(100)),
					responseWithResult("g1", false, BASE.plusMillis(110)));
			// when
			final List<ToolHealthStat> stats = ToolHealthAggregator.computeToolHealth(events);
			// then
			assertThat(stats).hasSize(1);
			assertThat(stats.get(0).p50Ms()).isEqualTo(20);
			assertThat(stats.get(0).p95Ms()).isEqualTo(40);
		}

		@Test
		@DisplayName("a tool with a single call has p50 = p95 = its duration")
		void singleCallPercentiles() {
			// given
			final List<TimelineEvent> events = List.of(requestEvent("s1", "solo", BASE),
					responseWithResult("s1", false, BASE.plusMillis(125)));
			// when
			final List<ToolHealthStat> stats = ToolHealthAggregator.computeToolHealth(events);
			// then
			assertThat(stats).hasSize(1);
			assertThat(stats.get(0).calls()).isEqualTo(1);
			assertThat(stats.get(0).p50Ms()).isEqualTo(125);
			assertThat(stats.get(0).p95Ms()).isEqualTo(125);
			assertThat(stats.get(0).lastIsError()).isFalse();
			assertThat(stats.get(0).lastCallAt()).isEqualTo(BASE.toEpochMilli());
		}

		@Test
		@DisplayName("an unanswered call counts in both noResponse and errors")
		void unansweredCallCountsAsError() {
			// given: request present, response evicted from the window
			final List<TimelineEvent> events = List.of(requestEvent("n1", "lonely", BASE));
			// when
			final List<ToolHealthStat> stats = ToolHealthAggregator.computeToolHealth(events);
			// then
			assertThat(stats).hasSize(1);
			final ToolHealthStat stat = stats.get(0);
			assertThat(stat.calls()).isEqualTo(1);
			assertThat(stat.noResponse()).isEqualTo(1);
			assertThat(stat.errors()).isEqualTo(1);
			assertThat(stat.errorRate()).isEqualTo(1.0);
			// unanswered calls carry no duration
			assertThat(stat.p50Ms()).isZero();
			assertThat(stat.p95Ms()).isZero();
			assertThat(stat.lastIsError()).isTrue();
			assertThat(stat.lastCallAt()).isEqualTo(BASE.toEpochMilli());
		}

		@Test
		@DisplayName("a JSON-RPC error response counts as an error but not as noResponse")
		void jsonRpcErrorIsErrorNotNoResponse() {
			// given
			final List<TimelineEvent> events = List.of(requestEvent("e1", "broken", BASE),
					jsonRpcErrorEvent("e1", BASE.plusMillis(50)));
			// when
			final List<ToolHealthStat> stats = ToolHealthAggregator.computeToolHealth(events);
			// then
			assertThat(stats).hasSize(1);
			assertThat(stats.get(0).errors()).isEqualTo(1);
			assertThat(stats.get(0).noResponse()).isZero();
			assertThat(stats.get(0).errorRate()).isEqualTo(1.0);
			// failed answers still have a recorded duration
			assertThat(stats.get(0).p50Ms()).isEqualTo(50);
			assertThat(stats.get(0).lastIsError()).isTrue();
		}

		@Test
		@DisplayName("lastIsError and lastCallAt reflect the most recent call only")
		void lastOutcomeReflectsMostRecentCall() {
			// given: earlier call failed, later call succeeded
			final List<TimelineEvent> events = List.of(requestEvent("l2", "flaky", BASE.plusMillis(500)),
					responseWithResult("l2", false, BASE.plusMillis(520)), requestEvent("l1", "flaky", BASE),
					responseWithResult("l1", true, BASE.plusMillis(30)));
			// when
			final List<ToolHealthStat> stats = ToolHealthAggregator.computeToolHealth(events);
			// then
			assertThat(stats).hasSize(1);
			assertThat(stats.get(0).calls()).isEqualTo(2);
			assertThat(stats.get(0).errors()).isEqualTo(1);
			assertThat(stats.get(0).lastIsError()).isFalse();
			assertThat(stats.get(0).lastCallAt()).isEqualTo(BASE.plusMillis(500).toEpochMilli());
		}

		@Test
		@DisplayName("a tools/call request without params.name is grouped under \"unknown\"")
		void missingToolNameFallsBackToUnknown() {
			// given
			final List<TimelineEvent> events = List.of(requestEvent("u1", null, BASE),
					responseWithResult("u1", false, BASE.plusMillis(10)));
			// when
			final List<ToolHealthStat> stats = ToolHealthAggregator.computeToolHealth(events);
			// then
			assertThat(stats).hasSize(1);
			assertThat(stats.get(0).method()).isEqualTo(ToolHealthAggregator.UNKNOWN_TOOL);
			assertThat(stats.get(0).calls()).isEqualTo(1);
		}

	}

}
