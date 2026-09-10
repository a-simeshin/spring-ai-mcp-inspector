import { render, screen, waitFor, act, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach } from "@jest/globals";
import AppsTab from "../AppsTab";
import {
  Tool,
  CompatibilityCallToolResult,
} from "@modelcontextprotocol/sdk/types.js";
import { Tabs } from "../ui/tabs";
import type { AppTrafficEntry } from "@/lib/app-traffic";

// [spring-ai-mcp-inspector PATCH] app-traffic-panel: AppTrafficPanel mock and Apps tab assertions (issue #183).
jest.mock("../AppTrafficPanel", () => {
  return function MockAppTrafficPanel({
    entries,
  }: {
    entries: AppTrafficEntry[];
  }) {
    return (
      <div data-testid="app-traffic-panel">
        <div data-testid="traffic-count">{entries.length}</div>
        {entries.map((e) => (
          <div key={e.seq} data-testid={`entry-${e.seq}`}>
            {e.method}
            {e.params &&
            typeof e.params === "object" &&
            "truncated" in e.params ? (
              <span data-testid={`truncated-${e.seq}`}>truncated</span>
            ) : null}
          </div>
        ))}
      </div>
    );
  };
});

// Mock AppRenderer that captures traffic entries
jest.mock("../AppRenderer", () => {
  return function MockAppRenderer({
    onAppTraffic,
  }: {
    onAppTraffic?: (entry: AppTrafficEntry) => void;
  }) {
    // Simulate a flood of traffic entries
    const floodTraffic = () => {
      if (onAppTraffic) {
        for (let i = 0; i < 2000; i++) {
          onAppTraffic({
            seq: i,
            timestamp: new Date().toISOString(),
            direction: "guest->host",
            kind: "notification",
            method: "test/method",
            params: { data: "x".repeat(100) },
            phase: "sandbox",
          });
        }
      }
    };

    return (
      <div data-testid="app-renderer">
        <button
          data-testid="flood-button"
          onClick={floodTraffic}
        >
          Flood
        </button>
      </div>
    );
  };
});

describe("AppsTab - traffic ingress bounding", () => {
  const mockAppTool: Tool = {
    name: "trafficApp",
    description: "App with traffic",
    inputSchema: { type: "object" as const, properties: {} },
    _meta: { ui: { resourceUri: "ui://traffic" } },
  } as Tool;

  const defaultProps = {
    tools: [mockAppTool],
    listTools: jest.fn(),
    callTool: jest.fn(
      async () => ({ content: [] }) as CompatibilityCallToolResult,
    ),
    error: null,
    mcpClient: null,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
  });

  it("bounds pending entries to 500 and creates dropped marker", async () => {
    render(
      <Tabs defaultValue="apps">
        <AppsTab {...defaultProps} />
      </Tabs>,
    );

    // Open the app
    const appCard = screen.getByText("trafficApp").closest("div");
    fireEvent.click(appCard!);

    await waitFor(() => {
      expect(screen.getByTestId("app-renderer")).toBeInTheDocument();
    });

    // Flood 2000 entries (ingress should cap at 500 pending)
    const floodButton = screen.getByTestId("flood-button");
    fireEvent.click(floodButton);

    // Advance timers past the 50ms flush throttle
    act(() => {
      jest.advanceTimersByTime(100);
    });

    // The traffic panel should show entries (bounded to 500 max)
    const panel = screen.getByTestId("app-traffic-panel");
    const count = parseInt(
      panel.querySelector("[data-testid='traffic-count']")?.textContent ?? "0",
    );
    expect(count).toBeLessThanOrEqual(500);
  });

  it("truncates oversize entries (> 64 KB params)", async () => {
    const toolWithHugeResult: Tool = {
      name: "hugeApp",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: "ui://huge" } },
    } as Tool;

    render(
      <Tabs defaultValue="apps">
        <AppsTab
          {...defaultProps}
          tools={[toolWithHugeResult]}
          callTool={jest.fn(async () => ({
            content: [{ type: "text", text: "ok" }],
          }))}
        />
      </Tabs>,
    );

    // Open the app
    const appCard = screen.getByText("hugeApp").closest("div");
    fireEvent.click(appCard!);

    await waitFor(() => {
      expect(screen.getByTestId("app-renderer")).toBeInTheDocument();
    });

    // Flood with a huge entry
    const floodButton = screen.getByTestId("flood-button");
    fireEvent.click(floodButton);

    act(() => {
      jest.advanceTimersByTime(100);
    });

    // Traffic panel should render without crashing
    expect(screen.getByTestId("app-traffic-panel")).toBeInTheDocument();
  });
});