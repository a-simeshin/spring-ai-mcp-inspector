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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Computes per-tool health statistics over the timeline ring buffer.
 *
 * <p>
 * Pure aggregation, no new data collection: the input is the list produced by
 * {@link TimelineService#query(TimelineQuery)} (newest first), and only
 * {@code tools/call} request/response pairs are considered. Shrinking the buffer window
 * automatically shrinks the sample: a call whose response was evicted counts as
 * unanswered, and durations are derived from the recorded event timestamps, so the
 * aggregator never needs data the ring buffer does not already hold.
 *
 * @author Artem Simeshin
 */
public final class ToolHealthAggregator {

	/**
	 * Group name for {@code tools/call} requests whose {@code params.name} is absent or
	 * not a scalar value.
	 */
	static final String UNKNOWN_TOOL = "unknown";

	/** The JSON-RPC method name that identifies a tool invocation. */
	private static final String TOOLS_CALL = "tools/call";

	private ToolHealthAggregator() {
	}

	/**
	 * Aggregates the given timeline events into per-tool health statistics, one
	 * {@link ToolHealthStat} per tool, sorted by tool name.
	 * <p>
	 * A call is failed when its response is a JSON-RPC error, when the tool-level
	 * {@code result.isError} flag is {@code true}, or when no response is present in the
	 * observed window; unanswered calls are counted both in {@code noResponse} and in
	 * {@code errors}, per java-sdk#1336. Percentiles are computed with the nearest-rank
	 * method over the durations of answered calls (failed answers included). The input is
	 * expected newest first, as produced by {@link TimelineService#query(TimelineQuery)};
	 * with equal timestamps the entry listed first is treated as the most recent.
	 * @param events the timeline events to aggregate, may be {@code null}
	 * @return an immutable list of per-tool statistics, sorted by tool name (never
	 * {@code null}; empty when no {@code tools/call} request is present)
	 */
	public static List<ToolHealthStat> computeToolHealth(final List<TimelineEvent> events) {
		if (events == null || events.isEmpty()) {
			return List.of();
		}
		// Newest response per correlation id: the walk is newest first, so the
		// first response seen for an id is the newest one.
		final Map<String, TimelineEvent> responseByCorrelationId = new HashMap<>();
		for (final TimelineEvent event : events) {
			if (event.type() == TimelineEventType.MCP_JSONRPC_RESPONSE && event.correlationId() != null
					&& !responseByCorrelationId.containsKey(event.correlationId())) {
				responseByCorrelationId.put(event.correlationId(), event);
			}
		}
		final Map<String, ToolState> states = new LinkedHashMap<>();
		for (final TimelineEvent event : events) {
			if (event.type() != TimelineEventType.MCP_JSONRPC_REQUEST || !TOOLS_CALL.equals(rpcMethodOf(event))) {
				continue;
			}
			final ToolState state = states.computeIfAbsent(toolNameOf(event), (key) -> new ToolState());
			state.calls++;
			final TimelineEvent response = (event.correlationId() != null)
					? responseByCorrelationId.get(event.correlationId()) : null;
			final boolean failed;
			if (response == null) {
				state.noResponse++;
				failed = true;
			}
			else {
				failed = isError(response);
				final long durationMs = Math.max(0L,
						Duration.between(event.timestamp(), response.timestamp()).toMillis());
				state.durations.add(durationMs);
			}
			if (failed) {
				state.errors++;
			}
			// Events arrive newest first: a strictly greater timestamp keeps the
			// first-seen entry, which is the most recent call.
			final long callAt = event.timestamp().toEpochMilli();
			if (callAt > state.lastCallAt) {
				state.lastCallAt = callAt;
				state.lastIsError = failed;
			}
		}
		final List<ToolHealthStat> result = new ArrayList<>(states.size());
		for (final Map.Entry<String, ToolState> entry : states.entrySet()) {
			final ToolState state = entry.getValue();
			Collections.sort(state.durations);
			final double errorRate = (state.calls == 0) ? 0.0 : (double) state.errors / state.calls;
			result.add(new ToolHealthStat(entry.getKey(), state.calls, state.errors, state.noResponse, errorRate,
					percentile(state.durations, 50), percentile(state.durations, 95), state.lastIsError,
					state.lastCallAt));
		}
		result.sort(Comparator.comparing(ToolHealthStat::method));
		return Collections.unmodifiableList(result);
	}

	/**
	 * Returns the nearest-rank percentile of the given ascending-sorted values.
	 * @param sorted the sorted values (must not be {@code null})
	 * @param rank the percentile to compute, e.g. {@code 50} or {@code 95}
	 * @return the value at {@code ceil(rank/100 * n)} (1-based), or {@code 0} when empty
	 */
	private static long percentile(final List<Long> sorted, final int rank) {
		if (sorted.isEmpty()) {
			return 0L;
		}
		final int index = (int) Math.ceil(rank / 100.0 * sorted.size());
		return sorted.get(Math.min(Math.max(index, 1), sorted.size()) - 1);
	}

	private static String rpcMethodOf(final TimelineEvent event) {
		if (event.payload() == null) {
			return "";
		}
		final JsonNode method = event.payload().get("method");
		return (method != null && method.isValueNode()) ? method.asText() : "";
	}

	private static String toolNameOf(final TimelineEvent event) {
		if (event.payload() == null) {
			return UNKNOWN_TOOL;
		}
		final JsonNode name = event.payload().path("params").path("name");
		return (name.isValueNode()) ? name.asText() : UNKNOWN_TOOL;
	}

	/**
	 * Returns whether a response represents a failed call: either a JSON-RPC error
	 * response (an {@code error} member that is not JSON null) or a tool-level failure
	 * ({@code result.isError == true}).
	 * @param response the response event (must not be {@code null})
	 * @return {@code true} when the call failed
	 */
	private static boolean isError(final TimelineEvent response) {
		if (response.payload() == null) {
			return false;
		}
		final JsonNode error = response.payload().get("error");
		if (error != null && !error.isNull()) {
			return true;
		}
		final JsonNode isError = response.payload().path("result").path("isError");
		return isError.isBoolean() && isError.asBoolean();
	}

	/**
	 * Mutable aggregation state for a single tool.
	 */
	private static final class ToolState {

		private long calls;

		private long errors;

		private long noResponse;

		private long lastCallAt;

		private Boolean lastIsError;

		private final List<Long> durations = new ArrayList<>();

	}

}
