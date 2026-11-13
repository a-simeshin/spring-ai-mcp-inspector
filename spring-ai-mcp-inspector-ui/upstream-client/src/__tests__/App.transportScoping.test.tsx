// [spring-ai-mcp-inspector PATCH] Transport scoping regression tests (#218).
import { render, waitFor, act } from "@testing-library/react";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import { appendHistory, clearHistory, loadHistory } from "../lib/persistentHistory";
import { auth } from "@modelcontextprotocol/sdk/client/auth.js";
import { SESSION_KEYS } from "../lib/constants";
import { SAVED_CONNECTIONS_KEY } from "../lib/savedConnections";
// D9B profile flow (review blocker round 5): the pending record writer is
// real (sessionStorage), only the server exchange is mocked below.
import {
  exchangeAuthCode,
  savePendingAuthCodeFlow,
} from "../lib/auth-profiles";

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
    savedConnections?: Array<{ id: string; name: string }>;
    activeConnectionId?: string;
    onSaveConnection?: (name: string) => unknown;
    onSelectConnection?: (connection: { id: string; name: string }) => void;
    onDeleteConnection?: (id: string) => void;
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
      <button
        data-testid="mock-save-connection"
        onClick={() => props.onSaveConnection?.("Team Server")}
      >
        Save
      </button>
      <button
        data-testid="mock-select-connection"
        onClick={() =>
          props.onSelectConnection?.(
            (props.savedConnections ?? [])[0] ?? {
              id: "missing",
              name: "Team Server",
            },
          )
        }
      >
        Select
      </button>
      <button
        data-testid="mock-delete-connection"
        onClick={() =>
          props.onDeleteConnection?.(
            props.activeConnectionId ??
              (props.savedConnections ?? [])[0]?.id ??
              "missing",
          )
        }
      >
        Delete
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

// D9B: OAuthCallback exchanges the auth code for a profile id. Keep the
// module's real pending-record storage (sessionStorage) and mock only the
// server exchange, exactly like the review-blocker probe harness.
jest.mock("../lib/auth-profiles", () => ({
  ...jest.requireActual("../lib/auth-profiles"),
  exchangeAuthCode: jest.fn(),
}));

