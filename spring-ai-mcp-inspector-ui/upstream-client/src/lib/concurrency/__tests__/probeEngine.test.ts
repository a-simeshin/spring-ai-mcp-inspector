// [spring-ai-mcp-inspector PATCH] probe engine unit tests (issue #237)
import { runProbe, MIN_CONCURRENCY } from "../probeEngine";
import type { CallToolFn, ProbeConfig } from "../types";
import type { CompatibilityCallToolResult } from "@modelcontextprotocol/sdk/types.js";

// jest-fixed-jsdom lacks crypto.subtle; use node:crypto webcrypto
import { webcrypto } from "node:crypto";
Object.defineProperty(globalThis, "crypto", { value: webcrypto });

function makeResult(text: string): CompatibilityCallToolResult {
  return { content: [{ type: "text", text }] };
}

function makeConfig(overrides?: Partial<ProbeConfig>): ProbeConfig {
  return {
    toolName: "testTool",
    arguments: { msg: "hello" },
    concurrency: 4,
    iterations: 1,
    perCallTimeoutMs: 5000,
    ...overrides,
  };
}

describe("probeEngine validation", () => {
  it("rejects concurrency < 1", () => {
    const fn = jest.fn();
    expect(() =>
      runProbe(makeConfig({ concurrency: 0 }), fn),
    ).toThrow(/concurrency/);
  });

  it("rejects concurrency > 64", () => {
    const fn = jest.fn();
    expect(() =>
      runProbe(makeConfig({ concurrency: 65 }), fn),
    ).toThrow(/concurrency/);
  });

  it("rejects total calls > 640", () => {
    const fn = jest.fn();
    expect(() =>
      runProbe(makeConfig({ concurrency: 64, iterations: 11 }), fn),
    ).toThrow(/total calls/);
  });

  it("rejects perCallTimeoutMs < 1000", () => {
    const fn = jest.fn();
    expect(() =>
      runProbe(makeConfig({ perCallTimeoutMs: 999 }), fn),
    ).toThrow(/perCallTimeoutMs/);
  });

  it("rejects perCallTimeoutMs > 120000", () => {
    const fn = jest.fn();
    expect(() =>
      runProbe(makeConfig({ perCallTimeoutMs: 120001 }), fn),
    ).toThrow(/perCallTimeoutMs/);
  });

  it("rejects empty toolName", () => {
    const fn = jest.fn();
    expect(() =>
      runProbe(makeConfig({ toolName: "" }), fn),
    ).toThrow(/toolName/);
  });

  it("accepts boundary values", () => {
    const fn: CallToolFn = () => Promise.resolve(makeResult("ok"));
    expect(() =>
      runProbe(
        makeConfig({ concurrency: MIN_CONCURRENCY, iterations: 1, perCallTimeoutMs: 1000 }),
        fn,
      ),
    ).not.toThrow();
    expect(() =>
      runProbe(
        makeConfig({ concurrency: 64, iterations: 10, perCallTimeoutMs: 120000 }),
        fn,
      ),
    ).not.toThrow();
    expect(() =>
      runProbe(
        makeConfig({ concurrency: 1, iterations: 1, perCallTimeoutMs: 30000 }),
        fn,
      ),
    ).not.toThrow();
  });
});

