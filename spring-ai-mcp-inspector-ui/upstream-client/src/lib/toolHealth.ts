// [spring-ai-mcp-inspector PATCH] Tool Health aggregates (#370): the wire
// contract of the backend endpoint GET ${path}/api/timeline/tool-health,
// which serves io.inspector.mcp.core.timeline.ToolHealthStat records computed
// by ToolHealthAggregator over the timeline ring buffer. Plus the client-side
// click-through filter: narrowing the timeline list to one tool's calls, the
// same purely client-side pattern as the side filter (#120), the server
// exposes no tool parameter.

// Mirrors io.inspector.mcp.core.timeline.ToolHealthStat as serialized by the
// REST API. Existing fields are the backend contract and must not change.
export interface ToolHealthStat {
  method: string;
  calls: number;
  errors: number;
  noResponse: number;
  errorRate: number;
  p50Ms: number;
  p95Ms: number;
  lastIsError: boolean | null;
  lastCallAt: number;
}

// Structural view of a timeline event sufficient for the tool filter; the
// TimelineTab event type is assignable to this shape.
export interface ToolTimelineEvent {
  id: string;
  correlationId: string | null;
  type: string;
  timestamp: string;
  payload: Record<string, unknown> | null;
}

// Sortable columns of the Tool Health table. The default view sorts by error
// rate descending: the "what is failing" ordering the issue asks for.
export type ToolHealthSortKey = "errorRate" | "p50Ms" | "p95Ms";

export type ToolHealthSortDir = "asc" | "desc";

// Returns the tool name carried by a tools/call request payload
// ({ method: "tools/call", params: { name: ... } }), or null for any other
// frame. Defensively narrow: a malformed node yields null, not a throw.
export function toolNameOfRequest(payload: Record<string, unknown> | null): string | null {
  if (!payload || payload["method"] !== "tools/call") return null;
  const params = payload["params"];
  if (!params || typeof params !== "object" || Array.isArray(params)) return null;
  const name = (params as Record<string, unknown>)["name"];
  return typeof name === "string" ? name : null;
}

// Narrows the loaded timeline window to one tool's calls: the requests that
// name the tool plus the responses correlated to those requests by
// correlationId. Everything else (other tools, notifications, logs) drops out.
export function filterEventsByTool<T extends ToolTimelineEvent>(events: T[], tool: string): T[] {
  const correlationIds = new Set<string>();
  for (const event of events) {
    if (
      event.type === "MCP_JSONRPC_REQUEST" &&
      event.correlationId !== null &&
      toolNameOfRequest(event.payload) === tool
    ) {
      correlationIds.add(event.correlationId);
    }
  }
  return events.filter(
    (event) =>
      (event.type === "MCP_JSONRPC_REQUEST" && toolNameOfRequest(event.payload) === tool) ||
      (event.type === "MCP_JSONRPC_RESPONSE" &&
        event.correlationId !== null &&
        correlationIds.has(event.correlationId)),
  );
}

// Sorts a copy of the stats by the chosen numeric column, ties broken by tool
// name so the table never flickers between equal rows.
export function sortToolHealth(
  stats: ToolHealthStat[],
  key: ToolHealthSortKey,
  dir: ToolHealthSortDir,
): ToolHealthStat[] {
  const factor = dir === "asc" ? 1 : -1;
  return [...stats].sort(
    (a, b) => factor * (a[key] - b[key]) || a.method.localeCompare(b.method),
  );
}
