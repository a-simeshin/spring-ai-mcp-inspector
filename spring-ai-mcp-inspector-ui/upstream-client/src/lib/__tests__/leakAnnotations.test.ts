// [spring-ai-mcp-inspector PATCH] leakAnnotations unit tests (#251): defensive
// parsing of the D5 wire shape, the tool-result join, display caps and the
// cross-tab scroll broker. Garbage inputs must always yield "absent".
import {
  capLeakText,
  currentToolCallRequestId,
  fetchTimelineEvents,
  findLeakForToolResult,
  leakClock,
  noteOutgoingRequestId,
  onTimelineScrollTo,
  parseLeakAnnotation,
  requestTimelineScrollTo,
  toolResultErrorText,
} from "../leakAnnotations";

const VALID_LEAK = {
  matchType: "EXACT",
  verbatim: true,
  logEventId: "evt-log-1",
  logTimestamp: "2026-08-30T12:00:04.100Z",
  logMessage: "E11000 duplicate key error collection: db.app",
  logThrowableClass: "org.springframework.dao.DuplicateKeyException",
  multipleCandidates: false,
  droppedCandidates: 0,
};

describe("parseLeakAnnotation", () => {
  it("parses a well-formed D5 node", () => {
    const leak = parseLeakAnnotation({ _leak: { ...VALID_LEAK } });
    expect(leak).toEqual({
      matchType: "EXACT",
      verbatim: true,
      logEventId: "evt-log-1",
      logTimestamp: "2026-08-30T12:00:04.100Z",
      logMessage: "E11000 duplicate key error collection: db.app",
      logThrowableClass: "org.springframework.dao.DuplicateKeyException",
      multipleCandidates: false,
      droppedCandidates: 0,
    });
  });

  it("returns null when the payload or the node is absent or not an object", () => {
    expect(parseLeakAnnotation(null)).toBeNull();
    expect(parseLeakAnnotation(undefined)).toBeNull();
    expect(parseLeakAnnotation({})).toBeNull();
    expect(parseLeakAnnotation({ _leak: null })).toBeNull();
    expect(parseLeakAnnotation({ _leak: "garbage" })).toBeNull();
    expect(parseLeakAnnotation({ _leak: 42 })).toBeNull();
  });

  it("returns null for a malformed matchType", () => {
    expect(
      parseLeakAnnotation({ _leak: { ...VALID_LEAK, matchType: "WEIRD" } }),
    ).toBeNull();
    expect(
      parseLeakAnnotation({ _leak: { ...VALID_LEAK, matchType: 7 } }),
    ).toBeNull();
    expect(
      parseLeakAnnotation({ _leak: { ...VALID_LEAK, matchType: undefined } }),
    ).toBeNull();
  });

  it("returns null when a required string field is missing or wrong-typed", () => {
    expect(
      parseLeakAnnotation({ _leak: { ...VALID_LEAK, logEventId: 1 } }),
    ).toBeNull();
    expect(
      parseLeakAnnotation({
        _leak: { ...VALID_LEAK, logTimestamp: undefined },
      }),
    ).toBeNull();
    expect(
      parseLeakAnnotation({ _leak: { ...VALID_LEAK, logMessage: null } }),
    ).toBeNull();
  });

  it("tolerates absent verbatim, throwable, multipleCandidates and droppedCandidates", () => {
    const minimal = {
      matchType: "EXACT",
      logEventId: "evt-log-1",
      logTimestamp: "2026-08-30T12:00:04.100Z",
      logMessage: "E11000 duplicate key error collection: db.app",
    };
    const leak = parseLeakAnnotation({ _leak: minimal });
    expect(leak).not.toBeNull();
    expect(leak?.verbatim).toBe(false);
    expect(leak?.logThrowableClass).toBeNull();
    expect(leak?.multipleCandidates).toBe(false);
    expect(leak?.droppedCandidates).toBe(0);
    // An explicit JSON null throwable (the engine serializes null) stays null.
    expect(
      parseLeakAnnotation({
        _leak: { ...VALID_LEAK, logThrowableClass: null },
      })?.logThrowableClass,
    ).toBeNull();
  });
});

describe("toolResultErrorText", () => {
  it("joins text blocks with newlines", () => {
    expect(
      toolResultErrorText({
        content: [
          { type: "text", text: "first" },
          { type: "image", data: "zzz" },
          { type: "text", text: "second" },
        ],
      }),
    ).toBe("first\nsecond");
  });

  it("returns null for absent, empty or non-text content", () => {
    expect(toolResultErrorText(null)).toBeNull();
    expect(toolResultErrorText({})).toBeNull();
    expect(toolResultErrorText({ content: "nope" })).toBeNull();
    expect(
      toolResultErrorText({ content: [{ type: "image", data: "x" }] }),
    ).toBeNull();
    expect(toolResultErrorText({ content: [{ type: "text", text: "  " }] })).toBeNull();
    expect(toolResultErrorText({ content: [] })).toBeNull();
  });
});

