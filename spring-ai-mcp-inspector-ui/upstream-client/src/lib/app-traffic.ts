/* [spring-ai-mcp-inspector PATCH] App traffic log types for SEP-1865 lifecycle bridge. */

/**
 * Structured log entry for a single JSON-RPC frame exchanged between the
 * host (inspector) and the guest (MCP App sandbox iframe).
 */
export interface AppTrafficEntry {
  /** Monotonically increasing sequence number per App session. */
  seq: number;
  /** ISO-8601 timestamp. */
  timestamp: string;
  /** Direction of travel. */
  direction: "host->guest" | "guest->host";
  /** JSON-RPC message kind. */
  kind: "request" | "response" | "notification";
  /** JSON-RPC method name; absent on bare responses. */
  method?: string;
  /** Correlation id for request/response pairs. */
  id?: string | number;
  /** Request/notification params (collapsible JSON in the panel). */
  params?: unknown;
  /** Success response payload. */
  result?: unknown;
  /** Error response payload. */
  error?: { code: number; message: string };
  /** Lifecycle phase derived from the method name. */
  phase: "sandbox" | "lifecycle" | "interactive";
}

/**
 * App lifecycle states per the SEP-1865 state machine.
 *
 * Per §2.2 of the design spec:
 * idle -> loading-resource -> sandbox-waiting -> initializing -> ready -> error -> torn-down
 */
export type AppLifecycleState =
  | "idle"
  | "loading-resource"
  | "sandbox-waiting"
  | "initializing"
  | "ready"
  | "error"
  | "torn-down";

/**
 * Derive the traffic phase from a JSON-RPC method name.
 */
export function derivePhase(
  method: string | undefined,
): AppTrafficEntry["phase"] {
  if (!method) return "interactive";
  if (method.startsWith("ui/notifications/sandbox-")) return "sandbox";
  if (
    method === "ui/initialize" ||
    method === "ui/notifications/initialized" ||
    method === "ui/notifications/tool-input" ||
    method === "ui/notifications/tool-input-partial" ||
    method === "ui/notifications/tool-result" ||
    method === "ui/notifications/tool-cancelled" ||
    method === "ui/resource-teardown"
  ) {
    return "lifecycle";
  }
  return "interactive";
}
