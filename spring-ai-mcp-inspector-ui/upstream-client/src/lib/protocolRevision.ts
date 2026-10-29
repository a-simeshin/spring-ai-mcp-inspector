/* [spring-ai-mcp-inspector PATCH] Client-side port of the protocol-revision
   compatibility matrix (io.inspector.mcp.core.protocol.ProtocolRevision).
   The connect-zone VersionBadge renders before any backend round-trip and
   must also work in Direct mode, where the inspector backend is not on the
   wire at all, so the check runs in the browser. Keep this table in sync
   with the Java class; the tests mirror ProtocolRevisionTests. */

/** Severity of a requested-vs-negotiated revision mismatch. */
export type ProtocolSeverity = "OK" | "DOWNGRADE" | "INCOMPATIBLE" | "UNKNOWN";

export interface CompatibilityResult {
  severity: ProtocolSeverity;
  /** Methods unavailable on the negotiated revision, sorted, never null. */
  affectedMethods: string[];
  /** Human-readable description of the outcome. */
  summary: string;
}

/** The initialize handshake as observed on the wire by this client. */
export interface InitializeSnapshot {
  clientRequestedVersion: string | null;
  negotiatedVersion: string | null;
  serverName: string | null;
  serverVersion: string | null;
  capabilities: Record<string, unknown> | null;
}

/**
 * Known spec revisions in chronological order, mapped to the methods that
 * revision REMOVED relative to its predecessor. The baseline revision
 * (2025-11-25) removes nothing. Extend by adding a new entry; the comparison
 * logic is table-driven, not an if-cascade.
 */
const KNOWN_REVISIONS: ReadonlyMap<string, readonly string[]> = new Map([
  // 2025-11-25: baseline revision, nothing removed.
  ["2025-11-25", []],
  // 2026-07-28: stateless protocol; removes handshake, ping, logging,
  // subscriptions, roots, tasks, and elicitation.
  [
    "2026-07-28",
    [
      // Major 2: initialize and notifications/initialized removed (stateless).
      "initialize",
      "notifications/initialized",
      // Major 4: resources/subscribe, resources/unsubscribe replaced by
      // subscriptions/listen.
      "resources/subscribe",
      "resources/unsubscribe",
      // Major 5: ping, logging/setLevel, notifications/roots/list_changed
      // removed.
      "ping",
      "logging/setLevel",
      "notifications/roots/list_changed",
      // Major 6: tasks/list and tasks/result moved to extension.
      "tasks/list",
      "tasks/result",
      // Minor 11: notifications/elicitation/complete removed (MRTR pattern).
      "notifications/elicitation/complete",
    ],
  ],
]);

function unknown(summary: string): CompatibilityResult {
  return { severity: "UNKNOWN", affectedMethods: [], summary };
}

/**
 * Check compatibility between a client-requested and a server-negotiated
 * protocol revision. The wire runs at the NEGOTIATED revision: methods that
 * the negotiated revision (or any revision newer than it) removed are not
 * available.
 */
export function checkProtocolRevision(
  requestedVersion: string | null | undefined,
  negotiatedVersion: string | null | undefined,
): CompatibilityResult {
  if (requestedVersion == null || negotiatedVersion == null) {
    return unknown(
      `Revision is missing: requested=${requestedVersion ?? String(requestedVersion)}, negotiated=${negotiatedVersion ?? String(negotiatedVersion)}`,
    );
  }

  const removedInRequested = KNOWN_REVISIONS.get(requestedVersion);
  const removedInNegotiated = KNOWN_REVISIONS.get(negotiatedVersion);

  if (removedInRequested == null && removedInNegotiated == null) {
    return unknown(
      `Both revisions are unknown: requested=${requestedVersion}, negotiated=${negotiatedVersion}`,
    );
  }
  if (removedInRequested == null) {
    return unknown(`Requested revision is unknown: ${requestedVersion}`);
  }
  if (removedInNegotiated == null) {
    return unknown(`Negotiated revision is unknown: ${negotiatedVersion}`);
  }

  if (requestedVersion === negotiatedVersion) {
    return {
      severity: "OK",
      affectedMethods: [],
      summary: `Client and server agreed on revision ${requestedVersion}.`,
    };
  }

  // Revision keys are ISO dates, so lexicographic order equals chronological
  // order.
  if (requestedVersion < negotiatedVersion) {
    // Client requested an OLDER revision than the server negotiated. The
    // server is running a newer protocol that may have removed methods the
    // client still expects: those removed by revisions strictly between
    // requested and negotiated (inclusive of negotiated).
    const affected = new Set<string>();
    for (const [revision, removed] of KNOWN_REVISIONS) {
      if (revision > requestedVersion && revision <= negotiatedVersion) {
        for (const method of removed) {
          affected.add(method);
        }
      }
    }
    if (affected.size === 0) {
      return {
        severity: "OK",
        affectedMethods: [],
        summary: `Client requested revision ${requestedVersion}, server negotiated newer ${negotiatedVersion}. Compatible.`,
      };
    }
    const affectedMethods = [...affected].sort();
    return {
      severity: "INCOMPATIBLE",
      affectedMethods,
      summary: `Client requested revision ${requestedVersion} but server negotiated newer ${negotiatedVersion}. The server removed methods: [${affectedMethods.join(", ")}]. Calls to these methods will fail with MethodNotFound.`,
    };
  }

  // Client requested a NEWER revision than the server negotiated: the server
  // is older. The table only tracks removals, not additions, so the specific
  // new features the client is missing cannot be enumerated; flag a
  // downgrade.
  return {
    severity: "DOWNGRADE",
    affectedMethods: [],
    summary: `Client requested revision ${requestedVersion} but server negotiated older ${negotiatedVersion}. The server is running an older protocol; new features from ${requestedVersion} are unavailable.`,
  };
}
