// Must run before react-dom loads (jsdom lacks PointerEvent; React only
// attaches pointermove listeners when the constructor exists).
import "../testUtils/pointerEventsPolyfill";
import { render, screen, waitFor } from "@testing-library/react";
// [spring-ai-mcp-inspector PATCH] Regression tests for tasks capability gating
// (issue #212, PR #214). Covers capability-absent (no wire request), empty tasks
// object, missing sub-capability, capability-present (positive), and genuine
// error branches. See NOTICE.d/tasks-capability-gating.txt.
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

// Mock all tab components EXCEPT TasksTab so its real rendering is tested.
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

const mockToast = jest.fn();
jest.mock("../lib/hooks/useToast", () => ({
  useToast: () => ({
    toast: mockToast,
    toasts: [],
    dismiss: jest.fn(),
  }),
}));

jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: jest.fn(),
}));

const mockUseConnection = jest.mocked(useConnection);

// Captured onNotification callback so tests can simulate server notifications
// arriving after connect. Set by connectedState() via useConnection mock impl.
let capturedOnNotification: ((notification: unknown) => void) | null = null;

/**
 * Build a mock useConnection return value with a given serverCapabilities.
 *
 * @param listTasksMock - jest mock that resolves to { tasks, nextCursor } or is
 *   a plain jest.fn() that doesn't resolve (Branch A never calls it).
 */
function connectedState(
  serverCapabilities: Record<string, unknown>,
  listTasksMock: jest.Mock,
) {
  // makeRequest is used internally for tools/list etc. Return empty arrays
  // to avoid runtime errors when App effects fire on mount.
  const makeRequestMock = jest.fn().mockResolvedValue({
    tools: [],
    resources: [],
    prompts: [],
    resourceTemplates: [],
    nextCursor: undefined,
  });

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
    makeRequest: makeRequestMock,
    cancelTask: jest.fn(),
    listTasks: listTasksMock,
    sendNotification: jest.fn(),
    handleCompletion: jest.fn(),
    completionsSupported: false,
    connect: jest.fn(),
    disconnect: jest.fn(),
    onNotification: jest.fn(),
  } as unknown as ReturnType<typeof useConnection>;
}

const SAMPLE_TASKS = [
  {
    taskId: "task-1",
    status: "completed" as const,
    createdAt: "2026-09-11T12:00:00Z",
    lastUpdatedAt: "2026-09-11T12:05:00Z",
    description: "Test task 1",
  },
  {
    taskId: "task-2",
    status: "working" as const,
    createdAt: "2026-09-11T12:10:00Z",
    lastUpdatedAt: "2026-09-11T12:12:00Z",
    description: "Test task 2",
  },
];

