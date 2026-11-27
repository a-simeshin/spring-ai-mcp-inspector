// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28) wiring
// coverage for the InputRequired PANEL (#253): proves the pending requests
// surface in the UI, Submit re-sends the original tools/call with the user's
// inputResponses and the requestState echoed byte-exact, multi-round answers
// advance the SAME chain in the panel, Cancel abandons it, and the final
// result lands on the tools tab (no dead-end truncated result).
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

describe("App - SEP-2322 input_required panel", () => {
  const mockUseConnection = jest.mocked(useConnection);

  // Byte-tricky opaque state (unicode + padding-looking chars): the echo must
  // be byte-exact, never normalized.
  const stateRound1 = "opaque-state-==\u{1F600}:1";
  const stateRound2 = "opaque-state-==\u{1F600}:2";

  const round1Result = {
    resultType: "input_required" as const,
    inputRequests: {
      "req-1": {
        method: "elicitation/create",
        params: {
          message: "Which seat?",
          requestedSchema: {
            type: "object" as const,
            properties: {
              seat: { type: "string" as const, title: "Seat" },
            },
            required: ["seat"],
          },
        },
      },
    },
    requestState: stateRound1,
  };

  const round2Result = {
    resultType: "input_required" as const,
    inputRequests: {
      "req-2": {
        method: "custom/input",
        params: { message: "Confirm the upgrade?" },
      },
    },
    requestState: stateRound2,
  };

  const finalResult = {
    content: [{ type: "text" as const, text: "Booked." }],
    isError: false,
  };

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

  beforeEach(() => {
    jest.clearAllMocks();
    window.location.hash = "#tools";
  });

  it("renders the pending requests, echoes requestState byte-exact, and lands the final result on the tools tab", async () => {
    const makeRequest = jest.fn(async (request: {
      method: string;
      params?: Record<string, unknown>;
    }) => {
      if (request.method === "tools/call") {
        if (request.params?.inputResponses === undefined) {
          return round1Result;
        }
        expect(request.params.requestState).toBe(stateRound1);
        return finalResult;
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    mockConnection(makeRequest);

    render(<App />);

    // The panel tab exists unconditionally, next to the legacy elicitation.
    expect(
      screen.getByRole("tab", { name: /input required/i }),
    ).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /mock call tool/i }));

    // Round 1 surfaces in the panel and focuses it.
    await waitFor(() => {
      expect(screen.getByTestId("input-required-chain")).toBeTruthy();
    });
    expect(window.location.hash).toBe("#input-required");
    expect(screen.getByTestId("input-required-round").textContent).toBe(
      "Round 1",
    );
    expect(screen.getByTestId("input-request-req-1")).toBeTruthy();
    expect(
      screen.getByTestId("input-required-request-state").getAttribute("title"),
    ).toBe(stateRound1);

    // Answer through the per-type control.
    const seatField = screen
      .getByTestId("input-request-req-1-field-seat")
      .querySelector("input");
    expect(seatField).not.toBeNull();
    fireEvent.change(seatField as HTMLInputElement, {
      target: { value: "1B" },
    });
    fireEvent.click(screen.getByTestId("input-required-submit"));

    // The re-send carries the ORIGINAL call plus inputResponses and the
    // requestState echoed byte-exact.
    await waitFor(() => {
      expect(makeRequest).toHaveBeenCalledTimes(2);
    });
    const retry = makeRequest.mock.calls[1][0];
    expect(retry.method).toBe("tools/call");
    expect(retry.params).toEqual({
      name: "book-flight",
      arguments: { seat: "12A" },
      _meta: { progressToken: expect.any(Number) },
      inputResponses: {
        "req-1": { action: "accept", content: { seat: "1B" } },
      },
      requestState: stateRound1,
    });

    // The chain closes: the panel empties and the final result is on the
    // tools tab, not a dead-end truncated frame.
    await waitFor(() => {
      expect(screen.queryByTestId("input-required-chain")).toBeNull();
    });
    fireEvent.click(screen.getByRole("tab", { name: "Tools" }));
    await waitFor(() => {
      const rendered = JSON.parse(
        screen.getByTestId("tools-result").textContent || "null",
      );
      expect(rendered.content[0].text).toBe("Booked.");
    });
  });

  it("advances the SAME panel chain across multiple rounds", async () => {
    const makeRequest = jest.fn(async (request: {
      method: string;
      params?: Record<string, unknown>;
    }) => {
      if (request.method === "tools/call") {
        if (request.params?.requestState === stateRound2) {
          return finalResult;
        }
        if (request.params?.inputResponses !== undefined) {
          return round2Result;
        }
        return round1Result;
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    mockConnection(makeRequest);

    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /mock call tool/i }));

    await waitFor(() => {
      expect(screen.getByTestId("input-request-req-1")).toBeTruthy();
    });
    const seatField = screen
      .getByTestId("input-request-req-1-field-seat")
      .querySelector("input");
    fireEvent.change(seatField as HTMLInputElement, {
      target: { value: "2C" },
    });
    fireEvent.click(screen.getByTestId("input-required-submit"));

    // Round 2 replaces the panel content on the same chain.
    await waitFor(() => {
      expect(screen.getByTestId("input-request-req-2")).toBeTruthy();
    });
    expect(screen.queryByTestId("input-request-req-1")).toBeNull();
    expect(screen.getByTestId("input-required-round").textContent).toBe(
      "Round 2",
    );

    fireEvent.change(screen.getByTestId("input-request-req-2-json"), {
      target: { value: '{"answer":"yes"}' },
    });
    fireEvent.click(screen.getByTestId("input-required-submit"));

    await waitFor(() => {
      expect(makeRequest).toHaveBeenCalledTimes(3);
    });
    const lastRetry = makeRequest.mock.calls[2][0];
    expect(lastRetry.params.requestState).toBe(stateRound2);
    expect(lastRetry.params.inputResponses).toEqual({
      "req-2": { answer: "yes" },
    });

    await waitFor(() => {
      expect(screen.queryByTestId("input-required-chain")).toBeNull();
    });
  });

  it("cancel abandons the chain cleanly: no re-send, panel emptied", async () => {
    const makeRequest = jest.fn(async (request: { method: string }) => {
      if (request.method === "tools/call") {
        return round1Result;
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    mockConnection(makeRequest);

    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /mock call tool/i }));

    await waitFor(() => {
      expect(screen.getByTestId("input-required-chain")).toBeTruthy();
    });
    fireEvent.click(screen.getByTestId("input-required-cancel"));

    await waitFor(() => {
      expect(screen.queryByTestId("input-required-chain")).toBeNull();
    });
    expect(makeRequest).toHaveBeenCalledTimes(1);
  });
});
