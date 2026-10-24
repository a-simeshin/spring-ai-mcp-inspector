// [spring-ai-mcp-inspector PATCH] aggregates unit tests (issue #237)
import {
  computePercentiles,
  computeBodyEquality,
  groupFailures,
} from "../aggregates";
import type { CallRecord } from "../types";

function makeCall(overrides: Partial<CallRecord>): CallRecord {
  return {
    index: 0,
    iteration: 0,
    lane: 0,
    startedAt: new Date().toISOString(),
    endedAt: new Date().toISOString(),
    latencyMs: 0,
    status: "success",
    ...overrides,
  };
}

describe("computePercentiles", () => {
  it("returns undefined for empty array", () => {
    expect(computePercentiles([])).toBeUndefined();
  });

  it("single value: all percentiles equal", () => {
    const result = computePercentiles([42])!;
    expect(result.p50).toBe(42);
    expect(result.p95).toBe(42);
    expect(result.p99).toBe(42);
    expect(result.max).toBe(42);
  });

  it("nearest-rank: p50 of [1..10] = 5", () => {
    const values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    const result = computePercentiles(values)!;
    expect(result.p50).toBe(5);
    expect(result.p95).toBe(10);
    expect(result.p99).toBe(10);
    expect(result.max).toBe(10);
  });

  it("nearest-rank: handles duplicate values", () => {
    const values = [1, 1, 1, 1, 1, 1, 1, 1, 1, 100];
    const result = computePercentiles(values)!;
    expect(result.p50).toBe(1);
    expect(result.p95).toBe(100);
    expect(result.max).toBe(100);
  });

  it("nearest-rank: two values", () => {
    const values = [10, 20];
    const result = computePercentiles(values)!;
    expect(result.p50).toBe(10);
    expect(result.p95).toBe(20);
    expect(result.p99).toBe(20);
    expect(result.max).toBe(20);
  });

  it("nearest-rank: three values", () => {
    const values = [1, 2, 3];
    const result = computePercentiles(values)!;
    expect(result.p50).toBe(2);
    expect(result.p95).toBe(3);
    expect(result.p99).toBe(3);
    expect(result.max).toBe(3);
  });

  it("sorts unsorted input", () => {
    const values = [50, 10, 30, 20, 40];
    const result = computePercentiles(values)!;
    expect(result.p50).toBe(30);
    expect(result.max).toBe(50);
  });

  it("nearest-rank: p95 of 4 values", () => {
    const values = [10, 20, 30, 40];
    const result = computePercentiles(values)!;
    // ceil(95/100 * 4) = ceil(3.8) = 4 -> index 3 -> 40
    expect(result.p95).toBe(40);
  });

  it("nearest-rank: p50 of 100 values", () => {
    const values = Array.from({ length: 100 }, (_, i) => i + 1);
    const result = computePercentiles(values)!;
    expect(result.p50).toBe(50);
    expect(result.p95).toBe(95);
    expect(result.p99).toBe(99);
    expect(result.max).toBe(100);
  });
});

describe("computeBodyEquality", () => {
  it("no-success when all calls fail", () => {
    const calls = [
      makeCall({ status: "error", errorMessage: "fail" }),
      makeCall({ status: "timeout", errorMessage: "timeout after 5000ms" }),
    ];
    const result = computeBodyEquality(calls);
    expect(result.kind).toBe("no-success");
    expect(result.distinctHashes).toBe(0);
  });

  it("single-success with one success", () => {
    const calls = [
      makeCall({ status: "success", bodyHash: "abc123" }),
      makeCall({ status: "error", errorMessage: "fail" }),
    ];
    const result = computeBodyEquality(calls);
    expect(result.kind).toBe("single-success");
    expect(result.distinctHashes).toBe(1);
  });

  it("all-equal when all successes have same hash", () => {
    const calls = [
      makeCall({ status: "success", bodyHash: "aaa" }),
      makeCall({ status: "success", bodyHash: "aaa" }),
      makeCall({ status: "success", bodyHash: "aaa" }),
    ];
    const result = computeBodyEquality(calls);
    expect(result.kind).toBe("all-equal");
    expect(result.distinctHashes).toBe(1);
  });

  it("mixed when successes have different hashes", () => {
    const calls = [
      makeCall({ status: "success", bodyHash: "aaa" }),
      makeCall({ status: "success", bodyHash: "bbb" }),
      makeCall({ status: "success", bodyHash: "aaa" }),
    ];
    const result = computeBodyEquality(calls);
    expect(result.kind).toBe("mixed");
    expect(result.distinctHashes).toBe(2);
  });
});

describe("groupFailures", () => {
  it("groups by error message", () => {
    const calls = [
      makeCall({ index: 0, status: "error", errorMessage: "fail A" }),
      makeCall({ index: 1, status: "error", errorMessage: "fail B" }),
      makeCall({ index: 2, status: "error", errorMessage: "fail A" }),
      makeCall({ index: 3, status: "success", bodyHash: "abc" }),
    ];
    const result = groupFailures(calls);
    expect(result).toHaveLength(2);
    expect(result[0].errorMessage).toBe("fail A");
    expect(result[0].count).toBe(2);
    expect(result[0].callIndices).toEqual([0, 2]);
    expect(result[1].errorMessage).toBe("fail B");
    expect(result[1].count).toBe(1);
  });

  it("sorts by count descending", () => {
    const calls = [
      makeCall({ index: 0, status: "error", errorMessage: "rare" }),
      makeCall({ index: 1, status: "error", errorMessage: "common" }),
      makeCall({ index: 2, status: "error", errorMessage: "common" }),
      makeCall({ index: 3, status: "error", errorMessage: "common" }),
    ];
    const result = groupFailures(calls);
    expect(result[0].errorMessage).toBe("common");
    expect(result[1].errorMessage).toBe("rare");
  });

  it("includes timeout in groups", () => {
    const calls = [
      makeCall({ index: 0, status: "timeout", errorMessage: "timeout after 5000ms" }),
      makeCall({ index: 1, status: "timeout", errorMessage: "timeout after 5000ms" }),
    ];
    const result = groupFailures(calls);
    expect(result).toHaveLength(1);
    expect(result[0].count).toBe(2);
    expect(result[0].callIndices).toEqual([0, 1]);
  });

  it("returns empty array when no failures", () => {
    const calls = [
      makeCall({ status: "success", bodyHash: "abc" }),
      makeCall({ status: "success", bodyHash: "abc" }),
    ];
    expect(groupFailures(calls)).toEqual([]);
  });
});
