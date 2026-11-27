// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28) client
// round-trip tests (#253): result parsing, byte-exact requestState echo,
// multi-round looping, cancellation, and the guard that keeps the legacy
// 2025-06-18 elicitation flow (a server REQUEST on elicitation/create, not a
// tools/call result) and plain final results out of the input_required path.
import {
  buildToolRetryParams,
  InputRequiredController,
  InputRequiredEvent,
  InputChainClosedEvent,
  isInputRequiredResult,
  ToolCallParams,
  ToolCallRetryParams,
} from "../inputRequired";

const originalParams: ToolCallParams = {
  name: "book-flight",
  arguments: { from: "LED", to: "AMS", passengers: 2 },
  _meta: { progressToken: 41 },
};

// Opaque state with bytes a naive client would mangle: non-ASCII, unpaired
// surrogate-safe emoji, base64-style padding. The client must never decode,
// re-encode, or otherwise normalize it.
const requestStateBytes =
  "st.βUn\ud83d\ude00==/+/w==";

const inputRequiredResult = (state?: string) => ({
  resultType: "input_required" as const,
  ...(state === undefined
    ? {}
    : {
        inputRequests: {
          "req-1": {
            method: "elicitation/create",
            params: { message: "Which seat?" },
          },
        },
        requestState: state,
      }),
});

describe("isInputRequiredResult (result parsing)", () => {
  it("accepts a full input_required result", () => {
    const result = {
      resultType: "input_required",
      inputRequests: {
        "req-1": { method: "elicitation/create" },
      },
      requestState: requestStateBytes,
    };
    expect(isInputRequiredResult(result)).toBe(true);
    if (isInputRequiredResult(result)) {
      expect(result.requestState).toBe(requestStateBytes);
    }
  });

  it("accepts a result carrying only inputRequests (spec: at least one field)", () => {
    expect(
      isInputRequiredResult({
        resultType: "input_required",
        inputRequests: { r: { method: "roots/list" } },
      }),
    ).toBe(true);
  });

  it("accepts a result carrying only requestState", () => {
    expect(
      isInputRequiredResult({
        resultType: "input_required",
        requestState: "opaque",
      }),
    ).toBe(true);
  });

  it.each([
    ["null", null],
    ["non-object", "input_required"],
    ["plain final result", { content: [{ type: "text", text: "ok" }] }],
    ["error result", { content: [], isError: true }],
    ["task result", { task: { taskId: "t1", status: "completed" } }],
    ["wrong resultType", { resultType: "done" }],
    [
      "bare resultType without inputRequests and requestState",
      { resultType: "input_required" },
    ],
    [
      "non-object inputRequests",
      { resultType: "input_required", inputRequests: "nope" },
    ],
    [
      "non-string requestState",
      { resultType: "input_required", requestState: 7 },
    ],
  ])("rejects %s", (_label, value) => {
    expect(isInputRequiredResult(value)).toBe(false);
  });
});

