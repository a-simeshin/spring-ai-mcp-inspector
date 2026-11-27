// [spring-ai-mcp-inspector PATCH] Regression tests for tab click navigation
// (#200): every click must switch the Radix content pane, and tab state flows
// through the hashchange listener (the click handler only writes the hash).
// Must run before react-dom loads (jsdom lacks PointerEvent; React only
// attaches pointermove listeners when the constructor exists).
import "../testUtils/pointerEventsPolyfill";
import { act, render, screen, waitFor } from "@testing-library/react";
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

/* eslint-disable @typescript-eslint/no-require-imports */
jest.mock("../components/AppsTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="apps" data-testid="apps-pane">
        Apps content pane
      </TabsContent>
    ),
  };
});

jest.mock("../components/ConsoleTab", () => ({
  __esModule: true,
  default: () => <div>ConsoleTab</div>,
}));

jest.mock("../components/PingTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="ping" data-testid="ping-pane">
        Ping content pane
      </TabsContent>
    ),
  };
});

jest.mock("../components/SamplingTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="sampling" data-testid="sampling-pane">
        Sampling content pane
      </TabsContent>
    ),
  };
});

jest.mock("../components/RootsTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="roots" data-testid="roots-pane">
        Roots content pane
      </TabsContent>
    ),
  };
});

jest.mock("../components/ElicitationTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="elicitations" data-testid="elicitations-pane">
        Elicitations content pane
      </TabsContent>
    ),
  };
});

jest.mock("../components/MetadataTab", () => {
  const { TabsContent } = require("@/components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="metadata" data-testid="metadata-pane">
        Metadata content pane
      </TabsContent>
    ),
  };
});
/* eslint-enable @typescript-eslint/no-require-imports */

jest.mock("../components/AuthDebugger", () => ({
  __esModule: true,
  default: () => <div data-testid="auth-pane">AuthDebugger</div>,
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

  it("switches the pane through the hashchange path (single state source)", async () => {
    // [spring-ai-mcp-inspector PATCH] The click handler only writes
    // window.location.hash; tab state updates exclusively from the hashchange
    // listener. This drives that path end-to-end with a deterministic dispatch
    // order: the location mock records the hash write and suppresses the
    // automatic hashchange event, the test then runs the listener the way the
    // browser task queue would, and asserts real pane switching once it runs.
    // All window mocks are restored in finally so a failure cannot leak them
    // into sibling tests.
    mockUseConnection.mockReturnValue(
      connectionState({
        prompts: { listChanged: true },
        tools: { listChanged: true },
      }),
    );

    // Set the initial hash so the useState initializer picks "prompts"
    window.location.hash = "#prompts";

    const originalLocation = window.location;
    const originalAddEventListener = window.addEventListener;
    let mockHash = "#prompts";
    const hashchangeHandlers: (() => void)[] = [];

    try {
      // Capture hashchange listeners added by App
      window.addEventListener = jest.fn((event: string, handler: () => void) => {
        if (event === "hashchange") {
          hashchangeHandlers.push(handler);
        }
        return originalAddEventListener(event, handler);
      });

      // @ts-expect-error - mocking location for deterministic hashchange ordering
      delete window.location;
      // @ts-expect-error - mocking location for deterministic hashchange ordering
      window.location = {
        ...originalLocation,
        get hash() {
          return mockHash;
        },
        set hash(value: string) {
          mockHash = value.startsWith("#") ? value : "#" + value;
          // Intentionally NOT dispatching hashchange automatically: the test
          // controls when the browser's hashchange task runs.
        },
      };

      const user = userEvent.setup();
      render(<App />);

      // Wait for initial Prompts tab
      const promptsTrigger = screen.getByRole("tab", { name: /^Prompts$/i });
      await waitFor(() => {
        expect(promptsTrigger).toHaveAttribute("aria-selected", "true");
      });

      const toolsTrigger = screen.getByRole("tab", { name: /^Tools$/i });
      const toolsPane = screen.getByTestId("tools-pane");
      const promptsPane = screen.getByTestId("prompts-pane");

      // Click Tools tab
      await user.click(toolsTrigger);

      // The click handler's contract: it writes the hash; the state update
      // itself arrives from the hashchange listener.
      expect(window.location.hash).toBe("#tools");

      // Run the hashchange listeners the way the browser task queue would
      // after the hash write above.
      act(() => {
        hashchangeHandlers.forEach((handler) => handler());
      });

      // Real pane switching: Tools trigger and pane become active and
      // visible, Prompts trigger and pane become inactive and hidden.
      await waitFor(() => {
        expect(toolsTrigger).toHaveAttribute("aria-selected", "true");
        expect(toolsTrigger).toHaveAttribute("data-state", "active");
      });
      expect(toolsPane).toBeVisible();
      expect(toolsPane).toHaveAttribute("data-state", "active");
      expect(promptsTrigger).toHaveAttribute("aria-selected", "false");
      expect(promptsPane).not.toBeVisible();
      expect(promptsPane).toHaveAttribute("data-state", "inactive");
    } finally {
      window.addEventListener = originalAddEventListener;
      // @ts-expect-error - restoring location
      delete window.location;
      // @ts-expect-error - restoring location
      window.location = originalLocation;
      window.location.hash = "";
    }
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

    // Verify the default Resources pane is visible and active
    const resourcesPane = screen.getByTestId("resources-pane");
    expect(resourcesPane).toBeVisible();

    // Tab order in the template: Resources, Prompts, Tools, Tasks, Apps,
    // Ping, Sampling, Elicitations, Roots, Auth, Metadata
    const tabNames = [
      { name: /^Prompts$/i, value: "prompts", pane: "prompts-pane" },
      { name: /^Tools$/i, value: "tools", pane: "tools-pane" },
      { name: /^Tasks$/i, value: "tasks", pane: "tasks-pane" },
      { name: /^Apps$/i, value: "apps", pane: "apps-pane" },
      { name: /^Ping$/i, value: "ping", pane: "ping-pane" },
      { name: /^Sampling$/i, value: "sampling", pane: "sampling-pane" },
      { name: /^Elicitations$/i, value: "elicitations", pane: "elicitations-pane" },
      { name: /^Roots$/i, value: "roots", pane: "roots-pane" },
      { name: /^Auth$/i, value: "auth", pane: "auth-pane" },
      { name: /^Metadata$/i, value: "metadata", pane: "metadata-pane" },
    ];

    let prevPane: HTMLElement = resourcesPane;

    for (const tab of tabNames) {
      const trigger = screen.getByRole("tab", { name: tab.name });
      await user.click(trigger);

      await waitFor(() => {
        expect(trigger).toHaveAttribute("aria-selected", "true");
        expect(trigger).toHaveAttribute("data-state", "active");
      });

      // Verify the new pane is visible (Radix switches content pane visibility)
      const newPane = screen.getByTestId(tab.pane);
      await waitFor(() => {
        expect(newPane).toBeVisible();
      });

      // Verify the previous pane is hidden (Radix hides inactive content)
      await waitFor(() => {
        expect(prevPane).not.toBeVisible();
      });

      prevPane = newPane;
    }
  }, 15000);
});
