// [spring-ai-mcp-inspector PATCH] Saved connections storage layer.
// Persists connection configurations in localStorage under a single
// schema-versioned key. See NOTICE.d/saved-connections.txt for details.

import type {
  SavedConnection,
  SavedConnectionDraft,
  SavedConnectionsV1,
} from "./types/savedConnection";

export const SAVED_CONNECTIONS_KEY = "mcp-inspector.savedConnections.v1";

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
 * Upsert a saved connection. If `existingId` is provided, update that
 * entry; otherwise create a new one. Updates lastUsedAt to now.
 * Beyond 20 connections, evicts the lowest lastUsedAt.
 * Returns the saved connection (with id/createdAt/lastUsedAt filled).
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

  // Check for duplicate name (case-insensitive)
  const sameName = connections.findIndex(
    (c) => c.name.toLowerCase() === draft.name.toLowerCase(),
  );
  if (sameName !== -1) {
    // Overwrite the existing connection with the same name
    connections[sameName] = {
      ...connections[sameName],
      ...draft,
      lastUsedAt: now_,
      createdAt: connections[sameName].createdAt,
    };
    persistConnections(connections);
    return connections[sameName];
  }

  // Create new connection
  const connection: SavedConnection = {
    ...draft,
    id: generateId(),
    createdAt: now_,
    lastUsedAt: now_,
  };

  connections.push(connection);

  // Evict oldest if over cap
  if (connections.length > MAX_CONNECTIONS) {
    connections.sort((a, b) => b.lastUsedAt - a.lastUsedAt);
    connections.length = MAX_CONNECTIONS;
  }

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
 */
export function migrateSavedConnections(raw: string | null): SavedConnectionsV1 {
  if (!raw) {
    return { schemaVersion: 1, connections: [] };
  }
  const parsed = JSON.parse(raw) as Partial<SavedConnectionsV1>;

  // Already v1
  if (parsed.schemaVersion === 1 && Array.isArray(parsed.connections)) {
    return parsed as SavedConnectionsV1;
  }

  // Unknown or missing schemaVersion: try to extract connections from legacy
  // or treat as empty
  if (Array.isArray(parsed.connections)) {
    return { schemaVersion: 1, connections: parsed.connections };
  }

  // Might be a legacy "Last session" migration: an array of connections directly
  if (Array.isArray(parsed)) {
    return { schemaVersion: 1, connections: parsed as SavedConnection[] };
  }

  return { schemaVersion: 1, connections: [] };
}