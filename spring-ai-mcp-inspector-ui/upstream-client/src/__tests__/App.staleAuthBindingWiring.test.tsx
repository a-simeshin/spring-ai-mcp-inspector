// [spring-ai-mcp-inspector PATCH] App-level wiring test for the stale
// auth-profile binding banner (issue #267): mounts the real App with a
// ghost-bound saved connection in localStorage and mocked 404 responses on
// the unconditional mount-time auth-profile list/prefill fetches, asserting
// the full Sidebar wiring path (App stale-binding state -> Sidebar banner ->
// unlink and open-settings handlers). See NOTICE.d/stale-auth-binding-404.txt
// for details.

import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { InspectorConfig } from "../lib/configurationTypes";
import { SAVED_CONNECTIONS_KEY } from "../lib/savedConnections";
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
  // Keep the real helpers (OAuthFlowProgress imports toAbsoluteServerUrl
  // from here; an undefined stub throws when the auth view mounts) and
  // override only what this suite drives.
  ...jest.requireActual("../lib/auth"),
  InspectorOAuthClientProvider: jest.fn().mockImplementation(() => ({
    tokens: jest.fn().mockResolvedValue(null),
    clear: jest.fn(),
  })),
  DebugInspectorOAuthClientProvider: jest.fn().mockImplementation(() => ({
    tokens: jest.fn().mockResolvedValue(null),
    clear: jest.fn(),
  })),
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

// Mock fetch: the mount-time auth-profile list and prefill requests return
// 404 (the issue #267 backend that predates the auth-profile API); any other
// request gets the default config payload.
global.fetch = jest.fn().mockImplementation((input: RequestInfo | URL) => {
  const url = String(input);
  if (url.includes("/auth-profile")) {
    return Promise.resolve({
      ok: false,
      status: 404,
      json: () => Promise.resolve({}),
    });
  }
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () =>
      Promise.resolve({
        defaultEnvironment: { TEST_ENV: "test" },
        defaultCommand: "test-command",
        defaultArgs: "test-args",
      }),
  });
});

const GHOST_ID = "conn-ghost";
const SIBLING_ID = "conn-keep";

const GHOST_ENTRY = {
  id: GHOST_ID,
  name: "Ghost Conn",
  transport: "sse",
  connectionType: "proxy",
  url: "http://localhost:3001/sse",
  createdAt: 1000,
  lastUsedAt: 2000,
  customHeaders: [] as unknown[],
  env: {},
  authProfileName: "vanished-profile",
};

const SIBLING_ENTRY = {
  id: SIBLING_ID,
  name: "Keep Conn",
  transport: "stdio",
  connectionType: "direct",
  command: "uvx",
  args: "mcp-server-everything",
  createdAt: 3000,
  lastUsedAt: 4000,
  customHeaders: [] as unknown[],
};

function seedGhostBinding(): void {
  localStorage.setItem(
    SAVED_CONNECTIONS_KEY,
    JSON.stringify({
      schemaVersion: 1,
      connections: [GHOST_ENTRY, SIBLING_ENTRY],
    }),
  );
}

function renderApp(): void {
  render(
    <TooltipProvider>
      <App />
    </TooltipProvider>,
  );
}

async function waitForStaleBanner(): Promise<HTMLElement> {
  return await waitFor(() =>
    screen.getByTestId(`stale-auth-binding-${GHOST_ID}`),
  );
}

describe("App stale auth binding wiring", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    seedGhostBinding();
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  it("surfaces the stale binding banner after the 404 list load resolves", async () => {
    renderApp();

    const banner = await waitForStaleBanner();

    // The banner names the connection and the missing profile.
    expect(banner).toHaveTextContent("Ghost Conn");
    expect(banner).toHaveTextContent("vanished-profile");
    expect(banner).toHaveTextContent("Unlink profile");
    expect(banner).toHaveTextContent("Open Auth Settings");

    // The valid sibling connection is not reported as stale.
    expect(
      screen.queryByTestId(`stale-auth-binding-${SIBLING_ID}`),
    ).not.toBeInTheDocument();
  });

  it("unlink removes the stored binding and hides the banner", async () => {
    renderApp();
    await waitForStaleBanner();

    fireEvent.click(screen.getByTestId(`stale-auth-binding-unlink-${GHOST_ID}`));

    const stored = JSON.parse(
      localStorage.getItem(SAVED_CONNECTIONS_KEY) as string,
    );
    expect(stored.schemaVersion).toBe(1);
    expect(stored.connections).toHaveLength(2);
    const unlinked = stored.connections.find(
      (entry: { id: string }) => entry.id === GHOST_ID,
    );
    expect("authProfileName" in unlinked).toBe(false);
    // Sibling entries and the schema version are untouched.
    expect(
      stored.connections.find((entry: { id: string }) => entry.id === SIBLING_ID),
    ).toEqual(SIBLING_ENTRY);

    await waitFor(() =>
      expect(
        screen.queryByTestId(`stale-auth-binding-${GHOST_ID}`),
      ).not.toBeInTheDocument(),
    );
  });

  it("open auth settings switches to the auth view", async () => {
    renderApp();
    await waitForStaleBanner();

    fireEvent.click(screen.getByTestId("stale-auth-binding-open-settings"));

    await waitFor(() => {
      expect(screen.getByText("Authentication Settings")).toBeInTheDocument();
    });
  });
});
