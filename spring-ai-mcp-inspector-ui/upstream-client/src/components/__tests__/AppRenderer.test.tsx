// [spring-ai-mcp-inspector PATCH] Restored upstream AppRenderer component tests
// (waiting state, ready render, min height, toast, post-init tool input/result,
// null result, wrapped-result normalization) plus a wiring test that drives the
// real handleCallTool through its onCallTool path (owner review findings 3-4).
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach } from "@jest/globals";
import AppRenderer from "../AppRenderer";
import {
  Tool,
  CompatibilityCallToolResult,
  CallToolResult,
} from "@modelcontextprotocol/sdk/types.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { RequestHandlerExtra } from "@mcp-ui/client";
import { McpUiMessageResult } from "@modelcontextprotocol/ext-apps";

type BridgeEvent = {
  type: "sendToolInput" | "sendToolResult";
  toolName: string;
  payload: unknown;
};

type OnCallToolHandler = (
  params: { name: string; arguments?: Record<string, unknown> },
  extra?: RequestHandlerExtra,
) => Promise<CallToolResult>;

type MockMcpUiRendererProps = {
  toolName: string;
  toolInput?: Record<string, unknown>;
  toolResult?: CompatibilityCallToolResult;
  sandbox?: { url: URL; csp?: Record<string, unknown> };
  onMessage?: (
    params: { role: "user"; content: { type: "text"; text: string }[] },
    extra: RequestHandlerExtra,
  ) => Promise<McpUiMessageResult>;
  onCallTool?: OnCallToolHandler;
};

const mockBridgeEvents: BridgeEvent[] = [];

// Sandbox probe: every sandbox prop the real AppRenderer passed down (PR #199
// round 2 BLOCK 2 coverage: csp must come from the UI resource meta).
const mockSandboxProps: MockMcpUiRendererProps["sandbox"][] = [];

// Wiring probe: the latest onCallTool handler the real AppRenderer passed down.
let capturedOnCallTool: OnCallToolHandler | undefined;

// Mock the ext-apps module
jest.mock("@modelcontextprotocol/ext-apps/app-bridge", () => ({
  getToolUiResourceUri: (tool: Tool) => {
    const meta = (tool as Tool & { _meta?: { ui?: { resourceUri?: string } } })
      ._meta;
    return meta?.ui?.resourceUri || null;
  },
}));

// Mock toast hook
const mockToast = jest.fn();
jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({
    toast: mockToast,
  }),
}));

// Mock @mcp-ui/client to simulate bridge delivery only after app initialization
jest.mock("@mcp-ui/client", () => {
  const React = jest.requireActual("react") as typeof import("react");

  return {
    AppRenderer: ({
      toolName,
      toolInput,
      toolResult,
      onMessage,
      onCallTool,
      sandbox,
    }: MockMcpUiRendererProps) => {
      mockSandboxProps.push(sandbox);
      const [isInitialized, setIsInitialized] = React.useState(false);

      capturedOnCallTool = onCallTool;

      React.useEffect(() => {
        setIsInitialized(false);
      }, [toolName]);

      React.useEffect(() => {
        if (isInitialized && toolInput) {
          mockBridgeEvents.push({
            type: "sendToolInput",
            toolName,
            payload: toolInput,
          });
        }
      }, [isInitialized, toolInput, toolName]);

      React.useEffect(() => {
        if (isInitialized && toolResult) {
          mockBridgeEvents.push({
            type: "sendToolResult",
            toolName,
            payload: toolResult,
          });
        }
      }, [isInitialized, toolResult, toolName]);

      return (
        <div data-testid="mcp-ui-app-renderer">
          <div data-testid="tool-name">{toolName}</div>
          <div data-testid="tool-result">
            {JSON.stringify(toolResult ?? null)}
          </div>
          <button
            data-testid="initialize-app"
            onClick={() => setIsInitialized(true)}
          >
            Initialize App
          </button>
          <button
            data-testid="trigger-message"
            onClick={() =>
              onMessage?.(
                {
                  role: "user",
                  content: [{ type: "text", text: "Test message" }],
                },
                {} as RequestHandlerExtra,
              )
            }
          >
            Trigger Message
          </button>
        </div>
      );
    },
  };
});

