// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28) unit
// tests for the Timeline chain threading (#253): input_required round-trips
// (original tools/call, input_required result, re-send, final result) must
// share one chain id anchored at the ORIGINAL call's pair correlation id,
// while unrelated traffic stays untouched.
import {
  threadInputRequiredChains,
  summarizeChain,
  ChainSourceEvent,
} from "../timelineChains";

let nextEventId = 0;

const request = (
  correlationId: string,
  timestamp: string,
  options: {
    requestState?: string;
    name?: string;
    sessionId?: string | null;
  } = {},
): ChainSourceEvent => ({
  id: `e${(nextEventId += 1)}`,
  correlationId,
  sessionId: options.sessionId ?? null,
  type: "MCP_JSONRPC_REQUEST",
  timestamp,
  payload: {
    jsonrpc: "2.0",
    id: nextEventId,
    method: "tools/call",
    params: {
      name: options.name ?? "book_seat",
      arguments: { seat: "12A" },
      ...(options.requestState === undefined
        ? {}
        : { requestState: options.requestState }),
    },
  },
});

const response = (
  correlationId: string,
  timestamp: string,
  options: {
    result?: Record<string, unknown>;
    error?: Record<string, unknown>;
    sessionId?: string | null;
  } = {},
): ChainSourceEvent => ({
  id: `e${(nextEventId += 1)}`,
  correlationId,
  sessionId: options.sessionId ?? null,
  type: "MCP_JSONRPC_RESPONSE",
  timestamp,
  payload: {
    jsonrpc: "2.0",
    id: nextEventId,
    ...(options.error ? { error: options.error } : { result: options.result ?? {} }),
  },
});

const inputRequiredResult = (requestState?: string) => ({
  resultType: "input_required",
  ...(requestState === undefined
    ? {}
    : {
        inputRequests: {
          "req-1": { method: "elicitation/create", params: { message: "Which seat?" } },
        },
        requestState,
      }),
});

const finalResult = () => ({
  content: [{ type: "text", text: "Booked." }],
});

