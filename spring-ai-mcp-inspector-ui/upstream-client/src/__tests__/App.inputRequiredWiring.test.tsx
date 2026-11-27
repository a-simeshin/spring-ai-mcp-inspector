// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28)
// wiring coverage (#253): proves App's callTool opens an input_required chain
// via InputRequiredController with the ORIGINAL request params while the
// result still flows to the tools tab unchanged.
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InputRequiredController } from "../lib/inputRequired";

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
  default: () => <div data-testid="mock-prompts" />,
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
  default: ({
    callTool,
    toolResult,
  }: {
    callTool: (
      name: string,
      params: Record<string, unknown>,
    ) => Promise<unknown>;
    toolResult: unknown;
  }) => (
    <div data-testid="tools-tab">
      <button
        type="button"
        onClick={() => {
          void callTool("book-flight", { seat: "12A" });
        }}
      >
        mock call tool
      </button>
      <div data-testid="tools-result">{JSON.stringify(toolResult ?? null)}</div>
    </div>
  ),
}));

jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: jest.fn(),
}));

describe("App - SEP-2322 input_required wiring", () => {
  const mockUseConnection = jest.mocked(useConnection);

  const inputRequiredResponse = {
    resultType: "input_required" as const,
    inputRequests: {
      "req-1": {
        method: "elicitation/create",
        params: { message: "Which seat?" },
      },
    },
    requestState: "opaque-state-\ud83d\ude00==",
  };

  beforeEach(() => {
    jest.clearAllMocks();
    window.location.hash = "#tools";
  });

  const mockConnection = (
    makeRequest: ReturnType<typeof jest.fn>,
  ): void => {
    mockUseConnection.mockReturnValue({
      connectionStatus: "connected",
      serverCapabilities: { tools: { listChanged: true } },
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
  };

  it("opens an input_required chain when tools/call answers with one", async () => {
    const makeRequest = jest.fn(async (request: { method: string }) => {
      if (request.method === "tools/call") {
        return inputRequiredResponse;
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    mockConnection(makeRequest);
    const openChain = jest
      .spyOn(InputRequiredController.prototype, "openChain")
      .mockReturnValue(null);

    render(<App />);

    fireEvent.click(screen.getByRole("button", { name: /mock call tool/i }));

    await waitFor(() => {
      expect(openChain).toHaveBeenCalledTimes(1);
    });
    const [params, result] = openChain.mock.calls[0];
    // The ORIGINAL params are registered, including the client-added
    // _meta.progressToken, so a resend re-issues exactly what was sent.
    expect(params).toEqual({
      name: "book-flight",
      arguments: { seat: "12A" },
      _meta: { progressToken: expect.any(Number) },
    });
    expect(result).toEqual(inputRequiredResponse);

    // The result still flows to the tools tab unchanged (byte-exact fields).
    await waitFor(() => {
      const rendered = JSON.parse(
        screen.getByTestId("tools-result").textContent || "null",
      );
      expect(rendered).toEqual(inputRequiredResponse);
    });
    openChain.mockRestore();
  });

  it("does not open a chain for a plain final tools/call result", async () => {
    const makeRequest = jest.fn(async (request: { method: string }) => {
      if (request.method === "tools/call") {
        return { content: [{ type: "text", text: "booked" }], isError: false };
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    mockConnection(makeRequest);
    const openChain = jest
      .spyOn(InputRequiredController.prototype, "openChain")
      .mockReturnValue(null);

    render(<App />);

    fireEvent.click(screen.getByRole("button", { name: /mock call tool/i }));

    await waitFor(() => {
      const rendered = JSON.parse(
        screen.getByTestId("tools-result").textContent || "null",
      );
      expect(rendered.content[0].text).toBe("booked");
    });
    expect(openChain).not.toHaveBeenCalled();
    openChain.mockRestore();
  });
});