describe("buildToolRetryParams (byte-exact echo)", () => {
  it("keeps the original params verbatim and echoes requestState byte-exact", () => {
    const params = buildToolRetryParams(
      originalParams,
      { "req-1": { action: "accept", content: { type: "text", text: "12A" } } },
      requestStateBytes,
    );
    expect(params.name).toBe("book-flight");
    expect(params.arguments).toEqual({
      from: "LED",
      to: "AMS",
      passengers: 2,
    });
    expect(params._meta).toEqual({ progressToken: 41 });
    expect(params.inputResponses).toEqual({
      "req-1": { action: "accept", content: { type: "text", text: "12A" } },
    });
    // Byte-exact echo: same string value, and it survives a JSON round trip
    // untouched (no re-encoding, no normalization of any kind).
    expect(params.requestState).toBe(requestStateBytes);
    expect(JSON.parse(JSON.stringify(params)).requestState).toBe(
      requestStateBytes,
    );
  });

  it("omits requestState when the server did not send one", () => {
    const params = buildToolRetryParams(
      originalParams,
      { "req-1": { action: "decline" } },
      undefined,
    );
    expect(Object.keys(params)).not.toContain("requestState");
    expect(params.inputResponses).toEqual({ "req-1": { action: "decline" } });
  });

  it("drops stale echo fields riding on originalParams and never mutates the input", () => {
    const stale: ToolCallParams = {
      ...originalParams,
      requestState: "stale-state",
      inputResponses: { old: true },
    } as ToolCallParams;
    const params = buildToolRetryParams(stale, { fresh: 1 }, "new-state");
    expect(params.requestState).toBe("new-state");
    expect(params.inputResponses).toEqual({ fresh: 1 });
    expect((stale as Record<string, unknown>).requestState).toBe("stale-state");
    expect((stale as Record<string, unknown>).inputResponses).toEqual({
      old: true,
    });
  });

  it("drops a stale requestState riding on originalParams when the new round sends none", () => {
    const stale: ToolCallParams = {
      ...originalParams,
      requestState: "stale-state",
    } as ToolCallParams;
    const params = buildToolRetryParams(stale, { fresh: 1 }, undefined);
    expect(Object.keys(params)).not.toContain("requestState");
    expect(params.requestState).toBeUndefined();
    expect((stale as Record<string, unknown>).requestState).toBe("stale-state");
  });
});

const makeController = (
  script: Array<unknown | Error>,
): {
  controller: InputRequiredController;
  sent: ToolCallRetryParams[];
} => {
  const sent: ToolCallRetryParams[] = [];
  let call = 0;
  const controller = new InputRequiredController(async (params) => {
    sent.push(params);
    const reply = script[Math.min(call, script.length - 1)];
    call += 1;
    if (reply instanceof Error) {
      throw reply;
    }
    return reply;
  });
  return { controller, sent };
};

describe("InputRequiredController (multi-round looping)", () => {
  it("loops input_required rounds on one correlation id until the final result", async () => {
    const { controller, sent } = makeController([
      inputRequiredResult("state-B"),
      { content: [{ type: "text", text: "booked 12A" }], isError: false },
    ]);
    const events: InputRequiredEvent[] = [];
    controller.onInputRequired((event) => events.push(event));
    const closed: InputChainClosedEvent[] = [];
    controller.onChainClosed((event) => closed.push(event));

    const first = controller.openChain(originalParams, inputRequiredResult("state-A"));
    expect(first?.round).toBe(1);
    expect(first?.requestState).toBe("state-A");
    expect(first?.requests).toHaveProperty("req-1");

    const midResult = await controller.resendToolCall(
      originalParams,
      { "req-1": { action: "accept" } },
      "state-A",
      first?.correlationId,
    );
    expect(isInputRequiredResult(midResult)).toBe(true);
    expect(events.map((e) => [e.correlationId, e.round])).toEqual([
      [first?.correlationId, 1],
      [first?.correlationId, 2],
    ]);
    expect(sent).toHaveLength(1);
    expect(sent[0].requestState).toBe("state-A");
    expect(sent[0].inputResponses).toEqual({ "req-1": { action: "accept" } });
    expect(sent[0].name).toBe("book-flight");

    const finalResult = await controller.resendToolCall(
      originalParams,
      { "req-2": { action: "accept" } },
      "state-B",
      first?.correlationId,
    );
    expect(finalResult).toEqual({
      content: [{ type: "text", text: "booked 12A" }],
      isError: false,
    });
    // Round 2 echo carries the LATEST server state, never the round 1 one.
    expect(sent[1].requestState).toBe("state-B");
    expect(closed).toEqual([
      {
        correlationId: first?.correlationId,
        outcome: "final",
        result: finalResult,
      },
    ]);
    expect(controller.getPendingChains()).toEqual([]);
  });

  it("opens a chain even for a correlation-less resend answered with input_required", async () => {
    const { controller } = makeController([inputRequiredResult("state-X")]);
    const events: InputRequiredEvent[] = [];
    controller.onInputRequired((event) => events.push(event));
    await controller.resendToolCall(
      originalParams,
      { "req-1": { action: "accept" } },
      undefined,
    );
    expect(events).toHaveLength(1);
    expect(events[0].round).toBe(1);
    expect(events[0].requestState).toBe("state-X");
    expect(controller.getPendingChains()).toHaveLength(1);
  });

  it("closes the chain with outcome error when the retry throws", async () => {
    const { controller } = makeController([new Error("connection lost")]);
    const closed: InputChainClosedEvent[] = [];
    controller.onChainClosed((event) => closed.push(event));
    const chain = controller.openChain(
      originalParams,
      inputRequiredResult("state-A"),
    );
    await expect(
      controller.resendToolCall(
        originalParams,
        {},
        "state-A",
        chain?.correlationId,
      ),
    ).rejects.toThrow("connection lost");
    expect(closed).toEqual([
      {
        correlationId: chain?.correlationId,
        outcome: "error",
        error: new Error("connection lost"),
      },
    ]);
    expect(controller.getPendingChains()).toEqual([]);
  });
});

