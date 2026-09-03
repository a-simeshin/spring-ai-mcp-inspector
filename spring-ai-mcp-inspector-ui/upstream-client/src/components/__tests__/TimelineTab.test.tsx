// [spring-ai-mcp-inspector PATCH] TimelineTab test — schema alignment, APP_LOG rendering, empty state (#130).
// [spring-ai-mcp-inspector PATCH] Extended with client traffic, diagnostic badge, filter tests (#120, #141).
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

// Client traffic event: outgoing request.
const CLIENT_REQUEST_EVENT: WireEvent = {
  id: "evt-3",
  correlationId: "mcpc:myClient:1",
  sessionId: null,
  type: "MCP_JSONRPC_REQUEST",
  timestamp: "2026-08-30T12:00:02.000Z",
  payload: {
    endpoint: "client",
    clientName: "myClient",
    transport: "stdio",
    direction: "client->server",
    method: "tools/call",
    id: "1",
  },
};

// Client traffic event: incoming response with latency.
const CLIENT_RESPONSE_EVENT: WireEvent = {
  id: "evt-4",
  correlationId: "mcpc:myClient:1",
  sessionId: null,
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:02.150Z",
  payload: {
    endpoint: "client",
    clientName: "myClient",
    transport: "stdio",
    direction: "server->client",
    latencyMs: 150,
  },
};

// Diagnostic event: orphan handler.
const DIAGNOSTIC_EVENT: WireEvent = {
  id: "evt-5",
  correlationId: "mcpcd:ORPHAN_HANDLER:myClient",
  sessionId: null,
  type: "APP_LOG",
  timestamp: "2026-08-30T12:00:03.000Z",
  payload: {
    endpoint: "client-diagnostics",
    desyncType: "ORPHAN_HANDLER",
    clientName: "myClient",
    handlerKind: "sampling",
    source: "myBean#handleSampling",
    message: "Handler references client 'myClient' which is not configured",
  },
};

// Client error event: orphan response.
const CLIENT_ORPHAN_EVENT: WireEvent = {
  id: "evt-6",
  correlationId: "mcpc:myClient:orphan",
  sessionId: null,
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:04.000Z",
  payload: {
    endpoint: "client",
    clientName: "myClient",
    transport: "stdio",
    direction: "server->client",
    orphan: true,
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

  it("renders client traffic events with direction and client name", async () => {
    mockFetch([CLIENT_REQUEST_EVENT, CLIENT_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("2 events")).toBeInTheDocument(),
    );
    // Direction labels appear in the row (also in the filter dropdown options).
    const dirLabels = screen.getAllByText("client->server");
    expect(dirLabels.length).toBeGreaterThanOrEqual(1);
    const respLabels = screen.getAllByText("server->client");
    expect(respLabels.length).toBeGreaterThanOrEqual(1);
    // Client name appears as a badge.
    const clientLabels = screen.getAllByText("myClient");
    expect(clientLabels.length).toBeGreaterThanOrEqual(2);
    // Latency appears on the response.
    expect(screen.getByText("150ms")).toBeInTheDocument();
  });

  it("renders diagnostic events with ORPHAN_HANDLER badge", async () => {
    mockFetch([DIAGNOSTIC_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
    );
    // Badge text is visible.
    expect(screen.getByText("ORPHAN_HANDLER")).toBeInTheDocument();
    // The diagnostic message is rendered.
    expect(
      screen.getByText(/Handler references client/),
    ).toBeInTheDocument();
  });

  it("highlights orphan/error client events", async () => {
    mockFetch([CLIENT_ORPHAN_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
    );
    // The orphan event renders without crashing.
    const dirLabels = screen.getAllByText("server->client");
    expect(dirLabels.length).toBeGreaterThanOrEqual(1);
  });

  it("filters by direction when the dropdown is selected", async () => {
    mockFetch([CLIENT_REQUEST_EVENT, CLIENT_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("2 events")).toBeInTheDocument(),
    );
    // Two comboboxes: direction and client name. Pick the first (direction).
    const selects = screen.getAllByRole("combobox");
    expect(selects.length).toBeGreaterThanOrEqual(1);
    fireEvent.change(selects[0], { target: { value: "server->client" } });
    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
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
});
