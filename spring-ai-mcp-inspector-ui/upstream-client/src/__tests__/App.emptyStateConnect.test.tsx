import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { InspectorConfig } from "../lib/configurationTypes";

// [spring-ai-mcp-inspector PATCH] Empty-state Connect CTA (issue #269):
// the landing page must expose the same connect action the sidebar CTA uses.

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

// Mock the config utils
jest.mock("../utils/configUtils", () => ({
  ...jest.requireActual("../utils/configUtils"),
  getMCPProxyAddress: jest.fn(() => "http://localhost:6277"),
  getMCPProxyAuthToken: jest.fn((config: InspectorConfig) => ({
    token: config.MCP_PROXY_AUTH_TOKEN.value,
    header: "X-MCP-Proxy-Auth",
  })),
  getInitialTransportType: jest.fn(() => "stdio"),
  getInitialSseUrl: jest.fn(() => "http://localhost:3001/sse"),
  getInitialCommand: jest.fn(() => "mcp-server-everything"),
  getInitialArgs: jest.fn(() => ""),
  initializeInspectorConfig: jest.fn(() => DEFAULT_INSPECTOR_CONFIG),
  saveInspectorConfig: jest.fn(),
}));

const mockConnect = jest.fn();

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
    connect: mockConnect,
    disconnect: jest.fn(),
    resetSessionAndConnect: jest.fn(),
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

// The sidebar owns the validated primary Connect CTA; stub it so the only
// Connect button in the DOM is the empty-state one under test.
jest.mock("../components/Sidebar", () => ({
  __esModule: true,
  default: () => <div>Sidebar</div>,
}));

// Mock fetch
global.fetch = jest.fn();

describe("App - Empty state Connect button", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (global.fetch as jest.Mock).mockResolvedValue({
      json: () =>
        Promise.resolve({
          defaultEnvironment: { TEST_ENV: "test" },
          defaultCommand: "test-command",
          defaultArgs: "test-args",
        }),
    });
  });

  test("shows a primary Connect button alongside Open Auth Settings in the empty state", async () => {
    render(<App />);

    expect(
      await screen.findByText("Connect to an MCP server to start inspecting"),
    ).toBeVisible();
    const connectButton = screen.getByTestId("empty-state-connect-button");
    expect(connectButton).toBeVisible();
    // Open Auth Settings remains as the secondary action.
    expect(screen.getByText("Open Auth Settings")).toBeVisible();
  });

  test("clicking the empty-state Connect button starts the same connect action as the sidebar", async () => {
    render(<App />);

    const connectButton = await screen.findByTestId(
      "empty-state-connect-button",
    );
    fireEvent.click(connectButton);

    expect(mockConnect).toHaveBeenCalledTimes(1);
    expect(mockConnect).toHaveBeenCalledWith();
  });
});
