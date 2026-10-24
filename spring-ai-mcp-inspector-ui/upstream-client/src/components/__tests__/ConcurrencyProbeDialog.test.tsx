import { render, screen, fireEvent, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach } from "@jest/globals";
import ConcurrencyProbeDialog from "../ConcurrencyProbeDialog";
import type { ProbeHandle, ProbeResult } from "../../lib/concurrency";
import type { Tool } from "@modelcontextprotocol/sdk/types.js";

// [spring-ai-mcp-inspector PATCH] Concurrency probe dialog tests (issue #237).

const mockTool: Tool = {
  name: "echo",
  description: "Echo tool",
  inputSchema: { type: "object", properties: {} },
};

const taskRequiredTool: Tool = {
  name: "tasky",
  description: "Task tool",
  inputSchema: { type: "object", properties: {} },
  execution: { taskSupport: "required" },
} as Tool;

function makeHandle(result: ProbeResult): ProbeHandle {
  const cbs: ((s: number, t: number) => void)[] = [];
  return {
    done: Promise.resolve(result),
    cancel: jest.fn(),
    onProgress: (cb) => {
      cbs.push(cb);
    },
  };
}

function makeResult(overrides: Partial<ProbeResult> = {}): ProbeResult {
  return {
    toolName: "echo",
    arguments: {},
    concurrency: 2,
    iterations: 1,
    perCallTimeoutMs: 30000,
    startedAt: "2026-09-20T00:00:00.000Z",
    endedAt: "2026-09-20T00:00:01.000Z",
    completed: true,
    totalCalls: 2,
    successCount: 2,
    errorCount: 0,
    timeoutCount: 0,
    cancelledCount: 0,
    latencyMs: { p50: 100, p95: 200, p99: 200, max: 200 },
    bodyEquality: { kind: "all-equal", distinctHashes: 1 },
    failureGroups: [],
    calls: [
      {
        index: 0,
        iteration: 0,
        lane: 0,
        startedAt: "2026-09-20T00:00:00.000Z",
        endedAt: "2026-09-20T00:00:00.100Z",
        latencyMs: 100,
        status: "success",
        bodyHash: "a".repeat(64),
      },
      {
        index: 1,
        iteration: 0,
        lane: 1,
        startedAt: "2026-09-20T00:00:00.000Z",
        endedAt: "2026-09-20T00:00:00.200Z",
        latencyMs: 200,
        status: "success",
        bodyHash: "a".repeat(64),
      },
    ],
    ...overrides,
  };
}

