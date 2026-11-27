// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28) Timeline
// chain threading for input_required tools/call round-trips (#253). The
// server-side recorder (McpTrafficRecorder) already correlates one JSON-RPC
// request with its own response (a pair shares one correlation id), but a
// multi-round input_required chain spans SEVERAL request/response pairs: the
// original tools/call, each re-send, and the final result. This module threads
// those pairs into one chain on the client side, reusing the server's pair
// correlation and joining consecutive rounds through the spec's opaque
// requestState: round k answers with requestState S, and the round k+1 re-send
// echoes S verbatim in its params, so "input_required response carrying S" ->
// "next tools/call request carrying S" is the join edge. requestState is
// compared as an opaque string only (never parsed, mutated, or re-encoded).
// A round that carries no requestState simply does not join: graceful
// degradation, those pairs render exactly as before this patch.
import { isInputRequiredResult } from "./inputRequired";

/** Minimal timeline event shape this threading needs (REST /api/timeline). */
export interface ChainSourceEvent {
  id: string;
  correlationId: string | null;
  sessionId: string | null;
  type: string;
  timestamp: string;
  payload: Record<string, unknown> | null;
}

/** A timeline event annotated with the threaded chain id (null: no chain). */
export type ThreadedEvent<T extends ChainSourceEvent> = T & {
  chainId: string | null;
};

/** Render-facing summary of one threaded chain. */
export interface ChainSummary {
  /** Number of tools/call requests in the chain (rounds, re-sends included). */
  rounds: number;
  /** Tool name from the chain's tools/call requests, when present. */
  toolName: string | null;
  /** Chain state derived from its chronologically last tools/call response. */
  state: "awaiting-input" | "completed" | "error";
}

interface RequestFacts {
  isToolCall: boolean;
  /** requestState echoed in params by a re-send, when present. */
  requestState: string | undefined;
  toolName: string | null;
}

interface ResponseFacts {
  isToolCall: boolean;
  /** True when the paired result is an input_required result. */
  isInputRequired: boolean;
  /** requestState carried by that input_required result, when present. */
  requestState: string | undefined;
  isError: boolean;
}

const NO_REQUEST: RequestFacts = {
  isToolCall: false,
  requestState: undefined,
  toolName: null,
};

const NO_RESPONSE: ResponseFacts = {
  isToolCall: false,
  isInputRequired: false,
  requestState: undefined,
  isError: false,
};

const asRecord = (
  value: unknown,
): Record<string, unknown> | null =>
  value !== null && typeof value === "object"
    ? (value as Record<string, unknown>)
    : null;

const factsOfRequest = (event: ChainSourceEvent): RequestFacts => {
  const payload = event.payload ?? {};
  if (event.type !== "MCP_JSONRPC_REQUEST" || payload["method"] !== "tools/call") {
    return NO_REQUEST;
  }
  const params = asRecord(payload["params"]) ?? {};
  const rawState = params["requestState"];
  const rawName = params["name"];
  return {
    isToolCall: true,
    requestState: typeof rawState === "string" ? rawState : undefined,
    toolName: typeof rawName === "string" ? rawName : null,
  };
};

const factsOfResponse = (event: ChainSourceEvent): ResponseFacts => {
  const payload = event.payload ?? {};
  if (event.type !== "MCP_JSONRPC_RESPONSE") {
    return NO_RESPONSE;
  }
  // A response is a tools/call response only through its pair correlation:
  // the caller marks requests first and passes hasToolCallPair for the group.
  const result = payload["result"];
  if (isInputRequiredResult(result)) {
    return {
      isToolCall: true,
      isInputRequired: true,
      requestState:
        typeof result.requestState === "string" ? result.requestState : undefined,
      isError: false,
    };
  }
  return {
    isToolCall: true,
    isInputRequired: false,
    requestState: undefined,
    isError: asRecord(payload["error"]) !== null,
  };
};

