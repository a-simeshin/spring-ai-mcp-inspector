// [spring-ai-mcp-inspector PATCH] App-level pin for the prompts/list
// fire-and-forget wiring (issue #276, see NOTICE.d/prompts-list-rejection-test.txt).
// PromptsTab's listPrompts prop is typed `() => void`, so the promise returned
// by App's listPrompts() has no reader: sendMCPRequest() records the failure
// under the prompts error key and rethrows, and without the
// swallowLoggedMcpError() wrapper at the App wiring the rethrow surfaces as a
// Node unhandledRejection. This test fails if only that wrapper regresses.
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";

// Mock auth dependencies first
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
  default: () => <div data-testid="mock-sidebar" />,
}));

jest.mock("../components/ResourcesTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-resources" />,
}));

jest.mock("../components/PromptsTab", () => ({
  __esModule: true,
  default: ({
    listPrompts,
    error,
  }: {
    listPrompts: () => void;
    error: string | null;
  }) => (
    <div data-testid="mock-prompts">
      <button
        type="button"
        onClick={() => {
          listPrompts();
        }}
      >
        mock list prompts
      </button>
      <div data-testid="prompts-error">{error ?? ""}</div>
    </div>
  ),
}));

jest.mock("../components/TasksTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-tasks" />,
}));

jest.mock("../components/ConsoleTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-console" />,
}));

jest.mock("../components/PingTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-ping" />,
}));

jest.mock("../components/SamplingTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-sampling" />,
}));

jest.mock("../components/RootsTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-roots" />,
}));

jest.mock("../components/ElicitationTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-elicitation" />,
}));

jest.mock("../components/MetadataTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-metadata" />,
}));

jest.mock("../components/AuthDebugger", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-auth-debugger" />,
}));

jest.mock("../components/HistoryAndNotifications", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-history" />,
}));

jest.mock("../components/AppsTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-apps" />,
}));

jest.mock("../components/ToolsTab", () => ({
  __esModule: true,
  default: () => <div data-testid="mock-tools" />,
}));

jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: jest.fn(),
}));

describe("App - prompts/list rejection never becomes unhandledRejection", () => {
  const mockUseConnection = jest.mocked(useConnection);

  beforeEach(() => {
    jest.clearAllMocks();
    window.location.hash = "#prompts";
  });

  it("routes a rejecting prompts/list through the swallowLoggedMcpError wrapper", async () => {
    const makeRequest = jest.fn(async (request: { method: string }) => {
      if (request.method === "prompts/list") {
        throw new Error("MCP error -32602: text must not be null");
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    mockUseConnection.mockReturnValue({
      connectionStatus: "connected",
      serverCapabilities: { prompts: { listChanged: false } },
      serverImplementation: null,
      mcpClient: {
        request: jest.fn(),
        notification: jest.fn(),
        close: jest.fn(),
      } as unknown as Client,
      requestHistory: [],
      clearRequestHistory: jest.fn(),
      makeRequest,
      cancelTask: jest.fn(),
      listTasks: jest.fn(),
      sendNotification: jest.fn(),
      handleCompletion: jest.fn(),
      completionsSupported: false,
      connect: jest.fn(),
      disconnect: jest.fn(),
    } as ReturnType<typeof useConnection>);

    render(<App />);

    fireEvent.click(screen.getByRole("button", { name: /mock list prompts/i }));

    // The surfaced half of the contract: sendMCPRequest records the failure
    // under the prompts error key before rethrowing, so the tab still shows it.
    await waitFor(() => {
      expect(screen.getByTestId("prompts-error")).toHaveTextContent(
        "MCP error -32602",
      );
    });
    // The flow must actually have reached the client, otherwise this test
    // would pass without exercising the error path at all.
    expect(makeRequest).toHaveBeenCalledWith(
      expect.objectContaining({ method: "prompts/list" }),
      expect.anything(),
    );

    // The swallowed half: Jest fails a test whose run leaves an unhandled
    // promise rejection on its own, so no manual listener is wired up here.
    // A rejection becomes "unhandled" once the microtask queue drains; two
    // macrotasks give that detection room to fire if the swallowLoggedMcpError
    // wrapper is gone.
    await new Promise((resolve) => setTimeout(resolve, 0));
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
});
