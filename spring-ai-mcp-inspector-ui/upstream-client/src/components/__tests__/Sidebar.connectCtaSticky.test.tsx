import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, beforeEach, jest } from "@jest/globals";
// [spring-ai-mcp-inspector PATCH] Issue #269: contract test for the sticky
// connect CTA bar of the sidebar scroll area (NOTICE.d/connect-cta-sticky.txt).
import Sidebar from "../Sidebar";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import { TooltipProvider } from "@/components/ui/tooltip";
// [spring-ai-mcp-inspector PATCH] Saved connections (#121).
import type { SavedConnection } from "@/lib/types/savedConnection";

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

jest.useFakeTimers();

describe("Sidebar connect CTA sticky pinning (#269)", () => {
  const defaultProps = {
    connectionStatus: "disconnected" as const,
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
    // [spring-ai-mcp-inspector PATCH] Saved connections (#121).
    savedConnections: [],
    activeConnectionId: undefined,
    onSaveConnection: jest.fn() as (name: string) => SavedConnection,
    onDeleteConnection: jest.fn(),
    onSelectConnection: jest.fn(),
  };

  const renderSidebar = () =>
    render(
      <TooltipProvider>
        <Sidebar {...defaultProps} />
      </TooltipProvider>,
    );

  beforeEach(() => {
    jest.clearAllMocks();
    jest.clearAllTimers();
  });

  it("renders the pristine Connect button inside a sticky bar pinned to the bottom of the sidebar scroll area", () => {
    renderSidebar();

    const connectButton = screen.getByTestId("sidebar-connect-button");
    expect(connectButton).toHaveTextContent("Connect");

    const stickyBar = connectButton.closest(".sticky");
    expect(stickyBar).not.toBeNull();
    expect(stickyBar).toHaveClass("sticky", "bottom-0", "bg-card");
  });
});