describe("App - transport scoping connectionId", () => {
  const mockUseConnection = jest.mocked(useConnection);

  beforeEach(() => {
    jest.restoreAllMocks();
    // useConnection is a module-scoped mock: mock.calls accumulate across
    // the whole file (the frozen-key describes below rely on that and read
    // only the last call). These tests read calls[0], so they need the
    // history reset to their own first render.
    jest.clearAllMocks();

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

// ---------------------------------------------------------------------------
// Saved connections key the history bucket by the saved-connection id
// (owner decision on PR #218, 2026-10-24: the history layer is keyed on
// activeConnectionId). Two saved connections pointing at the SAME server URL
// must get separate buckets, so editing a saved connection's URL cannot
// orphan its history, and #150 can rebase onto this key. The form-derived
// transport-scoped key remains only for ad-hoc (unsaved) sessions.
// RED on the pre-fix App.tsx: the key was always derived from form fields.
// ---------------------------------------------------------------------------

describe("App - history key follows activeConnectionId for saved connections", () => {
  const mockUseConnection = jest.mocked(useConnection);

  const connectedState = {
    ...disconnectedConnectionState,
    connectionStatus: "connected" as const,
  };

  const seedSavedConnection = (name: string, url: string): string => {
    // Appends to the seeded list (a second call must keep the first entry).
    const raw = localStorage.getItem(SAVED_CONNECTIONS_KEY);
    const existing = raw
      ? (JSON.parse(raw) as {
          schemaVersion: number;
          connections: Array<Record<string, unknown>>;
        })
      : { schemaVersion: 1, connections: [] };
    const nowMs = Date.now();
    const id = `saved-${name.replace(/\s+/g, "-").toLowerCase()}`;
    existing.connections.push({
      id,
      name,
      transport: "sse",
      connectionType: "proxy",
      url,
      customHeaders: [],
      lastUsedAt: nowMs,
      createdAt: nowMs,
    });
    localStorage.setItem(
      SAVED_CONNECTIONS_KEY,
      JSON.stringify(existing),
    );
    return id;
  };

  afterEach(() => {
    // jest.config randomizes test order; these tests leave a seeded saved
    // connection (and, once connected, the persisted association) behind.
    // The transport-scoping describe expects a pristine localStorage and
    // does not clear it, so the seeds must not leak into it.
    localStorage.clear();
  });

  test("after save and disconnect the key follows the saved-connection id, not the URL", async () => {
    localStorage.clear();
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    // Connect ad-hoc, then save under a name: the live session keeps its
    // captured (form-derived) bucket, but once the session is released the
    // pending key must follow the saved entry id, so later URL edits of the
    // saved connection never orphan its history.
    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    // Entries written under the frozen (form-derived) key while the live
    // session runs: the bucket carry-over must move them on disconnect.
    appendHistory("sse:http://localhost:3001/sse", {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 1 }),
      response: JSON.stringify({ jsonrpc: "2.0", id: 1, result: {} }),
      at: 1000,
    });
    appendHistory("sse:http://localhost:3001/sse", {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 2 }),
      response: JSON.stringify({ jsonrpc: "2.0", id: 2, result: {} }),
      at: 2000,
      isError: true,
    });
    await act(async () => {
      getByTestId("mock-save-connection").click();
    });
    const stored = JSON.parse(
      localStorage.getItem(SAVED_CONNECTIONS_KEY) ?? "null",
    ) as { connections: Array<{ id: string; name: string }> } | null;
    expect(stored?.connections).toHaveLength(1);
    const savedId = stored!.connections[0].id;

    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    await act(async () => {
      getByTestId("mock-disconnect").click();
    });
    await waitFor(() => {
      const keyAfterDisconnect =
        mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
          .connectionId;
      expect(keyAfterDisconnect).toBe(savedId);
    });
    expect(
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId,
    ).not.toBe("sse:http://localhost:3001/sse");

    // Owner review 2026-11-13: the carry-over must also move the entries,
    // not just the key. A mid-session Save defers the move to disconnect;
    // after disconnect the frozen bucket is empty and the saved bucket has
    // the entries, including the persisted isError flag.
    expect(loadHistory("sse:http://localhost:3001/sse")).toHaveLength(0);
    const carried = loadHistory(savedId);
    expect(carried).toHaveLength(2);
    expect(carried[1].isError).toBe(true);
  });

  test("saving a disconnected session moves its history bucket immediately", async () => {
    localStorage.clear();
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    // Saving an ad-hoc session WITHOUT ever connecting: no live session was
    // started, so there is no frozen key to defer the move to. The carry-over
    // must happen right here, at save time. The entries below are seeded
    // under the form-derived bucket before the save; a regression (no
    // migration on save) leaves them orphaned there while the History panel
    // moves to the saved entry's empty bucket.
    appendHistory("sse:http://localhost:3001/sse", {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 1 }),
      response: JSON.stringify({ jsonrpc: "2.0", id: 1, result: {} }),
      at: 1000,
    });
    appendHistory("sse:http://localhost:3001/sse", {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 2 }),
      response: JSON.stringify({ jsonrpc: "2.0", id: 2, result: {} }),
      at: 2000,
      isError: true,
    });

    await act(async () => {
      getByTestId("mock-save-connection").click();
    });
    const stored = JSON.parse(
      localStorage.getItem(SAVED_CONNECTIONS_KEY) ?? "null",
    ) as { connections: Array<{ id: string; name: string }> } | null;
    expect(stored?.connections).toHaveLength(1);
    const savedId = stored!.connections[0].id;
    expect(savedId).not.toBe("sse:http://localhost:3001/sse");

    // The History panel follows the saved entry's bucket...
    await waitFor(() => {
      const keyAfterSave =
        mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
          .connectionId;
      expect(keyAfterSave).toBe(savedId);
    });

    // ...and the entries moved into it right away: the form-derived bucket
    // is empty and the saved bucket holds both entries, including the
    // persisted isError flag.
    expect(loadHistory("sse:http://localhost:3001/sse")).toHaveLength(0);
    const moved = loadHistory(savedId);
    expect(moved).toHaveLength(2);
    expect(moved[1].isError).toBe(true);
  });

  test("disconnecting an ad-hoc session does not move its history into a sidebar-selected connection", async () => {
    localStorage.clear();
    const selectedId = seedSavedConnection(
      "Other Server",
      "http://localhost:3999/other",
    );
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    // Entries from earlier ad-hoc sessions to server X live under the
    // form-derived bucket before this session starts.
    appendHistory("sse:http://localhost:3001/sse", {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 1 }),
      response: JSON.stringify({ jsonrpc: "2.0", id: 1, result: {} }),
      at: 1000,
    });

    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    appendHistory("sse:http://localhost:3001/sse", {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 2 }),
      response: JSON.stringify({ jsonrpc: "2.0", id: 2, result: {} }),
      at: 2000,
    });

    // Owner review 2026-11-13: clicking a saved connection while the live
    // session runs re-points the pending association, but the frozen key
    // keeps receiving entries for server X. Disconnect must NOT carry the
    // whole bucket (earlier sessions included) into the selected entry.
    await act(async () => {
      getByTestId("mock-select-connection").click();
    });
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    await act(async () => {
      getByTestId("mock-disconnect").click();
    });

    expect(loadHistory("sse:http://localhost:3001/sse")).toHaveLength(2);
    expect(loadHistory(selectedId)).toHaveLength(0);
  });

  test("a mid-session URL edit followed by Save does not move the live history into the renamed entry", async () => {
    localStorage.clear();
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    appendHistory("sse:http://localhost:3001/sse", {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 1 }),
      response: JSON.stringify({ jsonrpc: "2.0", id: 1, result: {} }),
      at: 1000,
    });
    appendHistory("sse:http://localhost:3001/sse", {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 2 }),
      response: JSON.stringify({ jsonrpc: "2.0", id: 2, result: {} }),
      at: 2000,
      isError: true,
    });

    // Owner review 2026-11-13: editing the URL mid-session and saving under
    // a new name creates an entry pointing at the EDITED server. The live
    // history belongs to the connect-time server; disconnect must not
    // carry it into the renamed entry's bucket.
    await act(async () => {
      getByTestId("mock-edit-url").click();
    });
    await act(async () => {
      getByTestId("mock-save-connection").click();
    });
    const stored = JSON.parse(
      localStorage.getItem(SAVED_CONNECTIONS_KEY) ?? "null",
    ) as { connections: Array<{ id: string; name: string }> } | null;
    expect(stored?.connections).toHaveLength(1);
    const savedId = stored!.connections[0].id;

    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    await act(async () => {
      getByTestId("mock-disconnect").click();
    });

    expect(loadHistory("sse:http://localhost:3001/sse")).toHaveLength(2);
    expect(loadHistory(savedId)).toHaveLength(0);
  });

  test("two saved connections with the same URL get separate history buckets", async () => {
    localStorage.clear();
    const sharedUrl = "http://localhost:3001/sse";
    const firstId = seedSavedConnection("Team Server", sharedUrl);
    const secondId = seedSavedConnection("Team Server Copy", sharedUrl);
    expect(firstId).not.toBe(secondId);

    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    // Select the first saved connection and connect: the bucket key is the
    // saved entry id even though the URL matches the second entry's URL.
    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-select-connection").click();
    });
    await act(async () => {
      getByTestId("mock-connect").click();
    });

    const keyAfterConnect =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    expect(keyAfterConnect).toBe(firstId);
    expect(keyAfterConnect).not.toBe(secondId);
    expect(keyAfterConnect).not.toBe(`sse:${sharedUrl}`);
  });

  test("editing a saved connection's URL keeps its history bucket reachable", async () => {
    localStorage.clear();
    const savedId = seedSavedConnection("Team Server", "http://localhost:3001/sse");

    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-select-connection").click();
    });
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    // Mid-session URL edit must not move the bucket away from the saved id.
    await act(async () => {
      getByTestId("mock-edit-url").click();
    });

    const keyAfterEdit =
      mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
        .connectionId;
    expect(keyAfterEdit).toBe(savedId);
    expect(keyAfterEdit).not.toBe("sse:http://localhost:3001/moved");
  });
});

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

