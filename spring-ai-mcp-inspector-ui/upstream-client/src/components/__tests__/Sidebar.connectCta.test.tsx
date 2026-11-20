import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, beforeEach, jest } from "@jest/globals";
// [spring-ai-mcp-inspector PATCH] Issue #269: contract test for the connect
// CTA region of the sidebar (NOTICE.d/connect-cta.txt).
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

describe("Sidebar connect CTA region (#269)", () => {
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

  const renderSidebar = (
    overrides: Partial<Parameters<typeof Sidebar>[0]> = {},
  ) =>
    render(
      <TooltipProvider>
        <Sidebar {...defaultProps} {...overrides} />
      </TooltipProvider>,
    );

  beforeEach(() => {
    jest.clearAllMocks();
    jest.clearAllTimers();
  });

  it("renders the pristine Connect button in a fixed region outside the sidebar scroll area", () => {
    renderSidebar();

    const connectButton = screen.getByTestId("sidebar-connect-button");
    expect(connectButton).toHaveTextContent("Connect");

    // The CTA region must be a flex sibling of the scroll area rather
    // than a sticky overlay: a sticky bar pinned over the scroll area
    // intercepted clicks on sections scrolled underneath it, while a
    // sibling region keeps the button visible at any expansion and
    // never covers content. The contract keys on the dedicated region
    // testid, not on a Tailwind class list.
    const ctaRegion = connectButton.closest(
      '[data-testid="sidebar-connect-region"]',
    );
    expect(ctaRegion).not.toBeNull();
    expect(connectButton.closest(".overflow-auto")).toBeNull();
  });

  it("keeps the status line and the server URL input in the scroll area, outside the fixed region", () => {
    renderSidebar({ transportType: "sse" as const });

    // A failed connect renders a tall error banner next to the status
    // line; if any of that content lived in the fixed region it would
    // squeeze the scroll area instead of extending it. The geometry is
    // asserted in a real browser by InspectorUiIT#ConnectCtaArea.
    const urlInput = screen.getByTestId("sse-url-input");
    expect(
      urlInput.closest('[data-testid="sidebar-connect-region"]'),
    ).toBeNull();
    expect(urlInput.closest(".overflow-auto")).not.toBeNull();

    const statusLine = screen.getByText("Disconnected");
    expect(
      statusLine.closest('[data-testid="sidebar-connect-region"]'),
    ).toBeNull();
    expect(statusLine.closest(".overflow-auto")).not.toBeNull();
  });
});
