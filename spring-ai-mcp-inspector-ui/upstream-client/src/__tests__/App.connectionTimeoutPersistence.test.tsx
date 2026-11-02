// [spring-ai-mcp-inspector PATCH] App-level tests for connection timeout
// localStorage persistence (lastConnectionTimeout): seeding on mount and
// writing on change. See NOTICE.d/connection-timeout.txt for details.

import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { InspectorConfig } from "../lib/configurationTypes";
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

// Mock other dependencies
jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: () => ({
    connectionStatus: "disconnected",
    serverCapabilities: null,
    mcpClient: null,
    requestHistory: [],
    clearRequestHistory: jest.fn(),
    makeRequest: jest.fn(),
    sendNotification: jest.fn(),
    handleCompletion: jest.fn(),
    completionsSupported: false,
    connect: jest.fn(),
    disconnect: jest.fn(),
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

describe("App connection timeout persistence", () => {
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

  it("seeds the connection timeout field from localStorage on mount", async () => {
    localStorage.setItem("lastConnectionTimeout", "42");
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    const input = screen.getByLabelText("Connection timeout (s)");
    expect(input).toHaveValue(42);
  });

  it("persists connection timeout changes to localStorage", async () => {
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    const input = screen.getByLabelText("Connection timeout (s)");
    fireEvent.change(input, { target: { value: "120" } });
    await waitFor(() => {
      expect(localStorage.getItem("lastConnectionTimeout")).toBe("120");
    });
  });
});