describe("AppRenderer", () => {
  const mockTool: Tool = {
    name: "testApp",
    description: "Test app with UI",
    inputSchema: {
      type: "object" as const,
      properties: {},
    },
    _meta: {
      ui: {
        resourceUri: "ui://test-app",
      },
    },
  } as Tool & { _meta?: { ui?: { resourceUri?: string } } };

  const requestMock = jest.fn();
  const mockMcpClient = {
    getServerCapabilities: jest.fn().mockReturnValue({}),
    setNotificationHandler: jest.fn(),
    request: requestMock,
    readResource: jest.fn().mockResolvedValue({ contents: [] }),
    listResources: jest.fn().mockResolvedValue({ resources: [] }),
  } as unknown as Client;

  const defaultProps = {
    sandboxPath: "/sandbox",
    tool: mockTool,
    tools: [mockTool] as Tool[],
    mcpClient: mockMcpClient,
    toolResult: null,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    mockBridgeEvents.length = 0;
    mockSandboxProps.length = 0;
    capturedOnCallTool = undefined;
  });

  it("should display waiting state when mcpClient is null", () => {
    render(<AppRenderer {...defaultProps} mcpClient={null} />);
    expect(screen.getByText(/Waiting for MCP client/i)).toBeInTheDocument();
  });

  it("should render McpUiAppRenderer when client is ready", () => {
    render(<AppRenderer {...defaultProps} />);

    expect(screen.getByTestId("mcp-ui-app-renderer")).toBeInTheDocument();
    expect(screen.getByTestId("tool-name")).toHaveTextContent("testApp");
  });

  it("should set minimum height on container", () => {
    render(<AppRenderer {...defaultProps} />);

    const container = screen.getByTestId("mcp-ui-app-renderer").parentElement;
    expect(container).toHaveStyle({ minHeight: "400px" });
  });

  it("should show toast when onMessage is triggered", () => {
    render(<AppRenderer {...defaultProps} />);

    fireEvent.click(screen.getByTestId("trigger-message"));

    expect(mockToast).toHaveBeenCalledWith({
      description: "Test message",
    });
  });

  it("should send provided tool input and tool result after app initialization", async () => {
    const result: CompatibilityCallToolResult = {
      content: [{ type: "text", text: "Budget initialized" }],
    };

    render(
      <AppRenderer
        {...defaultProps}
        toolInput={{ monthlyBudget: 2500 }}
        toolResult={result}
      />,
    );

    expect(mockBridgeEvents).toHaveLength(0);

    fireEvent.click(screen.getByTestId("initialize-app"));

    await waitFor(() => {
      const toolEvents = mockBridgeEvents.filter(
        (event) => event.toolName === "testApp",
      );
      expect(toolEvents.map((event) => event.type)).toEqual([
        "sendToolInput",
        "sendToolResult",
      ]);
    });

    const resultEvent = mockBridgeEvents.find(
      (event) =>
        event.toolName === "testApp" && event.type === "sendToolResult",
    );
    expect(resultEvent?.payload).toEqual(result);
  });

  it("should not send tool result event when toolResult is null", async () => {
    render(
      <AppRenderer
        {...defaultProps}
        toolInput={{ monthlyBudget: 2500 }}
        toolResult={null}
      />,
    );

    fireEvent.click(screen.getByTestId("initialize-app"));

    await waitFor(() => {
      const inputEvents = mockBridgeEvents.filter(
        (event) =>
          event.toolName === "testApp" && event.type === "sendToolInput",
      );
      expect(inputEvents).toHaveLength(1);
    });

    const resultEvents = mockBridgeEvents.filter(
      (event) =>
        event.toolName === "testApp" && event.type === "sendToolResult",
    );
    expect(resultEvents).toHaveLength(0);
  });

  it("should normalize compatibility wrapper tool results before sending", async () => {
    const wrappedResult = {
      toolResult: {
        content: [{ type: "text", text: "Wrapped result payload" }],
      },
    } as CompatibilityCallToolResult;

    render(
      <AppRenderer
        {...defaultProps}
        toolInput={{ monthlyBudget: 2500 }}
        toolResult={wrappedResult}
      />,
    );

    fireEvent.click(screen.getByTestId("initialize-app"));

    await waitFor(() => {
      const resultEvent = mockBridgeEvents.find(
        (event) =>
          event.toolName === "testApp" && event.type === "sendToolResult",
      );
      expect(resultEvent).toBeTruthy();
      expect(resultEvent?.payload).toEqual(wrappedResult.toolResult);
    });
  });

  // [spring-ai-mcp-inspector PATCH] Wiring test (owner review finding 4):
  // drives the REAL handleCallTool (captured from the props the component
  // passes to @mcp-ui/client) and asserts a model-only tool is denied without
  // reaching the MCP client, while an app-visible tool is forwarded.
  it("should deny tools/call for a model-only tool through the wired onCallTool", async () => {
    const modelOnlyTool = {
      name: "modelTool",
      description: "Model-only",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { visibility: ["model"] } },
    } as unknown as Tool;
    const appTool = {
      name: "appTool",
      description: "App-visible",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { visibility: ["app"] } },
    } as unknown as Tool;
    const requestMock = mockMcpClient.request as unknown as jest.Mock;
    requestMock.mockResolvedValue({
      content: [{ type: "text", text: "called" }],
    });

    render(
      <AppRenderer
        {...defaultProps}
        tools={[modelOnlyTool, appTool]}
        tool={appTool}
      />,
    );

    expect(capturedOnCallTool).toBeInstanceOf(Function);

    let denial: CallToolResult | undefined;
    await act(async () => {
      denial = await capturedOnCallTool?.({ name: "modelTool" });
    });

    expect(denial?.isError).toBe(true);
    expect(denial?.content).toEqual([
      { type: "text", text: "Tool 'modelTool' is not available to apps" },
    ]);
    expect(requestMock).not.toHaveBeenCalled();

    let allowed: CallToolResult | undefined;
    await act(async () => {
      allowed = await capturedOnCallTool?.({
        name: "appTool",
        arguments: { city: "NYC" },
      });
    });

    expect(allowed?.isError).toBeUndefined();
    expect(requestMock).toHaveBeenCalledTimes(1);
    expect(requestMock).toHaveBeenCalledWith(
      expect.objectContaining({
        method: "tools/call",
        params: { name: "appTool", arguments: { city: "NYC" } },
      }),
      expect.anything(),
      expect.anything(),
    );
  });
});