// ---------------------------------------------------------------------------
// D9B profile-authorized connect must capture the history bucket key
// (PR #218 review blocker, round 5). onProfileAuthorized starts the connect
// without going through handleConnect, so the key must be frozen there too.
// RED on dacbc6f: after a profile-authorized connect the key keeps following
// the editable URL field and moves to the half-typed bucket mid-session.
// Harness: real App + real OAuthCallback (lazy), only the auth-code exchange
// is mocked; the Connect-button control lives in the same harness.
// ---------------------------------------------------------------------------

describe("App - profile-authorized OAuth callback (D9B) freezes the bucket key", () => {
  const mockUseConnection = jest.mocked(useConnection);
  const mockExchangeAuthCode = exchangeAuthCode as unknown as jest.Mock;
  const formUrl = "http://localhost:3001/sse";

  beforeEach(() => {
    jest.restoreAllMocks();
    // jest.config randomizes test order, and the module-level useConnection
    // mock accumulates mock.calls across the whole file: the negative control
    // below must only ever see calls made by this describe, so the history
    // is cleared here (implementations are re-established right after).
    mockUseConnection.mockClear();
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    sessionStorage.clear();
    localStorage.clear();
    mockExchangeAuthCode.mockReset();
  });

  afterEach(() => {
    // OAuthCallback itself replaceState("/") after processing; restore the
    // root path for any later describe in this file.
    window.history.replaceState({}, document.title, "/");
  });

  const lastConnectionId = () =>
    mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
      .connectionId;

  test("onProfileAuthorized connect freezes the key: a mid-session URL edit does not move the bucket", async () => {
    const { getByTestId } = render(<App />);
    mockExchangeAuthCode.mockResolvedValue({ profileId: "prof-1" });
    savePendingAuthCodeFlow({
      profileId: "prof-1",
      codeVerifier: "verifier-1",
      state: "state-1",
    });
    // jsdom cannot navigate; pushState updates location.pathname/search,
    // which is all App and OAuthCallback read.
    window.history.pushState({}, "", "/oauth/callback?code=x&state=state-1");

    // The D9B connect bypasses handleConnect: connect(undefined, 0, profileId).
    await waitFor(
      () => {
        expect(disconnectedConnectionState.connect).toHaveBeenCalledWith(
          undefined,
          0,
          "prof-1",
        );
      },
      { timeout: 5000 },
    );

    // The key is captured at connect time as the transport-scoped form key
    // (a profile-authorized session has no saved connection id).
    expect(lastConnectionId()).toBe(`sse:${formUrl}`);

    // Wait for the main layout (OAuthCallback replaceState("/") once the
    // processing settles), then edit the URL field mid-session.
    const editButton = await waitFor(
      () => {
        const el = getByTestId("mock-edit-url");
        expect(el).toBeTruthy();
        return el;
      },
      { timeout: 5000 },
    );
    await act(async () => {
      editButton.click();
    });

    // RED on dacbc6f: the key follows the form field into the half-typed
    // bucket. GREEN after the fix: it stays frozen at the connect value.
    await waitFor(() => {
      expect(lastConnectionId()).toBe(`sse:${formUrl}`);
    });
    expect(
      mockUseConnection.mock.calls.some(
        (call) =>
          call[0].connectionId ===
          "sse:http://localhost:3001/half-typed",
      ),
    ).toBe(false);
  });

  test("control: the same edit after a Connect-button session keeps the key frozen", async () => {
    const { getByTestId } = render(<App />);

    await act(async () => {
      getByTestId("mock-connect").click();
    });
    await waitFor(() => {
      expect(disconnectedConnectionState.connect).toHaveBeenCalled();
    });
    expect(lastConnectionId()).toBe(`sse:${formUrl}`);

    await act(async () => {
      getByTestId("mock-edit-url").click();
    });
    await waitFor(() => {
      expect(lastConnectionId()).toBe(`sse:${formUrl}`);
    });
  });
});