describe("ConcurrencyProbeDialog", () => {
  const callTool = jest.fn();
  const onClose = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
  });

  const renderDialog = (props = {}) =>
    render(
      <ConcurrencyProbeDialog
        tool={mockTool}
        arguments_={{}}
        runProbeFn={jest.fn(() => makeHandle(makeResult()))}
        callTool={callTool}
        isConnected={true}
        isTaskRequired={false}
        onClose={onClose}
        {...props}
      />,
    );

  it("renders config form with defaults", () => {
    renderDialog();
    expect(screen.getByTestId("concurrency-probe-dialog")).toBeInTheDocument();
    expect(screen.getByLabelText("Parallel calls (N)")).toHaveValue(10);
    expect(screen.getByLabelText("Iterations")).toHaveValue(1);
    expect(screen.getByLabelText("Per-call timeout (ms)")).toHaveValue(30000);
    expect(screen.getByText("Total calls: 10")).toBeInTheDocument();
  });

  it("rejects out-of-range concurrency", async () => {
    renderDialog();
    fireEvent.change(screen.getByLabelText("Parallel calls (N)"), {
      target: { value: "100" },
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId("probe-start-button"));
    });
    expect(
      screen.getByText(/Concurrency must be an integer 1..64/),
    ).toBeInTheDocument();
  });

  it("disables start when total calls exceed the 640 cap", () => {
    renderDialog();
    fireEvent.change(screen.getByLabelText("Parallel calls (N)"), {
      target: { value: "64" },
    });
    fireEvent.change(screen.getByLabelText("Iterations"), {
      target: { value: "100" },
    });
    expect(screen.getByText(/exceeds the 640 cap/)).toBeInTheDocument();
    expect(screen.getByTestId("probe-start-button")).toBeDisabled();
  });

  it("blocks launch when disconnected", () => {
    renderDialog({ isConnected: false });
    expect(screen.getByText("Not connected")).toBeInTheDocument();
    expect(screen.getByTestId("probe-start-button")).toBeDisabled();
  });

  it("blocks launch for taskSupport=required tool", () => {
    renderDialog({ tool: taskRequiredTool, isTaskRequired: true });
    expect(screen.getByText("Task-augmented tool")).toBeInTheDocument();
    expect(screen.getByTestId("probe-start-button")).toBeDisabled();
  });

  it("renders result panel with counts, percentiles and equality badge", async () => {
    const handle = makeHandle(makeResult());
    const runProbeFn = jest.fn(() => handle);
    renderDialog({ runProbeFn });

    await act(async () => {
      fireEvent.click(screen.getByTestId("probe-start-button"));
      await handle.done;
    });

    expect(screen.getByTestId("probe-result-panel")).toBeInTheDocument();
    expect(screen.getByTestId("body-equality-badge")).toHaveTextContent(
      "All responses identical",
    );
    expect(screen.getByTestId("latency-percentiles")).toHaveTextContent("p50");
    expect(screen.getByTestId("latency-percentiles")).toHaveTextContent(
      "100 ms",
    );
    expect(screen.getByText(/2 success \/ 0 error/)).toBeInTheDocument();
    expect(screen.getByTestId("per-call-table")).toBeInTheDocument();
  });

  it("renders failure groups and calls onNavigateToTimeline when clickable", async () => {
    const onNavigateToTimeline = jest.fn();
    const result = makeResult({
      successCount: 1,
      errorCount: 1,
      failureGroups: [
        {
          errorMessage: "boom",
          count: 1,
          callIndices: [1],
        },
      ],
      calls: [
        makeResult().calls[0],
        {
          index: 1,
          iteration: 0,
          lane: 1,
          startedAt: "2026-09-20T00:00:00.000Z",
          endedAt: "2026-09-20T00:00:00.050Z",
          latencyMs: 50,
          status: "error",
          errorMessage: "boom",
          timelineCorrelationId: "corr-123",
        },
      ],
    });
    const handle = makeHandle(result);
    renderDialog({ runProbeFn: jest.fn(() => handle), onNavigateToTimeline });

    await act(async () => {
      fireEvent.click(screen.getByTestId("probe-start-button"));
      await handle.done;
    });

    const group = screen.getByRole("button", { name: /boom/ });
    fireEvent.click(group);
    expect(onNavigateToTimeline).toHaveBeenCalledWith("corr-123");
  });

  it("cancel button calls handle.cancel", async () => {
    // Handle that never resolves until cancel is called
    let resolveDone: (r: ProbeResult) => void = () => {};
    const handle: ProbeHandle = {
      done: new Promise<ProbeResult>((res) => {
        resolveDone = res;
      }),
      cancel: jest.fn(() => {
        resolveDone(
          makeResult({
            completed: false,
            cancelledAt: "2026-09-20T00:00:00.500Z",
            totalCalls: 0,
            successCount: 0,
            latencyMs: undefined,
            bodyEquality: { kind: "no-success", distinctHashes: 0 },
            calls: [],
          }),
        );
      }),
      onProgress: jest.fn(),
    };
    renderDialog({ runProbeFn: jest.fn(() => handle) });

    await act(async () => {
      fireEvent.click(screen.getByTestId("probe-start-button"));
    });
    expect(screen.getByTestId("probe-progress")).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByText("Cancel probe"));
      await handle.done;
    });
    expect(handle.cancel).toHaveBeenCalled();
    expect(screen.getByTestId("probe-result-panel")).toBeInTheDocument();
    expect(screen.getByText("Cancelled")).toBeInTheDocument();
  });
});
