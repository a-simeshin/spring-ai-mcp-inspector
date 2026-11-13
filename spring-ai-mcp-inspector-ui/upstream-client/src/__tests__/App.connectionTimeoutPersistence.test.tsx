// [spring-ai-mcp-inspector PATCH] App-level tests for connection timeout
// localStorage persistence (lastConnectionTimeout): seeding on mount and
// writing on change. See NOTICE.d/connection-timeout.txt for details.

import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { InspectorConfig } from "../lib/configurationTypes";
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
  getInitialTransportType: jest.fn(() => "sse"),
  getInitialSseUrl: jest.fn(() => "http://localhost:3001/sse"),
  getInitialCommand: jest.fn(() => ""),
  getInitialArgs: jest.fn(() => ""),
  initializeInspectorConfig: jest.fn(() => DEFAULT_INSPECTOR_CONFIG),
  saveInspectorConfig: jest.fn(),
}));

// Mock other dependencies. The hook object is created once so the tests
// can reach the same jest.fn() instances (for example connect) that the
// component tree actually calls.
jest.mock("../lib/hooks/useConnection", () => {
  const mocks = {
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
  };
  return { useConnection: () => mocks };
});

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

// Mock fetch
global.fetch = jest.fn();

describe("App connection timeout persistence", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    (global.fetch as jest.Mock).mockResolvedValue({
      json: () =>
        Promise.resolve({
          defaultEnvironment: { TEST_ENV: "test" },
          defaultCommand: "test-command",
          defaultArgs: "test-args",
        }),
    });
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  const TIMEOUT_ERROR_TEXT =
    "Must be a whole number of seconds between 1 and 600";

  // [spring-ai-mcp-inspector PATCH] The #269 rework renders a second Connect
  // CTA on the empty canvas (data-testid=empty-state-connect-button); the
  // sidebar CTA is the name-match without that testid.
  const sidebarConnectButton = () =>
    screen
      .getAllByRole("button", { name: /^connect$/i })
      .find(
        (button) =>
          button.getAttribute("data-testid") !== "empty-state-connect-button",
      );

  it("seeds the connection timeout field from localStorage on mount", async () => {
    localStorage.setItem("lastConnectionTimeout", "42");
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    const input = screen.getByLabelText("Connection timeout (s)");
    expect(input).toHaveValue(42);
  });

  it("persists connection timeout changes to localStorage", async () => {
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    const input = screen.getByLabelText("Connection timeout (s)");
    fireEvent.change(input, { target: { value: "120" } });
    await waitFor(() => {
      expect(localStorage.getItem("lastConnectionTimeout")).toBe("120");
    });
  });

  // Regression: a stale invalid persisted timeout used to swallow the
  // Connect click in direct mode, where the field and its error never
  // render, leaving the button enabled but dead.
  it("connects in direct mode with a stale invalid persisted timeout in localStorage", async () => {
    localStorage.setItem("lastConnectionType", "direct");
    localStorage.setItem("lastConnectionTimeout", "700");
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    const connectButton = sidebarConnectButton();
    expect(connectButton).toBeDefined();
    expect(connectButton).toBeEnabled();
    fireEvent.click(connectButton!);
    const connect = (useConnection() as unknown as { connect: jest.Mock })
      .connect;
    expect(connect).toHaveBeenCalledTimes(1);
    expect(
      screen.queryByText(/whole number of seconds/i),
    ).not.toBeInTheDocument();
  });

  // [spring-ai-mcp-inspector PATCH] Owner decision on the #335 rebase
  // (variant a): timeout validation lives in App's shared connect handler,
  // so BOTH Connect CTAs must block on an invalid proxy timeout.
  it("blocks connect from the sidebar CTA with an invalid persisted timeout in proxy mode", async () => {
    localStorage.setItem("lastConnectionType", "proxy");
    localStorage.setItem("lastConnectionTimeout", "700");
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    const sidebarConnect = sidebarConnectButton();
    expect(sidebarConnect).toBeDefined();
    expect(sidebarConnect).toBeEnabled();
    fireEvent.click(sidebarConnect!);
    const connect = (useConnection() as unknown as { connect: jest.Mock })
      .connect;
    expect(connect).not.toHaveBeenCalled();
    expect(screen.getByText(/whole number of seconds/i)).toBeInTheDocument();
  });

  it("blocks connect from the empty-state canvas CTA with an invalid persisted timeout in proxy mode", async () => {
    localStorage.setItem("lastConnectionType", "proxy");
    localStorage.setItem("lastConnectionTimeout", "700");
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    const canvasConnect = screen.getByTestId("empty-state-connect-button");
    expect(canvasConnect).toBeEnabled();
    fireEvent.click(canvasConnect);
    const connect = (useConnection() as unknown as { connect: jest.Mock })
      .connect;
    expect(connect).not.toHaveBeenCalled();
    expect(screen.getByText(/whole number of seconds/i)).toBeInTheDocument();
  });

  it("connects from both CTAs with a valid persisted timeout in proxy mode", async () => {
    localStorage.setItem("lastConnectionType", "proxy");
    localStorage.setItem("lastConnectionTimeout", "45");
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    const connect = (useConnection() as unknown as { connect: jest.Mock })
      .connect;

    const sidebarConnect = sidebarConnectButton();
    expect(sidebarConnect).toBeDefined();
    expect(sidebarConnect).toBeEnabled();
    fireEvent.click(sidebarConnect!);
    expect(connect).toHaveBeenCalledTimes(1);

    const canvasConnect = screen.getByTestId("empty-state-connect-button");
    expect(canvasConnect).toBeEnabled();
    fireEvent.click(canvasConnect);
    expect(connect).toHaveBeenCalledTimes(2);
    expect(
      screen.queryByText(/whole number of seconds/i),
    ).not.toBeInTheDocument();
  });

  // [spring-ai-mcp-inspector PATCH] Value matrix moved here from
  // Sidebar.test.tsx: the timeout validator lives in App since the #335
  // rebase owner decision (variant a), so blur re-validation is observable
  // through the shared handler's state.
  it("rejects non-decimal and out-of-range timeouts on blur", async () => {
    for (const value of [
      "abc",
      "0",
      "1.5",
      "601",
      "1e3",
      "1e2",
      "0x10",
      "1e1",
      "+30",
    ]) {
      localStorage.clear();
      localStorage.setItem("lastConnectionType", "proxy");
      localStorage.setItem("lastConnectionTimeout", value);
      const { unmount } = render(
        <TooltipProvider>
          <App />
        </TooltipProvider>,
      );

      await waitFor(() => {
        expect(global.fetch).toHaveBeenCalled();
      });

      fireEvent.blur(screen.getByLabelText("Connection timeout (s)"));
      expect(screen.getByText(TIMEOUT_ERROR_TEXT)).toBeInTheDocument();
      unmount();
    }
  });

  it("accepts decimal in-range timeouts on blur", async () => {
    for (const value of ["1", "120", "600", "45", ""]) {
      localStorage.clear();
      localStorage.setItem("lastConnectionType", "proxy");
      localStorage.setItem("lastConnectionTimeout", value);
      const { unmount } = render(
        <TooltipProvider>
          <App />
        </TooltipProvider>,
      );

      await waitFor(() => {
        expect(global.fetch).toHaveBeenCalled();
      });

      fireEvent.blur(screen.getByLabelText("Connection timeout (s)"));
      expect(screen.queryByText(TIMEOUT_ERROR_TEXT)).not.toBeInTheDocument();
      unmount();
    }
  });
});
