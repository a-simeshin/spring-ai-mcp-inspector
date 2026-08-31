// jsdom lacks MediaQueryList; useTheme calls window.matchMedia on mount.
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import Sidebar from "../Sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import type { ConnectFailure } from "@/lib/connectErrors";
import { LoggingLevel } from "@modelcontextprotocol/sdk/types.js";

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

const baseProps = {
  connectionStatus: "disconnected" as const,
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
};

describe("Sidebar connect-failure alert", () => {
  type SidebarTestProps = Partial<Omit<typeof baseProps, "connectionError" | "connectionStatus">> & {
    connectionError?: ConnectFailure | null;
    connectionStatus?: string;
  };
  const renderSidebar = (props: SidebarTestProps = {}) =>
    render(
      <TooltipProvider>
        <Sidebar {...baseProps} {...props} />
      </TooltipProvider>,
    );

  it("renders nothing when there is no connection error", () => {
    renderSidebar();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("renders a role=alert with the failure reason and message", () => {
    renderSidebar({
      connectionError: {
        code: "MCP_CONNECT_FAILED",
        reason: "connection_refused",
        message: "Connection refused: connect ECONNREFUSED 127.0.0.1:9999",
        retryable: true,
      },
    });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Connection refused");
    expect(alert).toHaveTextContent(
      "Connection refused: connect ECONNREFUSED 127.0.0.1:9999",
    );
  });

  it("renders a DNS reason for dns failures", () => {
    renderSidebar({
      connectionError: {
        code: "MCP_CONNECT_FAILED",
        reason: "dns",
        message: "Unknown host: nowhere.example",
        retryable: true,
      },
    });

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Cannot resolve host",
    );
  });

  it("re-triggers connect when Retry is clicked", () => {
    const onConnect = jest.fn();
    renderSidebar({
      onConnect,
      connectionError: {
        code: "MCP_CONNECT_FAILED",
        reason: "timeout",
        message: "Connection timed out after 5000ms",
        retryable: true,
      },
    });

    fireEvent.click(screen.getByTestId("retry-connect-button"));
    expect(onConnect).toHaveBeenCalledTimes(1);
  });

  it("renders a dedicated amber unauthorized banner for 401", () => {
    renderSidebar({
      connectionError: {
        code: "MCP_CONNECT_FAILED",
        reason: "unauthorized",
        message: "Server rejected token: invalid",
        retryable: true,
      },
    });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Authentication Required");
    expect(alert).toHaveTextContent("X-MCP-Inspector-Auth");
    expect(alert).toHaveTextContent("server log or configuration");
    expect(alert).toHaveTextContent("invalid");
    // Still has a Retry button
    expect(screen.getByTestId("retry-connect-button")).toBeInTheDocument();
  });

  describe("sidebar status text (connectionStatus === error)", () => {
    const statusTextProps = { connectionStatus: "error" as const };

    it('shows "proxy token is correct" for unauthorized', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "unauthorized",
          message: "Server rejected token",
          retryable: true,
        },
      });
      expect(screen.getByText(/proxy token is correct/i)).toBeInTheDocument();
    });

    it('does NOT mention token for connection_refused', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "connection_refused",
          message: "Connection refused",
          retryable: true,
        },
      });
      expect(screen.getByText(/URL is reachable/i)).toBeInTheDocument();
      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
    });

    it('does NOT mention token for dns', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "dns",
          message: "Unknown host",
          retryable: true,
        },
      });
      expect(screen.getByText(/URL is reachable/i)).toBeInTheDocument();
      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
    });

    it('does NOT mention token for timeout', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "timeout",
          message: "Timed out",
          retryable: true,
        },
      });
      expect(screen.getByText(/not responding/i)).toBeInTheDocument();
      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
    });

    it('does NOT mention token for unknown', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "unknown",
          message: "Something went wrong",
          retryable: true,
        },
      });
      expect(screen.getByText(/MCP server is running/i)).toBeInTheDocument();
      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
    });
  });
});