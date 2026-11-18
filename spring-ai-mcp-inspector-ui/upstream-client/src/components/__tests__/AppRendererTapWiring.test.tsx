// [spring-ai-mcp-inspector PATCH] Pinning tests for the DOM-level traffic tap
// and lifecycle state machine in AppRenderer.tsx (issue #183 rework, PR #398).
//
// Unlike AppRenderer.test.tsx, the @mcp-ui/client AppRenderer mock here
// renders a REAL <iframe> element into the container div, so the production
// poll finds it and installs the real window message listener and the real
// contentWindow.postMessage wrap. These tests exercise the actual tap code:
// - lifecycle transitions driven by guest->host notifications,
// - the host->guest traffic log driven through the wrapped postMessage.
// Both behaviors are PR-body promises; mutants that silence them must go RED.

import { render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach } from "@jest/globals";
import AppRenderer from "../AppRenderer";
import type { AppTrafficEntry, AppLifecycleState } from "@/lib/app-traffic";
import { Client, Tool } from "@modelcontextprotocol/sdk/types.js";

// Mock toast hook (the component imports it at module level).
const mockToast = jest.fn();
jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({ toast: mockToast }),
}));

// Mock @mcp-ui/client: render a REAL iframe so the production tap effect
// finds it in the container (it polls container.querySelector("iframe")).
jest.mock("@mcp-ui/client", () => {
  const React = jest.requireActual("react") as typeof import("react");
  return {
    AppRenderer: () =>
      React.createElement("iframe", {
        "data-testid": "sandbox-frame",
        title: "sandbox proxy",
      }),
  };
});

const mockTool: Tool = {
  name: "formApp",
  description: "Form fixture App",
  inputSchema: { type: "object" as const, properties: {} },
  _meta: { ui: { resourceUri: "ui://demo/form-app" } },
} as Tool;

const mockMcpClient = {
  readResource: jest.fn().mockResolvedValue({ contents: [] }),
  listResources: jest.fn().mockResolvedValue({ resources: [] }),
} as unknown as Client;

const defaultProps = {
  sandboxPath: "/mcp-inspector-api/sandbox",
  tool: mockTool,
  tools: [mockTool] as Tool[],
  mcpClient: mockMcpClient,
  toolResult: null,
};

// Wait until the production tap is installed on the iframe (poll interval
// is 100 ms): the wrap fires on any ui/initialize we send, so keep sending
// inside waitFor until the traffic callback observes a host->guest frame.
async function waitForTapInstalled(
  iframe: HTMLIFrameElement,
  traffic: AppTrafficEntry[],
) {
  await waitFor(
    () => {
      iframe.contentWindow!.postMessage(
        {
          jsonrpc: "2.0",
          id: 1,
          method: "ui/initialize",
          params: {},
        },
        "*",
      );
      expect(traffic.length).toBeGreaterThan(0);
    },
    { timeout: 2000 },
  );
}

function guestFrame(iframe: HTMLIFrameElement, data: unknown) {
  window.dispatchEvent(
    new MessageEvent("message", {
      source: iframe.contentWindow!,
      data,
    }),
  );
}

