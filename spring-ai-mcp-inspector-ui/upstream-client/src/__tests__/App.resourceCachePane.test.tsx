// [spring-ai-mcp-inspector PATCH] Regression guards for the cache-hit
// pane handling of issue #344.
//
// Tools-link half: clicking a resource_link in a tool result calls
// App.readResource for a uri that is already cached, and the cache-hit
// pane commit used to overwrite the Resources pane while a different
// row stayed selected, leaving pane and selection diverged. With the
// guard, the cached payload stays visible inline in the Tools tab
// (ResourceLinkView reads the same per-uri map) and only the pane
// commit is suppressed.
//
// Positive half: the guarded commit must still run when it is
// legitimate. Two pins:
//   - re-clicking an already-cached row while it is the live selection
//     (mirrored into the ref by the selectedResource handler) must
//     commit the cached payload back into the pane;
//   - reading a cached uri through a resource template
//     (handleReadTemplateResource sets the selection before the read)
//     must commit the template payload into the pane.
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom";
// [spring-ai-mcp-inspector PATCH] jsdom lacks PointerEvent, which Radix
// tab triggers react to through userEvent; the polyfill must land before
// react-dom loads (same setup as App.tabClickRegression.test.tsx).
import "../testUtils/pointerEventsPolyfill";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { TooltipProvider } from "@/components/ui/tooltip";

jest.mock("@modelcontextprotocol/sdk/client/auth.js", () => ({
  auth: jest.fn(),
}));

jest.mock("../lib/oauth-state-machine", () => ({
  OAuthStateMachine: jest.fn(),
}));

jest.mock("../lib/auth", () => ({
  InspectorOAuthClientProvider: jest.fn().mockImplementation(() => ({
    tokens: jest.fn().mockResolvedValue(null),
    clear: jest.fn(),
  })),
  DebugInspectorOAuthClientProvider: jest.fn(),
}));

jest.mock("../utils/configUtils", () => ({
  ...jest.requireActual("../utils/configUtils"),
  getMCPProxyAddress: jest.fn(() => "http://localhost:6277"),
  getMCPProxyAuthToken: jest.fn(() => ({
    token: "",
    header: "X-MCP-Proxy-Auth",
  })),
  getInitialTransportType: jest.fn(() => "stdio"),
  getInitialSseUrl: jest.fn(() => "http://localhost:3001/sse"),
  getInitialCommand: jest.fn(() => "mcp-server-everything"),
  getInitialArgs: jest.fn(() => ""),
  initializeInspectorConfig: jest.fn(() => ({})),
  saveInspectorConfig: jest.fn(),
}));

jest.mock("../lib/hooks/useDraggablePane", () => ({
  useDraggablePane: () => ({
    height: 300,
    handleDragStart: jest.fn(),
  }),
  useDraggableSidebar: () => ({
    width: 320,
    isDragging: false,
    handleDragStart: jest.fn(),
  }),
}));

jest.mock("../components/Sidebar", () => ({
  __esModule: true,
  default: () => <div>Sidebar</div>,
}));

jest.mock("../components/HistoryAndNotifications", () => ({
  __esModule: true,
  default: () => <div>HistoryAndNotifications</div>,
}));

// [spring-ai-mcp-inspector PATCH] The template form is the only place
// the Resources pane uses Combobox; replaced with a plain input so
// template values can be set with fireEvent (same mock as
// ResourcesTab.test.tsx).
jest.mock("@/components/ui/combobox", () => ({
  Combobox: ({
    id,
    value,
    onChange,
    placeholder,
  }: {
    id: string;
    value: string;
    onChange: (value: string) => void;
    placeholder: string;
  }) => (
    <input
      id={id}
      value={value || ""}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      data-testid={`combobox-${id}`}
    />
  ),
}));

global.fetch = jest.fn().mockResolvedValue({ json: () => Promise.resolve({}) });

jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: jest.fn(),
}));

