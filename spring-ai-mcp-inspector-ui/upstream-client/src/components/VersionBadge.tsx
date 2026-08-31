/* [spring-ai-mcp-inspector PATCH] VersionBadge — shows the negotiated MCP
   protocol version in the connect zone, with expandable server info and a
   compatibility notice when the version differs from the client's request. */

import { useState, useEffect, useCallback } from "react";
import {
  ChevronDown,
  ChevronRight,
  AlertTriangle,
  Info,
  Server,
  CheckCircle2,
} from "lucide-react";
import { InspectorConfig } from "@/lib/configurationTypes";
import { getMCPProxyAuthToken } from "@/utils/configUtils";

interface InitializeSnapshot {
  clientRequestedVersion: string;
  negotiatedVersion: string;
  serverName: string | null;
  serverVersion: string | null;
  capabilities: Record<string, boolean | object>;
  compatibility: {
    severity: "OK" | "DOWNGRADE" | "INCOMPATIBLE" | "UNKNOWN";
    affectedMethods: string[];
    summary: string;
  };
}

interface VersionBadgeProps {
  mcpSessionId: string | null;
  config: InspectorConfig;
  connected: boolean;
  serverName?: string | null;
  serverVersion?: string | null;
}

export default function VersionBadge({
  mcpSessionId,
  config,
  connected,
  serverName: propServerName,
  serverVersion: propServerVersion,
}: VersionBadgeProps) {
  const [snapshot, setSnapshot] = useState<InitializeSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [showDetails, setShowDetails] = useState(false);
  const [fetchError, setFetchError] = useState(false);

  const fetchSnapshot = useCallback(async () => {
    if (!mcpSessionId || !connected) {
      setSnapshot(null);
      return;
    }
    setLoading(true);
    setFetchError(false);
    try {
      const { token: proxyAuthToken, header: proxyAuthTokenHeader } =
        getMCPProxyAuthToken(config);
      const headers: HeadersInit = {};
      if (proxyAuthToken) {
        headers[proxyAuthTokenHeader] = `Bearer ${proxyAuthToken}`;
      }
      // The REST API is same-origin, mounted under the inspector path.
      // The server advertises the deployed prefix through the bootstrap payload.
      const bootstrap = (window as unknown as {
        __MCP_INSPECTOR_BOOTSTRAP?: { inspectorPath?: string };
      }).__MCP_INSPECTOR_BOOTSTRAP;
      const apiBase = (bootstrap?.inspectorPath || "/mcp-inspector").replace(
        /\/$/,
        "",
      );
      const response = await fetch(
        `${apiBase}/api/session/${mcpSessionId}/initialize`,
        { headers },
      );
      if (response.ok) {
        const data = (await response.json()) as InitializeSnapshot;
        setSnapshot(data);
        setFetchError(false);
      } else {
        // Snapshot not available (404 or session not found) — no badge.
        setSnapshot(null);
        setFetchError(true);
      }
    } catch {
      // Network error — no badge.
      setSnapshot(null);
      setFetchError(true);
    } finally {
      setLoading(false);
    }
  }, [mcpSessionId, connected, config]);

  useEffect(() => {
    if (connected) {
      void fetchSnapshot();
    } else {
      setSnapshot(null);
      setShowDetails(false);
    }
  }, [connected, fetchSnapshot]);

  // When no snapshot, no fetch error, and not connected, render nothing.
  if (!connected || loading) {
    return null;
  }

  // Even if fetch failed, we can still show minimal info from props.
  const severity = snapshot?.compatibility.severity;
  const negotiatedVersion =
    snapshot?.negotiatedVersion;
  const name = snapshot?.serverName ?? propServerName ?? null;
  const version = snapshot?.serverVersion ?? propServerVersion ?? null;
  const capabilities = snapshot?.capabilities;

  // No data at all: render nothing.
  if (!snapshot && !fetchError) {
    return null;
  }

  const severityColor = (() => {
    switch (severity) {
      case "DOWNGRADE":
        return "bg-amber-100 dark:bg-amber-900 text-amber-800 dark:text-amber-200 border-amber-300 dark:border-amber-700";
      case "INCOMPATIBLE":
        return "bg-red-100 dark:bg-red-900 text-red-800 dark:text-red-200 border-red-300 dark:border-red-700";
      case "UNKNOWN":
        return "bg-blue-100 dark:bg-blue-900 text-blue-800 dark:text-blue-200 border-blue-300 dark:border-blue-700";
      default:
        return "bg-green-100 dark:bg-green-900 text-green-800 dark:text-green-200 border-green-300 dark:border-green-700";
    }
  })();

  const severityIcon = (() => {
    switch (severity) {
      case "DOWNGRADE":
        return <AlertTriangle className="w-4 h-4" />;
      case "INCOMPATIBLE":
        return <AlertTriangle className="w-4 h-4" />;
      case "UNKNOWN":
        return <Info className="w-4 h-4" />;
      default:
        return <CheckCircle2 className="w-4 h-4" />;
    }
  })();

  const severityLabel = (() => {
    switch (severity) {
      case "DOWNGRADE":
        return "Downgraded";
      case "INCOMPATIBLE":
        return "Incompatible";
      case "UNKNOWN":
        return "Unknown revision";
      default:
        return "OK";
    }
  })();

  // Collect capability names for display
  const capabilityNames = capabilities
    ? Object.keys(capabilities).sort()
    : [];

  return (
    <>
      {/* Compact badge — always visible when snapshot exists */}
      <div
        className={`flex items-center gap-2 px-3 py-2 rounded-lg border text-sm ${severityColor} mb-4`}
        role="status"
      >
        <div className="flex items-center gap-1.5 min-w-0">
          {severityIcon}
          <span className="font-medium truncate">
            {negotiatedVersion ?? "MCP"}
          </span>
        </div>
        {showDetails ? (
          <ChevronDown
            className="w-4 h-4 ml-auto cursor-pointer shrink-0"
            onClick={() => setShowDetails(false)}
          />
        ) : (
          <ChevronRight
            className="w-4 h-4 ml-auto cursor-pointer shrink-0"
            onClick={() => setShowDetails(true)}
          />
        )}
      </div>

      {/* Expandable details */}
      {showDetails && (
        <div className="mb-4 space-y-3 p-3 rounded-lg border bg-card text-sm">
          {/* Server info */}
          {(name || version) && (
            <div>
              <div className="flex items-center gap-1.5 font-medium text-xs text-muted-foreground mb-1">
                <Server className="w-3.5 h-3.5" />
                Server
              </div>
              <div className="space-y-0.5">
                {name && (
                  <div className="text-sm font-medium">{name}</div>
                )}
                {version && (
                  <div className="text-xs text-muted-foreground">
                    Version: {version}
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Protocol versions */}
          <div>
            <div className="text-xs font-medium text-muted-foreground mb-1">
              Protocol
            </div>
            <div className="space-y-0.5 text-xs">
              <div className="flex justify-between">
                <span className="text-muted-foreground">Client requested:</span>
                <span className="font-mono">
                  {snapshot?.clientRequestedVersion ?? "—"}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Negotiated:</span>
                <span className="font-mono">
                  {negotiatedVersion ?? "—"}
                </span>
              </div>
            </div>
          </div>

          {/* Capabilities */}
          {capabilityNames.length > 0 && (
            <div>
              <div className="text-xs font-medium text-muted-foreground mb-1">
                Capabilities
              </div>
              <div className="flex flex-wrap gap-1">
                {capabilityNames.map((cap) => (
                  <span
                    key={cap}
                    className="px-2 py-0.5 rounded-full bg-secondary text-xs text-secondary-foreground"
                  >
                    {cap}
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* Compatibility notice */}
          {(severity === "DOWNGRADE" || severity === "INCOMPATIBLE") && (
            <div className="rounded-lg border border-amber-300 dark:border-amber-700 bg-amber-50 dark:bg-amber-950 p-3">
              <div className="flex items-center gap-1.5 mb-1">
                <AlertTriangle className="w-4 h-4 text-amber-600 dark:text-amber-400" />
                <span className="text-sm font-medium text-amber-800 dark:text-amber-200">
                  Protocol {severityLabel}
                </span>
              </div>
              <p className="text-xs text-amber-700 dark:text-amber-300 mb-2">
                The client requested revision{" "}
                <code className="text-xs bg-amber-100 dark:bg-amber-900 px-1 rounded">
                  {snapshot?.clientRequestedVersion}
                </code>
                , but the server negotiated{" "}
                <code className="text-xs bg-amber-100 dark:bg-amber-900 px-1 rounded">
                  {negotiatedVersion}
                </code>
                . Calls to the following methods will result in{" "}
                <code className="text-xs bg-amber-100 dark:bg-amber-900 px-1 rounded">
                  MethodNotFound
                </code>
                :
              </p>
              {snapshot?.compatibility.affectedMethods &&
                snapshot.compatibility.affectedMethods.length > 0 && (
                  <ul className="text-xs space-y-0.5 list-disc list-inside text-amber-700 dark:text-amber-300">
                    {snapshot.compatibility.affectedMethods.map((method) => (
                      <li key={method} className="font-mono text-xs">
                        {method}
                      </li>
                    ))}
                  </ul>
                )}
            </div>
          )}

          {/* Unknown severity notice */}
          {severity === "UNKNOWN" && (
            <div className="rounded-lg border border-blue-300 dark:border-blue-700 bg-blue-50 dark:bg-blue-950 p-3">
              <div className="flex items-center gap-1.5 mb-1">
                <Info className="w-4 h-4 text-blue-600 dark:text-blue-400" />
                <span className="text-sm font-medium text-blue-800 dark:text-blue-200">
                  Unrecognized Revision
                </span>
              </div>
              <p className="text-xs text-blue-700 dark:text-blue-300">
                The protocol revision{" "}
                <code className="text-xs bg-blue-100 dark:bg-blue-900 px-1 rounded">
                  {negotiatedVersion}
                </code>{" "}
                is not recognized by this inspector. Compatibility with the
                methods in this revision cannot be assessed.
              </p>
            </div>
          )}
        </div>
      )}
    </>
  );
}