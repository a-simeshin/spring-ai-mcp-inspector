// [spring-ai-mcp-inspector PATCH] Concurrency probe dialog + result panel (issue #237).
// Contract: docs/concurrency-probe.md section 4 + docs/concurrency-probe.schema.json.
// The dialog collects concurrency/iterations/perCallTimeoutMs, reusing the current
// tool-detail params from ToolsTab (passed as `arguments`). During the run it shows
// progress with a Cancel button; on completion it renders the ProbeResult panel.

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { AlertCircle, Loader2, XCircle } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { Tool } from "@modelcontextprotocol/sdk/types.js";
import type {
  ProbeHandle,
  ProbeResult,
  CallRecord,
  BodyEquality,
  CallToolFn,
} from "@/lib/concurrency";

// [spring-ai-mcp-inspector PATCH] Probe limits mirror the engine constants
// (lib/concurrency/probeEngine.ts). Duplicated here so the dialog can validate
// before calling runProbe without importing engine internals.
const MIN_CONCURRENCY = 1;
const MAX_CONCURRENCY = 64;
const MIN_ITERATIONS = 1;
const MAX_ITERATIONS = 100;
const MIN_TIMEOUT_MS = 1000;
const MAX_TIMEOUT_MS = 120000;
const MAX_TOTAL_CALLS = 640;

const DEFAULT_CONCURRENCY = 10;
const DEFAULT_ITERATIONS = 1;
const DEFAULT_TIMEOUT_MS = 30000;

export interface ConcurrencyProbeDialogProps {
  /** The tool the probe will call. Null hides the dialog. */
  tool: Tool | null;
  /** Current tool-detail params from ToolsTab, reused as probe arguments. */
  arguments_: Record<string, unknown>;
  /** Current tool metadata entries, merged into _meta of each call. */
  metadata?: Record<string, unknown>;
  /** Injected runProbe so tests can stub the engine. */
  runProbeFn: (
    config: {
      toolName: string;
      arguments: Record<string, unknown>;
      concurrency: number;
      iterations: number;
      perCallTimeoutMs: number;
      metadata?: Record<string, unknown>;
    },
    callTool: CallToolFn,
  ) => ProbeHandle;
  /** callTool from App.tsx (signal-aware). */
  callTool: CallToolFn;
  /** true when the MCP session is connected; the dialog refuses to launch otherwise. */
  isConnected: boolean;
  /** true when the selected tool has execution.taskSupport === "required". */
  isTaskRequired: boolean;
  /** Called when the user dismisses the dialog (probe keeps running in background). */
  onClose: () => void;
  /** Called when the user clicks a failure group to jump into Timeline. */
  onNavigateToTimeline?: (correlationId: string) => void;
}

interface ValidationErrors {
  concurrency?: string;
  iterations?: string;
  timeout?: string;
  total?: string;
}

function validateInputs(
  concurrency: string,
  iterations: string,
  timeoutMs: string,
): ValidationErrors {
  const errors: ValidationErrors = {};
  const c = Number(concurrency);
  const i = Number(iterations);
  const t = Number(timeoutMs);

  if (
    concurrency.trim() === "" ||
    !Number.isInteger(c) ||
    c < MIN_CONCURRENCY ||
    c > MAX_CONCURRENCY
  ) {
    errors.concurrency = `Concurrency must be an integer ${MIN_CONCURRENCY}..${MAX_CONCURRENCY}`;
  }
  if (
    iterations.trim() === "" ||
    !Number.isInteger(i) ||
    i < MIN_ITERATIONS ||
    i > MAX_ITERATIONS
  ) {
    errors.iterations = `Iterations must be an integer ${MIN_ITERATIONS}..${MAX_ITERATIONS}`;
  }
  if (
    timeoutMs.trim() === "" ||
    !Number.isInteger(t) ||
    t < MIN_TIMEOUT_MS ||
    t > MAX_TIMEOUT_MS
  ) {
    errors.timeout = `Timeout must be an integer ${MIN_TIMEOUT_MS}..${MAX_TIMEOUT_MS} ms`;
  }
  if (!errors.concurrency && !errors.iterations && c * i > MAX_TOTAL_CALLS) {
    errors.total = `Total calls (concurrency x iterations = ${c * i}) must not exceed ${MAX_TOTAL_CALLS}`;
  }
  return errors;
}

