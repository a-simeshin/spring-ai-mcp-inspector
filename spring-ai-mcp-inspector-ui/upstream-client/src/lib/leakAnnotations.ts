import { useEffect, useState } from "react";

// [spring-ai-mcp-inspector PATCH] Exception-leak annotation plumbing (#251):
// shared parsing of the `_leak` timeline enrichment (spec D5, produced by the
// server-side ExceptionLeakDetector), a client-side join of an error
// CallToolResult with its annotated timeline response event (by the JSON-RPC
// request id the transport observed on the wire, not by payload text), and a
// small navigation broker that lets the Tool Result pane ask the Timeline tab
// to scroll to the matched log entry.

export type LeakMatchType = "EXACT" | "NORMALIZED" | "PARTIAL";

// Wire shape of the `_leak` node serialized by ExceptionLeakDetector#toLeakNode.
export interface LeakInfo {
  matchType: LeakMatchType;
  verbatim: boolean;
  logEventId: string;
  logTimestamp: string;
  logMessage: string;
  logThrowableClass: string | null;
  multipleCandidates: boolean;
  droppedCandidates: number;
}

const MATCH_TYPES: LeakMatchType[] = ["EXACT", "NORMALIZED", "PARTIAL"];

// Minimal shape of a timeline event as returned by /api/timeline; the join only
// needs the discriminator and the payload.
export interface TimelineLeakEvent {
  id: string;
  type: string;
  payload: Record<string, unknown> | null;
}

/**
 * Parses the `_leak` enrichment from a timeline event payload (spec D5).
 * Returns null for absent or malformed annotations: an old backend never sends
 * the key, and a corrupted one must not break rendering (zero visual noise).
 */
export function parseLeakAnnotation(
  payload: Record<string, unknown> | null | undefined,
): LeakInfo | null {
  if (!payload) {
    return null;
  }
  const raw = payload["_leak"];
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const obj = raw as Record<string, unknown>;
  const matchType = obj["matchType"];
  if (
    typeof matchType !== "string" ||
    !MATCH_TYPES.includes(matchType as LeakMatchType)
  ) {
    return null;
  }
  const logEventId = obj["logEventId"];
  const logTimestamp = obj["logTimestamp"];
  const logMessage = obj["logMessage"];
  if (
    typeof logEventId !== "string" ||
    typeof logTimestamp !== "string" ||
    typeof logMessage !== "string"
  ) {
    return null;
  }
  const throwableClass = obj["logThrowableClass"];
  const dropped = obj["droppedCandidates"];
  return {
    matchType: matchType as LeakMatchType,
    verbatim: obj["verbatim"] === true,
    logEventId,
    logTimestamp,
    logMessage,
    logThrowableClass:
      typeof throwableClass === "string" ? throwableClass : null,
    multipleCandidates: obj["multipleCandidates"] === true,
    droppedCandidates: typeof dropped === "number" ? dropped : 0,
  };
}

/**
 * Joined text of the `type=text` content blocks of a CallToolResult. Eligibility
 * gate for the leak note: the note quotes leaked text, so a result without any
 * text content can never carry one. Accepts both a client-side
 * CompatibilityCallToolResult and the `payload.result` node of a timeline
 * response frame. Returns null when there is no text at all.
 */
export function toolResultErrorText(result: unknown): string | null {
  if (!result || typeof result !== "object") {
    return null;
  }
  const obj = result as Record<string, unknown>;
  const content = obj["content"];
  if (!Array.isArray(content)) {
    return null;
  }
  const parts: string[] = [];
  for (const block of content) {
    if (block && typeof block === "object") {
      const b = block as Record<string, unknown>;
      if (b["type"] === "text" && typeof b["text"] === "string") {
        parts.push(b["text"]);
      }
    }
  }
  if (parts.length === 0) {
    return null;
  }
  const joined = parts.join("\n");
  return joined.trim().length > 0 ? joined : null;
}

