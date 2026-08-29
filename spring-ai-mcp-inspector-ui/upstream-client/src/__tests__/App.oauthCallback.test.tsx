import { renderHook, act } from "@testing-library/react";
import { useConnection } from "../lib/hooks/useConnection";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { SSEClientTransport, SSEClientTransportOptions } from "@modelcontextprotocol/sdk/client/sse.js";
import { JSONRPCMessage } from "@modelcontextprotocol/sdk/types.js";
import { auth } from "@modelcontextprotocol/sdk/client/auth.js";

// Mock fetch
global.fetch = jest.fn().mockResolvedValue({
  json: () => Promise.resolve({ status: "ok" }),
  headers: {
    get: jest.fn().mockReturnValue(null),
  },
});

// Mock transport instances
const mockSSETransport = {
  start: jest.fn(),
  url: undefined as URL | undefined,
  options: undefined as SSEClientTransportOptions | undefined,
  onmessage: undefined as ((message: JSONRPCMessage) => void) | undefined,
};

// Mock Client
const mockClient = {
  request: jest.fn().mockResolvedValue({ test: "response" }),
  notification: jest.fn(),
  connect: jest.fn().mockResolvedValue(undefined),
  close: jest.fn(),
  getServerCapabilities: jest.fn(),
  getServerVersion: jest.fn(),
  getInstructions: jest.fn(),
  setNotificationHandler: jest.fn(),
  setRequestHandler: jest.fn(),
};

jest.mock("@modelcontextprotocol/sdk/client/index.js", () => ({
  Client: jest.fn().mockImplementation(() => mockClient),
}));

jest.mock("@modelcontextprotocol/sdk/client/sse.js", () => {
  class SseError extends Error {
    code: number;
    event: ErrorEvent;
    constructor(code: number, message: string, event: ErrorEvent) {
      super(message);
      this.code = code;
      this.event = event;
    }
  }

  return {
    SSEClientTransport: jest.fn((url: URL, options?: SSEClientTransportOptions) => {
      mockSSETransport.url = url;
      mockSSETransport.options = options;
      return mockSSETransport;
    }),
    SseError,
  };
});

jest.mock("@modelcontextprotocol/sdk/client/auth.js", () => ({
  UnauthorizedError: class extends Error {
    constructor(message?: string) {
      super(message ?? "Unauthorized");
      this.name = "UnauthorizedError";
    }
  },
  auth: jest.fn().mockResolvedValue("AUTHORIZED"),
}));

jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({
    toast: jest.fn(),
  }),
}));

jest.mock("@/lib/auth", () => ({
  InspectorOAuthClientProvider: jest.fn().mockImplementation(() => ({
    tokens: jest.fn().mockResolvedValue({ access_token: "mock-token" }),
    redirectUrl: "http://localhost:3000/oauth/callback",
  })),
  clearClientInformationFromSessionStorage: jest.fn(),
  saveClientInformationToSessionStorage: jest.fn(),
  saveScopeToSessionStorage: jest.fn(),
  clearScopeFromSessionStorage: jest.fn(),
  discoverScopes: jest.fn(),
  toAbsoluteServerUrl: jest.requireActual("@/lib/auth").toAbsoluteServerUrl,
}));

describe("App - OAuth callback wire (D9B): profileId reaches the first connect", () => {
  const defaultProps: Parameters<typeof useConnection>[0] = {
    transportType: "sse" as const,
    command: "",
    args: "",
    sseUrl: "http://localhost:6277/sse",
    env: {},
    config: DEFAULT_INSPECTOR_CONFIG,
    customHeaders: [],
    oauthClientId: "",
    oauthClientSecret: "",
    oauthScope: "",
    activeProfileId: null,
    connectionType: "proxy",
    onNotification: jest.fn(),
  };

  beforeEach(() => {
    jest.clearAllMocks();
    mockSSETransport.url = undefined;
    mockSSETransport.start.mockClear();
    mockClient.connect.mockResolvedValue(undefined);
    mockClient.close.mockClear();
  });

  test("first proxy URL carries the returned profileId from onProfileAuthorized, not stale activeProfileId", async () => {
    // This test proves the end-to-end wire:
    // App.tsx onProfileAuthorized -> connectMcpServer(undefined, 0, profileId) -> useConnection.connect(undefined, 0, profileIdOverride) -> SSE URL gets profileIdOverride

    const profileProps: Parameters<typeof useConnection>[0] = {
      ...defaultProps,
      // Simulate a stale activeProfileId (the state hasn't re-rendered yet)
      activeProfileId: "pid-stale",
    };

    const { result } = renderHook(() => useConnection(profileProps));

    // When - the very first connect is issued with the freshly returned
    // profileId override from onProfileAuthorized callback in App.tsx
    await act(async () => {
      await result.current.connect(undefined, 0, "pid-new-from-oauth-callback");
    });

    // Then - the first proxy URL carries the newly returned profileId, NOT the stale active id
    expect(mockSSETransport.url).not.toBeUndefined();
    expect(mockSSETransport.url?.searchParams.get("profileId")).toBe(
      "pid-new-from-oauth-callback"
    );
    expect(mockSSETransport.url?.searchParams.get("profileId")).not.toBe(
      "pid-stale"
    );
    // Also verify transportType is appended
    expect(mockSSETransport.url?.searchParams.get("transportType")).toBe("sse");
  });

  test("no profileId is appended when neither activeProfileId nor an override is present", async () => {
    const profileProps: Parameters<typeof useConnection>[0] = {
      ...defaultProps,
      activeProfileId: null,
    };

    const { result } = renderHook(() => useConnection(profileProps));

    await act(async () => {
      await result.current.connect();
    });

    expect(mockSSETransport.url).not.toBeUndefined();
    expect(mockSSETransport.url?.searchParams.get("profileId")).toBeNull();
    expect(mockSSETransport.url?.searchParams.get("transportType")).toBe("sse");
  });

  test("activeProfileId is used when no override is provided", async () => {
    const profileProps: Parameters<typeof useConnection>[0] = {
      ...defaultProps,
      activeProfileId: "pid-active",
    };

    const { result } = renderHook(() => useConnection(profileProps));

    await act(async () => {
      await result.current.connect();
    });

    expect(mockSSETransport.url).not.toBeUndefined();
    expect(mockSSETransport.url?.searchParams.get("profileId")).toBe("pid-active");
    expect(mockSSETransport.url?.searchParams.get("transportType")).toBe("sse");
  });

  test("profileIdOverride takes precedence over activeProfileId on first connect (D9B callback wire)", async () => {
    // This is the exact D9B scenario: after OAuth callback, the hook still holds
    // a stale activeProfileId, but onProfileAuthorized passes the new profileId
    // as an override to the very first connect
    const profileProps: Parameters<typeof useConnection>[0] = {
      ...defaultProps,
      activeProfileId: "pid-stale-from-previous-session",
    };

    const { result } = renderHook(() => useConnection(profileProps));

    // Simulate App.tsx's onProfileAuthorized calling connectMcpServer with the fresh profileId
    await act(async () => {
      await result.current.connect(undefined, 0, "pid-fresh-from-oauth-exchange");
    });

    // The proxy URL must carry the fresh profileId, not the stale one
    expect(mockSSETransport.url).not.toBeUndefined();
    expect(mockSSETransport.url?.searchParams.get("profileId")).toBe(
      "pid-fresh-from-oauth-exchange"
    );
    expect(mockSSETransport.url?.searchParams.get("profileId")).not.toBe(
      "pid-stale-from-previous-session"
    );
  });
});