function bodyEqualityBadge(equality: BodyEquality): {
  text: string;
  className: string;
} {
  switch (equality.kind) {
    case "all-equal":
      return {
        text: `All ${equality.distinctHashes === 1 ? "responses" : "calls"} identical`,
        className:
          "bg-green-100 text-green-800 border-green-300 dark:bg-green-900 dark:text-green-200 dark:border-green-700",
      };
    case "mixed":
      return {
        text: `${equality.distinctHashes} distinct response bodies`,
        className:
          "bg-amber-100 text-amber-800 border-amber-300 dark:bg-amber-900 dark:text-amber-200 dark:border-amber-700",
      };
    case "single-success":
      return {
        text: "Single successful call",
        className:
          "bg-blue-100 text-blue-800 border-blue-300 dark:bg-blue-900 dark:text-blue-200 dark:border-blue-700",
      };
    case "no-success":
      return {
        text: "No successful calls",
        className:
          "bg-red-100 text-red-800 border-red-300 dark:bg-red-900 dark:text-red-200 dark:border-red-700",
      };
  }
}

function statusBadgeClass(status: CallRecord["status"]): string {
  switch (status) {
    case "success":
      return "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200";
    case "error":
      return "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200";
    case "timeout":
      return "bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200";
    case "cancelled":
      return "bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-gray-300";
  }
}

