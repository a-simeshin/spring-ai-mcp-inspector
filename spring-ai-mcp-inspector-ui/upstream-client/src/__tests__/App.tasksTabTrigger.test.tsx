// [spring-ai-mcp-inspector PATCH] App-level Tasks tab trigger regression tests
// (#182, #212). The capability-gap tooltip wrapper was removed: the Tasks
// trigger is always rendered and enabled, and the Tasks panel itself carries
// the capability-gap body (data-testid=tasks-capability-gap, asserted in
// App.tasksCapabilityGating.test.tsx). These tests pin the structural
// contract: the trigger is a direct child of the tablist (no wrapper span)
// in both capability branches, TasksTab always mounts, and the updateTask
// prop stays wired. See NOTICE.d/tasks-capability-gating.txt.
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
  default: () => <div>Sidebar</div>,
}));

jest.mock("../components/ResourcesTab", () => ({
  __esModule: true,
  default: () => <div>ResourcesTab</div>,
}));

jest.mock("../components/PromptsTab", () => ({
  __esModule: true,
  default: () => <div>PromptsTab</div>,
}));

jest.mock("../components/TasksTab", () => ({
  __esModule: true,
  // Renders the literal text the gate tests assert on, plus a marker for the
  // updateTask callback so the App-level prop wiring is observable here too
  // (owner review follow-up on ea2d2d1: dropping the prop left tests green).
  // The render is gated on activeTab like the real component: a test that
  // "clicks" the Tasks tab only passes when the switch really happened
  // (Radix TabsTrigger activates on mousedown, so tests fire mouseDown).
  default: (props: { activeTab?: string; updateTask?: unknown }) =>
    props.activeTab !== "tasks" ? null : (
      <div>
        <div>TasksTab</div>
        {typeof props.updateTask === "function" && (
          <div data-testid="tasks-tab-update-task-prop" />
        )}
      </div>
    ),
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

jest.mock("../components/ToolsTab", () => ({
  __esModule: true,
  default: () => <div>ToolsTab</div>,
}));

jest.mock("../components/AppsTab", () => ({
  __esModule: true,
  default: () => <div>AppsTab</div>,
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

describe("App - Tasks tab trigger for capability gap", () => {
  const mockUseConnection = jest.mocked(useConnection);

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("renders an always-enabled Tasks trigger with no tooltip wrapper when the tasks capability is absent", async () => {
    mockUseConnection.mockReturnValue(
      connectionState({ tools: { listChanged: true } }),
    );

    render(<App />);

    const tasksTab = screen.getByRole("tab", { name: /^Tasks$/i });
    // The trigger is enabled: clicking opens the Tasks panel, whose
    // capability-gap body explains the missing capability inline.
    expect(tasksTab).toBeInTheDocument();
    expect(tasksTab).not.toBeDisabled();

    // Structural pin: the trigger is a direct child of the tablist. A
    // re-introduced tooltip wrapper span between them fails here.
    expect(tasksTab.parentElement).toHaveAttribute("role", "tablist");

    // The tooltip affordance is gone: the hint text and the SEP-1686 docs
    // link must not come back anywhere in the tab bar. TasksTab is mocked,
    // so the only possible source of the hint text is a tooltip.
    expect(
      screen.queryByText(/Server does not advertise the tasks capability/i),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: /MCP Tasks documentation/i }),
    ).not.toBeInTheDocument();
  });

  it("keeps the Tasks tab a plain enabled trigger when the tasks capability is advertised", async () => {
    mockUseConnection.mockReturnValue(
      connectionState({ tasks: { listChanged: true, list: {} } }),
    );

    render(<App />);

    const tasksTab = screen.getByRole("tab", { name: /^Tasks$/i });
    expect(tasksTab).toBeInTheDocument();
    expect(tasksTab).not.toBeDisabled();
    // Same structural pin in the advertised branch.
    expect(tasksTab.parentElement).toHaveAttribute("role", "tablist");
  });
});

describe("App - Tasks tab capability gate", () => {
  const mockUseConnection = jest.mocked(useConnection);

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("mounts TasksTab even when the server does not advertise the tasks capability", async () => {
    // Regression (owner review on 0a41ca9, mutation a; adapted for issue
    // #212): the Tasks tab always mounts now, so removing the mount again
    // must be caught here. The trigger is enabled without the capability,
    // and clicking it mounts the (mocked) TasksTab that carries the
    // capability-gap body. TasksTab is mocked to a div with the literal
    // text, so mounting is observable without its polling internals.
    mockUseConnection.mockReturnValue(
      connectionState({ tools: { listChanged: true } }),
    );

    render(<App />);

    // Radix TabsTrigger activates on mousedown (left button), not click.
    fireEvent.mouseDown(screen.getByRole("tab", { name: /^Tasks$/i }));

    await waitFor(() => {
      expect(screen.getByText("TasksTab")).toBeInTheDocument();
    });
  });

  it("mounts TasksTab when the server advertises the tasks capability", async () => {
    // resources is included so App renders the real tab content area (it
    // falls back to a "no capabilities" pane without resources/prompts/tools)
    mockUseConnection.mockReturnValue(
      connectionState({
        resources: { listChanged: true },
        tasks: { listChanged: true },
      }),
    );

    render(<App />);

    // TabsContent only renders the active tab, so open the Tasks tab first.
    fireEvent.mouseDown(screen.getByRole("tab", { name: /^Tasks$/i }));

    await waitFor(() => {
      expect(screen.getByText("TasksTab")).toBeInTheDocument();
    });
  });

  it("passes an updateTask callback to TasksTab so poll results reach the tasks list", async () => {
    // Regression (owner review follow-up on ea2d2d1): removing the
    // updateTask prop from App.tsx left the whole suite green because the
    // mock above ignored props. The mock now surfaces the callback, so its
    // absence in App.tsx fails here.
    mockUseConnection.mockReturnValue(
      connectionState({
        resources: { listChanged: true },
        tasks: { listChanged: true },
      }),
    );

    render(<App />);

    fireEvent.mouseDown(screen.getByRole("tab", { name: /^Tasks$/i }));

    await waitFor(() => {
      expect(
        screen.getByTestId("tasks-tab-update-task-prop"),
      ).toBeInTheDocument();
    });
  });
});
