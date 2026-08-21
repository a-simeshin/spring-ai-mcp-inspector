// Must run before react-dom loads (jsdom lacks PointerEvent; React only
// attaches pointermove listeners when the constructor exists).
import "../testUtils/pointerEventsPolyfill";
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
  default: () => <div>TasksTab</div>,
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

// The Tasks tab is disabled when the server does not advertise the MCP Tasks
// capability (SEP-1686). In that case the trigger is wrapped in a Tooltip that
// explains why and links to the proposal. When the server DOES advertise it,
// the tab is a plain enabled trigger with no tooltip.
const MCP_TASKS_DOCS_URL = "https://modelcontextprotocol.io/seps/1686-tasks";
const MCP_TASKS_DISABLED_HINT =
  "This server does not support MCP Tasks. See the SEP-1686 proposal.";

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

describe("App - Tasks tab disabled-state tooltip", () => {
  const mockUseConnection = jest.mocked(useConnection);

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("shows a tooltip with reason + SEP-1686 docs link when connected without the tasks capability", async () => {
    mockUseConnection.mockReturnValue(
      connectionState({ tools: { listChanged: true } }),
    );

    render(<App />);

    const tasksTab = screen.getByRole("tab", { name: /^Tasks$/i });
    expect(tasksTab).toBeDisabled();

    // The wrapper span (the disabled trigger's parent) is the accessible
    // container: the disabled trigger itself has pointer-events:none and never
    // receives hover or focus, so the reason must live on the wrapper as a
    // native title and an aria-label.
    const wrapper = tasksTab.parentElement!;
    expect(wrapper).toHaveAttribute("title", MCP_TASKS_DISABLED_HINT);
    expect(wrapper).toHaveAttribute("aria-label", MCP_TASKS_DISABLED_HINT);

    // No tooltip content before hover
    expect(
      screen.queryByText(/does not support MCP Tasks/i),
    ).not.toBeInTheDocument();

    // Hover the (non-interactive) disabled trigger through its wrapper span.
    // Radix Tooltip opens on pointermove (700ms delay), so fire on the
    // TooltipTrigger span.
    fireEvent.pointerMove(wrapper);

    await waitFor(
      () => {
        // Radix renders the content twice: the visible popover plus a
        // visually-hidden accessible copy (role=tooltip), so use queryAllBy.
        expect(
          screen.queryAllByText(/does not support MCP Tasks/i),
        ).not.toHaveLength(0);
      },
      { timeout: 3000 },
    );

    const docsLinks = screen.getAllByRole("link", {
      name: /MCP Tasks documentation/i,
    });
    expect(docsLinks.length).toBeGreaterThan(0);
    for (const link of docsLinks) {
      expect(link).toHaveAttribute("href", MCP_TASKS_DOCS_URL);
    }
  });

  it("exposes the reason to the keyboard: wrapper is focusable and focus opens the tooltip", async () => {
    mockUseConnection.mockReturnValue(
      connectionState({ tools: { listChanged: true } }),
    );

    render(<App />);

    const tasksTab = screen.getByRole("tab", { name: /^Tasks$/i });
    expect(tasksTab).toBeDisabled();

    // The wrapper span is the keyboard entry point: the disabled trigger is
    // not focusable (disabled TabsTrigger), so tabIndex keeps the wrapper in
    // the tab order and Radix Tooltip opens on focus.
    const wrapper = tasksTab.parentElement!;
    expect(wrapper).toHaveAttribute("tabindex", "0");
    expect(wrapper).toHaveAttribute("aria-label", MCP_TASKS_DISABLED_HINT);

    // No tooltip content before focus
    expect(
      screen.queryByText(/does not support MCP Tasks/i),
    ).not.toBeInTheDocument();

    // Focus the wrapper (what happens when the user tabs to it) and verify
    // the explanation becomes available: tooltip content + SEP-1686 link.
    fireEvent.focus(wrapper);

    await waitFor(
      () => {
        expect(
          screen.queryAllByText(/does not support MCP Tasks/i),
        ).not.toHaveLength(0);
      },
      { timeout: 3000 },
    );

    const docsLinks = screen.getAllByRole("link", {
      name: /MCP Tasks documentation/i,
    });
    expect(docsLinks.length).toBeGreaterThan(0);
    for (const link of docsLinks) {
      expect(link).toHaveAttribute("href", MCP_TASKS_DOCS_URL);
    }
  });

  it("keeps the Tasks tab enabled with no tooltip when the tasks capability is advertised", async () => {
    mockUseConnection.mockReturnValue(
      connectionState({ tasks: { listChanged: true } }),
    );

    render(<App />);

    const tasksTab = screen.getByRole("tab", { name: /^Tasks$/i });
    expect(tasksTab).not.toBeDisabled();

    // Tooltip wrapper is only applied in the disabled branch: the trigger is a
    // direct child of the tablist, not wrapped in a TooltipTrigger span.
    expect(tasksTab.parentElement).toHaveAttribute("role", "tablist");

    // No tooltip content, even after hover + the default tooltip delay.
    fireEvent.pointerMove(tasksTab);
    await new Promise((resolve) => setTimeout(resolve, 800));
    await waitFor(() => {
      expect(
        screen.queryByText(/does not support MCP Tasks/i),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("link", { name: /MCP Tasks documentation/i }),
      ).not.toBeInTheDocument();
    });
  });
});
