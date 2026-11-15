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
 * Aggregated health statistics for a single tool, computed over the timeline ring buffer
 * by {@link ToolHealthAggregator#computeToolHealth(java.util.List)}.
 *
 * <p>
 * This is a public contract consumed by the inspector UI: existing fields must not
 * change; the record may only be extended.
 *
 * @param method the tool name, taken from the {@code params.name} member of the
 * {@code tools/call} request (never {@code null}; {@code "unknown"} when the request
 * carries no tool name)
 * @param calls the number of {@code tools/call} requests observed for this tool
 * @param errors the number of failed calls: JSON-RPC error responses, tool-level
 * {@code result.isError} responses, and calls without a recorded response
 * @param noResponse the number of calls whose response was not present in the observed
 * window (evicted from the ring buffer or still in flight); each of these also counts
 * towards {@code errors} and {@code errorRate}, per java-sdk#1336
 * @param errorRate the share of failed calls, {@code errors / calls} in
 * {@code [0.0, 1.0]} ({@code 0.0} when {@code calls == 0})
 * @param p50Ms the median call duration in milliseconds, nearest-rank percentile over the
 * durations of answered calls ({@code 0} when no call was answered)
 * @param p95Ms the 95th percentile call duration in milliseconds, nearest-rank percentile
 * over the durations of answered calls ({@code 0} when no call was answered)
 * @param lastIsError whether the most recent call failed; {@code null} when there are no
 * calls
 * @param lastCallAt the epoch millisecond timestamp of the most recent call ({@code 0}
 * when there are no calls)
 * @author Artem Simeshin
 */
public record ToolHealthStat(String method, long calls, long errors, long noResponse, double errorRate, long p50Ms,
		long p95Ms, Boolean lastIsError, long lastCallAt) {
}
