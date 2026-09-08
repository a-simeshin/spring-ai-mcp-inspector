// [spring-ai-mcp-inspector PATCH] owner-session isolation and auth-profile tests
// jsdom lacks MediaQueryList; useTheme calls window.matchMedia on mount.
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import Sidebar from "../Sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import type { ConnectFailure, ProxyErrorDto } from "@/lib/connectionErrors";
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
  authError: null,
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
  type SidebarTestProps = Partial<Omit<typeof baseProps, "connectionError">> & {
    connectionError?: ConnectFailure | null;
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
});

// [spring-ai-mcp-inspector PATCH] D3 structured error banner tests (issue #54):
// regression tests for the authError state rendering in the Sidebar.
describe("Sidebar D3 auth error banner", () => {
  const mockDto: ProxyErrorDto = {
    status: 401,
    code: "unauthorized",
    reason: "The MCP server rejected the request as unauthenticated.",
    guidance: "Verify the token/API key.",
    url: "https://server:8443/mcp",
  };

  type SidebarTestProps = Partial<
    Omit<typeof baseProps, "connectionError" | "authError">
  > & {
    connectionError?: ConnectFailure | null;
    authError?: ProxyErrorDto | null;
  };

  const renderSidebar = (props: SidebarTestProps = {}) =>
    render(
      <TooltipProvider>
        <Sidebar {...baseProps} {...props} />
      </TooltipProvider>,
    );

  it("renders the D3 banner when authError is provided", () => {
    renderSidebar({ authError: mockDto });

    const banner = screen.getByTestId("connection-error-dto");
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveTextContent("401 unauthorized");
    expect(banner).toHaveTextContent(
      "The MCP server rejected the request as unauthenticated.",
    );
    expect(banner).toHaveTextContent("Verify the token/API key.");
    expect(banner).toHaveTextContent("https://server:8443/mcp");
  });

  it("does not render the D3 banner when authError is null", () => {
    renderSidebar({ authError: null });

    expect(
      screen.queryByTestId("connection-error-dto"),
    ).not.toBeInTheDocument();
  });

  it("does not render the D3 banner when authError is undefined", () => {
    renderSidebar();

    expect(
      screen.queryByTestId("connection-error-dto"),
    ).not.toBeInTheDocument();
  });

  it("renders a D3 banner without url when the DTO has no url", () => {
    const dtoWithoutUrl: ProxyErrorDto = {
      status: 403,
      code: "forbidden",
      reason: "Access denied",
      guidance: "Check your permissions.",
    };

    renderSidebar({ authError: dtoWithoutUrl });

    const banner = screen.getByTestId("connection-error-dto");
    expect(banner).toHaveTextContent("403 forbidden");
    expect(banner).toHaveTextContent("Access denied");
    expect(banner).toHaveTextContent("Check your permissions.");
    expect(banner).not.toHaveTextContent("URL:");
  });

  it("renders the D3 banner alongside the connection error amber banner when both are present", () => {
    renderSidebar({
      authError: mockDto,
      connectionError: {
        code: "MCP_CONNECT_FAILED",
        reason: "unauthorized",
        message: "Fork server 401",
        retryable: true,
      },
    });

    expect(screen.getByTestId("connection-error-dto")).toBeInTheDocument();
    const alerts = screen.getAllByRole("alert");
    expect(alerts.length).toBe(2);
    expect(alerts[0]).toHaveTextContent("Authentication Required");
  });
});