// [spring-ai-mcp-inspector PATCH] Saved connections storage layer.
// Persists connection configurations in localStorage under a single
// schema-versioned key. See NOTICE.d/saved-connections.txt for details.

import type {
  SavedConnection,
  SavedConnectionDraft,
  SavedConnectionsV1,
} from "./types/savedConnection";

export const SAVED_CONNECTIONS_KEY = "mcp-inspector.savedConnections.v1";

// Header names whose values are treated as secrets and stripped on save.
// Authorization is the primary case: it would persist bearer tokens in
// plaintext localStorage. Env values are also stripped because stdio
// servers commonly hold API keys there.
const SECRET_HEADER_NAMES = new Set(["authorization"]);

/**
 * Strip secret fields from a draft before persisting.
 * - Authorization headers are removed from customHeaders.
 * - Env values are cleared (keys preserved with empty string).
 * Returns a new draft object; the original is not mutated.
 */
export function stripSecrets(draft: SavedConnectionDraft): SavedConnectionDraft {
  return {
    ...draft,
    customHeaders: draft.customHeaders
      ? draft.customHeaders.filter(
          (h) => !SECRET_HEADER_NAMES.has(h.name.toLowerCase()),
        )
      : draft.customHeaders,
    env: draft.env
      ? Object.fromEntries(
          Object.keys(draft.env).map((k) => [k, ""]),
        )
      : draft.env,
  };
}

const MAX_CONNECTIONS = 20;