// The JSON-RPC request id of the most recent outgoing request whose response
// becomes the displayed Tool Result: `tools/call` directly, or `tasks/result`
// for a task-augmented call. The send-side transport wrapper in useConnection
// records it; the join below resolves the pane's own response frame by this id,
// so a repeated failing call never borrows the previous call's annotation.
type ToolCallRequestId = string | number;
let lastToolCallRequestId: ToolCallRequestId | null = null;

const RESULT_BEARING_METHODS: ReadonlySet<string> = new Set([
  "tools/call",
  "tasks/result",
]);

/**
 * Observes one outgoing JSON-RPC frame (installed as a send wrapper on the
 * client transport). Records the request id when the method's response becomes
 * the displayed Tool Result; everything else (tasks/get polls, notifications,
 * frames without an id) is ignored.
 */
export function noteOutgoingRequestId(message: unknown): void {
  if (!message || typeof message !== "object") {
    return;
  }
  const obj = message as Record<string, unknown>;
  const method = obj["method"];
  if (typeof method !== "string" || !RESULT_BEARING_METHODS.has(method)) {
    return;
  }
  const id = obj["id"];
  if (typeof id === "number" || typeof id === "string") {
    lastToolCallRequestId = id;
  }
}

/** The request id recorded for the call whose result the pane is displaying. */
export function currentToolCallRequestId(): ToolCallRequestId | null {
  return lastToolCallRequestId;
}

/**
 * Finds the leak annotation of the timeline response event that carries the
 * given JSON-RPC request id: the pane's own response frame, joined by identity
 * rather than by comparing payload texts. Events arrive newest-first from the
 * API, so when a reconnect restarts the client's id counter the current
 * session's event wins. The matched event decides alone: without a `_leak`
 * enrichment there is no note, even when an older call with the same error
 * text was annotated. Returns null when the id is unusable or no frame matches.
 */
export function findLeakForToolResult(
  events: TimelineLeakEvent[],
  requestId: unknown,
): LeakInfo | null {
  if (typeof requestId !== "number" && typeof requestId !== "string") {
    return null;
  }
  const wanted = String(requestId);
  for (const event of events) {
    if (!event || event.type !== "MCP_JSONRPC_RESPONSE" || !event.payload) {
      continue;
    }
    const frameId = event.payload["id"];
    if (
      (typeof frameId === "number" || typeof frameId === "string") &&
      String(frameId) === wanted
    ) {
      return parseLeakAnnotation(event.payload);
    }
  }
  return null;
}

/**
 * Reads one page of the timeline exactly like TimelineTab does: same endpoint,
 * same auth header from the ephemeral config, same bootstrap-advertised prefix.
 * Any failure yields an empty list: the leak note is opt-in display sugar and
 * must never surface an error of its own.
 */
export async function fetchTimelineEvents(): Promise<TimelineLeakEvent[]> {
  const token = sessionStorage.getItem("inspectorConfig_v1_ephemeral");
  const headers: Record<string, string> = {};
  if (token) {
    try {
      const parsed = JSON.parse(token) as {
        MCP_PROXY_AUTH_TOKEN?: { value?: string };
      };
      if (parsed.MCP_PROXY_AUTH_TOKEN?.value) {
        headers["Authorization"] = "Bearer " + parsed.MCP_PROXY_AUTH_TOKEN.value;
      }
    } catch {
      // A malformed token blob must not break the lookup; proceed unauthenticated.
    }
  }
  // The API is mounted under the configured spring.ai.mcp.inspector.path; the
  // server advertises the deployed prefix through the bootstrap payload.
  const bootstrap = (window as unknown as {
    __MCP_INSPECTOR_BOOTSTRAP?: { inspectorPath?: string };
  }).__MCP_INSPECTOR_BOOTSTRAP;
  const apiBase = (bootstrap?.inspectorPath || "/mcp-inspector").replace(
    /\/$/,
    "",
  );
  const res = await fetch(`${apiBase}/api/timeline?limit=200`, { headers });
  if (!res.ok) {
    return [];
  }
  try {
    const data = (await res.json()) as unknown;
    return Array.isArray(data) ? (data as TimelineLeakEvent[]) : [];
  } catch {
    return [];
  }
}

