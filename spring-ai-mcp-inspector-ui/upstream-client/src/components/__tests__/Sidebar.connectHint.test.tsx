// [spring-ai-mcp-inspector PATCH] Transport-switch hint tests (issue #250):
// strong hint renders the verbatim S1 copy, the refs links and the
// Switch-to-SSE button; weak hint renders the S2 paragraph only; no hint
// keeps the legacy alert untouched.
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import Sidebar from "../Sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import type { ConnectFailure } from "@/lib/connectionErrors";
import { LoggingLevel } from "@modelcontextprotocol/sdk/types.js";
import type { SavedConnection } from "@/lib/types/savedConnection";

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
  savedConnections: [],
  activeConnectionId: undefined,
  onSaveConnection: jest.fn() as (name: string) => SavedConnection,
  onDeleteConnection: jest.fn(),
  onSelectConnection: jest.fn(),
};

const strongHintFailure: ConnectFailure = {
  code: "MCP_CONNECT_FAILED",
  reason: "not_found",
  message: "404 from upstream",
  retryable: true,
  hint: {
    kind: "sse_only",
    probePath: "/sse",
    refs: { upstream: "spring-projects/spring-ai#7207", local: "#250" },
  },
};

const weakHintFailure: ConnectFailure = {
  code: "MCP_CONNECT_FAILED",
  reason: "not_found",
  message: "404 from upstream",
  retryable: true,
  hint: {
    kind: "maybe_sse",
    probePath: "/sse",
    refs: { upstream: "spring-projects/spring-ai#7207", local: "#250" },
  },
};

describe("Sidebar transport-switch hint", () => {
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

  it("renders the strong hint with the S1 copy, refs and switch button", () => {
    renderSidebar({ connectionError: strongHintFailure, onSwitchToSse: jest.fn() });

    const strong = screen.getByTestId("connect-hint-strong");
    expect(strong).toHaveTextContent(
      "This server appears to serve the legacy SSE transport only.",
    );
    expect(strong).toHaveTextContent("spring.ai.mcp.server.protocol=STREAMABLE");
    const upstreamRef = screen.getByRole("link", {
      name: "spring-projects/spring-ai#7207",
    });
    expect(upstreamRef).toHaveAttribute(
      "href",
      "https://github.com/spring-projects/spring-ai/issues/7207",
    );
    const localRef = screen.getByRole("link", { name: "#250" });
    expect(localRef).toHaveAttribute(
      "href",
      "https://github.com/a-simeshin/spring-ai-mcp-inspector/issues/250",
    );
    expect(
      screen.getByTestId("switch-to-sse-button"),
    ).toBeInTheDocument();
    // The classified failure still shows: the hint only augments it.
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("404 from upstream");
  });

  it("calls onSwitchToSse when the Switch to SSE button is clicked", () => {
    const onSwitchToSse = jest.fn();
    renderSidebar({ connectionError: strongHintFailure, onSwitchToSse });

    fireEvent.click(screen.getByTestId("switch-to-sse-button"));
    expect(onSwitchToSse).toHaveBeenCalledTimes(1);
    // The switch button must not double as a plain retry.
    expect(baseProps.onConnect).not.toHaveBeenCalled();
  });

  it("renders the weak hint paragraph without links or a switch button", () => {
    renderSidebar({ connectionError: weakHintFailure });

    const weak = screen.getByTestId("connect-hint-weak");
    expect(weak).toHaveTextContent(
      "its SSE transport may be active instead",
    );
    expect(weak).toHaveTextContent("(#250).");
    expect(
      screen.queryByTestId("switch-to-sse-button"),
    ).not.toBeInTheDocument();
    // No refs line inside the weak hint block (the docs link lives in the
    // sidebar header and is unrelated).
    expect(weak.querySelector("a")).toBeNull();
    expect(screen.getByTestId("retry-connect-button")).toBeInTheDocument();
  });

  it("renders no hint markup when the failure carries no hint", () => {
    renderSidebar({
      connectionError: {
        code: "MCP_CONNECT_FAILED",
        reason: "connection_refused",
        message: "refused",
        retryable: true,
        hint: null,
      },
    });

    expect(screen.queryByTestId("connect-hint-strong")).not.toBeInTheDocument();
    expect(screen.queryByTestId("connect-hint-weak")).not.toBeInTheDocument();
    expect(screen.getByTestId("retry-connect-button")).toBeInTheDocument();
  });
});