interface Group {
  correlationId: string;
  events: number[];
  hasToolCallRequest: boolean;
  /** Latest input_required response state in the group (normally the only one). */
  joinState: { state: string; sessionId: string | null; timeMs: number; index: number } | null;
  /** Earliest tools/call request in the group. */
  earliestRequest: { timeMs: number; index: number } | null;
}

/**
 * Threads input_required round-trip pairs into chains. Pure function over the
 * fetched timeline events; order-agnostic (timestamps decide, the API happens
 * to return newest-first). Events whose component spans a single correlation
 * pair get chainId null and must render exactly as before this patch.
 */
export const threadInputRequiredChains = <T extends ChainSourceEvent>(
  events: T[],
): ThreadedEvent<T>[] => {
  const timeOf = (event: ChainSourceEvent): number => {
    const parsed = Date.parse(event.timestamp);
    return Number.isNaN(parsed) ? 0 : parsed;
  };

  const requests = events.map(factsOfRequest);

  // Union-find over pair correlation ids.
  const parent = new Map<string, string>();
  const find = (key: string): string => {
    let root = parent.get(key) ?? key;
    while (root !== (parent.get(root) ?? root)) {
      root = parent.get(root) ?? root;
    }
    // Path compression
    let current = key;
    while (current !== root) {
      const next = parent.get(current) ?? root;
      parent.set(current, root);
      current = next;
    }
    return root;
  };
  const union = (a: string, b: string): void => {
    const ra = find(a);
    const rb = find(b);
    if (ra !== rb) {
      parent.set(ra, rb);
    }
  };

  // Group events by pair correlation id, then extract join facts per group.
  const groups = new Map<string, Group>();
  events.forEach((event, index) => {
    if (event.correlationId === null) {
      return;
    }
    let group = groups.get(event.correlationId);
    if (!group) {
      group = {
        correlationId: event.correlationId,
        events: [],
        hasToolCallRequest: false,
        joinState: null,
        earliestRequest: null,
      };
      groups.set(event.correlationId, group);
    }
    group.events.push(index);
    const request = requests[index];
    if (request.isToolCall) {
      group.hasToolCallRequest = true;
      const time = timeOf(event);
      if (
        !group.earliestRequest ||
        time < group.earliestRequest.timeMs ||
        (time === group.earliestRequest.timeMs &&
          index > group.earliestRequest.index)
      ) {
        group.earliestRequest = { timeMs: time, index };
      }
    }
  });

  // Responses only count as tools/call responses inside groups that own a
  // tools/call request (the server pairs them by JSON-RPC id).
  for (const group of groups.values()) {
    for (const index of group.events) {
      if (requests[index].isToolCall) {
        continue;
      }
      const facts = factsOfResponse(events[index]);
      if (!facts.isToolCall) {
        continue;
      }
      if (facts.isInputRequired && facts.requestState !== undefined) {
        const event = events[index];
        const time = timeOf(event);
        if (
          !group.joinState ||
          time > group.joinState.timeMs ||
          (time === group.joinState.timeMs && index < group.joinState.index)
        ) {
          group.joinState = {
            state: facts.requestState,
            sessionId: event.sessionId,
            timeMs: time,
            index,
          };
        }
      }
    }
  }

  // Join edges: an input_required response carrying state S links to the
  // EARLIEST later tools/call request echoing S in the same session. Ties on
  // identical milliseconds use the API's newest-first order (a later insert
  // sits at a lower index), which the service documents for its ring buffer.
  const strictlyLater = (
    candidate: { timeMs: number; index: number },
    origin: { timeMs: number; index: number },
  ): boolean =>
    candidate.timeMs > origin.timeMs ||
    (candidate.timeMs === origin.timeMs && candidate.index < origin.index);

  for (const originGroup of groups.values()) {
    const origin = originGroup.joinState;
    if (!origin) {
      continue;
    }
    let best: { group: Group; timeMs: number; index: number } | null = null;
    for (const candidateGroup of groups.values()) {
      if (candidateGroup.correlationId === originGroup.correlationId) {
        continue;
      }
      for (const index of candidateGroup.events) {
        const request = requests[index];
        if (!request.isToolCall || request.requestState !== origin.state) {
          continue;
        }
        const event = events[index];
        if (
          event.sessionId !== null &&
          origin.sessionId !== null &&
          event.sessionId !== origin.sessionId
        ) {
          continue;
        }
        const candidate = { timeMs: timeOf(event), index };
        if (!strictlyLater(candidate, origin)) {
          continue;
        }
        if (
          !best ||
          candidate.timeMs < best.timeMs ||
          (candidate.timeMs === best.timeMs && candidate.index < best.index)
        ) {
          best = { group: candidateGroup, ...candidate };
        }
      }
    }
    if (best) {
      union(originGroup.correlationId, best.group.correlationId);
    }
  }

  // Chain id per component: the correlation id of the component's ORIGINAL
  // call, i.e. the pair holding the chronologically earliest tools/call
  // request. Components of a single pair are not chains.
  const chainIdByRoot = new Map<
    string,
    { correlationId: string; timeMs: number; index: number }
  >();
  for (const group of groups.values()) {
    if (!group.earliestRequest) {
      continue;
    }
    const root = find(group.correlationId);
    const incumbent = chainIdByRoot.get(root);
    if (
      incumbent === undefined ||
      group.earliestRequest.timeMs < incumbent.timeMs ||
      (group.earliestRequest.timeMs === incumbent.timeMs &&
        group.earliestRequest.index > incumbent.index)
    ) {
      chainIdByRoot.set(root, {
        correlationId: group.correlationId,
        timeMs: group.earliestRequest.timeMs,
        index: group.earliestRequest.index,
      });
    }
  }

  const componentSize = new Map<string, number>();
  for (const group of groups.values()) {
    const root = find(group.correlationId);
    componentSize.set(root, (componentSize.get(root) ?? 0) + 1);
  }

  return events.map((event) => {
    if (event.correlationId === null) {
      return { ...event, chainId: null };
    }
    const root = find(event.correlationId);
    const isChain = (componentSize.get(root) ?? 0) > 1;
    return {
      ...event,
      chainId: isChain
        ? ((chainIdByRoot.get(root) ?? { correlationId: event.correlationId })
            .correlationId as string)
        : null,
    };
  });
};