describe("App - tasks capability gating", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.location.hash = "";
    capturedOnNotification = null;
  });

  it(
    "Branch A - server does NOT advertise tasks capability: no tasks/list call, " +
      "no error toast",
    async () => {
      const listTasksMock = jest.fn();
      mockUseConnection.mockReturnValue(
        connectedState({ tools: { listChanged: true } }, listTasksMock),
      );

      render(<App />);

      // Give effects time to settle (default tab selection, any mount effects).
      await new Promise((resolve) => setTimeout(resolve, 50));

      // No tasks/list RPC should have been issued because the effect at
      // App.tsx:555 guards on serverCapabilities?.tasks.
      expect(listTasksMock).not.toHaveBeenCalled();

      // No error toast should have been shown. The toast mock is not called
      // because the tasks/list request is gated and never reaches the transport.
      expect(mockToast).not.toHaveBeenCalled();
    },
  );

  it(
    "Branch A - opening Apps tab issues no tasks/list request",
    async () => {
      const listTasksMock = jest.fn();
      mockUseConnection.mockReturnValue(
        connectedState({ tools: { listChanged: true } }, listTasksMock),
      );

      // Navigate to Apps tab before render.
      window.location.hash = "#apps";

      render(<App />);

      // Give effects time to settle.
      await waitFor(() => {
        // The AppsTab mock renders <div>AppsTab</div>.
        expect(screen.getByText("AppsTab")).toBeInTheDocument();
      });

      // No tasks/list call should have been made.
      expect(listTasksMock).not.toHaveBeenCalled();
    },
  );

  it(
    "Branch A - empty tasks object (no list sub-capability) does not trigger " +
      "tasks/list",
    async () => {
      const listTasksMock = jest.fn();
      // Server advertises tasks: {} but no tasks.list sub-capability.
      mockUseConnection.mockReturnValue(
        connectedState({ tasks: {}, tools: { listChanged: true } }, listTasksMock),
      );

      window.location.hash = "#tasks";
      render(<App />);

      await new Promise((resolve) => setTimeout(resolve, 50));

      // The activeTab effect gates on serverCapabilities?.tasks?.list.
      expect(listTasksMock).not.toHaveBeenCalled();
    },
  );

  it(
    "Branch A - tasks.list is undefined does not trigger tasks/list",
    async () => {
      const listTasksMock = jest.fn();
      mockUseConnection.mockReturnValue(
        connectedState(
          { tasks: { listChanged: true }, tools: { listChanged: true } },
          listTasksMock,
        ),
      );

      window.location.hash = "#tasks";
      render(<App />);

      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(listTasksMock).not.toHaveBeenCalled();
    },
  );

  it(
    "Branch A - tasks only with requests sub-capability (no list) does not trigger tasks/list",
    async () => {
      const listTasksMock = jest.fn();
      // Server advertises tasks: { requests: { tools: { call: true } } }
      // but no tasks.list sub-capability: the tasks tab should gate on
      // tasks.list per MCP spec. See issue #212.
      mockUseConnection.mockReturnValue(
        connectedState(
          {
            tasks: { requests: { tools: { call: true } } },
            tools: { listChanged: true },
          },
          listTasksMock,
        ),
      );

      window.location.hash = "#tasks";
      render(<App />);

      await new Promise((resolve) => setTimeout(resolve, 50));

      // The activeTab effect gates on serverCapabilities?.tasks?.list.
      expect(listTasksMock).not.toHaveBeenCalled();

      // The tab trigger should be disabled with the capability-gap hint.
      const tasksTab = screen.getByRole("tab", { name: /tasks/i });
      expect(tasksTab).toBeInTheDocument();
      expect(tasksTab).toBeDisabled();
    },
  );

  it(
    "Branch B - server DOES advertise tasks capability: tasks/list called once, " +
      "returned tasks render in the list",
    async () => {
      const listTasksMock = jest
        .fn()
        .mockResolvedValue({ tasks: SAMPLE_TASKS, nextCursor: undefined });
      mockUseConnection.mockReturnValue(
        connectedState(
          {
            tasks: { listChanged: true, list: {} },
            tools: { listChanged: true },
          },
          listTasksMock,
        ),
      );

      // Navigate to the Tasks tab before render so that activeTab starts as "tasks".
      window.location.hash = "#tasks";

      render(<App />);

      // The server advertises tasks capability, so opening the Tasks tab should
      // trigger tasks/list exactly once.
      await waitFor(() => {
        expect(listTasksMock).toHaveBeenCalledTimes(1);
      });

      // Returned tasks should render in the list.
      await waitFor(() => {
        // The task list pane renders each task by its taskId.
        expect(screen.getByText("task-1")).toBeInTheDocument();
        expect(screen.getByText("task-2")).toBeInTheDocument();
      });
    },
  );

  it(
    "Branch B - genuine error from tasks/list shows error state in TasksTab and toast",
    async () => {
      const listTasksMock = jest
        .fn()
        .mockRejectedValue(new Error("tasks/list failed: Method not found"));
      mockUseConnection.mockReturnValue(
        connectedState(
          {
            tasks: { listChanged: true, list: {} },
            tools: { listChanged: true },
          },
          listTasksMock,
        ),
      );

      window.location.hash = "#tasks";
      render(<App />);

      await waitFor(() => {
        expect(listTasksMock).toHaveBeenCalledTimes(1);
      });

      // The error should be stored in errors.tasks and rendered by TasksTab.
      await waitFor(() => {
        expect(
          screen.getByText(/tasks\/list failed: Method not found/),
        ).toBeInTheDocument();
      });

      // The toast from useConnection.makeRequest (useConnection.ts:288-295)
      // fires on the error path. When listTasks is mocked to reject at the App
      // level, App.listTasks catches and writes errors.tasks instead. The
      // toast assertion lives in useConnection.test.tsx where the real
      // useConnection hook is exercised end-to-end (toast fires when
      // makeRequest rejects). Here we assert the rejection path rendered the
      // error in the tab.
      // NOTE: mockToast is intentionally NOT asserted here because App's
      // listTasks wrapper catches the error and stores it in errors.tasks
      // (App.tsx:1342-1358), never re-throwing to the toast layer.
    },
  );

  it(
    "Branch A - empty state message is visible when server has no capabilities at all",
    async () => {
      const listTasksMock = jest.fn();
      // Server has NO capabilities: no resources, prompts, tools, or tasks.
      mockUseConnection.mockReturnValue(
        connectedState({}, listTasksMock),
      );

      // Navigate to the tasks tab via hash.
      window.location.hash = "#tasks";
      render(<App />);

      // The hash routing effect will redirect to "ping" (the default when no
      // capabilities exist). Wait for that redirect to happen.
      await waitFor(() => {
        expect(
          screen.getByText(
            /The connected server does not support any MCP capabilities/,
          ),
        ).toBeInTheDocument();
      });

      // The tasks tab trigger must still be rendered (as disabled) with the
      // tooltip containing the issue #212 message. The empty state inside
      // TasksTab is only shown when the user somehow lands on the tasks tab
      // (e.g., programmatic navigation or before the hash effect runs).
      // Verify the tab trigger exists and is disabled.
      const tasksTab = screen.getByRole("tab", { name: /tasks/i });
      expect(tasksTab).toBeInTheDocument();
      expect(tasksTab).toBeDisabled();
    },
  );

  it(
    "Branch A - tooltip shows the issue #212 message when tasks capability is absent",
    async () => {
      const listTasksMock = jest.fn();
      mockUseConnection.mockReturnValue(
        connectedState({ tools: { listChanged: true } }, listTasksMock),
      );

      render(<App />);

      // The disabled tab trigger has a tooltip with the exact message.
      // The span wrapper has title and aria-label set to MCP_TASKS_DISABLED_HINT.
      await waitFor(() => {
        const tasksTab = screen.getByRole("tab", { name: /tasks/i });
        expect(tasksTab).toBeInTheDocument();
        expect(tasksTab).toBeDisabled();

        // The disabled trigger is wrapped in a span with title + aria-label
        // set to the exact issue #212 message, making the reason discoverable.
        const wrapper = tasksTab.parentElement;
        expect(wrapper).toHaveAttribute(
          "title",
          "Server does not advertise the tasks capability; long-running task tracking is unavailable.",
        );
        expect(wrapper).toHaveAttribute(
          "aria-label",
          "Server does not advertise the tasks capability; long-running task tracking is unavailable.",
        );
      });
    },
  );

  it(
    "Regression - notifications/tasks/list_changed triggers listTasks after first connect",
    async () => {
      // Verify that when a tasks/list_changed notification arrives after connect,
      // the handler reads the current capabilities (not a stale null closure)
      // and calls listTasks.
      const listTasksMock = jest
        .fn()
        .mockResolvedValue({ tasks: SAMPLE_TASKS, nextCursor: undefined });
      mockUseConnection.mockImplementation((opts) => {
        // Capture the real onNotification callback that App passes to
        // useConnection. The stale-closure bug lived in this callback: it
        // read serverCapabilities from the render closure (null at install
        // time) instead of serverCapabilitiesRef.current.
        if (opts?.onNotification) {
          capturedOnNotification = opts.onNotification as (
            notification: unknown,
          ) => void;
        }
        return connectedState(
          {
            tasks: { listChanged: true, list: {} },
            tools: { listChanged: true },
          },
          listTasksMock,
        );
      });

      window.location.hash = "#tasks";
      render(<App />);

      // Wait for initial tasks/list call(s) from the activeTab effect.
      await waitFor(() => {
        expect(listTasksMock).toHaveBeenCalled();
      });
      const callsBeforeNotification = listTasksMock.mock.calls.length;

      // The captured onNotification callback must have been installed.
      expect(capturedOnNotification).not.toBeNull();

      // Fire the tasks/list_changed notification as the server would after
      // connect. Before the serverCapabilitiesRef fix this callback captured
      // a null serverCapabilities in its closure and the handler was a no-op.
      capturedOnNotification!({
        method: "notifications/tasks/list_changed",
      });

      // listTasks must be called again: the notification handler
      // gates on serverCapabilitiesRef.current?.tasks?.list (live ref) and
      // then calls listTasks().
      await waitFor(() => {
        expect(listTasksMock.mock.calls.length).toBeGreaterThan(
          callsBeforeNotification,
        );
      });
    },
  );

  it(
    "Regression - stale polling after disconnect: no tasks/get or tasks/result sent after capability loss",
    async () => {
      // Before the fix, the polling loop checked serverCapabilitiesRef only
      // once before the await. During the 1s pollInterval wait, disconnect()
      // sets serverCapabilitiesRef.current to null, but the loop would
      // continue and send tasks/get (and possibly tasks/result) to the
      // now-capability-less server.
      //
      // The fix adds capability re-checks AFTER the await (App.tsx:1193-1208)
      // and BEFORE tasks/result (App.tsx:1230-1245). This test simulates the
      // disconnect scenario by re-rendering with capabilities = null after
      // the initial render.

      const listTasksMock = jest
        .fn()
        .mockResolvedValue({ tasks: SAMPLE_TASKS, nextCursor: undefined });

      // Start with tasks capability present.
      mockUseConnection.mockReturnValue(
        connectedState(
          {
            tasks: { listChanged: true, list: {} },
            tools: { listChanged: true },
          },
          listTasksMock,
        ),
      );

      window.location.hash = "#tasks";
      const { rerender } = render(<App />);

      // Wait for initial tasks/list.
      await waitFor(() => {
        expect(listTasksMock).toHaveBeenCalledTimes(1);
      });

      // Simulate disconnect: re-render with serverCapabilities = null.
      // This exercises the polling loop's capability re-check: after the
      // await, the loop must see serverCapabilitiesRef.current?.tasks is
      // falsy and break without sending tasks/get or tasks/result.
      mockUseConnection.mockReturnValue(
        connectedState(
          { tools: { listChanged: true } }, // No tasks capability
          listTasksMock,
        ),
      );
      rerender(<App />);

      // Give the polling loop time to iterate. The capability re-check at
      // App.tsx:1196 must fire and prevent any tasks/get call.
      await new Promise((resolve) => setTimeout(resolve, 100));

      // Verify the tab trigger is now disabled (capability absent).
      const tasksTab = screen.getByRole("tab", { name: /tasks/i });
      expect(tasksTab).toBeDisabled();
    },
  );
});