function generateId(): string {
  // crypto.randomUUID() is available in modern browsers; jsdom may not have it.
  // Fallback to a simple random hex string.
  if (typeof crypto !== "undefined" && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function now(): number {
  return Date.now();
}

/**
 * Runtime type guard: returns true if `c` is a structurally valid
 * SavedConnection. This protects against corrupt persisted data
 * (null entries, wrong types, missing fields) that would otherwise
 * break the UI on render.
 */
export function isValidConnection(c: unknown): c is SavedConnection {
  if (c === null || c === undefined || typeof c !== "object") {
    return false;
  }
  const obj = c as Record<string, unknown>;
  if (typeof obj.id !== "string" || !obj.id) return false;
  if (typeof obj.name !== "string" || !obj.name) return false;
  if (typeof obj.transport !== "string") return false;
  if (!["stdio", "sse", "streamable-http"].includes(obj.transport as string)) {
    return false;
  }
  if (typeof obj.connectionType !== "string") return false;
  if (!["proxy", "direct"].includes(obj.connectionType as string)) return false;
  if (typeof obj.createdAt !== "number") return false;
  if (typeof obj.lastUsedAt !== "number") return false;
  if (!Array.isArray(obj.customHeaders)) return false;
  // Validate nested header objects: each must be a non-null object
  // with string name/value and boolean enabled.
  for (const h of obj.customHeaders) {
    if (h === null || h === undefined || typeof h !== "object") return false;
    const header = h as Record<string, unknown>;
    if (typeof header.name !== "string") return false;
    if (typeof header.value !== "string") return false;
    if (typeof header.enabled !== "boolean") return false;
  }
  // Validate env: if present, must be a non-null object with string values
  if (obj.env !== undefined) {
    if (obj.env === null || typeof obj.env !== "object") return false;
    for (const v of Object.values(obj.env as Record<string, unknown>)) {
      if (typeof v !== "string") return false;
    }
  }
  // Validate transport-specific optional fields
  if (obj.url !== undefined && typeof obj.url !== "string") return false;
  if (obj.command !== undefined && typeof obj.command !== "string") return false;
  if (obj.args !== undefined && typeof obj.args !== "string") return false;
  return true;
}

/**
 * Filter an array of unknown entries to only structurally valid
 * SavedConnection objects. Silently drops corrupt entries and
 * console.warns when entries are dropped.
 */
export function filterValidConnections(
  entries: unknown[],
): SavedConnection[] {
  const valid: SavedConnection[] = [];
  for (let i = 0; i < entries.length; i++) {
    if (isValidConnection(entries[i])) {
      valid.push(entries[i] as SavedConnection);
    } else {
      console.warn(
        `[savedConnections] Dropping corrupt connection at index ${i}:`,
        entries[i],
      );
    }
  }
  return valid;
}

/**
 * Parse and validate saved connections from localStorage.
 * Corrupt JSON -> returns [] and console.warns.
 */
export function loadSavedConnections(): SavedConnection[] {
  const raw = localStorage.getItem(SAVED_CONNECTIONS_KEY);
  if (!raw) {
    return [];
  }
  try {
    const store: SavedConnectionsV1 = migrateSavedConnections(raw);
    return store.connections;
  } catch (error) {
    console.warn(
      `[savedConnections] Failed to parse stored connections: "${raw}"`,
      error,
    );
    return [];
  }
}

/**
 * Write the full connections array to localStorage.
 */
function persistConnections(connections: SavedConnection[]): void {
  const store: SavedConnectionsV1 = {
    schemaVersion: 1,
    connections,
  };
  localStorage.setItem(SAVED_CONNECTIONS_KEY, JSON.stringify(store));
}

/**
 * Find a saved connection by name (case-insensitive).
 * Returns undefined if no match.
 */
export function findConnectionByName(
  name: string,
): SavedConnection | undefined {
  const connections = loadSavedConnections();
  return connections.find(
    (c) => c.name.toLowerCase() === name.toLowerCase(),
  );
}

/**
 * Upsert a saved connection. If `existingId` is provided, update that
 * entry; otherwise create a new one. Updates lastUsedAt to now.
 * Beyond 20 connections, evicts the lowest lastUsedAt.
 * Returns the saved connection (with id/createdAt/lastUsedAt filled).
 *
 * NOTE: This function does NOT check for duplicate names. The caller
 * (UI layer) must check with `findConnectionByName` first and
 * prompt the user for overwrite-or-rename before calling with
 * the existing id. See App.tsx handleSaveConnection.
 */
export function saveConnection(
  draft: SavedConnectionDraft,
  existingId?: string,
): SavedConnection {
  const connections = loadSavedConnections();
  const now_ = now();

  if (existingId) {
    // Update existing connection
    const idx = connections.findIndex((c) => c.id === existingId);
    if (idx !== -1) {
      connections[idx] = {
        ...connections[idx],
        ...draft,
        lastUsedAt: now_,
      };
      persistConnections(connections);
      return connections[idx];
    }
    // Fall through: id not found, create new
  }

  // Create new connection
  const connection: SavedConnection = {
    ...draft,
    id: generateId(),
    createdAt: now_,
    lastUsedAt: now_,
  };

  // Evict oldest if at cap BEFORE adding the new connection,
  // so the just-created entry always survives the truncation.
  if (connections.length >= MAX_CONNECTIONS) {
    connections.sort((a, b) => b.lastUsedAt - a.lastUsedAt);
    connections.length = MAX_CONNECTIONS - 1;
  }

  connections.push(connection);

  persistConnections(connections);
  return connection;
}

/**
 * Delete a saved connection by id.
 */
export function deleteSavedConnection(id: string): void {
  const connections = loadSavedConnections();
  const filtered = connections.filter((c) => c.id !== id);
  if (filtered.length < connections.length) {
    persistConnections(filtered);
  }
}

/**
 * Bump lastUsedAt for a saved connection after a successful connect.
 */
export function touchSavedConnection(id: string): void {
  const connections = loadSavedConnections();
  const idx = connections.findIndex((c) => c.id === id);
  if (idx !== -1) {
    connections[idx].lastUsedAt = now();
    persistConnections(connections);
  }
}

/**
 * Migrate raw localStorage content to SavedConnectionsV1.
 * Handles schema version upgrades and legacy format.
 * Validates all connections at runtime to filter out corrupt entries.
 */
export function migrateSavedConnections(raw: string | null): SavedConnectionsV1 {
  if (!raw) {
    return { schemaVersion: 1, connections: [] };
  }
  const parsed = JSON.parse(raw) as Partial<SavedConnectionsV1>;

  let connections: unknown[] = [];

  // Already v1
  if (parsed.schemaVersion === 1 && Array.isArray(parsed.connections)) {
    connections = parsed.connections;
  } else if (Array.isArray(parsed.connections)) {
    // Unknown or missing schemaVersion: try to extract connections
    connections = parsed.connections;
  } else if (Array.isArray(parsed)) {
    // Might be a legacy "Last session" migration
    connections = parsed;
  }

  // Filter out corrupt entries at runtime
  const valid = filterValidConnections(connections);
  if (valid.length < connections.length) {
    console.warn(
      `[savedConnections] Filtered out ${connections.length - valid.length} corrupt connection(s) during migration`,
    );
  }

  return { schemaVersion: 1, connections: valid };
}