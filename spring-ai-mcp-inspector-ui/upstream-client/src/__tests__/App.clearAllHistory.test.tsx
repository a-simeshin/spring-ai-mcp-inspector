// [spring-ai-mcp-inspector PATCH] App-level integration test for Clear All
// history across all connections. Renders the real App with the production
// callback, seeds localStorage with multiple buckets, and asserts the key
// is removed after clicking Clear All.  See NOTICE.d/persistent-history.txt.
//
// Mutation-proof: would fail if the App callback was changed back to
// clearRequestHistory() (which only clears the current bucket) or if the
// Clear All button was disabled when only other buckets exist.

import { render, screen, fireEvent, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { InspectorConfig } from "../lib/configurationTypes";
import { HISTORY_KEY } from "../lib/persistentHistory";
import { TooltipProvider } from "@/components/ui/tooltip";

// jsdom does not implement scrollIntoView; Radix UI Select's auto-scroll
// in the opened content popover crashes without it.
Element.prototype.scrollIntoView = jest.fn();

// Mock auth dependencies
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

// Mock the config utils
jest.mock("../utils/configUtils", () => ({
  ...jest.requireActual("../utils/configUtils"),
  getMCPProxyAddress: jest.fn(() => "http://localhost:6277"),
  getMCPProxyAuthToken: jest.fn((config: InspectorConfig) => ({
    token: config.MCP_PROXY_AUTH_TOKEN.value,
    header: "X-MCP-Proxy-Auth",
  })),
  getInitialTransportType: jest.fn(() => "sse"),
  getInitialSseUrl: jest.fn(() => "http://localhost:3001/sse"),
  getInitialCommand: jest.fn(() => ""),
  getInitialArgs: jest.fn(() => ""),
  initializeInspectorConfig: jest.fn(() => DEFAULT_INSPECTOR_CONFIG),
  saveInspectorConfig: jest.fn(),
}));

// Mock the useConnection hook
jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: () => ({
    connectionStatus: "disconnected",
    connectionError: null,
    serverCapabilities: null,
    serverImplementation: null,
    mcpClient: null,
    requestHistory: [],
    clearRequestHistory: jest.fn(),
    clearAllRequestHistory: jest.fn(),
    makeRequest: jest.fn(),
    cancelTask: jest.fn(),
    listTasks: jest.fn(),
    sendNotification: jest.fn(),
    handleCompletion: jest.fn(),
    completionsSupported: false,
    connect: jest.fn(),
    disconnect: jest.fn(),
    setAutoReconnect: jest.fn(),
  }),
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

// Mock theme hook
jest.mock("../lib/hooks/useTheme", () => ({
  __esModule: true,
  default: () => ["light", jest.fn()],
}));

// Mock fetch
global.fetch = jest.fn();

describe("App Clear All history integration", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    (global.fetch as jest.Mock).mockResolvedValue({
      json: () =>
        Promise.resolve({
          defaultEnvironment: { TEST_ENV: "test" },
          defaultCommand: "test-command",
          defaultArgs: "test-args",
        }),
    });
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  it("clears all history across all connections via Clear All button, using production App callback", () => {
    // Seed several history buckets in localStorage, with the current bucket
    // empty and another bucket non-empty.
    const store = {
      schemaVersion: 1,
      byConnection: {
        "conn-other": [
          {
            request: JSON.stringify({ method: "other/method", params: {} }),
            response: JSON.stringify({ result: "ok" }),
            at: 100,
          },
        ],
        "conn-another": [
          {
            request: JSON.stringify({ method: "another/method", params: {} }),
            response: JSON.stringify({ result: "done" }),
            at: 200,
          },
        ],
      },
    };
    localStorage.setItem(HISTORY_KEY, JSON.stringify(store));

    // Mock window.confirm to return true
    const originalConfirm = window.confirm;
    window.confirm = jest.fn(() => true) as unknown as typeof window.confirm;

    // Render the real App, which uses the production Clear All callback
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    // Find Clear All button in the History section
    const historyHeader = screen.getByText("History");
    const historyHeaderContainer = historyHeader.parentElement as HTMLElement;
    const clearAllButton = within(historyHeaderContainer).getByRole("button", {
      name: "Clear All",
    });

    // Clear All should be enabled because other buckets exist in the store
    expect(clearAllButton).not.toBeDisabled();

    // Click Clear All
    fireEvent.click(clearAllButton);

    // After the full production callback, localStorage key must be null
    expect(localStorage.getItem(HISTORY_KEY)).toBeNull();

    // Rendered React history state is cleared (No history yet shown)
    expect(screen.getByText("No history yet")).toBeTruthy();

    // Cleanup
    window.confirm = originalConfirm;
    localStorage.removeItem(HISTORY_KEY);
  });
});