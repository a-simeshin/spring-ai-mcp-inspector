import { render, waitFor } from "@testing-library/react";
import App from "../App";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";

// Mock auth dependencies
jest.mock("@modelcontextprotocol/sdk/client/auth.js", () => ({
  auth: jest.fn(),
}));

jest.mock("../lib/oauth-state-machine", () => ({
  OAuthStateMachine: jest.fn(),
}));

jest.mock("../lib/auth", () => ({
  InspectorOAuthClientProvider: jest.fn().mockImplementation(() => ({
    tokens: jest.fn().mockResolvedValue({ access_token: "mock-token" }),
    redirectUrl: "http://localhost:3000/oauth/callback",
  })),
  DebugInspectorOAuthClientProvider: jest.fn(),
  clearClientInformationFromSessionStorage: jest.fn(),
  saveClientInformationToSessionStorage: jest.fn(),
  saveScopeToSessionStorage: jest.fn(),
  clearScopeFromSessionStorage: jest.fn(),
  discoverScopes: jest.fn(),
  toAbsoluteServerUrl: jest.fn((url: string) => url),
}));

// Mock auth-profiles for D9B exchange
jest.mock("../lib/auth-profiles", () => ({
  exchangeAuthCode: jest.fn(),
  getPendingAuthCodeFlow: jest.fn(),
  clearPendingAuthCodeFlow: jest.fn(),
  migrateLegacyAuthStorage: jest.fn(() => ({
    bearerToken: "",
    headerName: "",
    oauthScope: "",
    oauthClientSecret: "",
    customHeaders: [],
  })),
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
  getInitialSseUrl: jest.fn(() => "http://localhost:6277/sse"),
  getInitialCommand: jest.fn(() => ""),
  getInitialArgs: jest.fn(() => ""),
  initializeInspectorConfig: jest.fn(() => DEFAULT_INSPECTOR_CONFIG),
  saveInspectorConfig: jest.fn(),
}));

// Mock other dependencies
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

// Mock fetch
global.fetch = jest.fn().mockResolvedValue({
  json: () => Promise.resolve({ status: "ok" }),
});

// Mock useConnection with a spy on connectMcpServer
const mockConnectMcpServer = jest.fn();
const mockDisconnectMcpServer = jest.fn();

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
    connect: mockConnectMcpServer,
    disconnect: mockDisconnectMcpServer,
    serverImplementation: null,
  }),
}));

// Mock OAuth utils
jest.mock("../utils/oauthUtils.ts", () => ({
  parseOAuthCallbackParams: jest.fn(),
  generateOAuthErrorDescription: jest.fn((p: unknown) => String(p)),
}));

const mockedExchangeAuthCode = jest.requireMock("../lib/auth-profiles").exchangeAuthCode as jest.Mock;
const mockedGetPendingAuthCodeFlow = jest.requireMock("../lib/auth-profiles").getPendingAuthCodeFlow as jest.Mock;
const mockParseParams = jest.requireMock("../utils/oauthUtils.ts").parseOAuthCallbackParams as jest.Mock;

describe("App - OAuth callback wire (D9B): profileId reaches connectMcpServer on first call", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockConnectMcpServer.mockClear();
    mockDisconnectMcpServer.mockClear();

    // Default OAuth callback params - successful auth-code profile exchange
    window.history.replaceState(
      {},
      document.title,
      "/oauth/callback?code=auth-code&state=state-123",
    );

    mockedGetPendingAuthCodeFlow.mockReturnValue({
      profileId: "pid-stale-from-previous-session",
      state: "state-123",
      codeVerifier: "verifier-123",
      redirectUri: "http://localhost/oauth/callback",
    });

    mockedExchangeAuthCode.mockResolvedValue({ profileId: "pid-fresh-from-oauth-exchange" });

    mockParseParams.mockReturnValue({
      successful: true,
      code: "auth-code",
    });
  });

  test("connectMcpServer is called with the fresh profileId from onProfileAuthorized (D9B callback wire)", async () => {
    // This test proves the end-to-end wire:
    // App.tsx onProfileAuthorized -> connectMcpServer(undefined, 0, profileId)
    // If the profileId argument is missing, this test FAILS.

    // Render App at the OAuth callback route
    const { unmount } = render(<App />);

    // Wait for the OAuth callback to process and trigger connectMcpServer
    await waitFor(() => {
      expect(mockedExchangeAuthCode).toHaveBeenCalledWith(
        expect.any(Object),
        "pid-stale-from-previous-session",
        "auth-code",
        "verifier-123",
        "state-123",
      );
    });

    // The onProfileAuthorized callback should have been called with the fresh profileId
    // and connectMcpServer should have been called with that profileId as the 3rd argument
    await waitFor(() => {
      expect(mockConnectMcpServer).toHaveBeenCalled();
    });

    // Verify the call signature: connectMcpServer(undefined, 0, profileId)
    // The third argument must be the FRESH profileId from the OAuth exchange, NOT the stale one
    expect(mockConnectMcpServer).toHaveBeenCalledWith(
      undefined,
      0,
      "pid-fresh-from-oauth-exchange",
    );

    // And NOT the stale profileId
    expect(mockConnectMcpServer).not.toHaveBeenCalledWith(
      undefined,
      0,
      "pid-stale-from-previous-session",
    );

    unmount();
  });

  test("fails if connectMcpServer is called without profileId (regression guard for D9B)", async () => {
    // This test would FAIL if App.tsx:1343 was changed to:
    // void connectMcpServer(undefined, 0); // missing profileId

    const { unmount } = render(<App />);

    await waitFor(() => {
      expect(mockedExchangeAuthCode).toHaveBeenCalled();
    });

    await waitFor(() => {
      expect(mockConnectMcpServer).toHaveBeenCalled();
    });

    // The call MUST include the profileId as the third argument
    const calls = mockConnectMcpServer.mock.calls;
    expect(calls.length).toBeGreaterThan(0);

    const lastCall = calls[calls.length - 1];
    expect(lastCall).toHaveLength(3);
    expect(lastCall[2]).toBe("pid-fresh-from-oauth-exchange");

    unmount();
  });
});