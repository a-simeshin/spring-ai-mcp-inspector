import { TabsContent } from "@/components/ui/tabs";
import { useEffect, useState, useCallback, useRef } from "react";

// [spring-ai-mcp-inspector PATCH] New TimelineTab — MCP event timeline panel (#112).
// [spring-ai-mcp-inspector PATCH] Extended with direction filter, client name grouping,
// diagnostic badges, and detail panel (#120, #141).

type TimelineEventType =
  | "MCP_JSONRPC_REQUEST"
  | "MCP_JSONRPC_RESPONSE"
  | "MCP_JSONRPC_NOTIFICATION"
  | "MCP_STREAM_EVENT"
  | "APP_LOG";

// Mirrors io.inspector.mcp.core.timeline.TimelineEvent as serialized by the REST
// API: flat metadata plus the raw JSON-RPC frame (MCP events) or a log-fields
// object (APP_LOG events) under `payload`.
interface TimelineEvent {
  id: string;
  correlationId: string | null;
  sessionId: string | null;
  type: TimelineEventType;
  timestamp: string;
  payload: Record<string, unknown> | null;
}

const EVENT_COLORS: Record<TimelineEventType, string> = {
  MCP_JSONRPC_REQUEST: "text-blue-400 border-l-blue-500",
  MCP_JSONRPC_RESPONSE: "text-green-400 border-l-green-500",
  MCP_JSONRPC_NOTIFICATION: "text-yellow-400 border-l-yellow-500",
  MCP_STREAM_EVENT: "text-purple-400 border-l-purple-500",
  APP_LOG: "text-gray-400 border-l-gray-500",
};

const EVENT_BG: Record<TimelineEventType, string> = {
  MCP_JSONRPC_REQUEST: "bg-blue-950/30",
  MCP_JSONRPC_RESPONSE: "bg-green-950/30",
  MCP_JSONRPC_NOTIFICATION: "bg-yellow-950/30",
  MCP_STREAM_EVENT: "bg-purple-950/30",
  APP_LOG: "bg-gray-950/30",
};

// Colour for diagnostic events (client handler desyncs).
const DIAGNOSTIC_BORDER = "border-l-orange-500";
const DIAGNOSTIC_BG = "bg-orange-950/20";

function formatTimestamp(ts: string): string {
  const d = new Date(ts);
  return d.toLocaleTimeString("en-US", { hour12: false }) + "." + String(d.getMilliseconds()).padStart(3, "0");
}

// Mask sensitive values in auth-related payload fields.
// When used as a JSON.stringify replacer, non-sensitive values are returned
// as-is so nested objects/array are preserved.
function maskSensitiveValue(key: string, value: unknown): unknown {
  if (typeof value !== "string") {
    return value;
  }
  const lower = key.toLowerCase();
  if (
    lower.includes("token") ||
    lower.includes("secret") ||
    lower.includes("password") ||
    lower.includes("apikey") ||
    lower.includes("auth")
  ) {
    if (value.length <= 8) {
      return "*".repeat(value.length);
    }
    return value.substring(0, 4) + "*".repeat(value.length - 8) + value.substring(value.length - 4);
  }
  return value;
}

function isDiagnosticEvent(payload: Record<string, unknown> | null): boolean {
  return payload?.endpoint === "client-diagnostics";
}

function isClientEvent(payload: Record<string, unknown> | null): boolean {
  return payload?.endpoint === "client";
}

// Badge colours for diagnostic types.
const DIAGNOSTIC_BADGE: Record<string, string> = {
  ORPHAN_HANDLER: "bg-red-700 text-white",
  ORPHAN_CLIENT: "bg-amber-600 text-white",
  TRANSPORT_MISMATCH: "bg-orange-600 text-white",
  DUPLICATE_BINDING: "bg-purple-700 text-white",
};