/**
 * Summarizes one threaded chain for rendering. `events` must be the members
 * of a single chain (same non-null chainId).
 */
export const summarizeChain = (
  events: ChainSourceEvent[],
): ChainSummary => {
  let rounds = 0;
  let toolName: string | null = null;
  let lastResponse: { facts: ResponseFacts; timeMs: number; index: number } | null =
    null;
  const timeOf = (event: ChainSourceEvent): number => {
    const parsed = Date.parse(event.timestamp);
    return Number.isNaN(parsed) ? 0 : parsed;
  };
  for (let index = 0; index < events.length; index += 1) {
    const event = events[index];
    const request = factsOfRequest(event);
    if (request.isToolCall) {
      rounds += 1;
      toolName = toolName ?? request.toolName;
    } else if (event.type === "MCP_JSONRPC_RESPONSE") {
      const facts = factsOfResponse(event);
      const time = timeOf(event);
      if (
        lastResponse === null ||
        time > lastResponse.timeMs ||
        (time === lastResponse.timeMs && index < lastResponse.index)
      ) {
        lastResponse = { facts, timeMs: time, index };
      }
    }
  }
  const state: ChainSummary["state"] = lastResponse
    ? lastResponse.facts.isInputRequired
      ? "awaiting-input"
      : lastResponse.facts.isError
        ? "error"
        : "completed"
    : "awaiting-input";
  return { rounds, toolName, state };
};
