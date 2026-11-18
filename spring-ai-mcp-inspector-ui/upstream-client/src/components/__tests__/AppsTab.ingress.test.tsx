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
            <span data-testid={`params-${e.seq}`}>
              {e.params ? JSON.stringify(e.params) : ""}
            </span>
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

    // Burst of consecutive tool-input-partial notifications, optionally
    // interrupted by a non-partial message (which must end the burst).
    const burstPartials = (interrupt: boolean) => {
      if (!onAppTraffic) return;
      const partial = (step: number) =>
        onAppTraffic({
          seq: 500 + step,
          timestamp: new Date().toISOString(),
          direction: "guest->host",
          kind: "notification",
          method: "ui/notifications/tool-input-partial",
          params: { arguments: { step } },
          phase: "sandbox",
        });
      const other = () =>
        onAppTraffic({
          seq: 600,
          timestamp: new Date().toISOString(),
          direction: "guest->host",
          kind: "notification",
          method: "test/method",
          params: { data: "y" },
          phase: "sandbox",
        });
      partial(0);
      partial(1);
      if (interrupt) other();
      partial(2);
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
        <button
          data-testid="burst-partials-button"
          onClick={() => burstPartials(false)}
        >
          Burst Partials
        </button>
        <button
          data-testid="burst-partials-interrupt-button"
          onClick={() => burstPartials(true)}
        >
          Burst Partials Interrupted
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

  // Pin for the PR-body promise "throttled collapse of tool-input-partial
  // bursts": a burst of consecutive partial notifications keeps only the
  // latest partial instead of one entry per chunk.
  it("collapses consecutive tool-input-partial bursts to the latest partial", async () => {
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

    fireEvent.click(screen.getByTestId("burst-partials-button"));

    act(() => {
      jest.advanceTimersByTime(100);
    });

    const partialEntries = screen.getAllByTestId(/^entry-/).filter((el) =>
      el.textContent?.includes("ui/notifications/tool-input-partial"),
    );
    // Burst of 3 partials collapses to exactly one entry...
    expect(partialEntries).toHaveLength(1);
    // ...holding the latest partial of the burst.
    expect(partialEntries[0].textContent).toContain("ui/notifications/tool-input-partial");
    expect(partialEntries[0].textContent).toContain('"step":2');
  });

  it("ends a tool-input-partial collapse burst at a non-partial entry", async () => {
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

    fireEvent.click(screen.getByTestId("burst-partials-interrupt-button"));

    act(() => {
      jest.advanceTimersByTime(100);
    });

    // partial(0), partial(1), test/method, partial(2): the interrupt must
    // split the burst, so both partial(1)-collapsed and partial(2) survive.
    const methods = screen
      .getAllByTestId(/^entry-/)
      .map((el) => el.textContent ?? "");
    const partials = methods.filter((m) =>
      m.includes("ui/notifications/tool-input-partial"),
    );
    expect(partials).toHaveLength(2);
    expect(partials[0]).toContain('"step":1');
    expect(partials[1]).toContain('"step":2');
    expect(methods.filter((m) => m.includes("test/method"))).toHaveLength(1);
  });
});
