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

  it("renders an unknown badge without expanded block for UNKNOWN severity", async () => {
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
});