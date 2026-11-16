// [spring-ai-mcp-inspector PATCH] Deterministic repro for issue #374: the
// /config effect overwrote a user-typed sseUrl when its fetch resolved after
// the edit (CI E2E flake: the URL input silently lost the absolute server
// URL and kept the relative default "/sse"). The second test pins the
// intended auto-populate when the user has not typed yet.

import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { TooltipProvider } from "@/components/ui/tooltip";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { InspectorConfig } from "../lib/configurationTypes";

// jsdom does not implement scrollIntoView; Radix UI Select's auto-scroll
// in the opened content popover crashes without it.
Element.prototype.scrollIntoView = jest.fn();

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

jest.mock("../utils/configUtils", () => ({
  ...jest.requireActual("../utils/configUtils"),
  getMCPProxyAddress: jest.fn(() => "http://localhost:6277"),
  getMCPProxyAuthToken: jest.fn((config: InspectorConfig) => ({
    token: config.MCP_PROXY_AUTH_TOKEN.value,
    header: "X-MCP-Proxy-Auth",
  })),
  getInitialTransportType: jest.fn(() => "sse"),
  getInitialSseUrl: jest.fn(() => ""),
  getInitialCommand: jest.fn(() => "npx"),
  getInitialArgs: jest.fn(() => ""),
  initializeInspectorConfig: jest.fn(() => DEFAULT_INSPECTOR_CONFIG),
  saveInspectorConfig: jest.fn(),
}));

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

// Mock fetch; the /config call is deferred and resolved manually per test to
// force the interleaving where the response lands after the user edit.
global.fetch = jest.fn();

const CONFIG_BODY = {
  defaultEnvironment: { TEST_ENV: "test" },
  defaultCommand: "test-command",
  defaultArgs: "test-args",
  defaultTransport: "sse",
  defaultServerUrl: "/sse",
};

function deferConfigFetch(): () => void {
  let resolveConfig!: (value: unknown) => void;
  (global.fetch as jest.Mock).mockImplementation((input: RequestInfo | URL) => {
    if (String(input).includes("/config")) {
      return new Promise((resolve) => {
        resolveConfig = (value: unknown) =>
          resolve({ ok: true, json: () => Promise.resolve(value) });
      });
    }
    return Promise.resolve({ ok: true, json: () => Promise.resolve([]) });
  });
  return () => resolveConfig(CONFIG_BODY);
}

describe("App - config defaults must not clobber user-typed sseUrl", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
  });

  test("user-typed sse URL survives a late /config response", async () => {
    const resolveConfig = deferConfigFetch();

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    // The user types the absolute server URL while /config is still in flight
    // (the exact interleaving the CI E2E hit under load).
    fireEvent.change(screen.getByLabelText("URL"), {
      target: { value: "http://localhost:6274/mcp" },
    });

    // The /config response lands AFTER the user edit.
    resolveConfig();

    // Flush the /config microtask chain (fetch resolve -> response.json ->
    // state updates) BEFORE reading the URL field. A bare waitFor would pass
    // on its first poll, while the typed value is still on screen and the
    // late response has not applied the default yet, so the clobber this
    // test pins would happen after the test stopped looking.
    await act(async () => {
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByLabelText("URL")).toHaveValue(
        "http://localhost:6274/mcp",
      );
    });
  });

  test("config default still populates the URL field when the user has not typed", async () => {
    const resolveConfig = deferConfigFetch();

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    // No user edit: the auto-populated server default must still land.
    resolveConfig();

    await waitFor(() => {
      expect(screen.getByLabelText("URL")).toHaveValue("/sse");
    });
  });
});
