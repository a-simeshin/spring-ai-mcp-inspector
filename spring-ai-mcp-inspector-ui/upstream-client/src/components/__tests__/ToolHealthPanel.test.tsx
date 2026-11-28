// [spring-ai-mcp-inspector PATCH] Tool Health panel tests (#370): wire
// contract, sorting anchors, click-through callback, and the empty / disabled
// / error states mirroring the ClientsTab handling.
import { render, screen, waitFor, fireEvent, cleanup } from "@testing-library/react";
import "@testing-library/jest-dom";
import { ToolHealthPanel } from "../ToolHealthPanel";
import { ToolHealthStat } from "@/lib/toolHealth";

const STAT_ECHO: ToolHealthStat = {
  method: "echo",
  calls: 4,
  errors: 1,
  noResponse: 0,
  errorRate: 0.25,
  p50Ms: 40,
  p95Ms: 120,
  lastIsError: false,
  lastCallAt: 1767261600000,
};

const STAT_ERROR_TOOL: ToolHealthStat = {
  method: "errorTool",
  calls: 2,
  errors: 2,
  noResponse: 1,
  errorRate: 1,
  p50Ms: 5,
  p95Ms: 9,
  lastIsError: true,
  lastCallAt: 1767261601000,
};

const STAT_SLOW_ECHO: ToolHealthStat = {
  method: "slowEcho",
  calls: 1,
  errors: 0,
  noResponse: 0,
  errorRate: 0,
  p50Ms: 500,
  p95Ms: 500,
  lastIsError: false,
  lastCallAt: 1767261602000,
};

const mockFetch = (body: ToolHealthStat[] | { status: number }) => {
  const fetchMock =
    typeof body === "object" && "status" in body
      ? jest.fn().mockResolvedValue({ ok: false, status: body.status })
      : jest.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body });
  (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
  return fetchMock;
};

beforeEach(() => {
  (window as unknown as { __MCP_INSPECTOR_BOOTSTRAP?: Record<string, string> }).__MCP_INSPECTOR_BOOTSTRAP =
    { inspectorPath: "/mcp-inspector", authToken: "token-1" };
});

afterEach(() => {
  cleanup();
  jest.restoreAllMocks();
});

describe("ToolHealthPanel", () => {
  it("renders one row per tool with calls, errors, rate, percentiles and last flag", async () => {
    mockFetch([STAT_ECHO, STAT_ERROR_TOOL, STAT_SLOW_ECHO]);
    render(<ToolHealthPanel onFilterTool={jest.fn()} />);

    await waitFor(() => expect(screen.getByTestId("tool-health-row-echo")).toBeInTheDocument());
    expect(screen.getByTestId("tool-health-table")).toBeInTheDocument();
    const echoRow = screen.getByTestId("tool-health-row-echo");
    expect(echoRow).toHaveTextContent("echo");
    expect(echoRow).toHaveTextContent("4");
    expect(echoRow).toHaveTextContent("25%");
    expect(echoRow).toHaveTextContent("40 ms");
    expect(echoRow).toHaveTextContent("120 ms");
    expect(echoRow).toHaveTextContent("ok");
    expect(screen.getByTestId("tool-health-row-errorTool")).toHaveTextContent("error");
  });

  it("sends the bootstrap auth header and hits the deployed API path", async () => {
    const fetchMock = mockFetch([]);
    render(<ToolHealthPanel onFilterTool={jest.fn()} />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(String(fetchMock.mock.calls[0][0])).toBe("/mcp-inspector/api/timeline/tool-health");
    const init = fetchMock.mock.calls[0][1] as { headers: Record<string, string> };
    expect(init.headers["X-MCP-Inspector-Auth"]).toBe("token-1");
  });

  it("sorts by error rate descending by default and re-sorts on header clicks", async () => {
    mockFetch([STAT_ECHO, STAT_ERROR_TOOL, STAT_SLOW_ECHO]);
    const { container } = render(<ToolHealthPanel onFilterTool={jest.fn()} />);
    await waitFor(() => expect(screen.getByTestId("tool-health-table")).toBeInTheDocument());

    const rowOrder = () =>
      Array.from(container.querySelectorAll("tbody tr")).map(
        (row) => row.getAttribute("data-testid") ?? "",
      );
    // Default: worst error rate first.
    expect(rowOrder()).toEqual([
      "tool-health-row-errorTool",
      "tool-health-row-echo",
      "tool-health-row-slowEcho",
    ]);

    // Click p95: slowest first, descending on the first click.
    fireEvent.click(screen.getByTestId("tool-health-sort-p95"));
    expect(rowOrder()).toEqual([
      "tool-health-row-slowEcho",
      "tool-health-row-echo",
      "tool-health-row-errorTool",
    ]);

    // Second click on the same header toggles to ascending.
    fireEvent.click(screen.getByTestId("tool-health-sort-p95"));
    expect(rowOrder()).toEqual([
      "tool-health-row-errorTool",
      "tool-health-row-echo",
      "tool-health-row-slowEcho",
    ]);
  });

  it("reports the clicked tool through onFilterTool", async () => {
    mockFetch([STAT_ECHO, STAT_ERROR_TOOL]);
    const onFilterTool = jest.fn();
    render(<ToolHealthPanel onFilterTool={onFilterTool} />);
    await waitFor(() => expect(screen.getByTestId("tool-health-row-errorTool")).toBeInTheDocument());
    fireEvent.click(screen.getByTestId("tool-health-row-errorTool"));
    expect(onFilterTool).toHaveBeenCalledWith("errorTool");
  });

  it("renders the empty state when no tools were called", async () => {
    mockFetch([]);
    render(<ToolHealthPanel onFilterTool={jest.fn()} />);
    await waitFor(() => expect(screen.getByTestId("tool-health-empty")).toBeInTheDocument());
  });

  it("renders the disabled state on 404 (timeline subsystem off)", async () => {
    const fetchMock = mockFetch({ status: 404 });
    render(<ToolHealthPanel onFilterTool={jest.fn()} />);
    await waitFor(() => expect(screen.getByTestId("tool-health-disabled")).toBeInTheDocument());
    // The poll stops after the 404: no further probes fire.
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("renders an explicit error state on other non-OK responses", async () => {
    mockFetch({ status: 500 });
    render(<ToolHealthPanel onFilterTool={jest.fn()} />);
    await waitFor(() => expect(screen.getByTestId("tool-health-error")).toBeInTheDocument());
    expect(screen.getByRole("alert")).toHaveTextContent("HTTP 500");
  });
});
