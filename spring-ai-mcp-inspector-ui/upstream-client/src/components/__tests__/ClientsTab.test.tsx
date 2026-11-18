// [spring-ai-mcp-inspector PATCH] ClientsTab test: connection cards, empty and
// disabled states, auth header (#120, layer C3).
import { render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import ClientsTab from "../ClientsTab";
import { Tabs } from "@/components/ui/tabs";

/**
 * ClientsTab consumes the serialized
 * io.inspector.mcp.core.timeline.ClientConnectionRegistry.ClientConnectionSnapshot
 * served by GET ${path}/api/client-connections. These tests pin that wire
 * contract through the component's rendering, including the deployed API path
 * advertised via window.__MCP_INSPECTOR_BOOTSTRAP.
 */

type WireConnection = {
  name: string;
  transportType: string | null;
  target: string | null;
  state: string;
  since: string;
  lastActivity: string | null;
  inCount: number;
  outCount: number;
  lastError: string | null;
};

const CONNECTED_CLIENT: WireConnection = {
  name: "demo",
  transportType: "streamable-http",
  target: "https://***@mcp.example.com/mcp",
  state: "CONNECTED",
  since: "2026-11-18T12:00:00Z",
  lastActivity: "2026-11-18T12:00:05Z",
  inCount: 2,
  outCount: 1,
  lastError: null,
};

const FAILED_CLIENT: WireConnection = {
  name: "alpha",
  transportType: "stdio",
  target: "npx -y demo-server",
  state: "FAILED",
  since: "2026-11-18T12:00:00Z",
  lastActivity: null,
  inCount: 0,
  outCount: 0,
  lastError: "spawn ENOENT",
};

function mockFetch(
  connections: WireConnection[],
  options?: { ok?: boolean; status?: number },
) {
  const fetchMock = jest.fn().mockResolvedValue({
    ok: options?.ok ?? true,
    status: options?.status ?? 200,
    json: async () => connections,
  });
  (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
  return fetchMock;
}

describe("ClientsTab", () => {
  const originalFetch = global.fetch;
  const originalBootstrap = (window as unknown as Record<string, unknown>)[
    "__MCP_INSPECTOR_BOOTSTRAP"
  ];

  afterEach(() => {
    global.fetch = originalFetch;
    (window as unknown as Record<string, unknown>)["__MCP_INSPECTOR_BOOTSTRAP"] =
      originalBootstrap;
  });

  const renderTab = () =>
    render(
      <Tabs defaultValue="clients">
        <ClientsTab />
      </Tabs>,
    );

  it(
    "renders one card per connection with name, transport, target, state badge and counters",
    async () => {
      mockFetch([FAILED_CLIENT, CONNECTED_CLIENT]);
      renderTab();

      const cards = await waitFor(() =>
        screen.getAllByTestId("client-connection-card"),
      );
      expect(cards).toHaveLength(2);

      const stateBadges = screen.getAllByTestId("client-connection-state");
      expect(stateBadges).toHaveLength(2);

      // Registry snapshot order is sorted by client name: alpha first.
      expect(cards[0]).toHaveTextContent("alpha");
      expect(cards[0]).toHaveTextContent("transport: stdio");
      expect(cards[0]).toHaveTextContent("target: npx -y demo-server");
      expect(cards[0]).toHaveTextContent("failed");
      expect(cards[0]).toHaveTextContent("last error: spawn ENOENT");

      expect(cards[1]).toHaveTextContent("demo");
      expect(cards[1]).toHaveTextContent("transport: streamable-http");
      expect(cards[1]).toHaveTextContent("target: https://***@mcp.example.com/mcp");
      expect(cards[1]).toHaveTextContent("live");
      expect(cards[1]).toHaveTextContent("in: 2 · out: 1");
    },
  );

  it("renders the empty state when the registry has no clients", async () => {
    mockFetch([]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByTestId("client-connection-state")).toHaveTextContent(
        "No client connections yet",
      ),
    );
    expect(screen.queryByTestId("client-connection-card")).not.toBeInTheDocument();
  });

  it("renders the disabled state on a 404 when client capture is off", async () => {
    mockFetch([], { ok: false, status: 404 });
    renderTab();

    await waitFor(() =>
      expect(screen.getByTestId("client-connection-state")).toHaveTextContent(
        "Client capture is disabled",
      ),
    );
    expect(screen.queryByTestId("client-connection-card")).not.toBeInTheDocument();
  });

  it(
    "sends the boot token as a raw X-MCP-Inspector-Auth header to the configured path",
    async () => {
      (window as unknown as Record<string, unknown>)[
        "__MCP_INSPECTOR_BOOTSTRAP"
      ] = { inspectorPath: "/mcp-inspector", authToken: "tok-120-secret" };
      const fetchMock = mockFetch([]);
      renderTab();

      await waitFor(() =>
        expect(screen.getByTestId("client-connection-state")).toBeInTheDocument(),
      );
      expect(fetchMock.mock.calls[0][0]).toBe("/mcp-inspector/api/client-connections");
      const init = fetchMock.mock.calls[0][1] as {
        headers: Record<string, string>;
      };
      expect(init.headers["X-MCP-Inspector-Auth"]).toBe("tok-120-secret");
      expect(init.headers["Authorization"]).toBeUndefined();
    },
  );

  it("renders an explicit error state on a non-OK, non-404 response", async () => {
    mockFetch([], { ok: false, status: 500 });
    renderTab();

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("HTTP 500"),
    );
  });
});
