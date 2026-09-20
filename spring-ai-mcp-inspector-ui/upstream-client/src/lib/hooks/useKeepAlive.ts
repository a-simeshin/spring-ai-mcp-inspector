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

function resolveApiBase(): string {
  const bootstrap = (
    window as unknown as {
      __MCP_INSPECTOR_BOOTSTRAP?: { inspectorPath?: string };
    }
  ).__MCP_INSPECTOR_BOOTSTRAP;
  return (bootstrap?.inspectorPath || "/mcp-inspector").replace(/\/$/, "");
}

function authHeaders(): Record<string, string> {
  const token = sessionStorage.getItem("inspectorConfig_v1_ephemeral");
  const headers: Record<string, string> = {};
  if (token) {
    try {
      const parsed = JSON.parse(token);
      if (parsed.MCP_PROXY_AUTH_TOKEN?.value) {
        headers["Authorization"] = "Bearer " + parsed.MCP_PROXY_AUTH_TOKEN.value;
      }
    } catch {
      // Ignore parse errors
    }
  }
  return headers;
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
    const sseUrl = `${apiBase}/api/events?sessionId=${encodeURIComponent(sessionId)}`;
    const eventSource = new EventSource(sseUrl);
    eventSourceRef.current = eventSource;

    eventSource.addEventListener("mcp:keepalive-ping", (event) => {
      const at = (event as MessageEvent).data as string;
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
