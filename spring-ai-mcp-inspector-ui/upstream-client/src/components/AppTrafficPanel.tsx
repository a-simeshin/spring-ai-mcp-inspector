import { useState, useCallback, useMemo } from "react";
import {
  type AppTrafficEntry,
  type AppLifecycleState,
} from "@/lib/app-traffic";
import { Copy, CheckCheck, ChevronDown, ChevronRight } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import JsonView from "@/components/JsonView";
import useCopy from "@/lib/hooks/useCopy";

/* [spring-ai-mcp-inspector PATCH] AppTrafficPanel for SEP-1865 traffic side panel. */

interface AppTrafficPanelProps {
  entries: AppTrafficEntry[];
  lifecycleState: AppLifecycleState;
  onClear?: () => void;
}

/**
 * Side panel for the Apps tab showing lifecycle status, JSON-RPC traffic log,
 * and guest console/error output forwarded through the bridge.
 */
const AppTrafficPanel = ({
  entries,
  lifecycleState,
  onClear,
}: AppTrafficPanelProps) => {
  const [filter, setFilter] = useState("");
  const [isLogsExpanded, setIsLogsExpanded] = useState(true);

  const { copied, setCopied } = useCopy();

  const filtered = useMemo(
    () =>
      filter
        ? entries.filter(
            (e) =>
              (e.method ?? "").toLowerCase().includes(filter.toLowerCase()) ||
              e.direction.includes(filter.toLowerCase()),
          )
        : entries,
    [entries, filter],
  );

  const lifecycleColor = useMemo(() => {
    switch (lifecycleState) {
      case "ready":
        return "bg-green-500";
      case "error":
      case "torn-down":
        return "bg-red-500";
      case "initializing":
      case "sandbox-waiting":
        return "bg-yellow-500";
      case "idle":
      case "loading-resource":
      default:
        return "bg-gray-400";
    }
  }, [lifecycleState]);

  const handleCopyAll = useCallback(() => {
    const json = JSON.stringify(
      filtered.map((e) => ({
        seq: e.seq,
        timestamp: e.timestamp,
        direction: e.direction,
        kind: e.kind,
        method: e.method,
        id: e.id,
        params: e.params,
        result: e.result,
        error: e.error,
      })),
      null,
      2,
    );
    navigator.clipboard.writeText(json).catch(() => {});
    setCopied(true);
  }, [filtered, setCopied]);

  return (
    <div className="flex flex-col h-full border-l border-border bg-card text-sm">
      {/* Header */}
      <div className="p-3 border-b border-border space-y-2">
        <div className="flex items-center justify-between">
          <h3 className="font-semibold text-sm">App Panel</h3>
          {onClear && (
            <Button
              variant="ghost"
              size="sm"
              className="h-6 px-2 text-xs"
              onClick={onClear}
            >
              Clear
            </Button>
          )}
        </div>

        {/* Lifecycle status */}
        <div className="flex items-center gap-2 text-xs">
          <span
            className={`inline-block w-2 h-2 rounded-full ${lifecycleColor}`}
          />
          <span className="font-medium capitalize">{lifecycleState}</span>
        </div>

        {/* Copy all / Filter */}
        <div className="flex items-center gap-2">
          <Input
            placeholder="Filter by method..."
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            className="h-7 text-xs flex-1"
          />
          <Button
            variant="ghost"
            size="sm"
            className="h-7 w-7 p-0"
            onClick={handleCopyAll}
            aria-label="Copy all as JSON"
            title="Copy all as JSON"
          >
            {copied ? (
              <CheckCheck className="size-3.5 text-green-600" />
            ) : (
              <Copy className="size-3.5" />
            )}
          </Button>
        </div>
      </div>

      {/* Traffic log */}
      <div className="flex-1 overflow-y-auto">
        {filtered.length === 0 ? (
          <div className="p-4 text-xs text-muted-foreground text-center">
            {filter ? "No matching messages" : "No traffic yet"}
          </div>
        ) : (
          filtered.map((entry) => (
            <TrafficEntryRow key={entry.seq} entry={entry} />
          ))
        )}
      </div>

      {/* Console / logs section */}
      <div className="border-t border-border">
        <button
          type="button"
          className="flex items-center gap-1 w-full p-2 text-xs font-medium text-left hover:bg-muted/50"
          onClick={() => setIsLogsExpanded(!isLogsExpanded)}
        >
          {isLogsExpanded ? (
            <ChevronDown className="size-3" />
          ) : (
            <ChevronRight className="size-3" />
          )}
          Guest Logs
        </button>
        {isLogsExpanded && (
          <div className="max-h-28 overflow-y-auto p-2 bg-gray-900 text-gray-100 font-mono text-[10px] leading-relaxed">
            <span className="opacity-50">
              Console output forwarded through the bridge will appear here.
            </span>
          </div>
        )}
      </div>
    </div>
  );
};

