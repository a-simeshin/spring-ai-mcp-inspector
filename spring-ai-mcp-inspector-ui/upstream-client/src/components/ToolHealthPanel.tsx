import { useEffect, useMemo, useRef, useState } from "react";

// [spring-ai-mcp-inspector PATCH] Tool Health panel (#370): per-tool table of
// calls, errors, error rate and p50/p95 latency over the recorded timeline
// traffic, served by GET ${path}/api/timeline/tool-health
// (ToolHealthAggregator over the ring buffer, no new collection layer).
// Rows are sortable by error rate and latency; clicking a row filters the
// Timeline to that tool's calls. No upstream equivalent exists; this is a
// Spring AI MCP Inspector extension.
import {
  sortToolHealth,
  ToolHealthSortDir,
  ToolHealthSortKey,
  ToolHealthStat,
} from "@/lib/toolHealth";

const POLL_MS = 3000;

// Column definitions drive both the rendering and the sort header anchors.
const COLUMNS: { key: ToolHealthSortKey; label: string; testid: string }[] = [
  { key: "errorRate", label: "error rate", testid: "tool-health-sort-error-rate" },
  { key: "p50Ms", label: "p50", testid: "tool-health-sort-p50" },
  { key: "p95Ms", label: "p95", testid: "tool-health-sort-p95" },
];

export const ToolHealthPanel = ({
  className,
  onFilterTool,
}: {
  className?: string;
  onFilterTool: (tool: string) => void;
}) => {
  // The endpoint exists only while the timeline subsystem is enabled (the
  // controller/handler bean is conditional on the same flag the whole timeline
  // API uses). Mirror the ClientsTab disabled handling: a 404 stops the poll
  // and renders an explicit disabled note instead of probing forever.
  const [stats, setStats] = useState<ToolHealthStat[]>([]);
  const [disabled, setDisabled] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [sort, setSort] = useState<{ key: ToolHealthSortKey; dir: ToolHealthSortDir }>({
    key: "errorRate",
    dir: "desc",
  });
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchToolHealth = async () => {
    // Same bootstrap contract as the timeline poll: the API is mounted under
    // the configured spring.ai.mcp.inspector.path and the guard reads the raw
    // X-MCP-Inspector-Auth header from the bootstrap token.
    const bootstrap = (window as unknown as {
      __MCP_INSPECTOR_BOOTSTRAP?: {
        inspectorPath?: string;
        authToken?: string;
      };
    }).__MCP_INSPECTOR_BOOTSTRAP;
    const headers: Record<string, string> = {};
    if (bootstrap?.authToken) {
      headers["X-MCP-Inspector-Auth"] = bootstrap.authToken;
    }
    const apiBase = (bootstrap?.inspectorPath || "/mcp-inspector").replace(/\/$/, "");
    try {
      const res = await fetch(`${apiBase}/api/timeline/tool-health`, { headers });
      if (res.ok) {
        const data = (await res.json()) as ToolHealthStat[];
        setStats(data);
        setFetchError(null);
      } else if (res.status === 404) {
        // No timeline bean: the tool health endpoint will not appear later.
        if (pollingRef.current) {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
        }
        setStats([]);
        setDisabled(true);
        setFetchError(null);
      } else {
        setFetchError(`Tool health request failed: HTTP ${res.status}`);
      }
    } catch {
      setFetchError("Tool health request failed: network error");
    }
  };

  useEffect(() => {
    const poll = () => void fetchToolHealth();
    poll();
    pollingRef.current = setInterval(poll, POLL_MS);
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, []);

  const sorted = useMemo(() => sortToolHealth(stats, sort.key, sort.dir), [stats, sort]);

  const onSortClick = (key: ToolHealthSortKey) => {
    setSort((prev) =>
      prev.key === key ? { key, dir: prev.dir === "desc" ? "asc" : "desc" } : { key, dir: "desc" },
    );
  };

  const formatRate = (rate: number) => `${Math.round(rate * 100)}%`;

  const lastFlag = (stat: ToolHealthStat) =>
    stat.lastIsError === null ? "-" : stat.lastIsError ? "error" : "ok";

  return (
    <div
      data-testid="tool-health-panel"
      className={`border border-gray-700 rounded bg-gray-950/30 text-xs ${className ?? ""}`}
    >
      <div className="px-2 py-1.5 text-[11px] opacity-50 shrink-0">
        Tool Health (per-tool aggregates over the recorded traffic; click a row to filter the
        timeline)
      </div>
      {fetchError ? (
        <div
          role="alert"
          data-testid="tool-health-error"
          className="border-l-4 border-red-500 bg-red-950/30 text-red-400 px-3 py-2 m-2"
        >
          {fetchError}
        </div>
      ) : disabled ? (
        <div data-testid="tool-health-disabled" className="opacity-50 text-center py-3">
          Tool health is unavailable (timeline disabled)
        </div>
      ) : stats.length === 0 ? (
        <div data-testid="tool-health-empty" className="opacity-50 text-center py-3">
          No tool calls recorded yet
        </div>
      ) : (
        <div className="max-h-40 overflow-y-auto">
          <table data-testid="tool-health-table" className="w-full text-[11px] font-mono">
            <thead className="opacity-70">
              <tr className="text-left">
                <th className="px-2 py-1 font-normal">tool</th>
                <th className="px-2 py-1 font-normal">calls</th>
                <th className="px-2 py-1 font-normal">errors</th>
                {COLUMNS.map((column) => (
                  <th key={column.key} className="px-2 py-1 font-normal">
                    <button
                      type="button"
                      data-testid={column.testid}
                      className="cursor-pointer hover:opacity-100 opacity-80"
                      title={`Sort by ${column.label}`}
                      onClick={() => onSortClick(column.key)}
                    >
                      {column.label} {sort.key === column.key ? (sort.dir === "desc" ? "▼" : "▲") : ""}
                    </button>
                  </th>
                ))}
                <th className="px-2 py-1 font-normal">last</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((stat) => (
                <tr
                  key={stat.method}
                  data-testid={`tool-health-row-${stat.method}`}
                  className="cursor-pointer hover:bg-gray-800/60 border-t border-gray-800"
                  title={`Filter the timeline to ${stat.method}'s calls`}
                  onClick={() => onFilterTool(stat.method)}
                >
                  <td className="px-2 py-1 font-semibold">{stat.method}</td>
                  <td className="px-2 py-1">{stat.calls}</td>
                  <td
                    className={`px-2 py-1 ${stat.errors > 0 ? "text-red-400" : ""}`}
                    title={
                      stat.noResponse > 0
                        ? `${stat.noResponse} of them got no response in the recorded window`
                        : undefined
                    }
                  >
                    {stat.errors}
                  </td>
                  <td className={`px-2 py-1 ${stat.errorRate > 0 ? "text-red-400" : ""}`}>
                    {formatRate(stat.errorRate)}
                  </td>
                  <td className="px-2 py-1">{stat.p50Ms} ms</td>
                  <td className="px-2 py-1">{stat.p95Ms} ms</td>
                  <td
                    className={`px-2 py-1 ${stat.lastIsError ? "text-red-400" : "opacity-70"}`}
                  >
                    {lastFlag(stat)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default ToolHealthPanel;
