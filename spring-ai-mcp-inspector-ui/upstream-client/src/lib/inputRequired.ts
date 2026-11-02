// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28) input
// round-trip support for tools/call, client layer (#253). Upstream 0.22.0 only
// implements the legacy 2025-06-18 SSE elicitation flow: when a server answers
// tools/call with { resultType: "input_required", inputRequests, requestState }
// the client shows a truncated result and has no way to answer. This module
// adds the missing client-side half: result detection, a typed onInputRequired
// event, and resendToolCall() which re-issues the ORIGINAL tools/call with the
// user's inputResponses and the requestState echoed byte-exact (it is opaque:
// never parsed, mutated, or re-encoded). Multi-round results keep advancing the
// same correlation chain until a final result or error; cancel() drops it.
import type { CallToolRequest } from "@modelcontextprotocol/sdk/types.js";

/** One server-initiated request the client must answer during a tools/call. */
export interface InputRequest {
  method:
    | "elicitation/create"
    | "sampling/createMessage"
    | "roots/list"
    | (string & {});
  params?: Record<string, unknown>;
}

/** Requests keyed by the server-assigned request id (spec 2026-07-28). */
export type InputRequests = Record<string, InputRequest>;

/** Answers keyed by the same server-assigned request ids. */
export type InputResponses = Record<string, unknown>;

/**
 * tools/call result that needs more input before it can complete.
 * At least one of inputRequests / requestState is present per the spec;
 * requestState is opaque and must be echoed back byte-exact.
 */
export interface InputRequiredResult {
  resultType: "input_required";
  inputRequests?: InputRequests;
  requestState?: string;
  [key: string]: unknown;
}

/** Params of the ORIGINAL tools/call that entered the input_required state. */
export type ToolCallParams = CallToolRequest["params"];

/**
 * Params for a retry: the original tools/call params plus the spec's
 * inputResponses field and, only when the server sent one, requestState.
 */
export type ToolCallRetryParams = ToolCallParams & {
  inputResponses?: InputResponses;
  requestState?: string;
};

/** Fired whenever a tools/call (or a retry) returns an input_required result. */
export interface InputRequiredEvent {
  /** Client-side correlation id, stable across all rounds of one chain. */
  correlationId: number;
  /** 1-based round number within the chain. */
  round: number;
  /** Requests to answer now, keyed by server request id. */
  requests: InputRequests;
  /** Opaque server state to echo byte-exact on the next resend, if sent. */
  requestState: string | undefined;
  /** Params of the ORIGINAL tools/call that started this chain. */
  originalParams: ToolCallParams;
}

export type InputChainOutcome = "final" | "error" | "cancelled";

/** Fired once when a chain ends: final result, error, or cancellation. */
export interface InputChainClosedEvent {
  correlationId: number;
  outcome: InputChainOutcome;
  /** Final tools/call result (outcome "final"). */
  result?: unknown;
  /** Thrown error (outcome "error"). */
  error?: unknown;
}

/** Sends a tools/call retry and resolves with the parsed result. */
export type ToolCallSender = (params: ToolCallRetryParams) => Promise<unknown>;

/**
 * Narrows a parsed tools/call result to InputRequiredResult.
 * A bare resultType without inputRequests AND without requestState is not
 * actionable (the spec requires at least one) and is treated as a final
 * result, so older servers that never heard of SEP-2322 are unaffected.
 */
export const isInputRequiredResult = (
  value: unknown,
): value is InputRequiredResult => {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  if (candidate.resultType !== "input_required") {
    return false;
  }
  const hasRequests = candidate.inputRequests !== undefined;
  const hasState = candidate.requestState !== undefined;
  if (!hasRequests && !hasState) {
    return false;
  }
  if (
    hasRequests &&
    (typeof candidate.inputRequests !== "object" ||
      candidate.inputRequests === null)
  ) {
    return false;
  }
  if (hasState && typeof candidate.requestState !== "string") {
    return false;
  }
  return true;
};

/**
 * Builds the retry params: the ORIGINAL tools/call params verbatim (name,
 * arguments, _meta and everything else survive untouched) plus inputResponses,
 * plus requestState only when the server actually sent one. The spec forbids
 * sending a requestState the server did not issue, so the field is omitted,
 * never `undefined`, when absent. Any stale echo fields that somehow rode in
 * on originalParams are dropped before the new values are set. The input
 * originalParams object is never mutated.
 */
export const buildToolRetryParams = (
  originalParams: ToolCallParams,
  inputResponses: InputResponses,
  requestState?: string,
): ToolCallRetryParams => {
  const params: Record<string, unknown> = { ...originalParams };
  delete params.inputResponses;
  delete params.requestState;
  params.inputResponses = inputResponses;
  if (typeof requestState === "string") {
    params.requestState = requestState;
  }
  return params as ToolCallRetryParams;
};

interface InputChain {
  correlationId: number;
  round: number;
  requests: InputRequests;
  requestState: string | undefined;
  originalParams: ToolCallParams;
}

/**
 * Tracks input_required chains for tools/call. All state is client-side; the
 * protocol has no in-flight request to abort (each round is a fresh tools/call
 * that already completed), so cancellation means: the chain is forgotten,
 * later rounds for it are rejected, and a resend that was already on the wire
 * when cancel() landed fires no further events when it resolves.
 */
