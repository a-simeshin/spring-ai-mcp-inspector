// jsdom lacks MediaQueryList; useTheme calls window.matchMedia on mount.
import { useState } from "react";
import type { ComponentProps } from "react";
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
  // [spring-ai-mcp-inspector PATCH] Saved connections (#121).
  savedConnections: [],
  activeConnectionId: undefined as string | undefined,
  onSaveConnection: jest.fn(),
  onDeleteConnection: jest.fn(),
  onSelectConnection: jest.fn(),
};

describe("Sidebar URL validation", () => {
  // [spring-ai-mcp-inspector PATCH] issue #269 rework: urlError/urlTouched
  // moved up to App; this harness owns them so these tests keep exercising
  // the same sidebar validation flow through the props.
  const SidebarHarness = ({
    ...props
  }: Partial<ComponentProps<typeof Sidebar>>) => {
    const [urlError, setUrlError] = useState<string | null>(null);
    const [urlTouched, setUrlTouched] = useState(false);
    return (
      <TooltipProvider>
        <Sidebar
          {...baseProps}
          {...props}
          urlError={urlError}
          setUrlError={setUrlError}
          urlTouched={urlTouched}
          setUrlTouched={setUrlTouched}
        />
      </TooltipProvider>
    );
  };

  const renderSidebar = (props: Partial<ComponentProps<typeof Sidebar>> = {}) =>
    render(<SidebarHarness {...props} />);

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

  it("delegates the Connect click to the shared validating onConnect handler", () => {
    const onConnect = jest.fn();
    renderSidebar({
      sseUrl: "example.com/mcp",
      onConnect,
    });

    // Issue #269 rework: click-time URL validation moved into App's shared
    // connect handler so the canvas CTA validates identically; Sidebar
    // forwards the click and App blocks the invalid connect (covered in
    // src/__tests__/App.emptyStateConnect.test.tsx).
    fireEvent.click(getConnectButton());

    expect(onConnect).toHaveBeenCalledTimes(1);
  });

  it("disables Connect button when URL is invalid, enables when valid", () => {
    const { rerender } = render(<SidebarHarness sseUrl="bad-url" />);

    // Blur triggers validation
    fireEvent.blur(screen.getByPlaceholderText("URL"));
    expect(getConnectButton()).toBeDisabled();

    // Rerender with valid URL (harness state persists)
    rerender(<SidebarHarness sseUrl="http://localhost:8080/mcp" />);

    // After blur with valid URL, error clears
    fireEvent.blur(screen.getByPlaceholderText("URL"));
    expect(getConnectButton()).not.toBeDisabled();
  });
});