function connectionState(serverCapabilities: Record<string, unknown>) {
  return {
    connectionStatus: "connected" as const,
    serverCapabilities,
    serverImplementation: null,
    mcpClient: {
      request: jest.fn(),
      notification: jest.fn(),
      close: jest.fn(),
    } as unknown as Client,
    requestHistory: [],
    clearRequestHistory: jest.fn(),
    makeRequest: jest.fn(),
    cancelTask: jest.fn(),
    listTasks: jest.fn(),
    sendNotification: jest.fn(),
    handleCompletion: jest.fn(),
    completionsSupported: false,
    connect: jest.fn(),
    disconnect: jest.fn(),
  } as ReturnType<typeof useConnection>;
}

describe("App - tools-link cache-hit pane guard (#344)", () => {
  const mockUseConnection = jest.mocked(useConnection);

  const makeRequest = jest.fn(
    async (request: { method: string; params?: { uri?: string } }) => {
      switch (request.method) {
        case "resources/list":
          return {
            resources: [
              {
                uri: "demo://cached",
                name: "cached-resource",
                mimeType: "text/plain",
              },
              {
                uri: "demo://selected",
                name: "selected-resource",
                mimeType: "text/plain",
              },
            ],
            nextCursor: undefined,
          };
        case "resources/read": {
          const uri = request.params?.uri ?? "";
          return {
            contents: [
              {
                uri,
                mimeType: "text/plain",
                text:
                  uri === "demo://cached"
                    ? "cached-payload"
                    : "selected-payload",
              },
            ],
          };
        }
        case "resources/templates/list":
          return {
            resourceTemplates: [
              {
                name: "cached-template",
                uriTemplate: "demo://{name}",
                description: "Expands to the cached resource uri",
              },
            ],
            nextCursor: undefined,
          };
        case "tools/list":
          return {
            tools: [
              {
                name: "link-tool",
                inputSchema: { type: "object", properties: {} },
              },
            ],
            nextCursor: undefined,
          };
        case "tools/call":
          return {
            content: [
              {
                type: "resource_link",
                uri: "demo://cached",
                name: "cached-resource",
                mimeType: "text/plain",
              },
            ],
          };
        default:
          return {};
      }
    },
  );

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("a Tools-link click on a cached resource does not overwrite the pane of the selected row", async () => {
    mockUseConnection.mockReturnValue({
      ...connectionState({
        resources: { listChanged: true },
        tools: { listChanged: true },
      }),
      makeRequest,
    } as ReturnType<typeof useConnection>);

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    fireEvent.click(screen.getByText("List Resources"));
    await waitFor(() => {
      expect(screen.getByTitle("demo://cached")).toBeInTheDocument();
    });

    const { act } = await import("@testing-library/react");

    // Cache both rows: read the cached resource first, then select the
    // other row. The pane shows selected-payload and the selection sits
    // on the selected-resource row.
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://cached"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/cached-payload/)).toBeInTheDocument();
    });
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://selected"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/selected-payload/)).toBeInTheDocument();
    });

    // To the Tools tab: list, select, and run the tool whose result
    // carries a resource_link to the already-cached uri. Tab state is
    // driven by the hashchange listener (#200), which fires as a
    // macrotask: userEvent.click plus a data-state wait keeps the pane
    // switch deterministic.
    const user = userEvent.setup();
    await user.click(screen.getByRole("tab", { name: "Tools" }));
    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "Tools" })).toHaveAttribute(
        "data-state",
        "active",
      );
    });
    fireEvent.click(screen.getByText("List Tools"));
    await waitFor(() => {
      expect(screen.getByText("link-tool")).toBeInTheDocument();
    });
    await act(async () => {
      fireEvent.click(screen.getByText("link-tool"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    fireEvent.click(screen.getByText("Run Tool"));
    await waitFor(() => {
      expect(
        screen.getByRole("button", {
          name: /Expand resource demo:\/\/cached/,
        }),
      ).toBeInTheDocument();
    });

    // Click the Tools link for the cached uri. The read is a cache hit:
    // the payload must surface inline in the tool result...
    await act(async () => {
      fireEvent.click(
        screen.getByRole("button", {
          name: /Expand resource demo:\/\/cached/,
        }),
      );
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    expect(screen.getByText(/cached-payload/)).toBeInTheDocument();

    // ...but back on the Resources tab the pane must still show the
    // selected row's payload: the cache-hit pane commit is guarded by
    // the live selected uri, and the selection never left the
    // selected-resource row.
    await user.click(screen.getByRole("tab", { name: "Resources" }));
    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "Resources" })).toHaveAttribute(
        "data-state",
        "active",
      );
    });
    expect(screen.getByText(/selected-payload/)).toBeInTheDocument();
    expect(screen.queryByText(/cached-payload/)).not.toBeInTheDocument();
  }, 15000);

  it("a re-click of an already-cached row commits the cached payload while the row is the live selection", async () => {
    mockUseConnection.mockReturnValue({
      ...connectionState({
        resources: { listChanged: true },
      }),
      makeRequest,
    } as ReturnType<typeof useConnection>);

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    fireEvent.click(screen.getByText("List Resources"));
    await waitFor(() => {
      expect(screen.getByTitle("demo://cached")).toBeInTheDocument();
    });

    const { act } = await import("@testing-library/react");

    // Cache both rows and leave the pane on the other row's payload.
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://cached"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/cached-payload/)).toBeInTheDocument();
    });
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://selected"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/selected-payload/)).toBeInTheDocument();
    });

    // Click back to the already-cached row: the read is a cache hit and
    // the clicked row IS the live selection (mirrored into the ref by
    // the selectedResource handler before the read starts), so the
    // guarded commit must run and the pane must show the cached payload
    // again. Without the ref sync the guard never matches and every
    // cache-hit commit is suppressed.
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://cached"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/cached-payload/)).toBeInTheDocument();
    });
    expect(screen.queryByText(/selected-payload/)).not.toBeInTheDocument();
  }, 15000);

  it("a cached template read commits the template payload because the selection is set before the read", async () => {
    mockUseConnection.mockReturnValue({
      ...connectionState({
        resources: { listChanged: true },
      }),
      makeRequest,
    } as ReturnType<typeof useConnection>);

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    fireEvent.click(screen.getByText("List Resources"));
    await waitFor(() => {
      expect(screen.getByTitle("demo://cached")).toBeInTheDocument();
    });
    fireEvent.click(screen.getByText("List Templates"));
    await waitFor(() => {
      expect(screen.getByText("cached-template")).toBeInTheDocument();
    });

    const { act } = await import("@testing-library/react");

    // Cache both rows and leave the pane on the selected row's payload:
    // demo://cached is cached while the live selection sits on the
    // other row.
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://cached"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/cached-payload/)).toBeInTheDocument();
    });
    await act(async () => {
      fireEvent.click(screen.getByTitle("demo://selected"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/selected-payload/)).toBeInTheDocument();
    });

    // Select the template (the selection resets to null), expand it to
    // the cached uri, and read. handleReadTemplateResource must set the
    // selection BEFORE the read: the cache-hit guard judges by the live
    // selected uri, and reading first would make it see the previous
    // (null) selection and skip the pane commit.
    await act(async () => {
      fireEvent.click(screen.getByText("cached-template"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    fireEvent.change(screen.getByTestId("combobox-name"), {
      target: { value: "cached" },
    });
    await act(async () => {
      fireEvent.click(screen.getByText("Read Resource"));
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await waitFor(() => {
      expect(screen.getByText(/cached-payload/)).toBeInTheDocument();
    });
    expect(screen.queryByText(/selected-payload/)).not.toBeInTheDocument();
  }, 15000);
});
