import { useEffect, useRef, useState } from "react";

// [spring-ai-mcp-inspector PATCH] useKeepAlive: subscribes to the inspector's
// keep-alive endpoint (SSE mcp:keepalive-ping + GET /api/keepalive snapshot) and
// exposes the state for Timeline and History rendering (#235, t_2c3e06c2).

const MAX_KEEPALIVE_PINGS = 10;
const POLL_INTERVAL_MS = 5000;

interface KeepAliveState {
  lastPingAt: string | null;
  recentPings: string[];
  estimatedIntervalMillis: number | null;
  isStale: boolean;
  observed: boolean;
}

const EMPTY_STATE: KeepAliveState = {
  lastPingAt: null,
  recentPings: [],
  estimatedIntervalMillis: null,
  isStale: false,
  observed: false,
};

interface KeepAliveSnapshotDto {
  observed?: boolean;
  lastPingAt?: string | null;
  recentPings?: string[];
  estimatedIntervalMillis?: number | null;
  isStale?: boolean;
}

// [spring-ai-mcp-inspector PATCH] The inspector API auth filter compares the
// X-MCP-Inspector-Auth header (or the auth= query parameter) against the
// server-generated token verbatim - no Authorization/Bearer scheme is
// recognized. The token is delivered to the UI via the server-rendered
// bootstrap payload (#235).
function resolveInspectorBootstrap(): {
  inspectorPath?: string;
  authToken?: string;
} | undefined {
  return (window as unknown as {
    __MCP_INSPECTOR_BOOTSTRAP?: { inspectorPath?: string; authToken?: string };
  }).__MCP_INSPECTOR_BOOTSTRAP;
}

function resolveApiBase(): string {
  const bootstrap = resolveInspectorBootstrap();
  return (bootstrap?.inspectorPath || "/mcp-inspector").replace(/\/$/, "");
}

function inspectorAuthToken(): string | null {
  const token = resolveInspectorBootstrap()?.authToken;
  return token ? token : null;
}

function authHeaders(): Record<string, string> {
  const token = inspectorAuthToken();
  return token ? { "X-MCP-Inspector-Auth": token } : {};
}

function authQuery(): string {
  const token = inspectorAuthToken();
  return token ? `&auth=${encodeURIComponent(token)}` : "";
}

// Keep-alive ping payloads are unified on the {"at":"..."} JSON shape across
// stacks; the bare timestamp string emitted by earlier server builds is
// still accepted. Normalize to the timestamp itself (#235).
function parsePingAt(data: unknown): string | null {
  if (typeof data !== "string" || data.length === 0) {
    return null;
  }
  try {
    const parsed = JSON.parse(data) as { at?: unknown };
    if (typeof parsed?.at === "string") {
      return parsed.at;
    }
  } catch {
    // Bare (non-JSON) payload: use as-is.
  }
  return data;
}

export function useKeepAlive(sessionId: string | null) {
  const [state, setState] = useState<KeepAliveState>(EMPTY_STATE);
  const eventSourceRef = useRef<EventSource | null>(null);
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const apiBase = resolveApiBase();

  useEffect(() => {
    // Reset state when session changes
    setState(EMPTY_STATE);

    if (!sessionId) {
      return;
    }

    const headers = authHeaders();

    // Initial snapshot fetch
    const fetchSnapshot = async () => {
      try {
        const res = await fetch(
          `${apiBase}/api/keepalive?sessionId=${encodeURIComponent(sessionId)}`,
          { headers },
        );
        if (res.ok) {
          const data = (await res.json()) as KeepAliveSnapshotDto;
          setState({
            observed: data.observed ?? false,
            lastPingAt: data.lastPingAt ?? null,
            recentPings: (data.recentPings ?? []).slice(-MAX_KEEPALIVE_PINGS),
            estimatedIntervalMillis: data.estimatedIntervalMillis ?? null,
            isStale: data.isStale ?? false,
          });
        }
      } catch {
        // Silently ignore fetch errors
      }
    };

    void fetchSnapshot();

    // SSE subscription for live ping events
    // EventSource cannot send custom headers, so the inspector auth token
    // travels as the auth= query parameter the filter accepts.
    const sseUrl = `${apiBase}/api/events?sessionId=${encodeURIComponent(sessionId)}${authQuery()}`;
    const eventSource = new EventSource(sseUrl);
    eventSourceRef.current = eventSource;

    eventSource.addEventListener("mcp:keepalive-ping", (event) => {
      const at = parsePingAt((event as MessageEvent).data);
      if (at === null) {
        return;
      }
      setState((prev) => {
        const nextPings = [...prev.recentPings, at].slice(-MAX_KEEPALIVE_PINGS);
        return {
          ...prev,
          observed: true,
          lastPingAt: at,
          recentPings: nextPings,
          isStale: false, // Fresh ping: not stale
        };
      });
    });

    eventSource.onerror = () => {
      // EventSource will attempt to reconnect automatically.
      // Polling below serves as a fallback for state consistency.
    };

    // Polling fallback for interval/staleness updates
    pollIntervalRef.current = setInterval(() => {
      void fetchSnapshot();
    }, POLL_INTERVAL_MS);

    return () => {
      eventSource.close();
      eventSourceRef.current = null;
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
    };
  }, [sessionId, apiBase]);

  return state;
}
