// [spring-ai-mcp-inspector PATCH] Unit tests for one-click reconnect UI.
// See NOTICE.d/one-click-reconnect.txt for details.

import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import Sidebar from "../Sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import { LoggingLevel } from "@modelcontextprotocol/sdk/types.js";
import type { SavedConnection } from "@/lib/types/savedConnection";
import type { ConnectionStatus } from "@/lib/constants";

jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}));

beforeAll(() => {
  window.matchMedia =
    window.matchMedia ||
    ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }));
});

const baseProps: Omit<React.ComponentProps<typeof Sidebar>, "children"> = {
  connectionStatus: "disconnected" as ConnectionStatus,
  connectionError: null,
  transportType: "streamable-http" as const,
  setTransportType: jest.fn(),
  command: "",
  setCommand: jest.fn(),
  args: "",
  setArgs: jest.fn(),
  sseUrl: "http://localhost:9999/mcp",
  setSseUrl: jest.fn(),
  env: {},
  setEnv: jest.fn(),
  customHeaders: [],
  setCustomHeaders: jest.fn(),
  oauthClientId: "",
  setOauthClientId: jest.fn(),
  oauthClientSecret: "",
  setOauthClientSecret: jest.fn(),
  oauthScope: "",
  setOauthScope: jest.fn(),
  onConnect: jest.fn(),
  onDisconnect: jest.fn(),
  logLevel: "debug" as LoggingLevel,
  sendLogLevelRequest: jest.fn(),
  loggingSupported: false,
  config: DEFAULT_INSPECTOR_CONFIG,
  setConfig: jest.fn(),
  connectionType: "proxy" as const,
  setConnectionType: jest.fn(),
  serverImplementation: null,
  savedConnections: [] as SavedConnection[],
  activeConnectionId: undefined,
  onSaveConnection: jest.fn() as (name: string) => SavedConnection,
  onDeleteConnection: jest.fn(),
  onSelectConnection: jest.fn(),
  setAutoReconnect: jest.fn(),
};

describe("Sidebar reconnect banner", () => {
  const renderSidebar = (props: Partial<typeof baseProps> = {}) =>
    render(
      <TooltipProvider>
        <Sidebar {...baseProps} {...props} />
      </TooltipProvider>,
    );

  it("does not render the reconnect banner when disconnected (user-initiated)", () => {
    renderSidebar({ connectionStatus: "disconnected" });
    expect(screen.queryByTestId("reconnect-button")).not.toBeInTheDocument();
    expect(screen.queryByText("Server disconnected")).not.toBeInTheDocument();
  });

  it("renders the reconnect banner when disconnected-remote", () => {
    renderSidebar({ connectionStatus: "disconnected-remote" });
    expect(screen.getByTestId("reconnect-button")).toBeInTheDocument();
    expect(screen.getByText("Server disconnected")).toBeInTheDocument();
    expect(
      screen.getByText(/The MCP server closed the connection/),
    ).toBeInTheDocument();
  });

  it("calls onConnect when Reconnect button is clicked", () => {
    const onConnect = jest.fn();
    renderSidebar({
      connectionStatus: "disconnected-remote",
      onConnect,
    });
    fireEvent.click(screen.getByTestId("reconnect-button"));
    expect(onConnect).toHaveBeenCalledTimes(1);
  });

  it("calls setAutoReconnect when auto-reconnect toggle is checked", () => {
    const setAutoReconnect = jest.fn();
    renderSidebar({
      connectionStatus: "disconnected-remote",
      setAutoReconnect,
    });
    const checkbox = screen.getByLabelText("Reconnect automatically");
    expect(checkbox).toBeInTheDocument();
    expect(checkbox).not.toBeChecked();
    fireEvent.click(checkbox);
    expect(setAutoReconnect).toHaveBeenCalledWith(true);
    expect(checkbox).toBeChecked();
  });

  it("shows the status indicator as amber with 'Server Disconnected' text", () => {
    renderSidebar({ connectionStatus: "disconnected-remote" });
    expect(screen.getByText("Server Disconnected")).toBeInTheDocument();
  });
});