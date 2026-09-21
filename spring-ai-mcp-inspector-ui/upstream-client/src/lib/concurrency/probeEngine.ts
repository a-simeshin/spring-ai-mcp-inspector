// [spring-ai-mcp-inspector PATCH] concurrency probe engine (issue #237)
/**
 * Concurrency probe engine (issue #237).
 * Pure module: receives callTool by injection, no React state, no DOM access.
 *
 * Execution model (spec section 2):
 *   - iterations are sequential
 *   - within one iteration, N calls are fired concurrently via Promise.allSettled
 *   - each call gets its own AbortController with a per-call timeout
 *   - cancellation aborts all in-flight controllers, stops scheduling further
 *     iterations, and returns a partial result
 *
 * Session: one shared MCP session via the injected callTool. The engine MUST
 * NOT create its own client or mutate connection state.
 */

import type {
  ProbeConfig,
  ProbeHandle,
  ProbeResult,
  CallRecord,
  CallToolFn,
} from "./types";
import { computeBodyHash } from "./canonicalJson";
import {
  computePercentiles,
  computeBodyEquality,
  groupFailures,
} from "./aggregates";

export const MIN_CONCURRENCY = 1;
export const MAX_CONCURRENCY = 64;
export const MIN_ITERATIONS = 1;
export const MAX_ITERATIONS = 100;
export const MIN_PER_CALL_TIMEOUT_MS = 1000;
export const MAX_PER_CALL_TIMEOUT_MS = 120000;
export const MAX_TOTAL_CALLS = 640;

const TIMEOUT_MESSAGE_PREFIX = "timeout after ";
const TIMEOUT_MESSAGE_SUFFIX = "ms";

class ProbeValidationError extends Error {
  code = "PROBE_VALIDATION";
}

function validateConfig(config: ProbeConfig): void {
  if (!config.toolName || config.toolName.trim().length === 0) {
    throw new ProbeValidationError("toolName must be a non-empty string");
  }
  if (
    !Number.isInteger(config.concurrency) ||
    config.concurrency < MIN_CONCURRENCY ||
    config.concurrency > MAX_CONCURRENCY
  ) {
    throw new ProbeValidationError(
      `concurrency must be an integer ${MIN_CONCURRENCY}..${MAX_CONCURRENCY}, got ${config.concurrency}`,
    );
  }
  if (
    !Number.isInteger(config.iterations) ||
    config.iterations < MIN_ITERATIONS ||
    config.iterations > MAX_ITERATIONS
  ) {
    throw new ProbeValidationError(
      `iterations must be an integer ${MIN_ITERATIONS}..${MAX_ITERATIONS}, got ${config.iterations}`,
    );
  }
  const total = config.concurrency * config.iterations;
  if (total > MAX_TOTAL_CALLS) {
    throw new ProbeValidationError(
      `total calls (concurrency * iterations = ${total}) must not exceed ${MAX_TOTAL_CALLS}`,
    );
  }
  if (
    !Number.isInteger(config.perCallTimeoutMs) ||
    config.perCallTimeoutMs < MIN_PER_CALL_TIMEOUT_MS ||
    config.perCallTimeoutMs > MAX_PER_CALL_TIMEOUT_MS
  ) {
    throw new ProbeValidationError(
      `perCallTimeoutMs must be an integer ${MIN_PER_CALL_TIMEOUT_MS}..${MAX_PER_CALL_TIMEOUT_MS}, got ${config.perCallTimeoutMs}`,
    );
  }
}

function now(): string {
  return new Date().toISOString();
}

