// [spring-ai-mcp-inspector PATCH] Regression tests for resources/read
// re-issuing (#288): Refresh and re-selecting an already-read resource must
// send resources/read again instead of serving the eternal
// resourceContentMap cache (the pane froze at the first-read snapshot until
// a page reload), and a read failure must surface gracefully without
// freezing the panel. Uses the real ResourcesTab so the row click and the
// Refresh button exercise App.readResource end to end.
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

describe("App - readResource re-issues resources/read (#288)", () => {
  const mockUseConnection = jest.mocked(useConnection);
  let readCount: number;
  let readShouldFail: boolean;
  let readFailureText: string;
  const makeRequest = jest.fn(
    async (request: { method: string; params?: { uri?: string } }) => {
      switch (request.method) {
        case "resources/list":
          return {
            resources: [
              {
                uri: "demo://clock",
                name: "demo-clock",
                mimeType: "text/plain",
              },
            ],
            nextCursor: undefined,
          };
        case "resources/read": {
          if (readShouldFail) {
            throw new Error(readFailureText);
          }
          return {
            contents: [
              {
                uri: request.params?.uri ?? "demo://clock",
                mimeType: "text/plain",
                text: `clock=${readCount++}`,
              },
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
    readCount = 0;
    readShouldFail = false;
    readFailureText = "boom";
  });

  async function renderConnectedApp() {
    mockUseConnection.mockReturnValue({
      ...connectionState({ resources: { listChanged: true } }),
      makeRequest,
    } as ReturnType<typeof useConnection>);

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    // Resources is the default tab when the server advertises resources.
    const resourcesTrigger = screen.getByRole("tab", { name: /^Resources$/i });
    await waitFor(() => {
      expect(resourcesTrigger).toHaveAttribute("aria-selected", "true");
    });

    fireEvent.click(screen.getByText("List Resources"));
    await waitFor(() => {
      expect(screen.getByText("demo-clock")).toBeInTheDocument();
    });
  }

  async function selectDemoClock() {
    // The click lands on the name span (title is the uri) and bubbles up to
    // the clickable row. The pane heading also says demo-clock after the
    // first selection, so target the span by its uri title.
    fireEvent.click(screen.getByTitle("demo://clock"));
    await waitFor(() => {
      expect(screen.getByText(/clock=0/)).toBeInTheDocument();
    });
    expect(makeRequest).toHaveBeenCalledWith(
      expect.objectContaining({ method: "resources/read" }),
      expect.anything(),
    );
  }

  it("Refresh re-issues resources/read and re-renders the pane after a cached read", async () => {
    await renderConnectedApp();
    await selectDemoClock();
    const readsBefore = countReadRequests();

    fireEvent.click(screen.getByText("Refresh"));

    await waitFor(() => {
      expect(screen.getByText(/clock=1/)).toBeInTheDocument();
    });
    expect(countReadRequests()).toBe(readsBefore + 1);
  }, 15000);

  it("re-selecting an already-read row re-issues resources/read instead of serving the cache", async () => {
    await renderConnectedApp();
    await selectDemoClock();
    const readsBefore = countReadRequests();

    fireEvent.click(screen.getByTitle("demo://clock"));

    await waitFor(() => {
      expect(screen.getByText(/clock=1/)).toBeInTheDocument();
    });
    expect(countReadRequests()).toBe(readsBefore + 1);
  }, 15000);

  it("a failed read surfaces the error and does not freeze the panel: Refresh retries", async () => {
    readShouldFail = true;
    readFailureText = "boom";
    await renderConnectedApp();

    fireEvent.click(screen.getByText("demo-clock"));
    await waitFor(() => {
      expect(screen.getByText("boom")).toBeInTheDocument();
    });
    // The failure is recorded in the cache, the panel stays alive and the
    // Refresh button remains clickable.
    expect(screen.getByText("Refresh")).toBeEnabled();

    readShouldFail = false;
    fireEvent.click(screen.getByText("Refresh"));

    await waitFor(() => {
      expect(screen.getByText(/clock=0/)).toBeInTheDocument();
    });
    expect(countReadRequests()).toBe(2);
  }, 15000);

  function countReadRequests(): number {
    return makeRequest.mock.calls.filter(
      ([request]) =>
        (request as { method: string }).method === "resources/read",
    ).length;
  }
});
