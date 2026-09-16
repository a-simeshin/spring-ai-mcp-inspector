// [spring-ai-mcp-inspector PATCH] Tab router fallback tests (#226, t_f32dedc6).
// A stale/bookmarked/mistyped hash (e.g. "#elicitation" instead of
// "#elicitations") used to leave the UI dead: no tab with aria-selected=true
// and zero visible tabpanels. These tests pin the resilient behavior:
// every hash resolves to exactly one selected tab and one visible panel,
// with near-miss normalization and a capability-aware default fallback.
import { act, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { useConnection } from "../lib/hooks/useConnection";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";

// Mock auth dependencies first
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

// Tab components are mocked as real TabsContent panels so the suite can
// assert on role=tabpanel visibility exactly like a user-facing DOM check.
jest.mock("../components/ResourcesTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="resources">ResourcesTab</TabsContent>,
  };
});

jest.mock("../components/PromptsTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="prompts">PromptsTab</TabsContent>,
  };
});

jest.mock("../components/ToolsTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="tools">ToolsTab</TabsContent>,
  };
});

jest.mock("../components/TasksTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="tasks">TasksTab</TabsContent>,
  };
});

jest.mock("../components/AppsTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="apps">AppsTab</TabsContent>,
  };
});

jest.mock("../components/ConsoleTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="console">ConsoleTab</TabsContent>,
  };
});

jest.mock("../components/PingTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="ping">PingTab</TabsContent>,
  };
});

jest.mock("../components/SamplingTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="sampling">SamplingTab</TabsContent>,
  };
});

jest.mock("../components/ElicitationTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => (
      <TabsContent value="elicitations">ElicitationTab</TabsContent>
    ),
  };
});

jest.mock("../components/RootsTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="roots">RootsTab</TabsContent>,
  };
});

jest.mock("../components/AuthDebugger", () => ({
  __esModule: true,
  default: () => <div>AuthDebugger</div>,
}));

jest.mock("../components/MetadataTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="metadata">MetadataTab</TabsContent>,
  };
});

jest.mock("../components/TimelineTab", () => {
  const { TabsContent } = jest.requireActual("../components/ui/tabs");
  return {
    __esModule: true,
    default: () => <TabsContent value="timeline">TimelineTab</TabsContent>,
  };
});

global.fetch = jest
  .fn()
  .mockResolvedValue({ json: () => Promise.resolve({}) });

jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: jest.fn(),
}));

function connectionState(serverCapabilities: Record<string, unknown> | null) {
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
    // The Apps/Tasks tabs auto-list on activation; answer list RPCs with
    // empty result envelopes so the panels render without touching network.
    makeRequest: jest.fn(async (request: { method: string }) => {
      if (request.method.endsWith("/list")) {
        const noun = request.method.split("/")[0];
        return { [noun]: [], nextCursor: undefined };
      }
      return {};
    }),
    cancelTask: jest.fn(),
    listTasks: jest.fn(),
    sendNotification: jest.fn(),
    handleCompletion: jest.fn(),
    completionsSupported: false,
    connect: jest.fn(),
    disconnect: jest.fn(),
  } as ReturnType<typeof useConnection>;
}

// A server that advertises everything, so every tab trigger is enabled and
// the default-tab ladder always resolves to "resources".
const ALL_CAPABILITIES = {
  resources: { listChanged: true, subscribe: true },
  prompts: { listChanged: true },
  tools: { listChanged: true },
  tasks: { listChanged: true },
};

// Every tab id and its trigger's accessible name (UI order).
const TAB_NAMES: Record<string, string> = {
  resources: "Resources",
  prompts: "Prompts",
  tools: "Tools",
  tasks: "Tasks",
  apps: "Apps",
  ping: "Ping",
  sampling: "Sampling",
  elicitations: "Elicitations",
  roots: "Roots",
  auth: "Auth",
  metadata: "Metadata",
  timeline: "Timeline",
};

const expectSingleSelectedTab = (tabId: string) => {
  const selected = screen
    .getAllByRole("tab")
    .filter((tab) => tab.getAttribute("aria-selected") === "true");
  expect(selected).toHaveLength(1);
  expect(selected[0]).toHaveAccessibleName(
    new RegExp(`^${TAB_NAMES[tabId]}$`, "i"),
  );
  // Exactly one tabpanel is rendered (Radix unmounts inactive panels).
  const panels = screen.getAllByRole("tabpanel");
  expect(panels).toHaveLength(1);
  expect(panels[0]).toBeVisible();
};