describe("InputRequiredController (cancellation)", () => {
  it("rejects resends for a cancelled chain and reports the cancellation once", async () => {
    const { controller } = makeController([inputRequiredResult("state-A")]);
    const closed: InputChainClosedEvent[] = [];
    controller.onChainClosed((event) => closed.push(event));
    const chain = controller.openChain(
      originalParams,
      inputRequiredResult("state-A"),
    );
    const id = chain?.correlationId as number;

    expect(controller.cancel(id)).toBe(true);
    expect(closed).toEqual([{ correlationId: id, outcome: "cancelled" }]);
    expect(controller.cancel(id)).toBe(false);
    expect(closed).toHaveLength(1);

    await expect(
      controller.resendToolCall(originalParams, {}, "state-A", id),
    ).rejects.toThrow(/not pending/);
  });

  it("silences a resend that was on the wire when cancel() landed", async () => {
    let resolveSend: (value: unknown) => void = () => {};
    const controller = new InputRequiredController(
      () =>
        new Promise((resolve) => {
          resolveSend = resolve;
        }),
    );
    const events: InputRequiredEvent[] = [];
    const closed: InputChainClosedEvent[] = [];
    controller.onInputRequired((event) => events.push(event));
    controller.onChainClosed((event) => closed.push(event));
    const chain = controller.openChain(
      originalParams,
      inputRequiredResult("state-A"),
    );
    const id = chain?.correlationId as number;

    const inFlight = controller.resendToolCall(
      originalParams,
      { "req-1": { action: "accept" } },
      "state-A",
      id,
    );
    controller.cancel(id);
    resolveSend(inputRequiredResult("state-B"));
    const result = await inFlight;

    // The wire result is returned to the caller, but the cancelled chain stays
    // dead: no round 2 event, no re-registered chain.
    expect(isInputRequiredResult(result)).toBe(true);
    expect(events).toHaveLength(1);
    expect(controller.getPendingChains()).toEqual([]);
    expect(closed).toEqual([{ correlationId: id, outcome: "cancelled" }]);
  });
});

describe("legacy elicitation regression guard", () => {
  it("ignores legacy flows: final tools/call results never open a chain", () => {
    const { controller } = makeController([
      { content: [{ type: "text", text: "done" }] },
    ]);
    const events: InputRequiredEvent[] = [];
    controller.onInputRequired((event) => events.push(event));

    // The legacy 2025-06-18 flow is a server REQUEST on elicitation/create
    // delivered over SSE and handled by onElicitationRequest in useConnection;
    // its confirmation shape is a RESULT of that request, not of tools/call.
    expect(
      controller.openChain(originalParams, {
        content: [
          {
            type: "text",
            text: 'Elicitation accepted with action "accept"',
          },
        ],
      }),
    ).toBeNull();
    expect(
      controller.openChain(originalParams, { content: [], isError: false }),
    ).toBeNull();
    expect(events).toEqual([]);
    expect(controller.getPendingChains()).toEqual([]);
  });
});