export function runProbe(
  config: ProbeConfig,
  callTool: CallToolFn,
): ProbeHandle {
  validateConfig(config);

  const totalPlanned = config.concurrency * config.iterations;
  const calls: CallRecord[] = [];
  const controllers: AbortController[] = [];
  const progressCallbacks: ((settled: number, total: number) => void)[] = [];
  let settledCount = 0;
  let cancelled = false;
  let cancelledAt: string | undefined;
  let abortedReason: string | undefined;
  let resolveDone: ((result: ProbeResult) => void) | undefined;

  const done = new Promise<ProbeResult>((res) => {
    resolveDone = res;
  });

  function notifyProgress(): void {
    settledCount++;
    for (const cb of progressCallbacks) {
      cb(settledCount, totalPlanned);
    }
  }

  function buildResult(completedFlag: boolean): ProbeResult {
    const successLatencies = calls
      .filter((c) => c.status === "success")
      .map((c) => c.latencyMs);
    const latencyMs = computePercentiles(successLatencies);
    const bodyEquality = computeBodyEquality(calls);
    const failureGroups = groupFailures(calls);

    const result: ProbeResult = {
      toolName: config.toolName,
      arguments: config.arguments,
      concurrency: config.concurrency,
      iterations: config.iterations,
      perCallTimeoutMs: config.perCallTimeoutMs,
      startedAt: calls.length > 0 ? calls[0].startedAt : now(),
      endedAt: now(),
      completed: completedFlag,
      totalCalls: calls.length,
      successCount: calls.filter((c) => c.status === "success").length,
      errorCount: calls.filter((c) => c.status === "error").length,
      timeoutCount: calls.filter((c) => c.status === "timeout").length,
      cancelledCount: calls.filter((c) => c.status === "cancelled").length,
      bodyEquality,
      failureGroups,
      calls,
    };
    if (latencyMs !== undefined) {
      result.latencyMs = latencyMs;
    }
    if (cancelledAt !== undefined) {
      result.cancelledAt = cancelledAt;
    }
    if (abortedReason !== undefined) {
      result.abortedReason = abortedReason;
    }
    return result;
  }

  function cancel(): void {
    if (cancelled) return;
    cancelled = true;
    cancelledAt = now();
    for (const ctrl of controllers) {
      if (!ctrl.signal.aborted) {
        ctrl.abort();
      }
    }
  }

  async function executeIteration(iteration: number): Promise<void> {
    if (cancelled) return;

    const promises: Promise<CallRecord>[] = [];
    for (let lane = 0; lane < config.concurrency; lane++) {
      const globalIndex = iteration * config.concurrency + lane;
      const controller = new AbortController();
      controllers.push(controller);

      const promise = (async (): Promise<CallRecord> => {
        const startedAt = now();
        let endedAt: string;
        let latencyMs: number;
        let status: CallRecord["status"];
        let errorMessage: string | undefined;
        let bodyHash: string | undefined;
        let bodyHashTruncated: boolean | undefined;

        // Set up per-call timeout
        const timeoutId = setTimeout(() => {
          if (!controller.signal.aborted) {
            controller.abort();
          }
        }, config.perCallTimeoutMs);

        try {
          const result = await callTool(
            config.toolName,
            config.arguments,
            config.metadata,
            false, // runAsTask: probe always calls directly
            controller.signal,
          );
          endedAt = now();
          latencyMs = new Date(endedAt).getTime() - new Date(startedAt).getTime();

          const hashResult = await computeBodyHash(result);
          bodyHash = hashResult.hash;
          bodyHashTruncated = hashResult.truncated;
          status = "success";
        } catch (e) {
          endedAt = now();
          latencyMs = new Date(endedAt).getTime() - new Date(startedAt).getTime();

          if (controller.signal.aborted) {
            if (cancelled) {
              status = "cancelled";
            } else {
              status = "timeout";
              errorMessage = TIMEOUT_MESSAGE_PREFIX + config.perCallTimeoutMs + TIMEOUT_MESSAGE_SUFFIX;
            }
          } else {
            status = "error";
            errorMessage = e instanceof Error ? e.message : String(e);
          }
        } finally {
          clearTimeout(timeoutId);
        }

        const record: CallRecord = {
          index: globalIndex,
          iteration,
          lane,
          startedAt,
          endedAt,
          latencyMs,
          status,
        };
        if (errorMessage !== undefined) record.errorMessage = errorMessage;
        if (bodyHash !== undefined) record.bodyHash = bodyHash;
        if (bodyHashTruncated !== undefined) record.bodyHashTruncated = bodyHashTruncated;

        return record;
      })();

      promises.push(promise);
    }

    const settled = await Promise.allSettled(promises);

    for (const s of settled) {
      if (s.status === "fulfilled") {
        calls.push(s.value);
        notifyProgress();
      } else {
        // Should not happen: the async function above never rejects
        // (all exceptions are caught inside). If it does, record as error.
        calls.push({
          index: calls.length,
          iteration,
          lane: calls.length % config.concurrency,
          startedAt: now(),
          endedAt: now(),
          latencyMs: 0,
          status: "error",
          errorMessage: s.reason instanceof Error ? s.reason.message : String(s.reason),
        });
        notifyProgress();
      }
    }
  }

  // Main execution loop
  (async () => {
    try {
      for (let iter = 0; iter < config.iterations; iter++) {
        if (cancelled) break;
        await executeIteration(iter);
      }
      resolveDone!(buildResult(!cancelled));
    } catch (e) {
      // Transport-level abort: connection drop etc.
      abortedReason = e instanceof Error ? e.message : String(e);
      // Mark any unsettled calls as cancelled
      const settledIndices = new Set(calls.map((c) => c.index));
      for (let i = 0; i < totalPlanned; i++) {
        if (!settledIndices.has(i)) {
          calls.push({
            index: i,
            iteration: Math.floor(i / config.concurrency),
            lane: i % config.concurrency,
            startedAt: now(),
            endedAt: now(),
            latencyMs: 0,
            status: "cancelled",
          });
        }
      }
      resolveDone!(buildResult(false));
    }
  })();

  return {
    done,
    cancel,
    onProgress(cb: (settled: number, total: number) => void) {
      progressCallbacks.push(cb);
    },
  };
}
