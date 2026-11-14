// [spring-ai-mcp-inspector PATCH] Regression guard for the preview-pane
// race of issue #344: a late resources/read response for a
// no-longer-selected resource must not overwrite the pane (unfixed
// App.readResource set `resourceContent` unconditionally when the
// response resolved). Test drives the real ResourcesTab: slow read is
// parked on a deferred promise, fast read resolves immediately, then
// the slow response lands last; the pane must keep the fast payload.
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
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

describe("App - stale preview pane race guard (#344)", () => {
  const mockUseConnection = jest.mocked(useConnection);
  let resolveSlowRead: (payload: unknown) => void;

  const makeRequest = jest.fn(
    async (request: { method: string; params?: { uri?: string } }) => {
      switch (request.method) {
        case "resources/list":
          return {
            resources: [
              {
                uri: "demo://slow",
                name: "slow-resource",
                mimeType: "text/plain",
              },
              {
                uri: "demo://fast",
                name: "fast-resource",
                mimeType: "text/plain",
              },
            ],
            nextCursor: undefined,
          };
        case "resources/read": {
          const uri = request.params?.uri ?? "";
          if (uri === "demo://slow") {
            // Park the slow read on a deferred promise: it resolves only
            // when the test releases it, after the fast read is done.
            return new Promise((resolve) => {
              resolveSlowRead = resolve;
            });
          }
          return {
            contents: [
              { uri, mimeType: "text/plain", text: "fast-payload" },
            ],
          };
        }
        default:
          return {};
      }
    },
  );

  beforeEach(() => {
    jest.clearAllMocks();
    resolveSlowRead = () => {};
  });

  it("a late slow read does not overwrite the pane while the fast row is selected", async () => {
    mockUseConnection.mockReturnValue({
      ...connectionState({ resources: { listChanged: true } }),
      makeRequest,
    } as ReturnType<typeof useConnection>);

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    fireEvent.click(screen.getByText("List Resources"));
    await waitFor(() => {
      expect(screen.getByTitle("demo://slow")).toBeInTheDocument();
    });

    // Select the slow resource: its read stays in flight. The click and a
    // microtask settle run inside act so React commits synchronously.
    const { act } = await import("@testing-library/react");
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://slow"));
      await new Promise((resolve) => setTimeout(resolve, 10));
    });

    // Switch to the fast resource while the slow read is still in flight.
    // The fast read resolves inside this act: its continuation (the
    // unconditional App.readResource set at App.tsx:1219) commits here.
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://fast"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/fast-payload/)).toBeInTheDocument();
    });

    // The slow response lands last. The pane must keep showing the fast
    // resource: the pane update in App.readResource is guarded by the
    // selected uri (#344), so the late response for the deselected slow
    // row must not overwrite it. The resolution runs inside act so the
    // late continuation commits.
    await act(async () => {
      resolveSlowRead({
        contents: [
          { uri: "demo://slow", mimeType: "text/plain", text: "slow-payload" },
        ],
      });
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    // The pane still shows the fast payload...
    expect(screen.getByText(/fast-payload/)).toBeInTheDocument();
    // ...and the slow payload never entered the pane.
    expect(screen.queryByText(/slow-payload/)).not.toBeInTheDocument();
    // Selection did not follow the payload: the header must not show the
    // slow resource name a second time (row list already has it once).
    expect(screen.getAllByText("slow-resource")).toHaveLength(1);
    expect(screen.getAllByText("fast-resource").length).toBeGreaterThan(0);
  }, 15000);
});
