// [spring-ai-mcp-inspector PATCH] Regression guard for the tools-link
// half of issue #344: clicking a resource_link in a tool result calls
// App.readResource for a uri that is already cached, and the cache-hit
// pane commit used to overwrite the Resources pane while a different
// row stayed selected, leaving pane and selection diverged. With the
// guard, the cached payload stays visible inline in the Tools tab
// (ResourceLinkView reads the same per-uri map) and only the pane
// commit is suppressed.
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom";
// [spring-ai-mcp-inspector PATCH] jsdom lacks PointerEvent, which Radix
// tab triggers react to through userEvent; the polyfill must land before
// react-dom loads (same setup as App.tabClickRegression.test.tsx).
import "../testUtils/pointerEventsPolyfill";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { TooltipProvider } from "@/components/ui/tooltip";

jest.mock("@modelcontextprotocol/sdk/client/auth.js", () => ({
  auth: jest.fn(),
}));

jest.mock("../lib/oauth-state-machine", () => ({
  OAuthStateMachine: jest.fn(),
}));

jest.mock("../lib/auth", () => ({
  InspectorOAuthClientProvider: jest.fn().mockImplementation(() => ({
    tokens: jest.fn().mockResolvedValue(null),
    clear: jest.fn(),
  })),
  DebugInspectorOAuthClientProvider: jest.fn(),
}));

jest.mock("../utils/configUtils", () => ({
  ...jest.requireActual("../utils/configUtils"),
  getMCPProxyAddress: jest.fn(() => "http://localhost:6277"),
  getMCPProxyAuthToken: jest.fn(() => ({
    token: "",
    header: "X-MCP-Proxy-Auth",
  })),
  getInitialTransportType: jest.fn(() => "stdio"),
  getInitialSseUrl: jest.fn(() => "http://localhost:3001/sse"),
  getInitialCommand: jest.fn(() => "mcp-server-everything"),
  getInitialArgs: jest.fn(() => ""),
  initializeInspectorConfig: jest.fn(() => ({})),
  saveInspectorConfig: jest.fn(),
}));

jest.mock("../lib/hooks/useDraggablePane", () => ({
  useDraggablePane: () => ({
    height: 300,
    handleDragStart: jest.fn(),
  }),
  useDraggableSidebar: () => ({
    width: 320,
    isDragging: false,
    handleDragStart: jest.fn(),
  }),
}));

jest.mock("../components/Sidebar", () => ({
  __esModule: true,
  default: () => <div>Sidebar</div>,
}));

jest.mock("../components/HistoryAndNotifications", () => ({
  __esModule: true,
  default: () => <div>HistoryAndNotifications</div>,
}));

global.fetch = jest.fn().mockResolvedValue({ json: () => Promise.resolve({}) });

jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: jest.fn(),
}));

function connectionState(serverCapabilities: Record<string, unknown>) {
  return {
    connectionStatus: "connected" as const,
    serverCapabilities,
    serverImplementation: null,
    mcpClient: {
      request: jest.fn(),
      notification: jest.fn(),
      close: jest.fn(),
    } as unknown as Client,
    requestHistory: [],
    clearRequestHistory: jest.fn(),
    makeRequest: jest.fn(),
    cancelTask: jest.fn(),
    listTasks: jest.fn(),
    sendNotification: jest.fn(),
    handleCompletion: jest.fn(),
    completionsSupported: false,
    connect: jest.fn(),
    disconnect: jest.fn(),
  } as ReturnType<typeof useConnection>;
}

describe("App - tools-link cache-hit pane guard (#344)", () => {
  const mockUseConnection = jest.mocked(useConnection);

  const makeRequest = jest.fn(
    async (request: { method: string; params?: { uri?: string } }) => {
      switch (request.method) {
        case "resources/list":
          return {
            resources: [
              {
                uri: "demo://cached",
                name: "cached-resource",
                mimeType: "text/plain",
              },
              {
                uri: "demo://selected",
                name: "selected-resource",
                mimeType: "text/plain",
              },
            ],
            nextCursor: undefined,
          };
        case "resources/read": {
          const uri = request.params?.uri ?? "";
          return {
            contents: [
              {
                uri,
                mimeType: "text/plain",
                text:
                  uri === "demo://cached"
                    ? "cached-payload"
                    : "selected-payload",
              },
            ],
          };
        }
        case "tools/list":
          return {
            tools: [
              {
                name: "link-tool",
                inputSchema: { type: "object", properties: {} },
              },
            ],
            nextCursor: undefined,
          };
        case "tools/call":
          return {
            content: [
              {
                type: "resource_link",
                uri: "demo://cached",
                name: "cached-resource",
                mimeType: "text/plain",
              },
            ],
          };
        default:
          return {};
      }
    },
  );

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("a Tools-link click on a cached resource does not overwrite the pane of the selected row", async () => {
    mockUseConnection.mockReturnValue({
      ...connectionState({
        resources: { listChanged: true },
        tools: { listChanged: true },
      }),
      makeRequest,
    } as ReturnType<typeof useConnection>);

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    fireEvent.click(screen.getByText("List Resources"));
    await waitFor(() => {
      expect(screen.getByTitle("demo://cached")).toBeInTheDocument();
    });

    const { act } = await import("@testing-library/react");

    // Cache both rows: read the cached resource first, then select the
    // other row. The pane shows selected-payload and the selection sits
    // on the selected-resource row.
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://cached"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/cached-payload/)).toBeInTheDocument();
    });
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://selected"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/selected-payload/)).toBeInTheDocument();
    });

    // To the Tools tab: list, select, and run the tool whose result
    // carries a resource_link to the already-cached uri. Tab state is
    // driven by the hashchange listener (#200), which fires as a
    // macrotask: userEvent.click plus a data-state wait keeps the pane
    // switch deterministic.
    const user = userEvent.setup();
    await user.click(screen.getByRole("tab", { name: "Tools" }));
    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "Tools" })).toHaveAttribute(
        "data-state",
        "active",
      );
    });
    fireEvent.click(screen.getByText("List Tools"));
    await waitFor(() => {
      expect(screen.getByText("link-tool")).toBeInTheDocument();
    });
    await act(async () => {
      fireEvent.click(screen.getByText("link-tool"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    fireEvent.click(screen.getByText("Run Tool"));
    await waitFor(() => {
      expect(
        screen.getByRole("button", {
          name: /Expand resource demo:\/\/cached/,
        }),
      ).toBeInTheDocument();
    });

    // Click the Tools link for the cached uri. The read is a cache hit:
    // the payload must surface inline in the tool result...
    await act(async () => {
      fireEvent.click(
        screen.getByRole("button", {
          name: /Expand resource demo:\/\/cached/,
        }),
      );
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    expect(screen.getByText(/cached-payload/)).toBeInTheDocument();

    // ...but back on the Resources tab the pane must still show the
    // selected row's payload: the cache-hit pane commit is guarded by
    // the live selected uri, and the selection never left the
    // selected-resource row.
    await user.click(screen.getByRole("tab", { name: "Resources" }));
    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "Resources" })).toHaveAttribute(
        "data-state",
        "active",
      );
    });
    expect(screen.getByText(/selected-payload/)).toBeInTheDocument();
    expect(screen.queryByText(/cached-payload/)).not.toBeInTheDocument();
  }, 15000);
});
