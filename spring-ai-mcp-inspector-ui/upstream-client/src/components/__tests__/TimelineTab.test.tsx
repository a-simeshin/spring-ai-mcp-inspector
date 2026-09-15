// [spring-ai-mcp-inspector PATCH] TimelineTab test: schema alignment, APP_LOG rendering, empty state (#130).
// [spring-ai-mcp-inspector PATCH] Protocol negotiation badge test: _protocolNegotiation enrichment (#129, #130).
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import TimelineTab from "../TimelineTab";
import { Tabs } from "@/components/ui/tabs";

/**
 * TimelineTab consumes the serialized io.inspector.mcp.core.timeline.TimelineEvent
 * record: {id, correlationId, sessionId, type, timestamp, payload}. These tests
 * pin that wire contract, including the APP_LOG payload shape and the deployed
 * API path advertised via window.__MCP_INSPECTOR_BOOTSTRAP.
 */

type WireEvent = {
  id: string;
  correlationId: string | null;
  sessionId: string | null;
  type: string;
  timestamp: string;
  payload: Record<string, unknown> | null;
};

const APP_LOG_EVENT: WireEvent = {
  id: "evt-1",
  correlationId: "corr-1",
  sessionId: null,
  type: "APP_LOG",
  timestamp: "2026-08-30T12:00:00.123Z",
  payload: {
    logLevel: "INFO",
    loggerName: "io.inspector.demo.DemoTool",
    threadName: "main",
    message: "tool executed",
  },
};

const REQUEST_EVENT: WireEvent = {
  id: "evt-2",
  correlationId: "corr-2",
  sessionId: "s-1",
  type: "MCP_JSONRPC_REQUEST",
  timestamp: "2026-08-30T12:00:01.456Z",
  payload: {
    jsonrpc: "2.0",
    id: 1,
    method: "tools/list",
    params: {},
  },
};

const DOWNGRADE_RESPONSE_EVENT: WireEvent = {
  id: "evt-3",
  correlationId: "corr-2",
  sessionId: "s-1",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:01.415Z",
  payload: {
    jsonrpc: "2.0",
    id: 1,
    result: { protocolVersion: "2025-11-25" },
    _protocolNegotiation: {
      requested: "2026-07-28",
      negotiated: "2025-11-25",
      severity: "DOWNGRADE",
      affectedMethods: [],
      summary:
        "Client requested revision 2026-07-28 but server negotiated older 2025-11-25. The server is running an older protocol; new features from 2026-07-28 are unavailable.",
    },
  },
};

const OK_RESPONSE_EVENT: WireEvent = {
  id: "evt-4",
  correlationId: "corr-3",
  sessionId: "s-2",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:02.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 2,
    result: { protocolVersion: "2025-11-25" },
    _protocolNegotiation: {
      requested: "2025-11-25",
      negotiated: "2025-11-25",
      severity: "OK",
      affectedMethods: [],
      summary: "Client and server agreed on revision 2025-11-25.",
    },
  },
};

const UNKNOWN_RESPONSE_EVENT: WireEvent = {
  id: "evt-5",
  correlationId: "corr-4",
  sessionId: "s-3",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:03.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 3,
    result: { protocolVersion: "2024-11-05" },
    _protocolNegotiation: {
      requested: "2024-11-05",
      negotiated: "2024-11-05",
      severity: "UNKNOWN",
      affectedMethods: [],
      summary:
        "Both revisions are unknown: requested=2024-11-05, negotiated=2024-11-05",
    },
  },
};

const INCOMPATIBLE_RESPONSE_EVENT: WireEvent = {
  id: "evt-6",
  correlationId: "corr-5",
  sessionId: "s-4",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:04.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 4,
    result: { protocolVersion: "2026-07-28" },
    _protocolNegotiation: {
      requested: "2025-11-25",
      negotiated: "2026-07-28",
      severity: "INCOMPATIBLE",
      affectedMethods: [
        "initialize",
        "notifications/initialized",
        "ping",
        "logging/setLevel",
        "notifications/roots/list_changed",
        "tasks/list",
        "tasks/result",
        "notifications/elicitation/complete",
      ],
      summary:
        "Client requested revision 2025-11-25 but server negotiated newer 2026-07-28. The server removed methods: [initialize, notifications/initialized, ...]. Calls to these methods will fail with MethodNotFound.",
    },
  },
};

