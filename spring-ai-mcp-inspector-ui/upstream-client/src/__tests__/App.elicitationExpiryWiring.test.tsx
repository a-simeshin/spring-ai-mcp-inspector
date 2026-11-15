// [spring-ai-mcp-inspector PATCH] Issue #298: wiring coverage. An elicitation
// card that arrives while a tools/call is in flight is correlated with that
// call, is expired when the call settles, refuses a late resolve, and never
// blocks the normal timely resolve.
import { fireEvent, render, screen, waitFor, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach } from "@jest/globals";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";

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

// Renders the App-side pending elicitation state so the wiring under test
// (correlation id recorded at arrival, expired flag after settlement, cards
// kept after a refused late resolve) is observable in the DOM.
jest.mock("../components/ElicitationTab", () => ({
  __esModule: true,
  default: ({
    pendingRequests,
    onResolve,
  }: {
    pendingRequests: Array<{
      id: number;
      expired?: boolean;
      toolCallId?: number;
    }>;
    onResolve: (id: number, response: { action: string }) => void;
  }) => (
    <div data-testid="mock-elicitation">
      {pendingRequests.map((request) => (
        <div
          key={request.id}
          data-testid={`elicitation-card-${request.id}`}
          data-expired={request.expired ? "true" : "false"}
          data-tool-call-id={
            request.toolCallId === undefined ? "" : String(request.toolCallId)
          }
        >
          <button
            type="button"
            onClick={() => onResolve(request.id, { action: "accept" })}
          >
            mock resolve card {request.id}
          </button>
        </div>
      ))}
    </div>
  ),
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
          void callTool("askUser", { question: "What is your favorite color?" });
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

type ElicitationRequestPayload = {
  params: {
    message: string;
    requestedSchema?: Record<string, unknown>;
  };
};

function createDeferred() {
  let resolve!: (value: unknown) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("App - elicitation expiry wiring (Issue #298)", () => {
  const mockUseConnection = jest.mocked(useConnection);

  const elicitationRequest = {
    params: {
      message: "What is your favorite color?",
      requestedSchema: {
        type: "object",
        properties: { color: { type: "string" } },
      },
    },
  };

  beforeEach(() => {
    jest.clearAllMocks();
    window.location.hash = "#tools";
  });

  const mockConnection = (
    makeRequest: ReturnType<typeof jest.fn>,
    captured: Record<string, unknown>,
  ): void => {
    mockUseConnection.mockImplementation((options) => {
      Object.assign(captured, options);
      return {
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
      } as ReturnType<typeof useConnection>;
    });
  };

  it("expires the card when the correlated tools/call settles with an error", async () => {
    const deferred = createDeferred();
    const makeRequest = jest.fn(async (request: { method: string }) => {
      if (request.method === "tools/call") {
        return deferred.promise;
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    const captured: Record<string, unknown> = {};
    mockConnection(makeRequest, captured);

    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /mock call tool/i }));

    await waitFor(() => {
      expect(makeRequest).toHaveBeenCalled();
    });

    // The elicitation arrives while the call is still in flight: the card is
    // live and correlated with the pending call id.
    const resolveSpy = jest.fn();
    const declineSpy = jest.fn();
    await act(async () => {
      (captured.onElicitationRequest as (
        request: ElicitationRequestPayload,
        resolve: (response: unknown) => void,
        decline: (error: Error) => void,
      ) => void)(elicitationRequest, resolveSpy, declineSpy);
    });
    expect(screen.getByTestId("elicitation-card-0")).toHaveAttribute(
      "data-expired",
      "false",
    );
    expect(screen.getByTestId("elicitation-card-0")).toHaveAttribute(
      "data-tool-call-id",
      "0",
    );

    // The call settles with the server-side timeout error: the card flips to
    // expired, stays mounted, and a late resolve neither removes the card nor
    // reaches the server handler.
    await act(async () => {
      deferred.reject(
        new Error(
          "askUser: elicitation request failed: TimeoutException: Did not observe any item or terminal signal within 20000ms",
        ),
      );
    });
    await waitFor(() => {
      expect(screen.getByTestId("elicitation-card-0")).toHaveAttribute(
        "data-expired",
        "true",
      );
    });

    fireEvent.click(
      screen.getByRole("button", { name: /mock resolve card 0/i }),
    );
    expect(screen.getByTestId("elicitation-card-0")).toBeInTheDocument();
    expect(screen.getByTestId("elicitation-card-0")).toHaveAttribute(
      "data-expired",
      "true",
    );
    expect(resolveSpy).not.toHaveBeenCalled();
    expect(declineSpy).not.toHaveBeenCalled();
  });

  it("delivers a timely resolve before the call settles (no regression)", async () => {
    const deferred = createDeferred();
    const makeRequest = jest.fn(async (request: { method: string }) => {
      if (request.method === "tools/call") {
        return deferred.promise;
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    const captured: Record<string, unknown> = {};
    mockConnection(makeRequest, captured);

    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /mock call tool/i }));

    await waitFor(() => {
      expect(makeRequest).toHaveBeenCalled();
    });

    const resolveSpy = jest.fn();
    await act(async () => {
      (captured.onElicitationRequest as (
        request: ElicitationRequestPayload,
        resolve: (response: unknown) => void,
        decline: (error: Error) => void,
      ) => void)(elicitationRequest, resolveSpy, jest.fn());
    });
    expect(screen.getByTestId("elicitation-card-0")).toHaveAttribute(
      "data-expired",
      "false",
    );

    // The user answers while the server still waits: the resolve reaches the
    // handler and the card is removed as before.
    fireEvent.click(
      screen.getByRole("button", { name: /mock resolve card 0/i }),
    );
    expect(resolveSpy).toHaveBeenCalledTimes(1);
    expect(
      screen.queryByTestId("elicitation-card-0"),
    ).not.toBeInTheDocument();

    // The call still completes normally afterwards.
    await act(async () => {
      deferred.resolve({ content: [{ type: "text", text: "blue" }], isError: false });
    });
    await waitFor(() => {
      const rendered = JSON.parse(
        screen.getByTestId("tools-result").textContent || "null",
      );
      expect(rendered.content[0].text).toBe("blue");
    });
  });

  it("drops nothing when resolve and settlement race", async () => {
    const deferred = createDeferred();
    const makeRequest = jest.fn(async (request: { method: string }) => {
      if (request.method === "tools/call") {
        return deferred.promise;
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    const captured: Record<string, unknown> = {};
    mockConnection(makeRequest, captured);

    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /mock call tool/i }));

    await waitFor(() => {
      expect(makeRequest).toHaveBeenCalled();
    });

    const resolveSpy = jest.fn();
    await act(async () => {
      (captured.onElicitationRequest as (
        request: ElicitationRequestPayload,
        resolve: (response: unknown) => void,
        decline: (error: Error) => void,
      ) => void)(elicitationRequest, resolveSpy, jest.fn());
    });

    // User resolves, and in the same tick the call settles: whichever order
    // the two updates land in, the state converges without errors and the
    // late resolve is not resurrected as a live card.
    await act(async () => {
      fireEvent.click(
        screen.getByRole("button", { name: /mock resolve card 0/i }),
      );
      deferred.reject(new Error("timeout"));
    });
    await waitFor(() => {
      expect(
        screen.queryByTestId("elicitation-card-0"),
      ).not.toBeInTheDocument();
    });
    expect(resolveSpy).toHaveBeenCalledTimes(1);
  });

  it("creates a card already expired when it arrives after the call settled", async () => {
    // The CI repro: a fast-settling call (tool error) outraces SSE delivery,
    // so the settle runs while the card does not exist yet. The card must be
    // created expired rather than arriving live and staying live forever.
    const deferred = createDeferred();
    const makeRequest = jest.fn(async (request: { method: string }) => {
      if (request.method === "tools/call") {
        return deferred.promise;
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });
    const captured: Record<string, unknown> = {};
    mockConnection(makeRequest, captured);

    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /mock call tool/i }));

    await waitFor(() => {
      expect(makeRequest).toHaveBeenCalled();
    });

    // The call settles before any elicitation card arrives.
    await act(async () => {
      deferred.reject(new Error("askUser: elicitation request failed: boom"));
    });
    await waitFor(() => {
      const rendered = JSON.parse(
        screen.getByTestId("tools-result").textContent || "null",
      );
      expect(rendered.isError).toBe(true);
    });

    // The card arrives now, after the settle: it is correlated with the
    // settled call and born expired, and a late resolve is refused.
    const resolveSpy = jest.fn();
    const declineSpy = jest.fn();
    await act(async () => {
      (captured.onElicitationRequest as (
        request: ElicitationRequestPayload,
        resolve: (response: unknown) => void,
        decline: (error: Error) => void,
      ) => void)(elicitationRequest, resolveSpy, declineSpy);
    });
    expect(screen.getByTestId("elicitation-card-0")).toHaveAttribute(
      "data-expired",
      "true",
    );
    expect(screen.getByTestId("elicitation-card-0")).toHaveAttribute(
      "data-tool-call-id",
      "0",
    );
    fireEvent.click(
      screen.getByRole("button", { name: /mock resolve card 0/i }),
    );
    expect(screen.getByTestId("elicitation-card-0")).toBeInTheDocument();
    expect(resolveSpy).not.toHaveBeenCalled();
    expect(declineSpy).not.toHaveBeenCalled();
  });
});
