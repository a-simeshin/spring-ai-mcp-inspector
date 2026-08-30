import { TabsContent } from "@/components/ui/tabs";
import { useEffect, useState, useCallback, useRef } from "react";

// [spring-ai-mcp-inspector PATCH] New TimelineTab — MCP event timeline panel (#112).

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

function formatTimestamp(ts: string): string {
  const d = new Date(ts);
  return d.toLocaleTimeString("en-US", { hour12: false }) + "." + String(d.getMilliseconds()).padStart(3, "0");
}

function TimelineEventRow({ event }: { event: TimelineEvent }) {
  const [expanded, setExpanded] = useState(false);
  const type = event.type;
  const colorClass = EVENT_COLORS[type] || "text-gray-400";
  const bgClass = EVENT_BG[type] || "bg-gray-950/30";

  const payload = event.payload ?? {};
  const typeLabel = (type || "").replace("MCP_", "").replace("_", " ");
  const label =
    (typeof payload.method === "string" && payload.method) ||
    (typeof payload.message === "string" && payload.message) ||
    (typeof payload.logLevel === "string" && payload.logLevel) ||
    typeLabel;

  return (
    <div
      className={`border-l-2 pl-3 py-1.5 mb-1 rounded-r cursor-pointer hover:opacity-80 ${bgClass} ${colorClass}`}
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-center gap-2 text-xs">
        <span className="font-mono opacity-70 shrink-0 w-14">{formatTimestamp(event.timestamp)}</span>
        <span className="font-semibold shrink-0 w-20">{typeLabel}</span>
        <span className="truncate">{label}</span>
        {event.correlationId && (
          <span className="opacity-50 ml-auto shrink-0 font-mono text-[10px]">
            {event.correlationId.substring(0, 8)}
          </span>
        )}
      </div>
      {expanded ? (
        <div className="mt-1 text-[11px] font-mono opacity-80 overflow-x-auto whitespace-pre-wrap max-h-40 overflow-y-auto">
          {Object.keys(payload).length > 0 ? (
            <div className="mb-1">{JSON.stringify(payload, null, 2)}</div>
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
      const res = await fetch(`${apiBase}/api/timeline?limit=200`, { headers });
      if (res.ok) {
        const data = (await res.json()) as TimelineEvent[];
        setEvents(data);
      }
    } catch {
      // Silently ignore fetch errors
    }
  }, []);

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

  return (
    <TabsContent value="timeline" className="h-96">
      <div className="bg-gray-900 text-gray-100 p-4 rounded-lg h-full font-mono text-sm overflow-auto flex flex-col">
        <div className="flex items-center justify-between mb-2 shrink-0">
          <span className="text-xs opacity-50">
            {events.length} event{events.length !== 1 ? "s" : ""}
          </span>
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
        <div className="flex-1 overflow-y-auto">
          {events.length === 0 ? (
            <div className="opacity-50 text-center mt-8">No timeline events yet</div>
          ) : (
            events.map((event) => <TimelineEventRow key={event.id} event={event} />)
          )}
        </div>
      </div>
    </TabsContent>
  );
};

export default TimelineTab;