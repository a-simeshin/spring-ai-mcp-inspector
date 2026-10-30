// [spring-ai-mcp-inspector PATCH] leakAnnotations unit tests (#251): defensive
// parsing of the D5 wire shape, the tool-result join, display caps and the
// cross-tab scroll broker. Garbage inputs must always yield "absent".
import {
  capLeakText,
  fetchTimelineEvents,
  findLeakForToolResult,
  leakClock,
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
  const annotated = (id: string, text: string, matchType = "EXACT") => ({
    id,
    type: "MCP_JSONRPC_RESPONSE",
    payload: {
      jsonrpc: "2.0",
      result: { content: [{ type: "text", text }], isError: true },
      _leak: { ...VALID_LEAK, matchType, logEventId: id + "-log" },
    },
  });

  it("finds the newest annotated response with equal text", () => {
    const events = [
      annotated("newest", "boom\nboom"),
      annotated("older", "boom\nboom"),
    ];
    expect(findLeakForToolResult(events, "boom\nboom")?.logEventId).toBe(
      "newest-log",
    );
  });

  it("matches across whitespace differences introduced by re-encoding", () => {
    const events = [annotated("e1", "boom   again")];
    expect(findLeakForToolResult(events, "boom\n again")?.logEventId).toBe(
      "e1-log",
    );
  });

  it("skips non-response events, unannotated responses and different texts", () => {
    const events = [
      { id: "l", type: "APP_LOG", payload: { message: "boom" } },
      {
        id: "r",
        type: "MCP_JSONRPC_RESPONSE",
        payload: { result: { content: [{ type: "text", text: "boom" }] } },
      },
      annotated("r2", "different"),
    ];
    expect(findLeakForToolResult(events, "boom")).toBeNull();
  });

  it("returns null for empty lookup text", () => {
    expect(findLeakForToolResult([annotated("e1", "boom")], "")).toBeNull();
    expect(findLeakForToolResult([annotated("e1", "boom")], "   ")).toBeNull();
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
