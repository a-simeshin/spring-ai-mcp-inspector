import { TabsContent } from "@/components/ui/tabs";
import { useEffect, useState, useCallback, useRef } from "react";

// [spring-ai-mcp-inspector PATCH] New ClientsTab: live registry of MCP client
// connections captured by the recording client transports (#120, layer C3).
// No upstream equivalent exists; this is a Spring AI MCP Inspector extension.

// Mirrors io.inspector.mcp.core.timeline.ClientConnectionRegistry
// .ClientConnectionSnapshot as serialized by GET ${path}/api/client-connections.
interface ClientConnectionSnapshot {
  name: string;
  transportType: string | null;
  target: string | null;
  state: "INITIALIZING" | "CONNECTED" | "FAILED" | "CLOSED";
  since: string;
  lastActivity: string | null;
  inCount: number;
  outCount: number;
  lastError: string | null;
}

// State badge styling per lifecycle state: live clients are the signal case,
// the rest are visually recessive but distinct.
const STATE_BADGE: Record<ClientConnectionSnapshot["state"], { text: string; color: string }> = {
  INITIALIZING: { text: "initializing", color: "bg-amber-800 text-amber-200" },
  CONNECTED: { text: "live", color: "bg-green-800 text-green-200" },
  FAILED: { text: "failed", color: "bg-red-800 text-red-200" },
  CLOSED: { text: "closed", color: "bg-gray-700 text-gray-300" },
};

const ClientConnectionsTab = () => {
  // The API exists only while client capture is enabled (the controller bean is
  // conditional on the same flag the registry uses). Read the flag from the
  // bootstrap payload the page already carries, so a disabled Clients tab never
  // probes a missing endpoint and never writes a console 404.
  const captureEnabled =
    (
      window as unknown as {
        __MCP_INSPECTOR_BOOTSTRAP?: {
          inspectorPath?: string;
          authToken?: string;
          clientCaptureEnabled?: boolean;
        };
      }
    ).__MCP_INSPECTOR_BOOTSTRAP?.clientCaptureEnabled === true;
  const [connections, setConnections] = useState<ClientConnectionSnapshot[]>([]);
  const [disabled, setDisabled] = useState(!captureEnabled);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchConnections = useCallback(async () => {
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
      const res = await fetch(`${apiBase}/api/client-connections`, { headers });
      if (res.ok) {
        const data = (await res.json()) as ClientConnectionSnapshot[];
        setConnections(data);
        setDisabled(false);
        setFetchError(null);
      } else if (res.status === 404) {
        // No controller bean: client capture is off (or was turned off under a
        // stale bootstrap). The endpoint will not appear later, so stop
        // polling entirely; same empty-state anchor with an explicit reason.
        if (pollingRef.current) {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
        }
        setConnections([]);
        setDisabled(true);
        setFetchError(null);
      } else {
        setFetchError(`Client connections request failed: HTTP ${res.status}`);
      }
    } catch {
      setFetchError("Client connections request failed: network error");
    }
  }, []);

  useEffect(() => {
    if (!captureEnabled) {
      return;
    }
    void fetchConnections();
    pollingRef.current = setInterval(() => void fetchConnections(), 3000);
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, [captureEnabled, fetchConnections]);

  return (
    <TabsContent value="clients" className="h-96">
      <div className="bg-gray-900 text-gray-100 p-4 rounded-lg h-full font-mono text-sm overflow-auto flex flex-col">
        <div className="flex items-center justify-between mb-2 shrink-0">
          <span className="text-xs opacity-50">
            {connections.length} connection{connections.length !== 1 ? "s" : ""}
          </span>
        </div>
        <div className="flex-1 overflow-y-auto">
          {fetchError ? (
            <div
              role="alert"
              className="border-l-4 border-red-500 bg-red-950/30 text-red-400 px-3 py-2 mt-4"
            >
              {fetchError}
            </div>
          ) : connections.length === 0 ? (
            <div
              data-testid="client-connection-state"
              className="opacity-50 text-center mt-8"
            >
              {disabled
                ? "Client capture is disabled (spring.ai.mcp.inspector.timeline.client-capture-enabled)"
                : "No client connections yet"}
            </div>
          ) : (
            connections.map((connection) => {
              const badge = STATE_BADGE[connection.state] ?? STATE_BADGE.CLOSED;
              return (
                <div
                  key={connection.name}
                  data-testid="client-connection-card"
                  className="border border-gray-700 rounded p-2 mb-2 bg-gray-950/30 text-xs"
                >
                  <div className="flex items-center gap-2 mb-1">
                    <span className="font-semibold">{connection.name}</span>
                    <span
                      data-testid="client-connection-state"
                      className={`shrink-0 px-1.5 py-0.5 rounded text-[10px] font-mono ${badge.color}`}
                      title={`state: ${connection.state.toLowerCase()} since ${connection.since}`}
                    >
                      {badge.text}
                    </span>
                  </div>
                  <div className="opacity-70">
                    transport: {connection.transportType ?? "unknown"}
                  </div>
                  <div className="opacity-70 truncate" title={connection.target ?? undefined}>
                    target: {connection.target ?? "unknown"}
                  </div>
                  <div className="opacity-70">
                    in: {connection.inCount} · out: {connection.outCount}
                  </div>
                  {connection.lastError && (
                    <div className="text-red-400 mt-1">
                      last error: {connection.lastError}
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>
      </div>
    </TabsContent>
  );
};

export default ClientConnectionsTab;
