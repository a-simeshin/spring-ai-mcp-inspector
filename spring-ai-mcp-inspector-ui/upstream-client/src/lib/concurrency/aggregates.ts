// [spring-ai-mcp-inspector PATCH] aggregates for concurrency probe (issue #237)
/**
 * Aggregates for the concurrency probe: percentiles (nearest-rank),
 * body equality, and failure grouping.
 * Spec: docs/concurrency-probe.md section 3.2 / 3.4.
 */

import type { CallRecord, LatencyPercentiles, BodyEquality, FailureGroup } from "./types";

function nearestRank(sorted: number[], k: number): number {
  const len = sorted.length;
  if (len === 0) return 0;
  const rank = Math.ceil((k / 100) * len);
  const idx = Math.max(1, Math.min(rank, len)) - 1;
  return sorted[idx];
}

export function computePercentiles(
  latencies: number[],
): LatencyPercentiles | undefined {
  if (latencies.length === 0) return undefined;
  const sorted = [...latencies].sort((a, b) => a - b);
  return {
    p50: nearestRank(sorted, 50),
    p95: nearestRank(sorted, 95),
    p99: nearestRank(sorted, 99),
    max: sorted[sorted.length - 1],
  };
}

export function computeBodyEquality(calls: CallRecord[]): BodyEquality {
  const successes = calls.filter((c) => c.status === "success");
  if (successes.length === 0) {
    return { kind: "no-success", distinctHashes: 0 };
  }
  if (successes.length === 1) {
    return { kind: "single-success", distinctHashes: 1 };
  }
  const hashes = new Set(successes.map((c) => c.bodyHash!));
  if (hashes.size === 1) {
    return { kind: "all-equal", distinctHashes: 1 };
  }
  return { kind: "mixed", distinctHashes: hashes.size };
}

export function groupFailures(calls: CallRecord[]): FailureGroup[] {
  const map = new Map<string, number[]>();
  for (const call of calls) {
    if (call.status === "error" || call.status === "timeout") {
      const msg = call.errorMessage!;
      const indices = map.get(msg) ?? [];
      indices.push(call.index);
      map.set(msg, indices);
    }
  }
  const groups: FailureGroup[] = Array.from(map.entries()).map(
    ([errorMessage, callIndices]) => ({
      errorMessage,
      count: callIndices.length,
      callIndices: callIndices.sort((a, b) => a - b),
    }),
  );
  groups.sort((a, b) => b.count - a.count);
  return groups;
}