describe("threadInputRequiredChains (SEP-2322 Timeline correlation)", () => {
  it("threads a single-round round-trip into one chain anchored at the original call", () => {
    const threaded = threadInputRequiredChains([
      request("corr-1", "2026-11-02T10:00:00.000Z"),
      response("corr-1", "2026-11-02T10:00:01.000Z", {
        result: inputRequiredResult("S1"),
      }),
      request("corr-2", "2026-11-02T10:00:05.000Z", { requestState: "S1" }),
      response("corr-2", "2026-11-02T10:00:06.000Z", { result: finalResult() }),
    ]);
    expect(threaded.map((event) => event.chainId)).toEqual([
      "corr-1",
      "corr-1",
      "corr-1",
      "corr-1",
    ]);
  });

  it("threads a multi-round chain (3 rounds) onto the original pair id", () => {
    const threaded = threadInputRequiredChains([
      request("p1", "2026-11-02T10:00:00.000Z"),
      response("p1", "2026-11-02T10:00:01.000Z", {
        result: inputRequiredResult("S1"),
      }),
      request("p2", "2026-11-02T10:00:05.000Z", { requestState: "S1" }),
      response("p2", "2026-11-02T10:00:06.000Z", {
        result: inputRequiredResult("S2"),
      }),
      request("p3", "2026-11-02T10:00:10.000Z", { requestState: "S2" }),
      response("p3", "2026-11-02T10:00:11.000Z", { result: finalResult() }),
    ]);
    const chainIds = new Set(threaded.map((event) => event.chainId));
    expect(chainIds).toEqual(new Set(["p1"]));
    const summary = summarizeChain(threaded);
    expect(summary).toEqual({
      rounds: 3,
      toolName: "book_seat",
      state: "completed",
    });
  });

  it("keeps interleaved chains with distinct requestStates disjoint", () => {
    const threaded = threadInputRequiredChains([
      request("a1", "2026-11-02T10:00:00.000Z"),
      request("b1", "2026-11-02T10:00:00.500Z", { name: "other_tool" }),
      response("a1", "2026-11-02T10:00:01.000Z", {
        result: inputRequiredResult("SA"),
      }),
      response("b1", "2026-11-02T10:00:01.500Z", {
        result: inputRequiredResult("SB"),
      }),
      request("a2", "2026-11-02T10:00:05.000Z", { requestState: "SA" }),
      request("b2", "2026-11-02T10:00:05.500Z", {
        requestState: "SB",
        name: "other_tool",
      }),
      response("a2", "2026-11-02T10:00:06.000Z", { result: finalResult() }),
      response("b2", "2026-11-02T10:00:06.500Z", { result: finalResult() }),
    ]);
    expect(threaded.map((event) => event.chainId)).toEqual([
      "a1",
      "b1",
      "a1",
      "b1",
      "a1",
      "b1",
      "a1",
      "b1",
    ]);
  });

  it("does not join a round that carries no requestState (graceful degradation)", () => {
    const threaded = threadInputRequiredChains([
      request("p1", "2026-11-02T10:00:00.000Z"),
      response("p1", "2026-11-02T10:00:01.000Z", {
        result: inputRequiredResult(undefined),
      }),
      request("p2", "2026-11-02T10:00:05.000Z"),
      response("p2", "2026-11-02T10:00:06.000Z", { result: finalResult() }),
    ]);
    expect(threaded.every((event) => event.chainId === null)).toBe(true);
  });

  it("leaves unrelated JSON-RPC traffic untouched", () => {
    const threaded = threadInputRequiredChains([
      {
        id: "n1",
        correlationId: "ping",
        sessionId: null,
        type: "MCP_JSONRPC_REQUEST",
        timestamp: "2026-11-02T10:00:00.000Z",
        payload: { jsonrpc: "2.0", id: 1, method: "ping", params: {} },
      },
      {
        id: "n2",
        correlationId: "ping",
        sessionId: null,
        type: "MCP_JSONRPC_RESPONSE",
        timestamp: "2026-11-02T10:00:00.100Z",
        payload: { jsonrpc: "2.0", id: 1, result: {} },
      },
      {
        id: "n3",
        correlationId: null,
        sessionId: null,
        type: "MCP_JSONRPC_NOTIFICATION",
        timestamp: "2026-11-02T10:00:00.200Z",
        payload: { jsonrpc: "2.0", method: "notifications/initialized" },
      },
    ]);
    expect(threaded.every((event) => event.chainId === null)).toBe(true);
  });

  it("is order-agnostic: works on the API's newest-first list", () => {
    const events = [
      request("corr-1", "2026-11-02T10:00:00.000Z"),
      response("corr-1", "2026-11-02T10:00:01.000Z", {
        result: inputRequiredResult("S1"),
      }),
      request("corr-2", "2026-11-02T10:00:05.000Z", { requestState: "S1" }),
      response("corr-2", "2026-11-02T10:00:06.000Z", { result: finalResult() }),
    ];
    const threaded = threadInputRequiredChains([...events].reverse());
    expect(threaded.map((event) => event.chainId)).toEqual([
      "corr-1",
      "corr-1",
      "corr-1",
      "corr-1",
    ]);
  });

  it("folds stream events sharing a pair correlation into the chain", () => {
    const threaded = threadInputRequiredChains([
      request("p1", "2026-11-02T10:00:00.000Z"),
      response("p1", "2026-11-02T10:00:01.000Z", {
        result: inputRequiredResult("S1"),
      }),
      {
        id: "s1",
        correlationId: "p1",
        sessionId: null,
        type: "MCP_STREAM_EVENT",
        timestamp: "2026-11-02T10:00:01.500Z",
        payload: { jsonrpc: "2.0", method: "notifications/progress" },
      },
      request("p2", "2026-11-02T10:00:05.000Z", { requestState: "S1" }),
      response("p2", "2026-11-02T10:00:06.000Z", { result: finalResult() }),
    ]);
    const streamEvent = threaded.find((event) => event.id === "s1");
    expect(streamEvent?.chainId).toBe("p1");
  });

  it("never joins a re-send earlier than the input_required answer", () => {
    // A previous chain re-used the same opaque state string; the resend that
    // PRECEDES the answer must not be attached to it.
    const threaded = threadInputRequiredChains([
      request("old-send", "2026-11-02T09:00:00.000Z", { requestState: "S1" }),
      response("old-send", "2026-11-02T09:00:01.000Z", { result: finalResult() }),
      request("p1", "2026-11-02T10:00:00.000Z"),
      response("p1", "2026-11-02T10:00:01.000Z", {
        result: inputRequiredResult("S1"),
      }),
    ]);
    const byCorrelation = new Map(
      threaded.map((event) => [event.correlationId, event.chainId]),
    );
    expect(byCorrelation.get("old-send")).toBeNull();
    expect(byCorrelation.get("p1")).toBeNull();
  });

  it("scopes the join to one session", () => {
    const threaded = threadInputRequiredChains([
      request("s1a", "2026-11-02T10:00:00.000Z", { sessionId: "sess-1" }),
      response("s1a", "2026-11-02T10:00:01.000Z", {
        sessionId: "sess-1",
        result: inputRequiredResult("S1"),
      }),
      request("s2b", "2026-11-02T10:00:02.000Z", {
        requestState: "S1",
        sessionId: "sess-2",
      }),
      request("s1b", "2026-11-02T10:00:03.000Z", {
        requestState: "S1",
        sessionId: "sess-1",
      }),
    ]);
    const byCorrelation = new Map(
      threaded.map((event) => [event.correlationId, event.chainId]),
    );
    expect(byCorrelation.get("s2b")).toBeNull();
    expect(byCorrelation.get("s1b")).toBe("s1a");
    expect(byCorrelation.get("s1a")).toBe("s1a");
  });
});

describe("summarizeChain (chain rendering facts)", () => {
  const chainOf = (...events: ChainSourceEvent[]) =>
    threadInputRequiredChains(events).filter(
      (event) => event.chainId !== null,
    ) as ChainSourceEvent[];

  it("reports awaiting-input while the last answer still needs input", () => {
    const chain = chainOf(
      request("p1", "2026-11-02T10:00:00.000Z"),
      response("p1", "2026-11-02T10:00:01.000Z", {
        result: inputRequiredResult("S1"),
      }),
      request("p2", "2026-11-02T10:00:05.000Z", { requestState: "S1" }),
      response("p2", "2026-11-02T10:00:06.000Z", {
        result: inputRequiredResult("S2"),
      }),
    );
    expect(summarizeChain(chain)).toEqual({
      rounds: 2,
      toolName: "book_seat",
      state: "awaiting-input",
    });
  });

  it("reports error when the last answer is a JSON-RPC error", () => {
    const chain = chainOf(
      request("p1", "2026-11-02T10:00:00.000Z"),
      response("p1", "2026-11-02T10:00:01.000Z", {
        result: inputRequiredResult("S1"),
      }),
      request("p2", "2026-11-02T10:00:05.000Z", { requestState: "S1" }),
      response("p2", "2026-11-02T10:00:06.000Z", {
        error: { code: -32602, message: "requestState mismatch" },
      }),
    );
    expect(summarizeChain(chain)).toEqual({
      rounds: 2,
      toolName: "book_seat",
      state: "error",
    });
  });
});
