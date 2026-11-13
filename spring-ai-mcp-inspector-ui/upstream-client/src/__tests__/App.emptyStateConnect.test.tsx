import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { InspectorConfig } from "../lib/configurationTypes";

// [spring-ai-mcp-inspector PATCH] Empty-state Connect CTA (issue #269):
// the landing page must expose the sidebar's validated connect flow: one
// handler validates the URL, blocks invalid connects and disables the CTA.

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

// Overridable initial state: the validation specs need a URL-based
// transport with an empty or invalid server URL.
let mockInitialTransportType: "stdio" | "sse" | "streamable-http" = "stdio";
let mockInitialSseUrl = "http://localhost:3001/sse";

// Mock the config utils
jest.mock("../utils/configUtils", () => ({
  ...jest.requireActual("../utils/configUtils"),
  getMCPProxyAddress: jest.fn(() => "http://localhost:6277"),
  getMCPProxyAuthToken: jest.fn((config: InspectorConfig) => ({
    token: config.MCP_PROXY_AUTH_TOKEN.value,
    header: "X-MCP-Proxy-Auth",
  })),
  getInitialTransportType: jest.fn(() => mockInitialTransportType),
  getInitialSseUrl: jest.fn(() => mockInitialSseUrl),
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

// Stub the sidebar (convention of the App specs). This no longer hides the
// connect flow: URL validation lives in App's shared connect handler, so
// the empty-state CTA below is exercised with the real
// utils/urlValidation.ts logic.
jest.mock("../components/Sidebar", () => ({
  __esModule: true,
  default: () => <div>Sidebar</div>,
}));

// Mock fetch
global.fetch = jest.fn();

describe("App - Empty state Connect button", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockInitialTransportType = "stdio";
    mockInitialSseUrl = "http://localhost:3001/sse";
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

  test("clicking the empty-state Connect button uses the same validated connect flow as the sidebar", async () => {
    // A URL-based transport with a valid URL connects.
    mockInitialTransportType = "sse";
    mockInitialSseUrl = "http://localhost:3001/sse";
    render(<App />);

    const connectButton = await screen.findByTestId(
      "empty-state-connect-button",
    );
    fireEvent.click(connectButton);

    expect(mockConnect).toHaveBeenCalledTimes(1);
    expect(mockConnect).toHaveBeenCalledWith();
  });

  test("empty URL: clicking the canvas Connect shows the validation error and does not connect", async () => {
    mockInitialTransportType = "sse";
    mockInitialSseUrl = "";
    render(<App />);

    const connectButton = await screen.findByTestId(
      "empty-state-connect-button",
    );
    // Before the first attempt the button is enabled, same as the sidebar CTA.
    expect(connectButton).toBeEnabled();
    fireEvent.click(connectButton);

    expect(mockConnect).not.toHaveBeenCalled();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("URL must not be empty");
    // Same disable condition as the sidebar CTA once the error is set.
    expect(connectButton).toBeDisabled();
    // The shared state surfaces the error next to the canvas CTA too.
    expect(screen.getByTestId("empty-state-url-error")).toHaveTextContent(
      "URL must not be empty",
    );
  });

  test("invalid URL: clicking the canvas Connect shows the validation error and does not connect", async () => {
    mockInitialTransportType = "sse";
    mockInitialSseUrl = "htp://not-a-valid-url";
    render(<App />);

    const connectButton = await screen.findByTestId(
      "empty-state-connect-button",
    );
    fireEvent.click(connectButton);

    expect(mockConnect).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "URL must start with http://, https://, ws://, wss://, or /",
    );
    expect(connectButton).toBeDisabled();
  });
});