describe("findLeakForToolResult", () => {
  // A timeline response frame: `rpcId` is the JSON-RPC request id the client
  // put on the wire; `eventId` is the timeline event's own id.
  const response = (
    eventId: string,
    rpcId: number | string,
    text: string,
    matchType?: string,
  ) => ({
    id: eventId,
    type: "MCP_JSONRPC_RESPONSE",
    payload: {
      jsonrpc: "2.0",
      id: rpcId,
      result: { content: [{ type: "text", text }], isError: true },
      ...(matchType
        ? { _leak: { ...VALID_LEAK, matchType, logEventId: rpcId + "-log" } }
        : {}),
    },
  });
  const annotated = (rpcId: number | string, text: string) =>
    response("evt-" + rpcId, rpcId, text, "EXACT");
  const plain = (rpcId: number | string, text: string) =>
    response("evt-" + rpcId, rpcId, text);

  it("joins the pane's own frame by request id: a repeated failing call never borrows the older call's note", () => {
    // Two identical failing calls with the same error text; only the FIRST
    // response was annotated. Events arrive newest-first, the pane displays
    // the second call.
    const events = [plain(2, "boom\nboom"), annotated(1, "boom\nboom")];
    // The displayed call's own frame carries no `_leak`: no note, even though
    // an annotated frame with an equal text exists.
    expect(findLeakForToolResult(events, 2)).toBeNull();
    // The first call joined to its own frame still resolves its annotation.
    expect(findLeakForToolResult(events, 1)?.logEventId).toBe("1-log");
  });

  it("resolves a duplicate request id to the newest frame (reconnect restarts the id counter)", () => {
    const events = [plain(4, "boom"), annotated(4, "boom")];
    expect(findLeakForToolResult(events, 4)).toBeNull();
  });

  it("matches the frame id across number and string encodings", () => {
    expect(findLeakForToolResult([annotated(7, "boom")], "7")?.logEventId).toBe(
      "7-log",
    );
    expect(findLeakForToolResult([response("evt-s", "7", "boom", "EXACT")], 7)?.logEventId).toBe(
      "7-log",
    );
  });

  it("skips non-response events and frames with other request ids", () => {
    const events = [
      { id: "l", type: "APP_LOG", payload: { message: "boom" } },
      {
        id: "r",
        type: "MCP_JSONRPC_RESPONSE",
        payload: {
          jsonrpc: "2.0",
          id: 99,
          result: { content: [{ type: "text", text: "boom" }] },
        },
      },
      annotated(5, "boom"),
    ];
    expect(findLeakForToolResult(events, 5)?.logEventId).toBe("5-log");
    expect(findLeakForToolResult(events, 99)).toBeNull();
    expect(findLeakForToolResult(events, 42)).toBeNull();
  });

  it("yields null when the own frame's annotation is malformed", () => {
    const events = [
      {
        id: "evt-x",
        type: "MCP_JSONRPC_RESPONSE",
        payload: {
          jsonrpc: "2.0",
          id: 3,
          result: { content: [{ type: "text", text: "boom" }] },
          _leak: { matchType: "WEIRD" },
        },
      },
    ];
    expect(findLeakForToolResult(events, 3)).toBeNull();
  });

  it("returns null for absent or unusable request ids", () => {
    expect(findLeakForToolResult([annotated(1, "boom")], null)).toBeNull();
    expect(findLeakForToolResult([annotated(1, "boom")], undefined)).toBeNull();
    expect(findLeakForToolResult([annotated(1, "boom")], "not-a-number")).toBeNull();
    expect(findLeakForToolResult([], 1)).toBeNull();
  });
});

describe("noteOutgoingRequestId", () => {
  it("records tools/call and tasks/result ids and keeps the latest", () => {
    noteOutgoingRequestId({ jsonrpc: "2.0", method: "tools/call", id: 3 });
    expect(currentToolCallRequestId()).toBe(3);
    // Task-status polls never displace the id of the call being displayed.
    noteOutgoingRequestId({ jsonrpc: "2.0", method: "tasks/get", id: 4 });
    expect(currentToolCallRequestId()).toBe(3);
    noteOutgoingRequestId({ jsonrpc: "2.0", method: "tasks/result", id: 5 });
    expect(currentToolCallRequestId()).toBe(5);
    noteOutgoingRequestId({ jsonrpc: "2.0", method: "tools/call", id: "str" });
    expect(currentToolCallRequestId()).toBe("str");
  });

  it("ignores notifications, id-less frames, foreign methods and junk", () => {
    noteOutgoingRequestId({ jsonrpc: "2.0", method: "tools/call", id: 9 });
    noteOutgoingRequestId({ jsonrpc: "2.0", method: "notifications/initialized" });
    noteOutgoingRequestId({ jsonrpc: "2.0", method: "tools/call" });
    noteOutgoingRequestId({ jsonrpc: "2.0", method: "ping", id: 12 });
    noteOutgoingRequestId(null);
    noteOutgoingRequestId("garbage");
    noteOutgoingRequestId(42);
    expect(currentToolCallRequestId()).toBe(9);
  });
});

