// [spring-ai-mcp-inspector PATCH] issue #269 rework, review blocker (second
// return of the same finding): the sidebar Connect CTA must stay wired to
// App's shared validating handler. Every other spec either stubs the Sidebar
// (App.emptyStateConnect.test.tsx), injects its own onConnect mock
// (Sidebar.urlValidation.test.tsx) or only connects with a valid URL, so a
// regression to the raw non-validating handleConnect (which keeps the #218
// bucket freeze but loses click-time URL validation) survives them all. This
// spec clicks through the forwarding mock at the exact wiring App.tsx passes
// to Sidebar: an empty or invalid URL with no prior blur must surface the
// validation alert and must never reach useConnection.connect.
import { render, screen, fireEvent, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { InspectorConfig } from "../lib/configurationTypes";

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

// Overridable initial state: a URL-based transport with an empty or invalid
// server URL, and no prior interaction with the input.
let mockInitialTransportType: "stdio" | "sse" | "streamable-http" =
  "streamable-http";
let mockInitialSseUrl = "";

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

// Forwarding mock (pattern of App.transportScoping.test.tsx): the button
// invokes the onConnect prop exactly as the real sidebar CTA does, and the
// urlError prop is surfaced with role="alert" like the real sidebar error
// text, so the click-time validation outcome is observable in the DOM. The
// error queries are scoped to this container because App renders its own
// role="alert" for the canvas CTA once the lifted urlError is set.
jest.mock("../components/Sidebar", () => ({
  __esModule: true,
  default: (props: {
    onConnect?: () => void;
    urlError?: string | null;
  }) => (
    <div data-testid="mock-sidebar">
      Sidebar
      {props.urlError && <div role="alert">{props.urlError}</div>}
      <button data-testid="mock-connect" onClick={() => props.onConnect?.()}>
        Connect
      </button>
    </div>
  ),
}));

// Mock fetch
global.fetch = jest.fn().mockResolvedValue({ json: () => Promise.resolve({}) });

describe("App - sidebar Connect runs the shared validating handler", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockInitialTransportType = "streamable-http";
    mockInitialSseUrl = "";
  });

  test("empty URL: clicking the sidebar Connect shows the validation error and does not connect", async () => {
    render(<App />);

    // No prior blur: the error is unset before the click, exactly like a
    // fresh page where click-time validation is the only guarantee.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    const sidebar = screen.getByTestId("mock-sidebar");
    fireEvent.click(within(sidebar).getByTestId("mock-connect"));

    expect(await within(sidebar).findByRole("alert")).toHaveTextContent(
      "URL must not be empty",
    );
    expect(mockConnect).not.toHaveBeenCalled();
  });

  test("invalid URL: clicking the sidebar Connect shows the validation error and does not connect", async () => {
    mockInitialSseUrl = "htp://not-a-valid-url";
    render(<App />);

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    const sidebar = screen.getByTestId("mock-sidebar");
    fireEvent.click(within(sidebar).getByTestId("mock-connect"));

    expect(await within(sidebar).findByRole("alert")).toHaveTextContent(
      "URL must start with http://, https://, ws://, wss://, or /",
    );
    expect(mockConnect).not.toHaveBeenCalled();
  });
});
