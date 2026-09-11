// [spring-ai-mcp-inspector PATCH] Regression test for tab click race (#200):
// verifies that removing the synchronous setActiveTab from onValueChange does
// not break tab switching. Hashchange listener is the sole state source.
// Must run before react-dom loads (jsdom lacks PointerEvent; React only
// attaches pointermove listeners when the constructor exists).
import "../testUtils/pointerEventsPolyfill";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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
  default: () => <div>Sidebar</div>,
}));

// Tab components mocked with TabsContent wrappers so Radix controls visibility.
/* eslint-disable @typescript-eslint/no-require-imports */
jest.mock("../components/ResourcesTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="resources" data-testid="resources-pane">
        Resources content pane
      </TabsContent>
    ),
  };
});

jest.mock("../components/PromptsTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="prompts" data-testid="prompts-pane">
        Prompts content pane
      </TabsContent>
    ),
  };
});

jest.mock("../components/ToolsTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="tools" data-testid="tools-pane">
        Tools content pane
      </TabsContent>
    ),
  };
});

jest.mock("../components/TasksTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="tasks" data-testid="tasks-pane">
        Tasks content pane
      </TabsContent>
    ),
  };
});
/* eslint-enable @typescript-eslint/no-require-imports */

jest.mock("../components/AppsTab", () => ({
  __esModule: true,
  default: () => <div>AppsTab</div>,
}));

jest.mock("../components/ConsoleTab", () => ({
  __esModule: true,
  default: () => <div>ConsoleTab</div>,
}));

jest.mock("../components/PingTab", () => ({
  __esModule: true,
  default: () => <div>PingTab</div>,
}));

jest.mock("../components/SamplingTab", () => ({
  __esModule: true,
  default: () => <div>SamplingTab</div>,
}));

jest.mock("../components/RootsTab", () => ({
  __esModule: true,
  default: () => <div>RootsTab</div>,
}));

jest.mock("../components/ElicitationTab", () => ({
  __esModule: true,
  default: () => <div>ElicitationTab</div>,
}));

jest.mock("../components/MetadataTab", () => ({
  __esModule: true,
  default: () => <div>MetadataTab</div>,
}));

jest.mock("../components/AuthDebugger", () => ({
  __esModule: true,
  default: () => <div>AuthDebugger</div>,
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

describe("App - tab click switching content pane (regression t_c0dcfe9a)", () => {
  const mockUseConnection = jest.mocked(useConnection);

  beforeEach(() => {
    jest.clearAllMocks();
    window.location.hash = "";
  });

  it("switches content pane on tab click", async () => {
    // Give the server prompts + tools capabilities but NOT resources,
    // so the default tab (first available) is Prompts.
    mockUseConnection.mockReturnValue(
      connectionState({
        prompts: { listChanged: true },
        tools: { listChanged: true },
      }),
    );

    const user = userEvent.setup();
    render(<App />);

    // --- Step 1: Wait for the initial Prompts tab to be active ---
    const promptsTrigger = screen.getByRole("tab", { name: /^Prompts$/i });
    await waitFor(() => {
      expect(promptsTrigger).toHaveAttribute("aria-selected", "true");
      expect(promptsTrigger).toHaveAttribute("data-state", "active");
    });

    // Resources trigger exists but is disabled (no resources capability)
    const resourcesTrigger = screen.getByRole("tab", { name: /^Resources$/i });
    expect(resourcesTrigger).toBeDisabled();

    // The Prompts pane is visible initially
    const promptsPane = screen.getByTestId("prompts-pane");
    expect(promptsPane).toBeVisible();
    expect(promptsPane).toHaveAttribute("data-state", "active");

    // The Tools pane is hidden initially
    const toolsPane = screen.getByTestId("tools-pane");
    expect(toolsPane).not.toBeVisible();
    expect(toolsPane).toHaveAttribute("data-state", "inactive");

    // --- Step 2: Click the Tools tab trigger ---
    const toolsTrigger = screen.getByRole("tab", { name: /^Tools$/i });
    await user.click(toolsTrigger);

    // --- Step 3: Assert Tools trigger is now active ---
    await waitFor(() => {
      expect(toolsTrigger).toHaveAttribute("aria-selected", "true");
      expect(toolsTrigger).toHaveAttribute("data-state", "active");
    });

    // The old Prompts trigger should be inactive
    expect(promptsTrigger).toHaveAttribute("aria-selected", "false");
    expect(promptsTrigger).toHaveAttribute("data-state", "inactive");

    // The Tools pane is visible; the Prompts pane is hidden
    expect(toolsPane).toBeVisible();
    expect(toolsPane).toHaveAttribute("data-state", "active");
    expect(promptsPane).not.toBeVisible();
    expect(promptsPane).toHaveAttribute("data-state", "inactive");

    // --- Step 4: Click back to the Prompts tab ---
    await user.click(promptsTrigger);

    await waitFor(() => {
      expect(promptsTrigger).toHaveAttribute("aria-selected", "true");
      expect(promptsTrigger).toHaveAttribute("data-state", "active");
    });

    expect(toolsTrigger).toHaveAttribute("aria-selected", "false");
    expect(toolsTrigger).toHaveAttribute("data-state", "inactive");
    expect(promptsPane).toBeVisible();
    expect(promptsPane).toHaveAttribute("data-state", "active");
    expect(toolsPane).not.toBeVisible();
    expect(toolsPane).toHaveAttribute("data-state", "inactive");
  });

  it("click-through every remaining tab (regression t_c0dcfe9a)", async () => {
    // Enable all capabilities so every tab trigger is available
    const makeRequest = jest.fn(async (request: { method: string }) => {
      if (request.method === "tools/list") {
        return { tools: [], nextCursor: undefined };
      }
      if (request.method === "tasks/list") {
        return { tasks: [], nextCursor: undefined };
      }
      throw new Error(`Unexpected method: ${request.method}`);
    });

    mockUseConnection.mockReturnValue({
      ...connectionState({
        resources: { listChanged: true },
        prompts: { listChanged: true },
        tools: { listChanged: true },
        tasks: { listChanged: true },
      }),
      makeRequest,
    } as ReturnType<typeof useConnection>);

    const user = userEvent.setup();

    render(<App />);

    // The default tab when resources is available is "resources"
    const resourcesTrigger = screen.getByRole("tab", { name: /^Resources$/i });
    await waitFor(() => {
      expect(resourcesTrigger).toHaveAttribute("aria-selected", "true");
    });

    // Tab order in the template: Resources, Prompts, Tools, Tasks, Apps,
    // Ping, Sampling, Elicitations, Roots, Auth, Metadata
    const tabNames = [
      { name: /^Prompts$/i, value: "prompts" },
      { name: /^Tools$/i, value: "tools" },
      { name: /^Tasks$/i, value: "tasks" },
      { name: /^Apps$/i, value: "apps" },
      { name: /^Ping$/i, value: "ping" },
      { name: /^Sampling$/i, value: "sampling" },
      { name: /^Elicitations$/i, value: "elicitations" },
      { name: /^Roots$/i, value: "roots" },
      { name: /^Auth$/i, value: "auth" },
      { name: /^Metadata$/i, value: "metadata" },
    ];

    for (const tab of tabNames) {
      const trigger = screen.getByRole("tab", { name: tab.name });
      await user.click(trigger);

      await waitFor(() => {
        expect(trigger).toHaveAttribute("aria-selected", "true");
        expect(trigger).toHaveAttribute("data-state", "active");
      });
    }
  });
});