// ---------------------------------------------------------------------------
// Owner review 2026-11-13 (rework on PR #218): reloading the page used to
// drop the saved-connection association, so the history panel fell back to
// the ad-hoc form-derived bucket and the saved connection's history became
// unreachable until re-selection. The association is now persisted under
// lastActiveConnectionId and re-validated against the saved list on load.
// ---------------------------------------------------------------------------

describe("App - activeConnectionId survives reload (owner review 2026-11-13)", () => {
  const mockUseConnection = jest.mocked(useConnection);

  const connectedState = {
    ...disconnectedConnectionState,
    connectionStatus: "connected" as const,
  };

  const seedSavedConnection = (name: string, url: string): string => {
    const raw = localStorage.getItem(SAVED_CONNECTIONS_KEY);
    const existing = raw
      ? (JSON.parse(raw) as {
          schemaVersion: number;
          connections: Array<Record<string, unknown>>;
        })
      : { schemaVersion: 1, connections: [] };
    const nowMs = Date.now();
    const id = `saved-${name.replace(/\s+/g, "-").toLowerCase()}`;
    existing.connections.push({
      id,
      name,
      transport: "sse",
      connectionType: "proxy",
      url,
      customHeaders: [],
      lastUsedAt: nowMs,
      createdAt: nowMs,
    });
    localStorage.setItem(SAVED_CONNECTIONS_KEY, JSON.stringify(existing));
    return id;
  };

  const lastConnectionId = () =>
    mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
      .connectionId;

  afterEach(() => {
    // jest.config randomizes test order; these tests seed the saved
    // connection and the persisted association. The transport-scoping
    // describe expects a pristine localStorage and does not clear it, so
    // the seeds must not leak into it.
    localStorage.clear();
  });

  test("reload restores the saved-connection bucket key without re-selecting", async () => {
    localStorage.clear();
    const savedId = seedSavedConnection(
      "Team Server",
      "http://localhost:3001/sse",
    );
    localStorage.setItem("lastActiveConnectionId", savedId);
    appendHistory(savedId, {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 1 }),
      at: 1000,
    });

    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });
    // A remount equals a reload: the restored association keys the bucket
    // before any interaction.
    expect(lastConnectionId()).toBe(savedId);

    // Connect lands in the saved bucket too.
    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    expect(lastConnectionId()).toBe(savedId);
    expect(lastConnectionId()).not.toBe("sse:http://localhost:3001/sse");
  });

  test("a stale persisted id (deleted elsewhere) falls back to the ad-hoc key", async () => {
    localStorage.clear();
    localStorage.setItem("lastActiveConnectionId", "saved-vanished");

    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });
    expect(lastConnectionId()).toBe("sse:http://localhost:3001/sse");
  });

  test("deleting the active saved connection clears its bucket and the association", async () => {
    localStorage.clear();
    const savedId = seedSavedConnection(
      "Team Server",
      "http://localhost:3001/sse",
    );
    localStorage.setItem("lastActiveConnectionId", savedId);
    appendHistory(savedId, {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 1 }),
      at: 1000,
    });

    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });
    expect(lastConnectionId()).toBe(savedId);

    await act(async () => {
      getByTestId("mock-delete-connection").click();
    });

    // The stored bucket is gone...
    expect(loadHistory(savedId)).toHaveLength(0);
    // ...and the association is cleared: the key falls back to the
    // form-derived ad-hoc bucket.
    await waitFor(() => {
      expect(lastConnectionId()).toBe("sse:http://localhost:3001/sse");
    });
    expect(localStorage.getItem("lastActiveConnectionId")).toBeNull();
  });

  test("selecting a saved connection writes the association for the next mount", async () => {
    localStorage.clear();
    const savedId = seedSavedConnection(
      "Team Server",
      "http://localhost:3001/sse",
    );
    // Owner review 2026-11-13 (rework on PR #218), second blocker: the
    // reload tests above seed lastActiveConnectionId manually, so dropping
    // the persist effect leaves the whole suite green. This test performs
    // a real selection and lets the app write the key itself.
    expect(localStorage.getItem("lastActiveConnectionId")).toBeNull();

    mockUseConnection.mockReturnValue(disconnectedConnectionState);
    const { getByTestId, unmount } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    await act(async () => {
      getByTestId("mock-select-connection").click();
    });

    // The selection persisted the association under the bucket key.
    await waitFor(() => {
      expect(localStorage.getItem("lastActiveConnectionId")).toBe(savedId);
    });

    // A remount (a reload) then restores the saved bucket from that key
    // alone.
    unmount();
    render(<App />);
    await waitFor(() => {
      expect(lastConnectionId()).toBe(savedId);
    });
  });
});