function TimelineEventRow({ event }: { event: TimelineEvent }) {
  const [expanded, setExpanded] = useState(false);
  const payload = event.payload ?? {};
  const isDiag = isDiagnosticEvent(payload);
  const isClient = isClientEvent(payload);

  const type = event.type;
  let colorClass = EVENT_COLORS[type] || "text-gray-400";
  let bgClass = EVENT_BG[type] || "bg-gray-950/30";

  // Diagnostic events get a distinct border and background.
  if (isDiag) {
    colorClass = "text-orange-300";
    bgClass = DIAGNOSTIC_BG + " " + DIAGNOSTIC_BORDER;
  } else if (isClient) {
    // Client events with errors or orphan flag get a red highlight.
    if (payload.error || payload.orphan) {
      bgClass = "bg-red-950/30 border-l-red-500";
      colorClass = "text-red-300";
    }
  }

  const typeLabel = (type || "").replace("MCP_", "").replace("_", " ");
  const label =
    (typeof payload.method === "string" && payload.method) ||
    (typeof payload.message === "string" && payload.message) ||
    (typeof payload.logLevel === "string" && payload.logLevel) ||
    typeLabel;

  const desyncType = isDiag ? String(payload.desyncType ?? "") : "";
  const badgeClass = DIAGNOSTIC_BADGE[desyncType] || "";

  return (
    <div
      className={`border-l-2 pl-3 py-1.5 mb-1 rounded-r cursor-pointer hover:opacity-80 ${bgClass} ${colorClass}`}
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-center gap-2 text-xs">
        <span className="font-mono opacity-70 shrink-0 w-14">{formatTimestamp(event.timestamp)}</span>
        <span className="font-semibold shrink-0 w-20">{typeLabel}</span>
        {isDiag && badgeClass ? (
          <span className={`px-1 py-0.5 rounded text-[10px] font-bold ${badgeClass} shrink-0`}>
            {desyncType}
          </span>
        ) : null}
        {isClient ? (
          <span className="opacity-60 shrink-0 font-mono text-[10px]">
            {String(payload.direction ?? "")}
          </span>
        ) : null}
        <span className="truncate">{label}</span>
        {typeof payload.clientName === "string" ? (
          <span className="opacity-50 shrink-0 font-mono text-[10px]">
            {payload.clientName}
          </span>
        ) : null}
        {typeof payload.latencyMs === "number" ? (
          <span className="opacity-50 shrink-0 font-mono text-[10px]">
            {payload.latencyMs}ms
          </span>
        ) : null}
        {event.correlationId && (
          <span className="opacity-50 ml-auto shrink-0 font-mono text-[10px]">
            {event.correlationId.substring(0, 8)}
          </span>
        )}
      </div>
      {expanded ? (
        <div className="mt-1 text-[11px] font-mono opacity-80 overflow-x-auto whitespace-pre-wrap max-h-40 overflow-y-auto">
          {Object.keys(payload).length > 0 ? (
            <div className="mb-1">
              {JSON.stringify(payload, (key, value) => {
                // Mask sensitive fields in the detail view.
                return maskSensitiveValue(key, value);
              }, 2)}
            </div>
          ) : (
            <div className="opacity-50">no payload</div>
          )}
        </div>
      ) : null}
    </div>
  );
}