// Navigation broker: the Tool Result pane (Tools tab) and the Timeline tab are
// separate Radix tab panels, so a plain prop cannot carry the jump request. The
// broker delivers to a mounted Timeline immediately and otherwise keeps one
// pending request that the Timeline consumes on mount.
type TimelineScrollHandler = (eventId: string) => void;
let pendingTimelineScrollTarget: string | null = null;
const timelineScrollHandlers = new Set<TimelineScrollHandler>();

/**
 * Asks the Timeline tab to scroll to the given timeline event id. Safe to call
 * while the Timeline is not mounted: the request is held until it subscribes.
 */
export function requestTimelineScrollTo(eventId: string): void {
  if (typeof eventId !== "string" || eventId.length === 0) {
    return;
  }
  if (timelineScrollHandlers.size > 0) {
    timelineScrollHandlers.forEach((handler) => handler(eventId));
    return;
  }
  pendingTimelineScrollTarget = eventId;
}

/**
 * Subscribes the mounted Timeline to jump requests. Delivers a request that
 * arrived while the Timeline was not mounted. Returns an unsubscribe function.
 */
export function onTimelineScrollTo(handler: TimelineScrollHandler): () => void {
  timelineScrollHandlers.add(handler);
  const pending = pendingTimelineScrollTarget;
  if (pending !== null) {
    pendingTimelineScrollTarget = null;
    handler(pending);
  }
  return () => {
    timelineScrollHandlers.delete(handler);
  };
}

/**
 * Display cap for text exposed through DOM attributes (spec UI hygiene:
 * titles capped at 200 chars so long stack-ish payloads never bloat tooltips).
 */
export function capLeakText(text: string, cap = 200): string {
  return text.length > cap ? text.slice(0, cap - 3) + "..." : text;
}

/**
 * Clock part (HH:MM:SS) of a recorded ISO-8601 timestamp, shown in the
 * non-verbatim note ("matches an exception logged at HH:MM:SS"). Extracted
 * from the recorded string rather than Date.toLocaleTimeString so the note
 * cites the server-side clock regardless of the browser timezone. Falls back
 * to the raw string when it does not carry a time part.
 */
export function leakClock(timestamp: string): string {
  const match = /T(\d{2}:\d{2}:\d{2})/.exec(timestamp);
  return match !== null ? match[1] : timestamp;
}

const LEAK_LOOKUP_ATTEMPTS = 3;
const LEAK_LOOKUP_RETRY_MS = 3000;

/**
 * Resolves the leak annotation for the error tool result currently displayed in
 * the Tool Result pane. The response frame may reach the timeline ring a moment
 * after the client sees the result (and the log appender flush lags further),
 * so the lookup retries on the Timeline poll cadence before giving up. The
 * response frame is matched by the JSON-RPC request id recorded on the wire
 * (see noteOutgoingRequestId), never by comparing texts. Absent or malformed
 * annotations yield null: no annotation means no visual noise.
 */
export function useLeakForToolResult(
  isError: boolean,
  toolResult: unknown,
): LeakInfo | null {
  const [leak, setLeak] = useState<LeakInfo | null>(null);

  useEffect(() => {
    setLeak(null);
    if (!isError) {
      return;
    }
    // Eligibility gate only: the note quotes leaked text, and a text-less
    // result can never carry one.
    if (toolResultErrorText(toolResult) === null) {
      return;
    }
    const requestId = currentToolCallRequestId();
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let attempts = 0;
    const attempt = async () => {
      if (cancelled) {
        return;
      }
      attempts += 1;
      let events: TimelineLeakEvent[] = [];
      try {
        events = await fetchTimelineEvents();
      } catch {
        // Network failure is not a leak signal; retry quietly if attempts left.
      }
      if (cancelled) {
        return;
      }
      const found = findLeakForToolResult(events, requestId);
      if (found) {
        setLeak(found);
        return;
      }
      if (attempts < LEAK_LOOKUP_ATTEMPTS) {
        timer = setTimeout(() => void attempt(), LEAK_LOOKUP_RETRY_MS);
      }
    };
    void attempt();
    return () => {
      cancelled = true;
      if (timer) {
        clearTimeout(timer);
      }
    };
  }, [isError, toolResult]);

  return leak;
}