// ---------------------------------------------------------------------------
// Owner review minors on PR #218 (2026-11-13): the saved association was
// sticky across F5 (typing a different URL after selecting A still wrote
// into A's bucket), and deleting the selected connection during its live
// session left the frozen key writing and recreating an orphan bucket for
// the deleted id.
// ---------------------------------------------------------------------------
describe("App - saved association tracks the form endpoint (owner review 2026-11-13)", () => {
  const mockUseConnection = jest.mocked(useConnection);

  const connectedState = {
    ...disconnectedConnectionState,
    connectionStatus: "connected" as const,
  };

  const seedSavedConnection = (name: string, url: string): string => {
    const raw = localStorage.getItem(SAVED_CONNECTIONS_KEY);
    const existing = raw
      ? (JSON.parse(raw) as {
          schemaVersion: number;
          connections: Array<Record<string, unknown>>;
        })
      : { schemaVersion: 1, connections: [] };
    const nowMs = Date.now();
    const id = `saved-${name.replace(/\s+/g, "-").toLowerCase()}`;
    existing.connections.push({
      id,
      name,
      transport: "sse",
      connectionType: "proxy",
      url,
      customHeaders: [],
      lastUsedAt: nowMs,
      createdAt: nowMs,
    });
    localStorage.setItem(SAVED_CONNECTIONS_KEY, JSON.stringify(existing));
    return id;
  };

  const lastConnectionId = () =>
    mockUseConnection.mock.calls[mockUseConnection.mock.calls.length - 1][0]
      .connectionId;

  beforeEach(() => {
    jest.restoreAllMocks();
    jest.clearAllMocks();
    mockUseConnection.mockReturnValue(disconnectedConnectionState);
  });

  afterEach(() => {
    localStorage.clear();
  });

  test("typing a different URL after selecting A does not write into A's bucket", async () => {
    localStorage.clear();
    const savedId = seedSavedConnection(
      "Team Server",
      "http://localhost:3001/sse",
    );

    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    await act(async () => {
      getByTestId("mock-select-connection").click();
    });
    await waitFor(() => {
      expect(lastConnectionId()).toBe(savedId);
    });

    // The form no longer points at the selected entry's endpoint: the
    // pending bucket key must follow the form, not stay sticky on A.
    await act(async () => {
      getByTestId("mock-edit-url").click();
    });
    await waitFor(() => {
      expect(lastConnectionId()).toBe("sse:http://localhost:3001/half-typed");
    });

    // A session started against the edited URL freezes at the ad-hoc key.
    mockUseConnection.mockReturnValue(connectedState);
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    expect(lastConnectionId()).toBe("sse:http://localhost:3001/half-typed");
    expect(lastConnectionId()).not.toBe(savedId);
  });

  test("deleting the selected connection during its live session does not recreate its bucket", async () => {
    localStorage.clear();
    const savedId = seedSavedConnection(
      "Team Server",
      "http://localhost:3001/sse",
    );

    const { getByTestId } = render(<App />);
    await waitFor(() => {
      expect(mockUseConnection).toHaveBeenCalled();
    });

    await act(async () => {
      getByTestId("mock-select-connection").click();
    });
    await act(async () => {
      getByTestId("mock-connect").click();
    });
    expect(lastConnectionId()).toBe(savedId);

    // The live session's frozen bucket key is released together with the
    // association: the deleted id must never receive writes again.
    await act(async () => {
      getByTestId("mock-delete-connection").click();
    });
    expect(loadHistory(savedId)).toHaveLength(0);
    expect(lastConnectionId()).not.toBe(savedId);

    // A later write in the still-live session (the hook appends into the
    // key App handed it) must not resurrect the deleted bucket.
    appendHistory(lastConnectionId(), {
      request: JSON.stringify({ jsonrpc: "2.0", method: "ping", id: 2 }),
      at: 2000,
    });
    expect(loadHistory(savedId)).toHaveLength(0);
  });
});