const ConcurrencyProbeDialog = ({
  tool,
  arguments_,
  metadata,
  runProbeFn,
  callTool,
  isConnected,
  isTaskRequired,
  onClose,
  onNavigateToTimeline,
}: ConcurrencyProbeDialogProps) => {
  const [concurrency, setConcurrency] = useState(String(DEFAULT_CONCURRENCY));
  const [iterations, setIterations] = useState(String(DEFAULT_ITERATIONS));
  const [timeoutMs, setTimeoutMs] = useState(String(DEFAULT_TIMEOUT_MS));
  const [validationErrors, setValidationErrors] = useState<ValidationErrors>(
    {},
  );
  const [probeHandle, setProbeHandle] = useState<ProbeHandle | null>(null);
  const [progress, setProgress] = useState<{ settled: number; total: number }>({
    settled: 0,
    total: 0,
  });
  const [result, setResult] = useState<ProbeResult | null>(null);
  const [launchError, setLaunchError] = useState<string | null>(null);

  // Keep the handle in a ref so the cleanup function always sees the latest value.
  const handleRef = useRef<ProbeHandle | null>(null);
  handleRef.current = probeHandle;

  // Reset run state when the dialog targets a different tool.
  const toolName = tool?.name;
  useEffect(() => {
    setValidationErrors({});
    setProbeHandle(null);
    setProgress({ settled: 0, total: 0 });
    setResult(null);
    setLaunchError(null);
  }, [toolName]);

  // Cancel in-flight probe when the dialog unmounts.
  useEffect(() => {
    return () => {
      handleRef.current?.cancel();
    };
  }, []);

  const totalCalls =
    Number(concurrency) > 0 && Number(iterations) > 0
      ? Number(concurrency) * Number(iterations)
      : 0;

  const runState: "config" | "running" | "done" = result
    ? "done"
    : probeHandle
      ? "running"
      : "config";

  const startProbe = () => {
    if (!tool) return;
    const errors = validateInputs(concurrency, iterations, timeoutMs);
    setValidationErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setLaunchError(null);
    setResult(null);
    setProgress({ settled: 0, total: Number(concurrency) * Number(iterations) });

    try {
      const handle = runProbeFn(
        {
          toolName: tool.name,
          arguments: arguments_,
          concurrency: Number(concurrency),
          iterations: Number(iterations),
          perCallTimeoutMs: Number(timeoutMs),
          metadata,
        },
        callTool,
      );
      handle.onProgress((settled, total) => {
        setProgress({ settled, total });
      });
      setProbeHandle(handle);
      void handle.done.then((probeResult) => {
        setResult(probeResult);
        setProbeHandle(null);
      });
    } catch (e) {
      setLaunchError(e instanceof Error ? e.message : String(e));
    }
  };

  const cancelProbe = () => {
    probeHandle?.cancel();
  };

  const validationErrorList = Object.values(validationErrors).filter(Boolean);

  return (
    <Dialog open={tool !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        className="max-w-2xl max-h-[85vh] overflow-y-auto"
        data-testid="concurrency-probe-dialog"
      >
        <DialogHeader>
          <DialogTitle>
            Concurrency probe: {tool ? tool.title || tool.name : ""}
          </DialogTitle>
          <DialogDescription>
            Fire N parallel tools/call requests and analyze latencies, errors,
            and response-body equality.
          </DialogDescription>
        </DialogHeader>

        {!isConnected && (
          <Alert variant="destructive">
            <AlertCircle className="h-4 w-4" />
            <AlertTitle>Not connected</AlertTitle>
            <AlertDescription>
              Connect to an MCP server before running a probe.
            </AlertDescription>
          </Alert>
        )}

        {isTaskRequired && (
          <Alert variant="destructive">
            <AlertCircle className="h-4 w-4" />
            <AlertTitle>Task-augmented tool</AlertTitle>
            <AlertDescription>
              This tool requires task execution. Task-augmented calls poll
              asynchronously and are out of scope for the concurrency probe.
            </AlertDescription>
          </Alert>
        )}

        {launchError && (
          <Alert variant="destructive">
            <AlertCircle className="h-4 w-4" />
            <AlertTitle>Launch failed</AlertTitle>
            <AlertDescription className="break-all">
              {launchError}
            </AlertDescription>
          </Alert>
        )}

        {runState === "config" && (
          <div
            className="grid grid-cols-3 gap-4"
            data-testid="probe-config-form"
          >
            <div>
              <Label htmlFor="probe-concurrency">Parallel calls (N)</Label>
              <Input
                id="probe-concurrency"
                type="number"
                min={MIN_CONCURRENCY}
                max={MAX_CONCURRENCY}
                value={concurrency}
                onChange={(e) => setConcurrency(e.target.value)}
                className="mt-1"
              />
            </div>
            <div>
              <Label htmlFor="probe-iterations">Iterations</Label>
              <Input
                id="probe-iterations"
                type="number"
                min={MIN_ITERATIONS}
                max={MAX_ITERATIONS}
                value={iterations}
                onChange={(e) => setIterations(e.target.value)}
                className="mt-1"
              />
            </div>
            <div>
              <Label htmlFor="probe-timeout">Per-call timeout (ms)</Label>
              <Input
                id="probe-timeout"
                type="number"
                min={MIN_TIMEOUT_MS}
                max={MAX_TIMEOUT_MS}
                step={1000}
                value={timeoutMs}
                onChange={(e) => setTimeoutMs(e.target.value)}
                className="mt-1"
              />
            </div>
            <div className="col-span-3 text-sm text-muted-foreground">
              Total calls: {totalCalls}
              {totalCalls > MAX_TOTAL_CALLS && (
                <span className="text-red-600 dark:text-red-400 ml-2">
                  exceeds the {MAX_TOTAL_CALLS} cap
                </span>
              )}
            </div>
            {validationErrorList.length > 0 && (
              <div className="col-span-3 space-y-1">
                {validationErrorList.map((msg) => (
                  <p
                    key={msg}
                    className="text-xs text-red-600 dark:text-red-400"
                  >
                    {msg}
                  </p>
                ))}
              </div>
            )}
          </div>
        )}

        {runState === "running" && (
          <div className="space-y-3" data-testid="probe-progress">
            <div className="flex items-center gap-2 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" />
              <span>
                Running: {progress.settled} / {progress.total} calls settled
              </span>
            </div>
            <div
              className="w-full bg-gray-200 dark:bg-gray-700 rounded h-2"
              role="progressbar"
              aria-valuenow={progress.settled}
              aria-valuemin={0}
              aria-valuemax={progress.total}
            >
              <div
                className="bg-blue-600 h-2 rounded transition-all"
                style={{
                  width:
                    progress.total > 0
                      ? `${(progress.settled / progress.total) * 100}%`
                      : "0%",
                }}
              />
            </div>
            <Button variant="outline" onClick={cancelProbe}>
              <XCircle className="h-4 w-4 mr-2" />
              Cancel probe
            </Button>
          </div>
        )}

        {runState === "done" && result && (
          <ProbeResultPanel
            result={result}
            onNavigateToTimeline={onNavigateToTimeline}
          />
        )}

        <div className="flex justify-end gap-2 pt-2">
          {runState === "config" && (
            <Button
              onClick={startProbe}
              disabled={
                !isConnected || isTaskRequired || totalCalls > MAX_TOTAL_CALLS
              }
              data-testid="probe-start-button"
            >
              Start probe
            </Button>
          )}
          {runState === "done" && (
            <Button
              variant="outline"
              onClick={() => {
                setResult(null);
                setProbeHandle(null);
                setProgress({ settled: 0, total: 0 });
              }}
            >
              Configure new run
            </Button>
          )}
          <Button variant="outline" onClick={onClose}>
            {runState === "running" ? "Run in background" : "Close"}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
};

interface ProbeResultPanelProps {
  result: ProbeResult;
  onNavigateToTimeline?: (correlationId: string) => void;
}

function ProbeResultPanel({
  result,
  onNavigateToTimeline,
}: ProbeResultPanelProps) {
  const badge = bodyEqualityBadge(result.bodyEquality);
  return (
    <div className="space-y-4" data-testid="probe-result-panel">
      {!result.completed && (
        <Alert>
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>
            {result.cancelledAt ? "Cancelled" : "Aborted"}
          </AlertTitle>
          <AlertDescription>
            {result.abortedReason
              ? `Transport-level abort: ${result.abortedReason}`
              : "Partial result: the run was cancelled before completion."}
          </AlertDescription>
        </Alert>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <span
          className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border ${badge.className}`}
          data-testid="body-equality-badge"
        >
          {badge.text}
        </span>
        <span className="text-sm">
          {result.successCount} success / {result.errorCount} error /{" "}
          {result.timeoutCount} timeout / {result.cancelledCount} cancelled of{" "}
          {result.totalCalls}
        </span>
      </div>

      {result.latencyMs && (
        <div
          className="grid grid-cols-4 gap-2 text-center"
          data-testid="latency-percentiles"
        >
          {(
            [
              ["p50", result.latencyMs.p50],
              ["p95", result.latencyMs.p95],
              ["p99", result.latencyMs.p99],
              ["max", result.latencyMs.max],
            ] as const
          ).map(([label, value]) => (
            <div
              key={label}
              className="bg-gray-50 dark:bg-gray-900 rounded p-2"
            >
              <div className="text-xs text-muted-foreground">{label}</div>
              <div className="font-mono text-sm">{value} ms</div>
            </div>
          ))}
        </div>
      )}

      {result.failureGroups.length > 0 && (
        <div data-testid="failure-groups">
          <h4 className="text-sm font-semibold mb-2">Failures</h4>
          <div className="space-y-1">
            {result.failureGroups.map((group) => {
              const firstCall = result.calls.find(
                (c) => c.index === group.callIndices[0],
              );
              const correlationId = firstCall?.timelineCorrelationId;
              const clickable = Boolean(
                correlationId && onNavigateToTimeline,
              );
              return (
                <button
                  key={group.errorMessage}
                  type="button"
                  className={`w-full text-left px-2 py-1.5 rounded border text-sm ${
                    clickable
                      ? "hover:bg-gray-100 dark:hover:bg-secondary cursor-pointer border-gray-200 dark:border-border"
                      : "border-gray-200 dark:border-border opacity-80 cursor-default"
                  }`}
                  disabled={!clickable}
                  onClick={() => {
                    if (correlationId && onNavigateToTimeline) {
                      onNavigateToTimeline(correlationId);
                    }
                  }}
                >
                  <span className="font-mono text-xs mr-2">
                    x{group.count}
                  </span>
                  <span className="break-all">{group.errorMessage}</span>
                  {clickable && (
                    <span className="text-xs text-muted-foreground ml-2">
                      (show in Timeline)
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}

      <div data-testid="per-call-table">
        <h4 className="text-sm font-semibold mb-2">
          Per-call records ({result.calls.length})
        </h4>
        <div className="max-h-64 overflow-y-auto border border-gray-200 dark:border-border rounded">
          <table className="w-full text-xs">
            <thead className="bg-gray-50 dark:bg-gray-900 sticky top-0">
              <tr>
                <th className="text-left px-2 py-1">#</th>
                <th className="text-left px-2 py-1">iter</th>
                <th className="text-left px-2 py-1">latency</th>
                <th className="text-left px-2 py-1">status</th>
                <th className="text-left px-2 py-1">error</th>
              </tr>
            </thead>
            <tbody>
              {result.calls.map((call) => (
                <tr
                  key={call.index}
                  className="border-t border-gray-100 dark:border-gray-800"
                >
                  <td className="px-2 py-1 font-mono">{call.index}</td>
                  <td className="px-2 py-1">{call.iteration}</td>
                  <td className="px-2 py-1 font-mono">{call.latencyMs} ms</td>
                  <td className="px-2 py-1">
                    <span
                      className={`inline-flex px-1.5 py-0.5 rounded text-[10px] ${statusBadgeClass(call.status)}`}
                    >
                      {call.status}
                    </span>
                  </td>
                  <td className="px-2 py-1 break-all max-w-48">
                    {call.errorMessage ?? ""}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

export default ConcurrencyProbeDialog;