// [spring-ai-mcp-inspector PATCH] TimelineTab — scrollable timeline of MCP events.
const TimelineTab = () => {
  const [events, setEvents] = useState<TimelineEvent[]>([]);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [directionFilter, setDirectionFilter] = useState<string>("");
  const [clientNameFilter, setClientNameFilter] = useState<string>("");
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchTimeline = useCallback(async () => {
    const token = sessionStorage.getItem("inspectorConfig_v1_ephemeral");
    const headers: Record<string, string> = {};
    if (token) {
      const parsed = JSON.parse(token);
      if (parsed.MCP_PROXY_AUTH_TOKEN?.value) {
        headers["Authorization"] = "Bearer " + parsed.MCP_PROXY_AUTH_TOKEN.value;
      }
    }
    // The API is mounted under the configured spring.ai.mcp.inspector.path; the
    // server advertises the deployed prefix through the bootstrap payload.
    const bootstrap = (window as unknown as {
      __MCP_INSPECTOR_BOOTSTRAP?: { inspectorPath?: string };
    }).__MCP_INSPECTOR_BOOTSTRAP;
    const apiBase = (bootstrap?.inspectorPath || "/mcp-inspector").replace(/\/$/, "");
    try {
      const params = new URLSearchParams();
      params.set("limit", "200");
      if (directionFilter) {
        params.set("direction", directionFilter);
      }
      if (clientNameFilter) {
        params.set("clientName", clientNameFilter);
      }
      const res = await fetch(`${apiBase}/api/timeline?${params.toString()}`, { headers });
      if (res.ok) {
        const data = (await res.json()) as TimelineEvent[];
        setEvents(data);
      }
    } catch {
      // Silently ignore fetch errors
    }
  }, [directionFilter, clientNameFilter]);

  useEffect(() => {
    void fetchTimeline();
  }, [fetchTimeline]);

  useEffect(() => {
    if (autoRefresh) {
      pollingRef.current = setInterval(() => void fetchTimeline(), 3000);
    } else if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, [autoRefresh, fetchTimeline]);

  // Collect unique client names and directions from the fetched events.
  const clientNames = [
    ...new Set(
      events
        .map((e) => (e.payload && typeof e.payload.clientName === "string" ? e.payload.clientName : null))
        .filter((n): n is string => n !== null),
    ),
  ].sort();
  const directions = [
    ...new Set(
      events
        .map((e) => (e.payload && typeof e.payload.direction === "string" ? e.payload.direction : null))
        .filter((n): n is string => n !== null),
    ),
  ].sort();

  // Filter by client name and direction on the client side as well (the API
  // already filters, but this catches rapid polling updates).
  const filteredEvents = events.filter((e) => {
    const p = e.payload ?? {};
    if (directionFilter && p.direction !== directionFilter) return false;
    if (clientNameFilter && p.clientName !== clientNameFilter) return false;
    return true;
  });

  return (
    <TabsContent value="timeline" className="h-96">
      <div className="bg-gray-900 text-gray-100 p-4 rounded-lg h-full font-mono text-sm overflow-auto flex flex-col">
        <div className="flex items-center justify-between mb-2 shrink-0">
          <span className="text-xs opacity-50">
            {filteredEvents.length} event{filteredEvents.length !== 1 ? "s" : ""}
          </span>
          <div className="flex items-center gap-2">
            {directions.length > 0 ? (
              <select
                className="bg-gray-800 text-xs border border-gray-700 rounded px-1 py-0.5"
                value={directionFilter}
                onChange={(e) => setDirectionFilter(e.target.value)}
              >
                <option value="">All directions</option>
                {directions.map((d) => (
                  <option key={d} value={d}>
                    {d}
                  </option>
                ))}
              </select>
            ) : null}
            {clientNames.length > 0 ? (
              <select
                className="bg-gray-800 text-xs border border-gray-700 rounded px-1 py-0.5"
                value={clientNameFilter}
                onChange={(e) => setClientNameFilter(e.target.value)}
              >
                <option value="">All clients</option>
                {clientNames.map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            ) : null}
            <label className="flex items-center gap-1 text-xs opacity-70 cursor-pointer">
              <input
                type="checkbox"
                checked={autoRefresh}
                onChange={(e) => setAutoRefresh(e.target.checked)}
                className="w-3 h-3"
              />
              Auto-refresh (3s)
            </label>
          </div>
        </div>
        <div className="flex-1 overflow-y-auto">
          {filteredEvents.length === 0 ? (
            <div className="opacity-50 text-center mt-8">No timeline events yet</div>
          ) : (
            filteredEvents.map((event) => <TimelineEventRow key={event.id} event={event} />)
          )}
        </div>
      </div>
    </TabsContent>
  );
};

export default TimelineTab;