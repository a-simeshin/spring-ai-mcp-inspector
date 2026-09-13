import { render, waitFor, act } from "@testing-library/react";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";

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
  getMCPProxyAuthToken: jest.fn(() => ({
    token: "",
    header: "X-MCP-Proxy-Auth",
  })),
  getInitialTransportType: jest.fn(() => "sse"),
  getInitialSseUrl: jest.fn(() => "http://localhost:3001/sse"),
  getInitialCommand: jest.fn(() => "mcp-server-everything"),
  getInitialArgs: jest.fn(() => "--verbose"),
  initializeInspectorConfig: jest.fn(() => ({})),
  saveInspectorConfig: jest.fn(),
}));

// Default connection state is disconnected
const disconnectedConnectionState = {
  connectionStatus: "disconnected" as const,
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
  serverImplementation: null,
};

// Mock required dependencies, but unrelated to transport switching.
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

// Mock Sidebar with a switcher button so the test can switch transport.
jest.mock("../components/Sidebar", () => ({
  __esModule: true,
  default: (props: {
    transportType?: string;
    setTransportType?: (t: "stdio" | "sse" | "streamable-http") => void;
  }) => (
    <div>
      Sidebar
      <button
        data-testid="switch-to-stdio"
        onClick={() => props.setTransportType?.("stdio")}
      >
        STDIO
      </button>
      <button
        data-testid="switch-to-streamable-http"
        onClick={() => props.setTransportType?.("streamable-http")}
      >
        Streamable HTTP
      </button>
    </div>
  ),
}));

// Mock fetch
global.fetch = jest.fn().mockResolvedValue({ json: () => Promise.resolve({}) });

// Use an empty module mock, so that mock state can be reset between tests.
jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: jest.fn(),
}));

describe("App - transport scoping connectionId", () => {
  const mockUseConnection = jest.mocked(useConnection);

  beforeEach(() => {
    jest.restoreAllMocks();

    // Inspector starts disconnected.
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
  });

  test("App passes transport-scoped connectionId to useConnection", async () => {
    const fixedUrl = "http://localhost:3001/sse";
    const stdioCommand = "mcp-server-everything";
    const stdioArgs = "--verbose";

    const { getByTestId } = render(<App />);

    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    // First render: transportType is "sse" (from getInitialTransportType mock).
    // App.tsx:439-443 must produce `sse:${url}`.
    const sseCall = mockUseConnection.mock.calls[0][0];
    expect(sseCall.connectionId).toBe(`sse:${fixedUrl}`);
    expect(sseCall.connectionId).not.toBe(fixedUrl);
    expect(sseCall.connectionId).not.toBe("ephemeral");

    // Switch transport to "stdio". On the FIXED code this produces
    // `stdio:${command} ${args}`. On the OLD (buggy) code it would still be
    // `${fixedUrl}` because the old mapping ignored transportType.
    await act(async () => {
      getByTestId("switch-to-stdio").click();
    });

    // Wait for re-render with the new transportType.
    await waitFor(() => {
      const calls = mockUseConnection.mock.calls;
      const lastCall = calls[calls.length - 1][0];
      expect(lastCall.connectionId).toBe(
        `stdio:${stdioCommand} ${stdioArgs}`,
      );
    });

    // The stdio connectionId must NOT equal the URL-only identity.
    const lastCall = mockUseConnection.mock.calls[
      mockUseConnection.mock.calls.length - 1
    ][0];
    expect(lastCall.connectionId).not.toBe(fixedUrl);
    expect(lastCall.connectionId).not.toBe(`stdio:${fixedUrl}`);
  });

  test("App passes different connectionIds for streamable-http vs sse with same URL", async () => {
    const fixedUrl = "http://localhost:3001/sse";

    const { getByTestId } = render(<App />);

    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    // First render: sse
    const sseCall = mockUseConnection.mock.calls[0][0];
    expect(sseCall.connectionId).toBe(`sse:${fixedUrl}`);

    // Switch transport to streamable-http
    await act(async () => {
      getByTestId("switch-to-streamable-http").click();
    });

    await waitFor(() => {
      const calls = mockUseConnection.mock.calls;
      const lastCall = calls[calls.length - 1][0];
      expect(lastCall.connectionId).toBe(`streamable-http:${fixedUrl}`);
    });

    // streamable-http connectionId must differ from sse connectionId
    const lastCall = mockUseConnection.mock.calls[
      mockUseConnection.mock.calls.length - 1
    ][0];
    expect(lastCall.connectionId).not.toBe(`sse:${fixedUrl}`);
    expect(lastCall.connectionId).not.toBe(fixedUrl);
  });
});