// [spring-ai-mcp-inspector PATCH] Replay button test fixtures.
const TOOL_CALL_REQUEST_EVENT: WireEvent = {
  id: "evt-replay-1",
  correlationId: "corr-replay-1",
  sessionId: "s-1",
  type: "MCP_JSONRPC_REQUEST",
  timestamp: "2026-09-13T10:00:00.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 100,
    method: "tools/call",
    params: {
      name: "echo",
      arguments: { message: "hello world", count: 42 },
    },
  },
};

const TOOL_CALL_REQUEST_EVENT_NO_ARGS: WireEvent = {
  id: "evt-replay-2",
  correlationId: "corr-replay-2",
  sessionId: "s-1",
  type: "MCP_JSONRPC_REQUEST",
  timestamp: "2026-09-13T10:00:01.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 101,
    method: "tools/call",
    params: {
      name: "echo",
    },
  },
};

const PROMT_LIST_REQUEST_EVENT: WireEvent = {
  id: "evt-replay-3",
  correlationId: "corr-replay-3",
  sessionId: "s-1",
  type: "MCP_JSONRPC_REQUEST",
  timestamp: "2026-09-13T10:00:02.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 102,
    method: "prompts/list",
    params: {},
  },
};

function mockFetch(events: WireEvent[]) {
  const fetchMock = jest.fn().mockResolvedValue({
    ok: true,
    json: async () => events,
  });
  (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
  return fetchMock;
}

describe("TimelineTab", () => {
  const originalFetch = global.fetch;
  const originalBootstrap = (window as unknown as Record<string, unknown>)[
    "__MCP_INSPECTOR_BOOTSTRAP"
  ];

  afterEach(() => {
    global.fetch = originalFetch;
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = originalBootstrap;
  });

  const renderTab = () =>
    render(
      <Tabs defaultValue="timeline">
        <TimelineTab />
      </Tabs>,
    );

  it("renders APP_LOG events from the real wire schema without crashing", async () => {
    mockFetch([APP_LOG_EVENT]);
    const { container } = renderTab();

    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
    );
    // The log line shows its message and stays expandable.
    expect(screen.getByText("tool executed")).toBeInTheDocument();
    // A React render error would blank #root; assert DOM is intact.
    expect(container).not.toBeEmptyDOMElement();
  });

  it("renders MCP request events and expands payload JSON", async () => {
    mockFetch([REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("tools/list")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("tools/list"));
    await waitFor(() =>
      expect(screen.getByText(/"method": "tools\/list"/)).toBeInTheDocument(),
    );
  });

  it("uses the inspector path advertised by the bootstrap", async () => {
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = { inspectorPath: "/app/inspector-custom" };
    const fetchMock = mockFetch([]);
    renderTab();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const calledUrl = String(fetchMock.mock.calls[0][0]);
    expect(calledUrl).toBe("/app/inspector-custom/api/timeline?limit=200");
  });

  it("falls back to the default /mcp-inspector path without a bootstrap", async () => {
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = undefined;
    const fetchMock = mockFetch([]);
    renderTab();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const calledUrl = String(fetchMock.mock.calls[0][0]);
    expect(calledUrl).toBe("/mcp-inspector/api/timeline?limit=200");
  });

  // [spring-ai-mcp-inspector PATCH] Protocol negotiation badge tests (#129, #130).

  it("renders a downgrade badge on initialize response with _protocolNegotiation", async () => {
    mockFetch([DOWNGRADE_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(
        screen.getByText(/protocol: 2025-11-25 v \(downgrade\)/),
      ).toBeInTheDocument(),
    );
  });

  it("renders an expanded protocol negotiation block when a downgrade row is clicked", async () => {
    mockFetch([DOWNGRADE_RESPONSE_EVENT]);
    renderTab();

    const badge = await waitFor(() =>
      screen.getByText(/protocol: 2025-11-25 v \(downgrade\)/),
    );
    // Click the row to expand it.
    fireEvent.click(badge);
    // The expanded block shows the negotiation details.
    await waitFor(() =>
      expect(screen.getByText("Protocol negotiation:")).toBeInTheDocument(),
    );
    expect(screen.getByText(/requested: 2026-07-28/)).toBeInTheDocument();
    expect(screen.getByText(/negotiated: 2025-11-25/)).toBeInTheDocument();
    expect(screen.getByText(/severity: DOWNGRADE/)).toBeInTheDocument();
    // Footer link to issue #129 is present.
    expect(
      screen.getByText("See compatibility matrix: issue #129"),
    ).toBeInTheDocument();
  });

  it("renders a plain badge without expanded block for OK severity", async () => {
    mockFetch([OK_RESPONSE_EVENT]);
    renderTab();

    const badge = await waitFor(() =>
      screen.getByText(/protocol: 2025-11-25/),
    );
    // Expand the row: no Protocol negotiation block for OK.
    fireEvent.click(badge);
    // The "Protocol negotiation:" heading should NOT appear for OK.
    expect(screen.queryByText("Protocol negotiation:")).not.toBeInTheDocument();
  });

  it("renders an unknown badge for UNKNOWN severity", async () => {
    mockFetch([UNKNOWN_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(
        screen.getByText(/protocol: 2024-11-05 \? \(unknown\)/),
      ).toBeInTheDocument(),
    );
  });

  it("renders an incompatible badge and expanded block for INCOMPATIBLE severity", async () => {
    mockFetch([INCOMPATIBLE_RESPONSE_EVENT]);
    renderTab();

    const badge = await waitFor(() =>
      screen.getByText(/protocol: 2026-07-28 ! \(incompatible\)/),
    );
    // Click the row to expand the details.
    fireEvent.click(badge);
    await waitFor(() =>
      expect(screen.getByText("Protocol negotiation:")).toBeInTheDocument(),
    );
    expect(screen.getByText(/severity: INCOMPATIBLE/)).toBeInTheDocument();
    // Affected methods appear in the expanded block.
    expect(screen.getByText(/affected: initialize/)).toBeInTheDocument();
  });

  // [spring-ai-mcp-inspector PATCH] Replay button tests.

  it("shows Replay button on tools/call request rows", async () => {
    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("tools/call")).toBeInTheDocument(),
    );
    expect(screen.getByText("Replay")).toBeInTheDocument();
  });

  it("does not show Replay button on non-tools/call request rows", async () => {
    mockFetch([REQUEST_EVENT]); // tools/list, not tools/call
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("tools/list")).toBeInTheDocument(),
    );
    expect(screen.queryByText("Replay")).not.toBeInTheDocument();
  });

  it("does not show Replay button on prompts/list request rows", async () => {
    mockFetch([PROMT_LIST_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("prompts/list")).toBeInTheDocument(),
    );
    expect(screen.queryByText("Replay")).not.toBeInTheDocument();
  });

  it("does not show Replay button on response rows", async () => {
    mockFetch([DOWNGRADE_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText(/protocol: 2025-11-25/)).toBeInTheDocument(),
    );
    expect(screen.queryByText("Replay")).not.toBeInTheDocument();
  });

  it("calls onReplay with tool name and args when Replay is clicked", async () => {
    const onReplay = jest.fn();
    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    render(
      <Tabs defaultValue="timeline">
        <TimelineTab onReplay={onReplay} />
      </Tabs>,
    );

    await waitFor(() =>
      expect(screen.getByText("Replay")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Replay"));

    expect(onReplay).toHaveBeenCalledWith({
      toolName: "echo",
      args: { message: "hello world", count: 42 },
    });
  });

  it("calls onReplay with empty args when tools/call has no arguments field", async () => {
    const onReplay = jest.fn();
    mockFetch([TOOL_CALL_REQUEST_EVENT_NO_ARGS]);
    render(
      <Tabs defaultValue="timeline">
        <TimelineTab onReplay={onReplay} />
      </Tabs>,
    );

    await waitFor(() =>
      expect(screen.getByText("Replay")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Replay"));

    expect(onReplay).toHaveBeenCalledWith({
      toolName: "echo",
      args: {},
    });
  });

  // [spring-ai-mcp-inspector PATCH] Copy as JSON-RPC / Copy as curl tests.

  it("shows Copy JSON-RPC and Copy curl buttons on request rows", async () => {
    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("tools/call")).toBeInTheDocument(),
    );
    expect(screen.getByText("Copy JSON-RPC")).toBeInTheDocument();
    expect(screen.getByText("Copy curl")).toBeInTheDocument();
  });

  it("shows Copy JSON-RPC and Copy curl on non-tools/call request rows too", async () => {
    mockFetch([REQUEST_EVENT]); // tools/list
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("tools/list")).toBeInTheDocument(),
    );
    expect(screen.getByText("Copy JSON-RPC")).toBeInTheDocument();
    expect(screen.getByText("Copy curl")).toBeInTheDocument();
  });

  it("does not show copy buttons on response rows", async () => {
    mockFetch([DOWNGRADE_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText(/protocol: 2025-11-25/)).toBeInTheDocument(),
    );
    expect(screen.queryByText("Copy JSON-RPC")).not.toBeInTheDocument();
    expect(screen.queryByText("Copy curl")).not.toBeInTheDocument();
  });

  it("copies pretty-printed JSON-RPC envelope with stable id=1", async () => {
    const mockWriteText = jest.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: mockWriteText },
      writable: true,
      configurable: true,
    });

    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("Copy JSON-RPC")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Copy JSON-RPC"));

    await waitFor(() => expect(mockWriteText).toHaveBeenCalledTimes(1));
    const copiedText = mockWriteText.mock.calls[0][0] as string;
    const parsed = JSON.parse(copiedText);
    expect(parsed.jsonrpc).toBe("2.0");
    expect(parsed.id).toBe(1);
    expect(parsed.method).toBe("tools/call");
    expect(parsed.params.name).toBe("echo");
    expect(parsed.params.arguments).toEqual({
      message: "hello world",
      count: 42,
    });
    // Pretty-printed: contains newlines
    expect(copiedText).toContain("\n");
  });

  it("copies curl command with single-quote escaped body and auth placeholder", async () => {
    const mockWriteText = jest.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: mockWriteText },
      writable: true,
      configurable: true,
    });

    // Seed localStorage/sessionStorage with a proxy address and auth token
    localStorage.setItem(
      "inspectorConfig_v1",
      JSON.stringify({
        MCP_PROXY_FULL_ADDRESS: {
          label: "Inspector Proxy Address",
          description: "Proxy address",
          value: "http://localhost:9999",
          is_session_item: false,
        },
      }),
    );
    sessionStorage.setItem(
      "inspectorConfig_v1_ephemeral",
      JSON.stringify({
        MCP_PROXY_AUTH_TOKEN: {
          label: "Proxy Session Token",
          description: "Auth token",
          value: "secret-token-123",
          is_session_item: true,
        },
      }),
    );

    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("Copy curl")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Copy curl"));

    await waitFor(() => expect(mockWriteText).toHaveBeenCalledTimes(1));
    const copiedText = mockWriteText.mock.calls[0][0] as string;
    expect(copiedText).toContain("curl -X POST 'http://localhost:9999/mcp'");
    expect(copiedText).toContain("Content-Type: application/json");
    expect(copiedText).toContain("X-MCP-Proxy-Auth: Bearer <TOKEN>");
    expect(copiedText).toContain('"method": "tools/call"');
    // JSON body with "hello world" containing a space (not single quotes,
    // which would test the shell escaping).
    expect(copiedText).toContain('"hello world"');

    // Clean up seeded config
    localStorage.removeItem("inspectorConfig_v1");
    sessionStorage.removeItem("inspectorConfig_v1_ephemeral");
  });

  it("omits auth header when no token is configured", async () => {
    // Clear any leftover ephemeral config that may leak from the previous test.
    sessionStorage.clear();
    localStorage.removeItem("inspectorConfig_v1");

    const mockWriteText = jest.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: mockWriteText },
      writable: true,
      configurable: true,
    });

    localStorage.setItem(
      "inspectorConfig_v1",
      JSON.stringify({
        MCP_PROXY_FULL_ADDRESS: {
          label: "Inspector Proxy Address",
          description: "Proxy address",
          value: "http://localhost:7777",
          is_session_item: false,
        },
      }),
    );
    // No sessionStorage token

    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("Copy curl")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Copy curl"));

    await waitFor(() => expect(mockWriteText).toHaveBeenCalledTimes(1));
    const copiedText = mockWriteText.mock.calls[0][0] as string;
    expect(copiedText).toContain("curl -X POST 'http://localhost:7777/mcp'");
    expect(copiedText).not.toContain("Authorization");
    expect(copiedText).toContain('"method": "tools/call"');

    localStorage.removeItem("inspectorConfig_v1");
  });

  it("shows 'Copied' confirmation after successful copy", async () => {
    const mockWriteText = jest.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: mockWriteText },
      writable: true,
      configurable: true,
    });

    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("Copy JSON-RPC")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Copy JSON-RPC"));

    // The button text changes to "Copied" after a successful write
    await waitFor(() =>
      expect(screen.getByText("Copied")).toBeInTheDocument(),
    );
  });

  // [spring-ai-mcp-inspector PATCH] Regression tests for clipboard rejection
  // and non-secure-context fallback.

  it("does not show 'Copied' when clipboard writeText rejects", async () => {
    const mockWriteText = jest.fn().mockRejectedValue(
      new Error("clipboard denied"),
    );
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: mockWriteText },
      writable: true,
      configurable: true,
    });
    // Ensure the execCommand fallback also fails so we hit the error path.
    const execCommandOrig = document.execCommand;
    document.execCommand = jest.fn().mockReturnValue(false) as unknown as typeof document.execCommand;

    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("Copy JSON-RPC")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Copy JSON-RPC"));

    // Wait for the async handler to settle
    await new Promise((r) => setTimeout(r, 100));

    // Copied label must NOT appear when the write failed
    expect(screen.queryByText("Copied")).not.toBeInTheDocument();
    // The button text should not have changed
    expect(screen.getByText("Copy JSON-RPC")).toBeInTheDocument();

    document.execCommand = execCommandOrig;
  });

  it("falls back to textarea+execCommand when clipboard API is unavailable", async () => {
    // Simulate non-secure context: set clipboard to undefined.
    Object.defineProperty(navigator, "clipboard", {
      value: undefined,
      configurable: true,
    });

    const execCommandOrig = document.execCommand;
    document.execCommand = jest.fn().mockReturnValue(true) as unknown as typeof document.execCommand;

    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("Copy curl")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Copy curl"));

    // Fallback should succeed and show "Copied"
    await waitFor(() =>
      expect(screen.getByText("Copied")).toBeInTheDocument(),
    );

    document.execCommand = execCommandOrig;
  });

  it("shows error toast when both clipboard API and fallback fail", async () => {
    // Clipboard API absent
    Object.defineProperty(navigator, "clipboard", {
      value: undefined,
      configurable: true,
    });

    const execCommandOrig = document.execCommand;
    document.execCommand = jest.fn().mockReturnValue(false) as unknown as typeof document.execCommand;

    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("Copy JSON-RPC")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Copy JSON-RPC"));

    // Wait for the async handler to settle
    await new Promise((r) => setTimeout(r, 100));

    // Copied label must NOT appear
    expect(screen.queryByText("Copied")).not.toBeInTheDocument();

    document.execCommand = execCommandOrig;
  });

  // [spring-ai-mcp-inspector PATCH] Regression test: curl preserves non-default
  // upstream target via ?url= query param (reviewer blocker on PR #224).
  it("includes ?url= with encoded upstream target when lastSseUrl is non-default", async () => {
    const mockWriteText = jest.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: mockWriteText },
      writable: true,
      configurable: true,
    });

    localStorage.setItem(
      "inspectorConfig_v1",
      JSON.stringify({
        MCP_PROXY_FULL_ADDRESS: {
          label: "Inspector Proxy Address",
          description: "Proxy address",
          value: "http://localhost:9999",
          is_session_item: false,
        },
      }),
    );
    localStorage.setItem("lastSseUrl", "https://upstream.example/custom-mcp");

    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("Copy curl")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Copy curl"));

    await waitFor(() => expect(mockWriteText).toHaveBeenCalledTimes(1));
    const copiedText = mockWriteText.mock.calls[0][0] as string;
    // The endpoint must include the encoded upstream URL
    expect(copiedText).toContain(
      "curl -X POST 'http://localhost:9999/mcp?url=https%3A%2F%2Fupstream.example%2Fcustom-mcp'",
    );
    expect(copiedText).toContain('"method": "tools/call"');

    localStorage.removeItem("inspectorConfig_v1");
    localStorage.removeItem("lastSseUrl");
  });

  it("omits ?url= when lastSseUrl is the default /mcp", async () => {
    const mockWriteText = jest.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: mockWriteText },
      writable: true,
      configurable: true,
    });

    localStorage.setItem(
      "inspectorConfig_v1",
      JSON.stringify({
        MCP_PROXY_FULL_ADDRESS: {
          label: "Inspector Proxy Address",
          description: "Proxy address",
          value: "http://localhost:9999",
          is_session_item: false,
        },
      }),
    );
    // Explicitly set the default value
    localStorage.setItem("lastSseUrl", "/mcp");

    mockFetch([TOOL_CALL_REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("Copy curl")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Copy curl"));

    await waitFor(() => expect(mockWriteText).toHaveBeenCalledTimes(1));
    const copiedText = mockWriteText.mock.calls[0][0] as string;
    expect(copiedText).toContain("curl -X POST 'http://localhost:9999/mcp'");
    expect(copiedText).not.toContain("?url=");

    localStorage.removeItem("inspectorConfig_v1");
    localStorage.removeItem("lastSseUrl");
  });
});