describe("AppRenderer sandbox csp from the UI resource meta (PR #199 round 2 BLOCK 2)", () => {
  // Spec-shaped tool meta: McpUiToolMeta carries resourceUri and visibility ONLY
  // (McpUiToolMeta.csp is `never` in @modelcontextprotocol/ext-apps 1.7.2), so an
  // App declaring CSP per the spec puts it on McpUiResourceMeta.csp instead.
  const specTool = {
    name: "weatherApp",
    description: "Weather app with UI",
    inputSchema: { type: "object" as const, properties: {} },
    _meta: {
      ui: {
        resourceUri: "ui://weather/view.html",
        visibility: ["model", "app"],
      },
    },
  } as unknown as Tool;

  // Local client: the outer describe's mockMcpClient is out of scope here, and
  // these tests need their own readResource/listResources stubs anyway.
  const client = {
    getServerCapabilities: jest.fn().mockReturnValue({}),
    setNotificationHandler: jest.fn(),
    request: jest.fn(),
    readResource: jest.fn().mockResolvedValue({ contents: [] }),
    listResources: jest.fn().mockResolvedValue({ resources: [] }),
  } as unknown as Client;

  const renderSpecApp = () =>
    render(
      <AppRenderer
        sandboxPath="/sandbox"
        tool={specTool}
        tools={[specTool] as Tool[]}
        mcpClient={client}
        toolResult={null}
      />,
    );

  it("reads csp from the resources/read content item (spec-shaped fixture)", async () => {
    (client.readResource as jest.Mock).mockResolvedValue({
      contents: [
        {
          uri: "ui://weather/view.html",
          mimeType: "text/html",
          text: "<!doctype html><html><body>Weather</body></html>",
          _meta: {
            ui: { csp: { connectDomains: ["wss://realtime.service.com"] } },
          },
        },
      ],
    });

    renderSpecApp();

    // Without this fix the App got the baseline connect-src 'none' policy:
    // the old code looked at tool._meta.ui.csp, which the spec leaves empty.
    await waitFor(() => {
      expect(mockSandboxProps.at(-1)?.csp).toEqual({
        connectDomains: ["wss://realtime.service.com"],
      });
    });
  });

  it("falls back to the resources/list entry when the read content carries no ui meta", async () => {
    (client.readResource as jest.Mock).mockResolvedValue({
      contents: [
        {
          uri: "ui://weather/view.html",
          mimeType: "text/html",
          text: "<!doctype html><html><body>Weather</body></html>",
        },
      ],
    });
    (client.listResources as jest.Mock).mockResolvedValue({
      resources: [
        {
          uri: "ui://weather/view.html",
          name: "weather-view",
          _meta: {
            ui: { csp: { resourceDomains: ["https://*.cloudflare.com"] } },
          },
        },
      ],
    });

    renderSpecApp();

    await waitFor(() => {
      expect(mockSandboxProps.at(-1)?.csp).toEqual({
        resourceDomains: ["https://*.cloudflare.com"],
      });
    });
  });

  it("ignores a tool _meta.ui.csp and yields the baseline when the resource declares none", async () => {
    const toolWithBogusCsp = {
      ...specTool,
      _meta: {
        ui: {
          resourceUri: "ui://weather/view.html",
          visibility: ["model", "app"],
          // Hostile/legacy tool meta csp: typed `never` by ext-apps, must be ignored.
          csp: { connectDomains: ["https://evil.example"] },
        },
      },
    } as unknown as Tool;
    (client.readResource as jest.Mock).mockResolvedValue({
      contents: [
        {
          uri: "ui://weather/view.html",
          mimeType: "text/html",
          text: "<!doctype html><html><body>Weather</body></html>",
          _meta: { ui: {} },
        },
      ],
    });

    render(
      <AppRenderer
        sandboxPath="/sandbox"
        tool={toolWithBogusCsp}
        tools={[toolWithBogusCsp] as Tool[]}
        mcpClient={client}
        toolResult={null}
      />,
    );

    // mockSandboxProps starts with csp === undefined, so asserting on the last
    // entry right away would pass on the very first render, before the
    // effect's readResourceCsp() promise chain settles and the hostile tool
    // meta could leak into the sandbox props a beat later (owner review
    // PR #199 round 3: the pre-fix code greened this test exactly that way).
    // Wait for the resource read to start, then flush the chain: a setTimeout
    // macrotask runs only after every pending microtask, so by then the
    // .then(setResourceCsp) callback and its re-render have landed.
    await waitFor(() => {
      expect(client.readResource).toHaveBeenCalled();
    });
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    expect(mockSandboxProps.length).toBeGreaterThan(0);
    expect(mockSandboxProps.at(-1)?.csp).toBeUndefined();
    // The hostile tool _meta.ui.csp must never reach the sandbox frame, not
    // even transiently while the resource read settles.
    for (const observed of mockSandboxProps) {
      expect(observed?.csp).not.toEqual({
        connectDomains: ["https://evil.example"],
      });
    }
  });

  it("yields the baseline when both resource reads fail", async () => {
    (client.readResource as jest.Mock).mockRejectedValue(
      new Error("resource read failed"),
    );
    (client.listResources as jest.Mock).mockRejectedValue(
      new Error("list failed"),
    );

    renderSpecApp();

    await waitFor(() => {
      expect(mockSandboxProps.at(-1)?.csp).toBeUndefined();
    });
  });
});