export class InputRequiredController {
  private readonly sendToolCall: ToolCallSender;
  private readonly chains = new Map<number, InputChain>();
  private readonly inputRequiredListeners = new Set<
    (event: InputRequiredEvent) => void
  >();
  private readonly chainClosedListeners = new Set<
    (event: InputChainClosedEvent) => void
  >();
  private nextCorrelationId = 1;

  constructor(sendToolCall: ToolCallSender) {
    this.sendToolCall = sendToolCall;
  }

  /** Subscribes to input_required rounds. Returns an unsubscribe function. */
  onInputRequired(listener: (event: InputRequiredEvent) => void): () => void {
    this.inputRequiredListeners.add(listener);
    return () => this.inputRequiredListeners.delete(listener);
  }

  /** Subscribes to chain endings. Returns an unsubscribe function. */
  onChainClosed(listener: (event: InputChainClosedEvent) => void): () => void {
    this.chainClosedListeners.add(listener);
    return () => this.chainClosedListeners.delete(listener);
  }

  /** Snapshot of the chains currently waiting for user input. */
  getPendingChains(): {
    correlationId: number;
    round: number;
    requests: InputRequests;
    requestState: string | undefined;
  }[] {
    return [...this.chains.values()].map((chain) => ({
      correlationId: chain.correlationId,
      round: chain.round,
      requests: chain.requests,
      requestState: chain.requestState,
    }));
  }

  /**
   * Registers a tools/call result that carries input_required. Non-matching
   * results are ignored (returns null), so callers can feed every tools/call
   * result through this single detection point.
   */
  openChain(
    originalParams: ToolCallParams,
    result: unknown,
  ): InputRequiredEvent | null {
    if (!isInputRequiredResult(result)) {
      return null;
    }
    const chain: InputChain = {
      correlationId: this.nextCorrelationId++,
      round: 1,
      requests: result.inputRequests ?? {},
      requestState: result.requestState,
      originalParams,
    };
    this.chains.set(chain.correlationId, chain);
    const event = this.toEvent(chain);
    for (const listener of this.inputRequiredListeners) {
      listener(event);
    }
    return event;
  }

  /**
   * Re-issues the ORIGINAL tools/call with the user's inputResponses and the
   * requestState echoed byte-exact. A retry answered with input_required again
   * advances the SAME correlation chain (round + 1) and fires onInputRequired
   * again; a final result or a thrown error closes it. When correlationId is
   * given, it must belong to a pending chain: cancelled or unknown chains are
   * rejected so a stale panel can never resurrect a dead conversation.
   */
  async resendToolCall(
    originalParams: ToolCallParams,
    inputResponses: InputResponses,
    requestState?: string,
    correlationId?: number,
  ): Promise<unknown> {
    let chain: InputChain | undefined;
    if (correlationId !== undefined) {
      chain = this.chains.get(correlationId);
      if (!chain) {
        throw new Error(
          `input_required chain ${correlationId} is not pending (already closed or cancelled)`,
        );
      }
    }
    const params = buildToolRetryParams(
      originalParams,
      inputResponses,
      requestState,
    );
    let result: unknown;
    try {
      result = await this.sendToolCall(params);
    } catch (error) {
      if (chain) {
        this.chains.delete(chain.correlationId);
        this.emitClosed({
          correlationId: chain.correlationId,
          outcome: "error",
          error,
        });
      }
      throw error;
    }
    // Cancelled while the retry was on the wire: stay silent, the chain is gone.
    if (chain && !this.chains.has(chain.correlationId)) {
      return result;
    }
    if (isInputRequiredResult(result)) {
      if (chain) {
        chain.round += 1;
        chain.requests = result.inputRequests ?? {};
        chain.requestState = result.requestState;
      } else {
        chain = {
          correlationId: this.nextCorrelationId++,
          round: 1,
          requests: result.inputRequests ?? {},
          requestState: result.requestState,
          originalParams,
        };
        this.chains.set(chain.correlationId, chain);
      }
      const event = this.toEvent(chain);
      for (const listener of this.inputRequiredListeners) {
        listener(event);
      }
      return result;
    }
    if (chain) {
      this.chains.delete(chain.correlationId);
      this.emitClosed({
        correlationId: chain.correlationId,
        outcome: "final",
        result,
      });
    }
    return result;
  }

  /**
   * Cancels a chain: it stops being pending, further resends for it are
   * rejected, and a resend already on the wire goes silent when it lands.
   * Returns true when a pending chain was cancelled.
   */
  cancel(correlationId: number): boolean {
    const cancelled = this.chains.delete(correlationId);
    if (cancelled) {
      this.emitClosed({ correlationId, outcome: "cancelled" });
    }
    return cancelled;
  }

  private toEvent(chain: InputChain): InputRequiredEvent {
    return {
      correlationId: chain.correlationId,
      round: chain.round,
      requests: chain.requests,
      requestState: chain.requestState,
      originalParams: chain.originalParams,
    };
  }

  private emitClosed(event: InputChainClosedEvent): void {
    for (const listener of this.chainClosedListeners) {
      listener(event);
    }
  }
}
