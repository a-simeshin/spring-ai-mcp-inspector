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
            {e.params && typeof e.params === "object" && "droppedCount" in e.params ? (
              <span data-testid={`dropped-count-${e.seq}`}>
                {JSON.stringify(e.params)}
              </span>
            ) : null}
          </div>
        ))}
      </div>
    );
  };
});

// Mock AppRenderer that captures traffic entries
jest.mock("../AppRenderer", () => {
  let hugeMode = false;
  return function MockAppRenderer({
    onAppTraffic,
  }: {
    onAppTraffic?: (entry: AppTrafficEntry) => void;
  }) {
    const floodTraffic = () => {
      if (onAppTraffic) {
        for (let i = 0; i < 2000; i++) {
          let params: unknown = { data: "x".repeat(100) };
          if (hugeMode && (i % 400 === 0 || i === 1999)) {
            params = { data: "x".repeat(70000) };
          }
          onAppTraffic({
            seq: i,
            timestamp: new Date().toISOString(),
            direction: "guest->host",
            kind: "notification",
            method: "test/method",
            params,
            phase: "sandbox",
          });
        }
      }
    };

    const floodHuge = () => {
      hugeMode = true;
      if (onAppTraffic) {
        for (let i = 0; i < 10; i++) {
          onAppTraffic({
            seq: i + 10000,
            timestamp: new Date().toISOString(),
            direction: "guest->host",
            kind: "notification",
            method: "test/huge",
            params: { data: "x".repeat(70000) },
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
        <button
          data-testid="flood-huge-button"
          onClick={floodHuge}
        >
          Flood Huge
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

    const appCard = screen.getByText("trafficApp").closest("div");
    fireEvent.click(appCard!);

    await waitFor(() => {
      expect(screen.getByTestId("app-renderer")).toBeInTheDocument();
    });

    const floodButton = screen.getByTestId("flood-button");
    fireEvent.click(floodButton);

    act(() => {
      jest.advanceTimersByTime(100);
    });

    const panel = screen.getByTestId("app-traffic-panel");
    const count = parseInt(
      panel.querySelector("[data-testid='traffic-count']")?.textContent ?? "0",
    );
    expect(count).toBeLessThanOrEqual(500);

    const droppedEntry = screen.queryByTestId("entry--1");
    expect(droppedEntry).toBeInTheDocument();
    expect(droppedEntry!.textContent).toContain("ui/traffic/dropped");

    const droppedCountSpan = screen.queryByTestId("dropped-count--1");
    expect(droppedCountSpan).toBeInTheDocument();
    expect(droppedCountSpan!.textContent).toContain("1500");
  });

  it("truncates oversize entries (> 64 KB params)", async () => {
    render(
      <Tabs defaultValue="apps">
        <AppsTab {...defaultProps} />
      </Tabs>,
    );

    const appCard = screen.getByText("trafficApp").closest("div");
    fireEvent.click(appCard!);

    await waitFor(() => {
      expect(screen.getByTestId("app-renderer")).toBeInTheDocument();
    });

    const floodHugeButton = screen.getByTestId("flood-huge-button");
    fireEvent.click(floodHugeButton);

    act(() => {
      jest.advanceTimersByTime(100);
    });

    expect(screen.getByTestId("app-traffic-panel")).toBeInTheDocument();

    const truncatedEntries = screen.queryAllByText("truncated");
    expect(truncatedEntries.length).toBeGreaterThan(0);
  });
});
