// [spring-ai-mcp-inspector PATCH] concurrency probe engine types (issue #237)
/**
 * Types for the concurrency probe engine (issue #237).
 * Contract: docs/concurrency-probe.md + docs/concurrency-probe.schema.json
 */

import type { CompatibilityCallToolResult } from "@modelcontextprotocol/sdk/types.js";

export interface ProbeConfig {
  toolName: string;
  arguments: Record<string, unknown>;
  concurrency: number;
  iterations: number;
  perCallTimeoutMs: number;
  metadata?: Record<string, unknown>;
}

export interface LatencyPercentiles {
  p50: number;
  p95: number;
  p99: number;
  max: number;
}

export interface BodyEquality {
  kind: "all-equal" | "mixed" | "single-success" | "no-success";
  distinctHashes: number;
}

export interface FailureGroup {
  errorMessage: string;
  count: number;
  callIndices: number[];
}

export type CallStatus = "success" | "error" | "timeout" | "cancelled";

export interface CallRecord {
  index: number;
  iteration: number;
  lane: number;
  startedAt: string;
  endedAt: string;
  latencyMs: number;
  status: CallStatus;
  errorMessage?: string;
  bodyHash?: string;
  bodyHashTruncated?: boolean;
  timelineCorrelationId?: string;
}

export interface ProbeResult {
  toolName: string;
  arguments: Record<string, unknown>;
  concurrency: number;
  iterations: number;
  perCallTimeoutMs: number;
  startedAt: string;
  endedAt: string;
  completed: boolean;
  cancelledAt?: string;
  abortedReason?: string;
  totalCalls: number;
  successCount: number;
  errorCount: number;
  timeoutCount: number;
  cancelledCount: number;
  latencyMs?: LatencyPercentiles;
  bodyEquality: BodyEquality;
  failureGroups: FailureGroup[];
  calls: CallRecord[];
}

export type CallToolFn = (
  name: string,
  params: Record<string, unknown>,
  toolMetadata?: Record<string, unknown>,
  runAsTask?: boolean,
  signal?: AbortSignal,
) => Promise<CompatibilityCallToolResult>;

export interface ProbeHandle {
  readonly done: Promise<ProbeResult>;
  cancel(): void;
  onProgress(cb: (settled: number, total: number) => void): void;
}

export interface ProbeError extends Error {
  code: string;
}