describe("capLeakText and leakClock", () => {
  it("caps at 200 characters with an ellipsis", () => {
    expect(capLeakText("x".repeat(199)).length).toBe(199);
    expect(capLeakText("x".repeat(200)).length).toBe(200);
    expect(capLeakText("x".repeat(201))).toBe("x".repeat(197) + "...");
    expect(capLeakText("x".repeat(400)).length).toBe(200);
  });

  it("extracts the server-side clock part from an ISO timestamp", () => {
    expect(leakClock("2026-08-30T12:00:04.100Z")).toBe("12:00:04");
    expect(leakClock("2026-08-30T12:00:04+03:00")).toBe("12:00:04");
    expect(leakClock("not-a-timestamp")).toBe("not-a-timestamp");
  });
});

// Randomized test order: every broker test must unsubscribe its handlers and
// drain its own pending request so no state leaks between tests.
describe("timeline scroll broker", () => {
  it("holds a request made while nobody subscribes and delivers it on subscribe", () => {
    requestTimelineScrollTo("evt-1");
    const received: string[] = [];
    const off = onTimelineScrollTo((id) => received.push(id));
    expect(received).toEqual(["evt-1"]);
    // The pending request is consumed exactly once.
    const second: string[] = [];
    const off2 = onTimelineScrollTo((id) => second.push(id));
    expect(second).toEqual([]);
    off();
    off2();
  });

  it("delivers immediately to a live subscriber and stops after unsubscribe", () => {
    const received: string[] = [];
    const off = onTimelineScrollTo((id) => received.push(id));
    requestTimelineScrollTo("evt-2");
    expect(received).toEqual(["evt-2"]);
    off();
    requestTimelineScrollTo("evt-3");
    expect(received).toEqual(["evt-2"]);
    // Drain evt-3 so no pending request leaks into a later test.
    const drain: string[] = [];
    const drainOff = onTimelineScrollTo((id) => drain.push(id));
    expect(drain).toEqual(["evt-3"]);
    drainOff();
  });

  it("ignores empty or non-string targets", () => {
    const received: string[] = [];
    const off = onTimelineScrollTo((id) => received.push(id));
    requestTimelineScrollTo("");
    expect(received).toEqual([]);
    off();
  });
});

describe("fetchTimelineEvents", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
    sessionStorage.clear();
    delete (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ];
  });

  it("sends the bootstrap path and the bearer token, and returns the page", async () => {
    sessionStorage.setItem(
      "inspectorConfig_v1_ephemeral",
      JSON.stringify({ MCP_PROXY_AUTH_TOKEN: { value: "tok" } }),
    );
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = { inspectorPath: "/app/insp" };
    const fetchMock = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => [{ id: "a", type: "APP_LOG", payload: null }],
    });
    (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;

    const events = await fetchTimelineEvents();

    expect(fetchMock).toHaveBeenCalledWith(
      "/app/insp/api/timeline?limit=200",
      expect.objectContaining({
        headers: { Authorization: "Bearer tok" },
      }),
    );
    expect(events).toEqual([{ id: "a", type: "APP_LOG", payload: null }]);
  });

  it("survives a malformed token blob without sending an Authorization header", async () => {
    sessionStorage.setItem("inspectorConfig_v1_ephemeral", "not-json{{");
    const fetchMock = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => [],
    });
    (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;

    const events = await fetchTimelineEvents();

    expect(fetchMock).toHaveBeenCalledWith(
      "/mcp-inspector/api/timeline?limit=200",
      { headers: {} },
    );
    expect(events).toEqual([]);
  });

  it("yields an empty list on a non-ok response or non-array body", async () => {
    (global as unknown as { fetch: jest.Mock }).fetch = jest
      .fn()
      .mockResolvedValue({ ok: false });
    expect(await fetchTimelineEvents()).toEqual([]);

    (global as unknown as { fetch: jest.Mock }).fetch = jest
      .fn()
      .mockResolvedValue({ ok: true, json: async () => ({ nope: 1 }) });
    expect(await fetchTimelineEvents()).toEqual([]);
  });
});
