/* [spring-ai-mcp-inspector PATCH] VersionBadge : shows the negotiated MCP
   protocol revision in the connect zone, with expandable server info and a
   compatibility notice when the client and server agreed on different
   revisions. The snapshot comes from the client's own initialize handshake
   (captured in useConnection), so it works for every connection type and
   transport, including direct connections where the inspector backend is
   not on the wire. */

import { useState, useMemo } from "react";
import {
  ChevronDown,
  ChevronRight,
  AlertTriangle,
  Info,
  Server,
  CheckCircle2,
  HelpCircle,
} from "lucide-react";
import {
  checkProtocolRevision,
  InitializeSnapshot,
  ProtocolSeverity,
} from "@/lib/protocolRevision";

interface VersionBadgeProps {
  snapshot: InitializeSnapshot | null;
}

const SEVERITY_COLOR: Record<ProtocolSeverity | "NO_SNAPSHOT", string> = {
  OK: "bg-green-100 dark:bg-green-900 text-green-800 dark:text-green-200 border-green-300 dark:border-green-700",
  DOWNGRADE:
    "bg-amber-100 dark:bg-amber-900 text-amber-800 dark:text-amber-200 border-amber-300 dark:border-amber-700",
  INCOMPATIBLE:
    "bg-red-100 dark:bg-red-900 text-red-800 dark:text-red-200 border-red-300 dark:border-red-700",
  UNKNOWN:
    "bg-blue-100 dark:bg-blue-900 text-blue-800 dark:text-blue-200 border-blue-300 dark:border-blue-700",
  // No handshake was observed for this connection: neutral gray, never green.
  NO_SNAPSHOT:
    "bg-gray-100 dark:bg-gray-900 text-gray-800 dark:text-gray-200 border-gray-300 dark:border-gray-700",
};

export default function VersionBadge({ snapshot }: VersionBadgeProps) {
  const [showDetails, setShowDetails] = useState(false);

  const compatibility = useMemo(
    () =>
      snapshot
        ? checkProtocolRevision(
            snapshot.clientRequestedVersion,
            snapshot.negotiatedVersion,
          )
        : null,
    [snapshot],
  );

  const severity: ProtocolSeverity | "NO_SNAPSHOT" = compatibility
    ? compatibility.severity
    : "NO_SNAPSHOT";

  const severityIcon = (() => {
    switch (severity) {
      case "DOWNGRADE":
      case "INCOMPATIBLE":
        return <AlertTriangle className="w-4 h-4" />;
      case "UNKNOWN":
        return <Info className="w-4 h-4" />;
      case "NO_SNAPSHOT":
        return <HelpCircle className="w-4 h-4" />;
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
      case "NO_SNAPSHOT":
        return "unknown";
      default:
        return "OK";
    }
  })();

  const negotiatedVersion = snapshot?.negotiatedVersion ?? null;

  const capabilityNames = snapshot?.capabilities
    ? Object.keys(snapshot.capabilities).sort()
    : [];

  return (
    <>
      {/* Compact badge : always visible while connected; without a snapshot
          it honestly reads "unknown" instead of guessing green. */}
      <div
        className={`flex items-center gap-2 px-3 py-2 rounded-lg border text-sm ${SEVERITY_COLOR[severity]} mb-4`}
        role="status"
        data-testid="version-badge"
        data-severity={severity}
      >
        <div className="flex items-center gap-1.5 min-w-0">
          {severityIcon}
          <span className="font-medium truncate">
            {negotiatedVersion ?? "unknown"}
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
          {/* No snapshot notice */}
          {!snapshot && (
            <div className="rounded-lg border border-gray-300 dark:border-gray-700 bg-gray-50 dark:bg-gray-950 p-3">
              <div className="flex items-center gap-1.5 mb-1">
                <HelpCircle className="w-4 h-4 text-gray-500 dark:text-gray-400" />
                <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
                  Protocol revision unknown
                </span>
              </div>
              <p className="text-xs text-gray-600 dark:text-gray-400">
                The initialize handshake was not observed for this connection
                (for example a transport-level resume), so the negotiated
                revision cannot be reported.
              </p>
            </div>
          )}

          {/* Server info */}
          {snapshot && (snapshot.serverName || snapshot.serverVersion) && (
            <div>
              <div className="flex items-center gap-1.5 font-medium text-xs text-muted-foreground mb-1">
                <Server className="w-3.5 h-3.5" />
                Server
              </div>
              <div className="space-y-0.5">
                {snapshot.serverName && (
                  <div className="text-sm font-medium">
                    {snapshot.serverName}
                  </div>
                )}
                {snapshot.serverVersion && (
                  <div className="text-xs text-muted-foreground">
                    Version: {snapshot.serverVersion}
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Protocol versions */}
          {snapshot && (
            <div>
              <div className="text-xs font-medium text-muted-foreground mb-1">
                Protocol
              </div>
              <div className="space-y-0.5 text-xs">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">
                    Client requested:
                  </span>
                  <span className="font-mono">
                    {snapshot.clientRequestedVersion ?? "-"}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Negotiated:</span>
                  <span className="font-mono">{negotiatedVersion ?? "-"}</span>
                </div>
              </div>
            </div>
          )}

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

          {/* Compatibility summary */}
          {compatibility && (
            <div>
              <div className="text-xs font-medium text-muted-foreground mb-1">
                Compatibility
              </div>
              <div className="text-xs text-muted-foreground">
                {severityLabel}. {compatibility.summary}
              </div>
            </div>
          )}

          {/* Affected methods notice */}
          {compatibility &&
            (compatibility.severity === "DOWNGRADE" ||
              compatibility.severity === "INCOMPATIBLE") && (
              <div className="rounded-lg border border-amber-300 dark:border-amber-700 bg-amber-50 dark:bg-amber-950 p-3">
                <div className="flex items-center gap-1.5 mb-1">
                  <AlertTriangle className="w-4 h-4 text-amber-600 dark:text-amber-400" />
                  <span className="text-sm font-medium text-amber-800 dark:text-amber-200">
                    Protocol {severityLabel}
                  </span>
                </div>
                <p className="text-xs text-amber-700 dark:text-amber-300">
                  The client requested revision{" "}
                  <code className="text-xs bg-amber-100 dark:bg-amber-900 px-1 rounded">
                    {snapshot?.clientRequestedVersion}
                  </code>
                  , but the server negotiated{" "}
                  <code className="text-xs bg-amber-100 dark:bg-amber-900 px-1 rounded">
                    {negotiatedVersion}
                  </code>
                  .
                  {compatibility.affectedMethods.length > 0 && (
                    <>
                      {" "}
                      Calls to the following methods will result in{" "}
                      <code className="text-xs bg-amber-100 dark:bg-amber-900 px-1 rounded">
                        MethodNotFound
                      </code>
                      :
                    </>
                  )}
                </p>
                {compatibility.affectedMethods.length > 0 && (
                  <ul className="text-xs space-y-0.5 list-disc list-inside text-amber-700 dark:text-amber-300">
                    {compatibility.affectedMethods.map((method) => (
                      <li key={method} className="font-mono text-xs">
                        {method}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}

          {/* Unknown severity notice */}
          {compatibility?.severity === "UNKNOWN" && (
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