/* ───── One traffic entry row ───── */

interface TrafficEntryRowProps {
  entry: AppTrafficEntry;
}

const TrafficEntryRow = ({ entry }: TrafficEntryRowProps) => {
  const [expanded, setExpanded] = useState(false);
  const { copied, setCopied } = useCopy();

  const hasBody =
    entry.params !== undefined ||
    entry.result !== undefined ||
    entry.error !== undefined;

  const handleCopy = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      const json = JSON.stringify(
        {
          seq: entry.seq,
          timestamp: entry.timestamp,
          direction: entry.direction,
          kind: entry.kind,
          method: entry.method,
          id: entry.id,
          params: entry.params,
          result: entry.result,
          error: entry.error,
        },
        null,
        2,
      );
      navigator.clipboard.writeText(json).catch(() => {});
      setCopied(true);
    },
    [entry, setCopied],
  );

  const dirColor =
    entry.direction === "host->guest"
      ? "text-blue-600"
      : "text-orange-600";

  const time = useMemo(() => {
    try {
      const d = new Date(entry.timestamp);
      return d.toLocaleTimeString();
    } catch {
      return entry.timestamp;
    }
  }, [entry.timestamp]);

  return (
    <div
      className="border-b border-border/50 hover:bg-muted/30 cursor-pointer"
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-start gap-1 p-2 text-[11px]">
        {/* Direction arrow */}
        <span
          className={`font-mono font-bold shrink-0 ${dirColor} ${
            entry.direction === "host->guest"
              ? "after:content-['→']"
              : "after:content-['←']"
          }`}
        >
          {entry.direction === "host->guest" ? "→" : "←"}
        </span>

        {/* Method / kind */}
        <span className="font-medium truncate flex-1 min-w-0">
          {entry.method ?? entry.kind}
        </span>

        {/* Expand indicator */}
        {hasBody && (
          <span className="shrink-0 text-muted-foreground">
            {expanded ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
          </span>
        )}

        {/* Copy button */}
        <button
          type="button"
          className="shrink-0 p-0.5 opacity-0 hover:opacity-100 focus:opacity-100"
          onClick={handleCopy}
          aria-label="Copy message as JSON"
          title="Copy message as JSON"
        >
          {copied ? (
            <CheckCheck className="size-3 text-green-600" />
          ) : (
            <Copy className="size-3" />
          )}
        </button>

        {/* Timestamp */}
        <span className="shrink-0 text-[10px] text-muted-foreground">
          {time}
        </span>
      </div>

      {/* Expanded params / result / error */}
      {expanded && hasBody && (
        <div className="px-4 pb-2">
          {entry.params !== undefined && (
            <div className="mb-1">
              <span className="text-[10px] font-medium text-muted-foreground">
                params
              </span>
              <JsonView
                data={entry.params}
                initialExpandDepth={0}
                withCopyButton={false}
              />
            </div>
          )}
          {entry.result !== undefined && (
            <div className="mb-1">
              <span className="text-[10px] font-medium text-muted-foreground">
                result
              </span>
              <JsonView
                data={entry.result}
                initialExpandDepth={0}
                withCopyButton={false}
              />
            </div>
          )}
          {entry.error !== undefined && (
            <div className="mb-1">
              <span className="text-[10px] font-medium text-red-500">error</span>
              <JsonView
                data={entry.error}
                initialExpandDepth={0}
                withCopyButton={false}
                isError
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default AppTrafficPanel;