describe("App - tab router hash fallback (#226)", () => {
  const mockUseConnection = jest.mocked(useConnection);

  beforeEach(() => {
    jest.clearAllMocks();
    window.location.hash = "";
    mockUseConnection.mockReturnValue(connectionState(ALL_CAPABILITIES));
  });

  describe("valid hashes select the corresponding tab", () => {
    it.each(Object.keys(TAB_NAMES))(
      "#%s selects its tab with exactly one visible panel",
      async (tabId) => {
        window.location.hash = `#${tabId}`;
        render(<App />);

        await waitFor(() => {
          expectSingleSelectedTab(tabId);
        });
        // A canonical hash is never rewritten.
        expect(window.location.hash).toBe(`#${tabId}`);
      },
    );
  });

  describe("unknown/empty hashes fall back to the default tab", () => {
    it.each(["#bogus", "#elicitation2", "#not-a-tab", "#"])(
      "cold load with %s selects Resources and rewrites the hash",
      async (hash) => {
        window.location.hash = hash;
        render(<App />);

        await waitFor(() => {
          expectSingleSelectedTab("resources");
        });
        await waitFor(() => {
          expect(window.location.hash).toBe("#resources");
        });
      },
    );

    it("empty hash on connect selects Resources", async () => {
      window.location.hash = "";
      render(<App />);

      await waitFor(() => {
        expectSingleSelectedTab("resources");
      });
      await waitFor(() => {
        expect(window.location.hash).toBe("#resources");
      });
    });

    it("default follows the capability ladder when resources are absent", async () => {
      mockUseConnection.mockReturnValue(
        connectionState({ tools: { listChanged: true } }),
      );
      window.location.hash = "#bogus";
      render(<App />);

      await waitFor(() => {
        expectSingleSelectedTab("tools");
      });
      await waitFor(() => {
        expect(window.location.hash).toBe("#tools");
      });
    });
  });

  describe("near-miss hashes are normalized", () => {
    it.each([
      ["#elicitation", "elicitations"],
      ["#ELICITATIONS", "elicitations"],
      ["#Resource", "resources"],
      ["#RESOURCES", "resources"],
      ["#root", "roots"],
      ["#Prompt", "prompts"],
    ])("%s normalizes to #%s", async (hash, canonical) => {
      window.location.hash = hash;
      render(<App />);

      await waitFor(() => {
        expectSingleSelectedTab(canonical);
      });
      await waitFor(() => {
        expect(window.location.hash).toBe(`#${canonical}`);
      });
    });
  });

  describe("hashchange after initial load", () => {
    const setHash = async (hash: string) => {
      await act(async () => {
        window.location.hash = hash;
        window.dispatchEvent(
          new HashChangeEvent("hashchange", { newURL: hash }),
        );
      });
    };

    it("an unknown hash typed later falls back to Resources", async () => {
      window.location.hash = "#tools";
      render(<App />);
      await waitFor(() => {
        expectSingleSelectedTab("tools");
      });

      await setHash("#bogus");

      await waitFor(() => {
        expectSingleSelectedTab("resources");
      });
      expect(window.location.hash).toBe("#resources");
    });

    it("a near-miss hash typed later is normalized", async () => {
      window.location.hash = "#resources";
      render(<App />);
      await waitFor(() => {
        expectSingleSelectedTab("resources");
      });

      await setHash("#elicitation");

      await waitFor(() => {
        expectSingleSelectedTab("elicitations");
      });
      expect(window.location.hash).toBe("#elicitations");
    });

    it("switching between valid hashes keeps exactly one panel", async () => {
      window.location.hash = "#resources";
      render(<App />);
      await waitFor(() => {
        expectSingleSelectedTab("resources");
      });

      for (const tabId of ["prompts", "timeline", "ping", "metadata"]) {
        await setHash(`#${tabId}`);
        await waitFor(() => {
          expectSingleSelectedTab(tabId);
        });
      }
    });
  });

  describe("null/empty capabilities always route to PingTab (#t_fdeb9f56)", () => {
    const setHash = async (hash: string) => {
      await act(async () => {
        window.location.hash = hash;
        window.dispatchEvent(
          new HashChangeEvent("hashchange", { newURL: hash }),
        );
      });
    };

    it("connected with null capabilities and #apps resolves to Ping", async () => {
      mockUseConnection.mockReturnValue(connectionState(null));
      window.location.hash = "#apps";
      render(<App />);

      await waitFor(() => {
        expectSingleSelectedTab("ping");
      });
      await waitFor(() => {
        expect(window.location.hash).toBe("#ping");
      });
    });

    it("connected with null capabilities and #bogus resolves to Ping", async () => {
      mockUseConnection.mockReturnValue(connectionState(null));
      window.location.hash = "#bogus";
      render(<App />);

      await waitFor(() => {
        expectSingleSelectedTab("ping");
      });
      await waitFor(() => {
        expect(window.location.hash).toBe("#ping");
      });
    });

    it("hashchange to #apps with null capabilities stays on Ping", async () => {
      mockUseConnection.mockReturnValue(connectionState(null));
      window.location.hash = "#ping";
      render(<App />);
      await waitFor(() => {
        expectSingleSelectedTab("ping");
      });

      await setHash("#apps");

      await waitFor(() => {
        expectSingleSelectedTab("ping");
      });
      expect(window.location.hash).toBe("#ping");
    });

    it("empty capabilities object {} also routes to Ping", async () => {
      mockUseConnection.mockReturnValue(connectionState({}));
      window.location.hash = "#apps";
      render(<App />);

      await waitFor(() => {
        expectSingleSelectedTab("ping");
      });
      await waitFor(() => {
        expect(window.location.hash).toBe("#ping");
      });
    });
  });
});
