import { render, screen, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, beforeEach, jest } from "@jest/globals";
import Sidebar from "../Sidebar";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import { TooltipProvider } from "@/components/ui/tooltip";

// Mock theme hook
jest.mock("../../lib/hooks/useTheme", () => ({
  __esModule: true,
  default: () => ["light", jest.fn()],
}));

// Mock toast hook
const mockToast = jest.fn();
jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({
    toast: mockToast,
  }),
}));

// Mock navigator clipboard
const mockClipboardWrite = jest.fn(() => Promise.resolve());
Object.defineProperty(navigator, "clipboard", {
  value: {
    writeText: mockClipboardWrite,
  },
});

describe("Sidebar Keep-Alive Indicator", () => {
  const defaultProps = {
    connectionStatus: "connected" as const,
    transportType: "stdio" as const,
    setTransportType: jest.fn(),
    command: "",
    setCommand: jest.fn(),
    args: "",
    setArgs: jest.fn(),
    sseUrl: "",
    setSseUrl: jest.fn(),
    oauthClientId: "",
    setOauthClientId: jest.fn(),
    oauthClientSecret: "",
    setOauthClientSecret: jest.fn(),
    oauthScope: "",
    setOauthScope: jest.fn(),
    env: {},
    setEnv: jest.fn(),
    customHeaders: [],
    setCustomHeaders: jest.fn(),
    onConnect: jest.fn(),
    onDisconnect: jest.fn(),
    stdErrNotifications: [],
    clearStdErrNotifications: jest.fn(),
    logLevel: "info" as const,
    sendLogLevelRequest: jest.fn(),
    loggingSupported: true,
    config: DEFAULT_INSPECTOR_CONFIG,
    setConfig: jest.fn(),
    connectionType: "proxy" as const,
    setConnectionType: jest.fn(),
  };

  const renderSidebar = (props = {}) => {
    return render(
      <TooltipProvider>
        <Sidebar {...defaultProps} {...props} />
      </TooltipProvider>,
    );
  };

  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("shows 'no keep-alive observed' when observed=false", () => {
    renderSidebar({ keepAliveObserved: false });
    expect(screen.getByTestId("keep-alive-indicator")).toHaveTextContent(
      "no keep-alive observed",
    );
  });

  it("shows ticking counter when lastPingAt is set", () => {
    const now = new Date();
    const fiveSecondsAgo = new Date(now.getTime() - 5000).toISOString();
    renderSidebar({
      keepAliveObserved: true,
      keepAliveLastPingAt: fiveSecondsAgo,
    });
    expect(screen.getByTestId("keep-alive-indicator")).toHaveTextContent(
      /Last keep-alive: \ds ago/,
    );
  });

  it("shows stale banner when isStale=true", () => {
    const now = new Date();
    const fiveSecondsAgo = new Date(now.getTime() - 5000).toISOString();
    renderSidebar({
      keepAliveObserved: true,
      keepAliveLastPingAt: fiveSecondsAgo,
      keepAliveIsStale: true,
    });
    expect(screen.getByTestId("keep-alive-stale-banner")).toBeInTheDocument();
    expect(screen.getByTestId("keep-alive-stale-banner")).toHaveTextContent(
      /Connection may be dead/,
    );
  });

  it("hides stale banner when isStale=false", () => {
    const now = new Date();
    const fiveSecondsAgo = new Date(now.getTime() - 5000).toISOString();
    renderSidebar({
      keepAliveObserved: true,
      keepAliveLastPingAt: fiveSecondsAgo,
      keepAliveIsStale: false,
    });
    expect(
      screen.queryByTestId("keep-alive-stale-banner"),
    ).not.toBeInTheDocument();
  });

  it("updates counter every second", () => {
    const now = new Date();
    const fiveSecondsAgo = new Date(now.getTime() - 5000).toISOString();
    renderSidebar({
      keepAliveObserved: true,
      keepAliveLastPingAt: fiveSecondsAgo,
    });

    // Initial render should show some seconds
    const indicator = screen.getByTestId("keep-alive-indicator");
    expect(indicator).toHaveTextContent(/Last keep-alive: \d+s ago/);

    // Advance time by 1 second
    act(() => {
      jest.advanceTimersByTime(1000);
    });

    // Counter should have updated
    expect(indicator).toHaveTextContent(/Last keep-alive: \d+s ago/);
  });

  it("does not show stale banner when not connected", () => {
    renderSidebar({
      connectionStatus: "disconnected",
      keepAliveObserved: true,
      keepAliveLastPingAt: new Date().toISOString(),
      keepAliveIsStale: true,
    });
    expect(
      screen.queryByTestId("keep-alive-indicator"),
    ).not.toBeInTheDocument();
  });

  it("does not show stale banner when interval cannot be estimated", () => {
    // When observed=true but isStale=false (backend returns false when <2 pings)
    renderSidebar({
      keepAliveObserved: true,
      keepAliveLastPingAt: new Date().toISOString(),
      keepAliveIsStale: false,
    });
    expect(
      screen.queryByTestId("keep-alive-stale-banner"),
    ).not.toBeInTheDocument();
  });
});
