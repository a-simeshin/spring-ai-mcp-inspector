// [spring-ai-mcp-inspector PATCH] Keep-alive session id wiring regression
// (#239 BLOCK 1): every keep-alive consumer must subscribe with the
// proxy-side session id. The upstream mcpSessionId is unknown to the
// inspector's /api/keepalive and /api/events endpoints (404 in both proxy
// and direct mode), so the App-level wiring is pinned here: reverting
// useKeepAlive(proxySessionId) or the TimelineTab/HistoryAndNotifications
// sessionId props back to mcpSessionId must fail these tests.
import "../testUtils/pointerEventsPolyfill";
import { render, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import { useKeepAlive } from "../lib/hooks/useKeepAlive";
import TimelineTab from "../components/TimelineTab";
import HistoryAndNotifications from "../components/HistoryAndNotifications";
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

jest.mock("../components/ToolsTab", () => ({
  __esModule: true,
  default: () => <div>ToolsTab</div>,
}));

jest.mock("../components/TasksTab", () => ({
  __esModule: true,
  default: () => <div>TasksTab</div>,
}));

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

jest.mock("../components/TimelineTab", () => ({
  __esModule: true,
  default: jest.fn(() => <div>TimelineTab</div>),
}));

jest.mock("../components/HistoryAndNotifications", () => ({
  __esModule: true,
  default: jest.fn(() => <div>HistoryAndNotifications</div>),
}));

jest.mock("../lib/hooks/useKeepAlive", () => ({
  useKeepAlive: jest.fn(() => ({
    observed: false,
    recentPings: [],
    lastPingAt: null,
    isStale: false,
  })),
}));

global.fetch = jest.fn().mockResolvedValue({ json: () => Promise.resolve({}) });

jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: jest.fn(),
}));

// Both ids are set on purpose: the discriminating scenario for the wiring.
// The upstream id belongs to the target server (direct mode capture), the
// proxy id belongs to the inspector-created session the keep-alive
// endpoints actually resolve.
const UPSTREAM_SESSION_ID = "upstream-session-abc";
const PROXY_SESSION_ID = "proxy-session-xyz";

function connectionState() {
  return {
    connectionStatus: "connected" as const,
    serverCapabilities: { tools: { listChanged: true } },
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
    mcpSessionId: UPSTREAM_SESSION_ID,
    proxySessionId: PROXY_SESSION_ID,
  } as ReturnType<typeof useConnection>;
}

describe("App - keep-alive subscribes with the proxy session id (#239)", () => {
  const mockUseConnection = jest.mocked(useConnection);
  const mockUseKeepAlive = jest.mocked(useKeepAlive);
  const mockTimelineTab = jest.mocked(TimelineTab);
  const mockHistory = jest.mocked(HistoryAndNotifications);

  beforeEach(() => {
    jest.clearAllMocks();
    mockUseConnection.mockReturnValue(connectionState());
    window.location.hash = "";
  });

  it("calls useKeepAlive with the proxy session id, never the upstream id", async () => {
    render(<App />);

    await waitFor(() => {
      expect(mockUseKeepAlive.mock.calls.length).toBeGreaterThan(0);
    });

    // Every render must feed the hook the proxy-side id; a single call with
    // the upstream id means /api/keepalive answers 404 again (#239 BLOCK 1).
    for (const call of mockUseKeepAlive.mock.calls) {
      expect(call[0]).toBe(PROXY_SESSION_ID);
    }
    expect(
      mockUseKeepAlive.mock.calls.some(
        (call) => call[0] === UPSTREAM_SESSION_ID,
      ),
    ).toBe(false);
  });

  it("feeds the Timeline tab and History panel the proxy session id", async () => {
    window.location.hash = "#timeline";
    render(<App />);

    await waitFor(() => {
      expect(mockTimelineTab.mock.calls.length).toBeGreaterThan(0);
    });
    for (const call of mockTimelineTab.mock.calls) {
      expect(call[0].sessionId).toBe(PROXY_SESSION_ID);
    }

    await waitFor(() => {
      expect(mockHistory.mock.calls.length).toBeGreaterThan(0);
    });
    for (const call of mockHistory.mock.calls) {
      expect(call[0].sessionId).toBe(PROXY_SESSION_ID);
    }

    expect(
      [...mockTimelineTab.mock.calls, ...mockHistory.mock.calls].some(
        (call) => call[0].sessionId === UPSTREAM_SESSION_ID,
      ),
    ).toBe(false);
  });
});
