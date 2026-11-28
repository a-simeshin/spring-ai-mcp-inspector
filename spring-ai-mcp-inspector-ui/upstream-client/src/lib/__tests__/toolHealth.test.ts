// [spring-ai-mcp-inspector PATCH] Tool Health lib tests (#370): the
// click-through filter and the sort helpers over the wire contract.
import {
  filterEventsByTool,
  sortToolHealth,
  toolNameOfRequest,
  ToolHealthStat,
} from "@/lib/toolHealth";

const TS = "2026-11-28T10:00:00.000Z";

type Event = {
  id: string;
  correlationId: string | null;
  type: string;
  timestamp: string;
  payload: Record<string, unknown> | null;
};

const toolRequest = (id: string, correlationId: string, tool: string): Event => ({
  id,
  correlationId,
  type: "MCP_JSONRPC_REQUEST",
  timestamp: TS,
  payload: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: tool } },
});

const toolResponse = (id: string, correlationId: string): Event => ({
  id,
  correlationId,
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: TS,
  payload: { jsonrpc: "2.0", id: 1, result: { content: [], isError: false } },
});

const logEvent = (id: string): Event => ({
  id,
  correlationId: null,
  type: "APP_LOG",
  timestamp: TS,
  payload: { logLevel: "INFO", message: "hello" },
});

const stat = (overrides: Partial<ToolHealthStat>): ToolHealthStat => ({
  method: "t",
  calls: 1,
  errors: 0,
  noResponse: 0,
  errorRate: 0,
  p50Ms: 0,
  p95Ms: 0,
  lastIsError: false,
  lastCallAt: 0,
  ...overrides,
});

describe("toolNameOfRequest", () => {
  it("extracts the tool name from a tools/call request payload", () => {
    expect(toolNameOfRequest({ method: "tools/call", params: { name: "echo" } })).toBe("echo");
  });

  it("returns null for non-tools/call methods and malformed payloads", () => {
    expect(toolNameOfRequest({ method: "tools/list", params: {} })).toBeNull();
    expect(toolNameOfRequest({ method: "tools/call", params: {} })).toBeNull();
    expect(toolNameOfRequest({ method: "tools/call", params: { name: 5 } })).toBeNull();
    expect(toolNameOfRequest(null)).toBeNull();
  });
});

describe("filterEventsByTool", () => {
  it("keeps the tool's requests and their correlated responses, drops the rest", () => {
    const events = [
      toolRequest("r1", "c1", "echo"),
      toolResponse("p1", "c1"),
      toolRequest("r2", "c2", "sum"),
      toolResponse("p2", "c2"),
      logEvent("l1"),
    ];
    const filtered = filterEventsByTool(events, "echo");
    expect(filtered.map((e) => e.id)).toEqual(["r1", "p1"]);
  });

  it("drops responses whose request is not in the window", () => {
    const events = [toolResponse("p9", "c9"), toolRequest("r1", "c1", "echo")];
    expect(filterEventsByTool(events, "echo").map((e) => e.id)).toEqual(["r1"]);
  });

  it("keeps requests without a parseable tool name out of every filter", () => {
    const events: Event[] = [
      {
        id: "r0",
        correlationId: "c0",
        type: "MCP_JSONRPC_REQUEST",
        timestamp: TS,
        payload: { method: "tools/call", params: {} },
      },
      toolRequest("r1", "c1", "echo"),
    ];
    expect(filterEventsByTool(events, "echo").map((e) => e.id)).toEqual(["r1"]);
    expect(filterEventsByTool(events, "unknown")).toEqual([]);
  });
});

describe("sortToolHealth", () => {
  const rows = [
    stat({ method: "alpha", errorRate: 0.5, p50Ms: 30, p95Ms: 100 }),
    stat({ method: "beta", errorRate: 1, p50Ms: 10, p95Ms: 200 }),
    stat({ method: "gamma", errorRate: 1, p50Ms: 20, p95Ms: 50 }),
  ];

  it("sorts descending by the chosen key, ties broken by tool name", () => {
    expect(sortToolHealth(rows, "errorRate", "desc").map((s) => s.method)).toEqual([
      "beta",
      "gamma",
      "alpha",
    ]);
  });

  it("sorts ascending and does not mutate the input", () => {
    const sorted = sortToolHealth(rows, "p50Ms", "asc");
    expect(sorted.map((s) => s.method)).toEqual(["beta", "gamma", "alpha"]);
    expect(rows.map((s) => s.method)).toEqual(["alpha", "beta", "gamma"]);
  });

  it("sorts by p95 descending", () => {
    expect(sortToolHealth(rows, "p95Ms", "desc").map((s) => s.method)).toEqual([
      "beta",
      "alpha",
      "gamma",
    ]);
  });
});
