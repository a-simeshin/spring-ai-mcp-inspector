// [spring-ai-mcp-inspector PATCH] Transport scoping regression tests (#218).
import { render, waitFor, act } from "@testing-library/react";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import { appendHistory, clearHistory, loadHistory } from "../lib/persistentHistory";
import { auth } from "@modelcontextprotocol/sdk/client/auth.js";
import { SESSION_KEYS } from "../lib/constants";

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

// Mock Sidebar with switcher/connect/disconnect/url controls so the tests
// can drive transport switching and the connect lifecycle.
jest.mock("../components/Sidebar", () => ({
  __esModule: true,
  default: (props: {
    transportType?: string;
    setTransportType?: (t: "stdio" | "sse" | "streamable-http") => void;
    setSseUrl?: (u: string) => void;
    onConnect?: () => void;
    onDisconnect?: () => void;
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
      <button
        data-testid="mock-connect"
        onClick={() => props.onConnect?.()}
      >
        Connect
      </button>
      <button
        data-testid="mock-disconnect"
        onClick={() => props.onDisconnect?.()}
      >
        Disconnect
      </button>
      <button
        data-testid="mock-edit-url"
        onClick={() => props.setSseUrl?.("http://localhost:3001/half-typed")}
      >
        Edit URL
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

// ---------------------------------------------------------------------------
// Bucket key is captured at connect() time (PR #218 review blocker 2).
// While a session is open, editing the URL/transport fields must NOT change
// the connectionId passed to useConnection: the History panel must not
// reshuffle mid-session, later calls must not land in a half-typed bucket,
// and Clear must clear the bucket the session is actually writing to.
// RED on the pre-rework App.tsx: the key was recomputed from the editable
// form fields on every render.
// ---------------------------------------------------------------------------

describe("App - history bucket key frozen while connected", () => {
  const mockUseConnection = jest.mocked(useConnection);

  const connectedState = {
    ...disconnectedConnectionState,
    connectionStatus: "connected" as const,
  };

  beforeEach(() => {
    jest.restoreAllMocks();
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    localStorage.clear();
  });

  test("editing the URL while connected keeps the connect-time connectionId", async () => {
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    // Connect: App captures the bucket key for this session.
    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    await waitFor(() => {
      const last =
        mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1];
      expect(last[0].connectionStatus ?? connectedState.connectionStatus).toBe(
        "connected",
      );
    });
    const keyAtConnect =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    expect(keyAtConnect).toBe("sse:http://localhost:3001/sse");

    // The URL input stays enabled while connected: typing a new URL must
    // NOT move the history bucket.
    await act(async () => {
      getByTestId("mock-edit-url").click();
    });
    await act(async () => {
      getByTestId("mock-edit-url").click();
    });
    const keyAfterEdit =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    expect(keyAfterEdit).toBe(keyAtConnect);
    expect(keyAfterEdit).not.toBe("sse:http://localhost:3001/half-typed");
  });

  test("switching transport while connected keeps the connect-time connectionId", async () => {
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    const keyAtConnect =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    expect(keyAtConnect).toBe("sse:http://localhost:3001/sse");

    await act(async () => {
      getByTestId("switch-to-stdio").click();
    });
    const keyAfterSwitch =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    // Frozen: the stdio recomputation must not win while a session id is held.
    expect(keyAfterSwitch).toBe(keyAtConnect);
    expect(keyAfterSwitch).not.toBe("stdio:mcp-server-everything --verbose");
  });

  test("disconnect releases the captured id and the key follows the form again", async () => {
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    const keyAtConnect =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    expect(keyAtConnect).toBe("sse:http://localhost:3001/sse");

    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    await act(async () => {
      getByTestId("mock-disconnect").click();
    });
    await waitFor(() => {
      const keyAfterDisconnect =
        mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
          .connectionId;
      expect(keyAfterDisconnect).toBe("sse:http://localhost:3001/sse");
    });

    // After disconnect the key follows the form fields again (e.g. a URL
    // edit changes the pending bucket key for the NEXT connection).
    await act(async () => {
      getByTestId("mock-edit-url").click();
    });
    const keyAfterEdit =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    expect(keyAfterEdit).toBe("sse:http://localhost:3001/half-typed");
  });

  test("Clear writes through the connect-time bucket, not the edited URL bucket", async () => {
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    // Pre-seed both buckets: the connect-time one and the half-typed one.
    appendHistory("sse:http://localhost:3001/sse", {
      request: JSON.stringify({ method: "tools/call", id: 1 }),
      at: 1,
    });
    appendHistory("sse:http://localhost:3001/half-typed", {
      request: JSON.stringify({ method: "tools/call", id: 2 }),
      at: 2,
    });

    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    // Edit the URL mid-session: Clear must still clear the CONNECT bucket.
    await act(async () => {
      getByTestId("mock-edit-url").click();
    });

    // The hook's clearRequestHistory closure received the SAME frozen
    // connectionId as every other call in this session: the option the App
    // passed at the last render (after the URL edit) is still the
    // connect-time bucket. Clearing through it clears the connect bucket
    // and leaves the half-typed bucket untouched.
    const lastOptions =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0];
    expect(lastOptions.connectionId).toBe("sse:http://localhost:3001/sse");
    clearHistory(lastOptions.connectionId!);
    expect(loadHistory("sse:http://localhost:3001/sse")).toEqual([]);
    expect(loadHistory("sse:http://localhost:3001/half-typed")).toHaveLength(
      1,
    );
  });
});


// ---------------------------------------------------------------------------
// OAuth callback captures a transport-correct bucket key (PR #218 review,
// round 2 finding). The SSE transport also carries an authProvider, so the
// OAuth redirect flow is reachable with transportType === "sse": the captured
// history key must derive from the live transport, not hardcode
// "streamable-http:". Otherwise the just-authenticated SSE session writes into
// a wrong-transport bucket, and after Disconnect+Reconnect (key re-derived as
// sse:<url>) the OAuth session's entries vanish from the History panel.
// RED on a hardcoded streamable-http: prefix.
// ---------------------------------------------------------------------------

describe("App - OAuth callback captures a transport-correct bucket key", () => {
  const mockUseConnection = jest.mocked(useConnection);

  beforeEach(() => {
    jest.restoreAllMocks();
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    sessionStorage.clear();
    localStorage.clear();
  });

  afterEach(() => {
    // OAuthCallback itself replaceState("/") after processing; restore the
    // root path for any later describe in this file.
    window.history.replaceState({}, document.title, "/");
  });

  test("OAuth callback on the sse transport captures sse:<url>, not streamable-http:<url>", async () => {
    // The OAuth redirect returns to /oauth/callback?code=... with the server
    // URL stashed in sessionStorage by InspectorOAuthClientProvider. Use an
    // OAuth URL DIFFERENT from the form's initial URL so the captured key is
    // distinguishable from the pre-callback form-derived key.
    const oauthServerUrl = "http://localhost:3001/oauth-mcp-sse";
    const formUrl = "http://localhost:3001/sse";
    sessionStorage.setItem(SESSION_KEYS.SERVER_URL, oauthServerUrl);
    // jsdom cannot navigate (location.replace is not implemented); pushState
    // updates location.pathname/search in place, which is all App reads.
    window.history.pushState({}, "", "/oauth/callback?code=x");
    // getInitialTransportType mock (this file) restores "sse".
    const mockAuth = auth as unknown as jest.Mock;
    mockAuth.mockResolvedValue("AUTHORIZED");

    render(<App />);

    // Pre-callback key follows the form fields: sse:<formUrl>.
    const preCallbackKey =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    expect(preCallbackKey).toBe(`sse:${formUrl}`);

    // OAuthCallback (lazy) resolves, auth() returns AUTHORIZED, onConnect
    // fires (connect invoked) and the captured key lands on a re-render
    // (connect is called synchronously before the state flush, so wait for
    // the connectionId to move off the pre-callback form key).
    await waitFor(
      () => {
        expect(disconnectedConnectionState.connect).toHaveBeenCalled();
        const last =
          mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1];
        expect(last[0].connectionId).not.toBe(preCallbackKey);
      },
      { timeout: 5000 },
    );
    // The captured key must follow the live transport ("sse") and the OAuth
    // server URL, not the hardcoded "streamable-http:" prefix.
    const capturedKey =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    expect(capturedKey).toBe(`sse:${oauthServerUrl}`);
    expect(
      mockUseConnection.mock.calls.some(
        (call) => call[0].connectionId === `streamable-http:${oauthServerUrl}`,
      ),
    ).toBe(false);
  });
});