describe("AppRenderer DOM tap wiring (real iframe)", () => {
  let lifecycle: AppLifecycleState[];
  let traffic: AppTrafficEntry[];

  beforeEach(() => {
    jest.clearAllMocks();
    lifecycle = [];
    traffic = [];
  });

  function renderTapped() {
    render(
      <AppRenderer
        {...defaultProps}
        onLifecycleChange={(state) => lifecycle.push(state)}
        onAppTraffic={(entry) => traffic.push(entry)}
      />,
    );
    return screen.getByTestId("sandbox-frame") as HTMLIFrameElement;
  }

  it("drives lifecycle transitions from guest->host notifications through the real tap", async () => {
    const iframe = renderTapped();

    // The production poll installs the tap within ~100 ms of the iframe
    // appearing; the wrap fires on any ui/initialize we send, so wait for it.
    await waitForTapInstalled(iframe, traffic);
    await waitFor(() => expect(traffic.length).toBeGreaterThan(0));
    traffic.length = 0;

    guestFrame(iframe, {
      jsonrpc: "2.0",
      method: "ui/notifications/sandbox-proxy-ready",
      params: {},
    });
    expect(lifecycle).toContain("initializing");

    guestFrame(iframe, {
      jsonrpc: "2.0",
      method: "ui/notifications/initialized",
      params: {},
    });
    expect(lifecycle).toContain("ready");

    guestFrame(iframe, {
      jsonrpc: "2.0",
      method: "ui/resource-teardown",
      params: {},
    });
    expect(lifecycle).toContain("torn-down");
  });

  it("logs host->guest JSON-RPC frames via the contentWindow.postMessage wrap", async () => {
    const iframe = renderTapped();
    await waitForTapInstalled(iframe, traffic);
    await waitFor(() => expect(traffic.length).toBeGreaterThan(0));
    traffic.length = 0;

    iframe.contentWindow!.postMessage(
      {
        jsonrpc: "2.0",
        id: 7,
        method: "ui/initialize",
        params: { protocolVersion: "2026-01-26" },
      },
      "*",
    );

    await waitFor(() => expect(traffic.length).toBeGreaterThan(0));
    const entry = traffic[traffic.length - 1];
    expect(entry.direction).toBe("host->guest");
    expect(entry.method).toBe("ui/initialize");
    expect(entry.id).toBe(7);
    expect(entry.kind).toBe("request");
  });

  it("logs guest->host frames and keeps the chronological order across directions", async () => {
    const iframe = renderTapped();
    await waitForTapInstalled(iframe, traffic);
    await waitFor(() => expect(traffic.length).toBeGreaterThan(0));
    traffic.length = 0;

    // Interleave directions; the panel promise is a chronological log.
    guestFrame(iframe, {
      jsonrpc: "2.0",
      method: "ui/notifications/sandbox-proxy-ready",
      params: {},
    });
    iframe.contentWindow!.postMessage(
      { jsonrpc: "2.0", id: 1, method: "ui/initialize", params: {} },
      "*",
    );
    guestFrame(iframe, {
      jsonrpc: "2.0",
      method: "ui/notifications/initialized",
      params: {},
    });
    iframe.contentWindow!.postMessage(
      {
        jsonrpc: "2.0",
        id: 1,
        result: { protocolVersion: "2026-01-26" },
      },
      "*",
    );

    await waitFor(() => expect(traffic).toHaveLength(4));
    expect(traffic.map((e) => e.method)).toEqual([
      "ui/notifications/sandbox-proxy-ready",
      "ui/initialize",
      "ui/notifications/initialized",
      undefined,
    ]);
    expect(traffic.map((e) => e.direction)).toEqual([
      "guest->host",
      "host->guest",
      "guest->host",
      "host->guest",
    ]);
    const kinds = traffic.map((e) => e.kind);
    expect(kinds).toEqual([
      "notification",
      "request",
      "notification",
      "response",
    ]);
    // Sequence numbers strictly increase in dispatch order.
    for (let i = 1; i < traffic.length; i++) {
      expect(traffic[i].seq).toBeGreaterThan(traffic[i - 1].seq);
    }
  });

  it("ignores frames whose source is not the sandbox iframe", async () => {
    const iframe = renderTapped();
    await waitForTapInstalled(iframe, traffic);
    await waitFor(() => expect(traffic.length).toBeGreaterThan(0));
    traffic.length = 0;

    window.dispatchEvent(
      new MessageEvent("message", {
        source: window,
        data: { jsonrpc: "2.0", method: "ui/notifications/initialized" },
      }),
    );
    window.dispatchEvent(
      new MessageEvent("message", {
        source: iframe.contentWindow!,
        data: { jsonrpc: "1.0", method: "ui/notifications/initialized" },
      }),
    );

    expect(traffic).toHaveLength(0);
  });
});