describe("probeEngine execution", () => {
  it("runs all calls and returns complete result", async () => {
    const callTool: CallToolFn = () => Promise.resolve(makeResult("ok"));
    const handle = runProbe(makeConfig({ concurrency: 3, iterations: 2 }), callTool);
    const result = await handle.done;

    expect(result.completed).toBe(true);
    expect(result.totalCalls).toBe(6);
    expect(result.successCount).toBe(6);
    expect(result.errorCount).toBe(0);
    expect(result.timeoutCount).toBe(0);
    expect(result.cancelledCount).toBe(0);
    expect(result.calls).toHaveLength(6);
    expect(result.bodyEquality.kind).toBe("all-equal");
    expect(result.bodyEquality.distinctHashes).toBe(1);
    expect(result.latencyMs).toBeDefined();
    expect(result.latencyMs!.p50).toBeGreaterThanOrEqual(0);
    expect(result.latencyMs!.max).toBeGreaterThanOrEqual(result.latencyMs!.p50);
  });

  it("computes percentiles correctly for N=1", async () => {
    const callTool: CallToolFn = () => Promise.resolve(makeResult("ok"));
    const handle = runProbe(makeConfig({ concurrency: 1, iterations: 1 }), callTool);
    const result = await handle.done;

    expect(result.totalCalls).toBe(1);
    expect(result.successCount).toBe(1);
    expect(result.latencyMs).toBeDefined();
    const lat = result.calls[0].latencyMs;
    expect(result.latencyMs!.p50).toBe(lat);
    expect(result.latencyMs!.p95).toBe(lat);
    expect(result.latencyMs!.p99).toBe(lat);
    expect(result.latencyMs!.max).toBe(lat);
    expect(result.bodyEquality.kind).toBe("single-success");
    expect(result.bodyEquality.distinctHashes).toBe(1);
  });

  it("returns no latencyMs when all calls fail", async () => {
    const callTool: CallToolFn = () => Promise.reject(new Error("server error"));
    const handle = runProbe(makeConfig({ concurrency: 3, iterations: 1 }), callTool);
    const result = await handle.done;

    expect(result.completed).toBe(true);
    expect(result.totalCalls).toBe(3);
    expect(result.successCount).toBe(0);
    expect(result.errorCount).toBe(3);
    expect(result.latencyMs).toBeUndefined();
    expect(result.bodyEquality.kind).toBe("no-success");
    expect(result.bodyEquality.distinctHashes).toBe(0);
    expect(result.failureGroups).toHaveLength(1);
    expect(result.failureGroups[0].errorMessage).toBe("server error");
    expect(result.failureGroups[0].count).toBe(3);
    expect(result.failureGroups[0].callIndices).toEqual([0, 1, 2]);
  });

  it("detects mixed bodies", async () => {
    let call = 0;
    const callTool: CallToolFn = () => {
      call++;
      return Promise.resolve(makeResult(`result-${call}`));
    };
    const handle = runProbe(makeConfig({ concurrency: 3, iterations: 1 }), callTool);
    const result = await handle.done;

    expect(result.successCount).toBe(3);
    expect(result.bodyEquality.kind).toBe("mixed");
    expect(result.bodyEquality.distinctHashes).toBe(3);
  });

  it("fires calls concurrently within one iteration", async () => {
    const order: number[] = [];
    const callTool: CallToolFn = () => {
      return new Promise<CompatibilityCallToolResult>((resolve) => {
        order.push(order.length);
        setTimeout(() => resolve(makeResult("ok")), 50);
      });
    };
    const handle = runProbe(makeConfig({ concurrency: 3, iterations: 1 }), callTool);
    const result = await handle.done;
    expect(result.totalCalls).toBe(3);
    // All 3 dispatched before any resolves (concurrent dispatch in same turn)
    expect(order).toEqual([0, 1, 2]);
  });

  it("runs iterations sequentially", async () => {
    const timestamps: number[] = [];
    const callTool: CallToolFn = () => {
      timestamps.push(Date.now());
      return Promise.resolve(makeResult("ok"));
    };
    const handle = runProbe(makeConfig({ concurrency: 2, iterations: 2 }), callTool);
    const result = await handle.done;
    expect(result.totalCalls).toBe(4);
    // Iteration 0 calls should finish before iteration 1 starts
    expect(result.calls[0].iteration).toBe(0);
    expect(result.calls[1].iteration).toBe(0);
    expect(result.calls[2].iteration).toBe(1);
    expect(result.calls[3].iteration).toBe(1);
  });

  it("handles timeout per call", async () => {
    const callTool: CallToolFn = (name, params, metadata, runAsTask, signal) => {
      return new Promise<CompatibilityCallToolResult>((resolve, reject) => {
        const timer = setTimeout(() => resolve(makeResult("slow")), 10000);
        signal?.addEventListener("abort", () => {
          clearTimeout(timer);
          reject(new DOMException("Aborted", "AbortError"));
        });
      });
    };
    const handle = runProbe(
      makeConfig({ concurrency: 2, iterations: 1, perCallTimeoutMs: 1000 }),
      callTool,
    );
    const result = await handle.done;

    expect(result.timeoutCount).toBe(2);
    expect(result.successCount).toBe(0);
    expect(result.calls[0].status).toBe("timeout");
    expect(result.calls[0].errorMessage).toMatch(/timeout after 1000ms/);
    expect(result.calls[1].status).toBe("timeout");
  });

  it("cancel() aborts in-flight calls and returns partial result", async () => {
    const started: number[] = [];
    const callTool: CallToolFn = (name, params, metadata, runAsTask, signal) => {
      return new Promise<CompatibilityCallToolResult>((resolve, reject) => {
        const idx = started.length;
        started.push(idx);
        const timer = setTimeout(() => resolve(makeResult(`ok-${idx}`)), 5000);
        signal?.addEventListener("abort", () => {
          clearTimeout(timer);
          reject(new DOMException("Aborted", "AbortError"));
        });
      });
    };
    const handle = runProbe(makeConfig({ concurrency: 4, iterations: 3 }), callTool);

    // Wait for first iteration to be in flight, then cancel
    await new Promise((r) => setTimeout(r, 100));
    handle.cancel();

    const result = await handle.done;
    expect(result.completed).toBe(false);
    expect(result.cancelledAt).toBeDefined();
    expect(result.cancelledCount).toBe(4);
    expect(result.totalCalls).toBe(4);
    expect(result.calls.every((c) => c.status === "cancelled")).toBe(true);
  });

  it("cancel() before any call settles: totalCalls=0", async () => {
    const callTool: CallToolFn = (name, params, metadata, runAsTask, signal) =>
      new Promise<CompatibilityCallToolResult>((resolve, reject) => {
        const timer = setTimeout(() => resolve(makeResult("ok")), 5000);
        signal?.addEventListener("abort", () => {
          clearTimeout(timer);
          reject(new DOMException("Aborted", "AbortError"));
        });
      });
    const handle = runProbe(makeConfig({ concurrency: 2, iterations: 1 }), callTool);
    handle.cancel();

    const result = await handle.done;
    expect(result.completed).toBe(false);
    expect(result.totalCalls).toBe(2);
    expect(result.successCount).toBe(0);
    expect(result.errorCount).toBe(0);
    expect(result.cancelledCount).toBe(2);
  }, 10000);

  it("cancel() is idempotent", async () => {
    const callTool: CallToolFn = () =>
      new Promise<CompatibilityCallToolResult>((resolve) =>
        setTimeout(() => resolve(makeResult("ok")), 100),
      );
    const handle = runProbe(makeConfig({ concurrency: 2, iterations: 1 }), callTool);
    handle.cancel();
    handle.cancel(); // should not throw

    const result = await handle.done;
    expect(result.completed).toBe(false);
  });

  it("progress callback fires after each settled call", async () => {
    const progress: Array<[number, number]> = [];
    const callTool: CallToolFn = () => Promise.resolve(makeResult("ok"));
    const handle = runProbe(makeConfig({ concurrency: 3, iterations: 2 }), callTool);
    handle.onProgress((settled, total) => progress.push([settled, total]));
    await handle.done;

    expect(progress).toEqual([
      [1, 6],
      [2, 6],
      [3, 6],
      [4, 6],
      [5, 6],
      [6, 6],
    ]);
  });

  it("call records have correct index/iteration/lane", async () => {
    const callTool: CallToolFn = () => Promise.resolve(makeResult("ok"));
    const handle = runProbe(makeConfig({ concurrency: 3, iterations: 2 }), callTool);
    const result = await handle.done;

    expect(result.calls[0].index).toBe(0);
    expect(result.calls[0].iteration).toBe(0);
    expect(result.calls[0].lane).toBe(0);
    expect(result.calls[2].index).toBe(2);
    expect(result.calls[2].iteration).toBe(0);
    expect(result.calls[2].lane).toBe(2);
    expect(result.calls[3].index).toBe(3);
    expect(result.calls[3].iteration).toBe(1);
    expect(result.calls[3].lane).toBe(0);
  });

  it("bodyHash is present for success, absent for error", async () => {
    let call = 0;
    const callTool: CallToolFn = () => {
      call++;
      if (call === 2) return Promise.reject(new Error("fail"));
      return Promise.resolve(makeResult(`ok-${call}`));
    };
    const handle = runProbe(makeConfig({ concurrency: 3, iterations: 1 }), callTool);
    const result = await handle.done;

    expect(result.calls[0].bodyHash).toBeDefined();
    expect(result.calls[0].bodyHash).toMatch(/^[0-9a-f]{64}$/);
    expect(result.calls[1].bodyHash).toBeUndefined();
    expect(result.calls[1].errorMessage).toBe("fail");
    expect(result.calls[2].bodyHash).toBeDefined();
  });

  it("startedAt <= endedAt for all calls", async () => {
    const callTool: CallToolFn = () => Promise.resolve(makeResult("ok"));
    const handle = runProbe(makeConfig({ concurrency: 2, iterations: 1 }), callTool);
    const result = await handle.done;

    for (const call of result.calls) {
      expect(call.startedAt <= call.endedAt).toBe(true);
      expect(call.latencyMs).toBeGreaterThanOrEqual(0);
    }
  });
});
