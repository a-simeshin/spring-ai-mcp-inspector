// jsdom lacks MediaQueryList; useTheme calls window.matchMedia on mount.
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import Sidebar from "../Sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
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

describe("Sidebar URL validation", () => {
  const renderSidebar = (props: Partial<typeof baseProps> = {}) =>
    render(
      <TooltipProvider>
        <Sidebar {...baseProps} {...props} />
      </TooltipProvider>,
    );

  const getConnectButton = () =>
    screen.getByRole("button", { name: /^connect$/i });

  it("renders the URL input without error for valid URL", () => {
    renderSidebar({ sseUrl: "http://localhost:8080/mcp" });
    const input = screen.getByPlaceholderText("URL");
    expect(input).not.toHaveClass("border-red-500");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(getConnectButton()).not.toBeDisabled();
  });

  it("shows red border and inline error on invalid URL after blur", () => {
    renderSidebar({ sseUrl: "example.com/mcp" });
    const input = screen.getByPlaceholderText("URL");

    fireEvent.blur(input);

    expect(input).toHaveClass("border-red-500");
    expect(screen.getByRole("alert")).toHaveTextContent("http://");
    expect(getConnectButton()).toBeDisabled();
  });

  it("shows red border and inline error on invalid URL without blur when Connect is clicked", () => {
    const onConnect = jest.fn();
    renderSidebar({
      sseUrl: "example.com/mcp",
      onConnect,
    });

    fireEvent.click(getConnectButton());

    const input = screen.getByPlaceholderText("URL");
    expect(input).toHaveClass("border-red-500");
    expect(screen.getByRole("alert")).toHaveTextContent("http://");
    expect(getConnectButton()).toBeDisabled();
    // Connect was NOT called because URL is invalid
    expect(onConnect).not.toHaveBeenCalled();
  });

  it("disables Connect button when URL is invalid, enables when valid", () => {
    const { rerender } = render(
      <TooltipProvider>
        <Sidebar {...baseProps} sseUrl="bad-url" />
      </TooltipProvider>,
    );

    // Blur triggers validation
    fireEvent.blur(screen.getByPlaceholderText("URL"));
    expect(getConnectButton()).toBeDisabled();

    // Rerender with valid URL
    rerender(
      <TooltipProvider>
        <Sidebar {...baseProps} sseUrl="http://localhost:8080/mcp" />
      </TooltipProvider>,
    );

    // After blur with valid URL, error clears
    fireEvent.blur(screen.getByPlaceholderText("URL"));
    expect(getConnectButton()).not.toBeDisabled();
  });
});
