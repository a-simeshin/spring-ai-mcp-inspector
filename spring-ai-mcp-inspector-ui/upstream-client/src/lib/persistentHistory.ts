// [spring-ai-mcp-inspector PATCH] Persistent history storage layer.
// Persists inspector request/response history in localStorage under a single
// schema-versioned key, bucketed by connection id.
// See NOTICE.d/persistent-history.txt for details.

import type { HistoryEntry, HistoryStoreV1 } from "./types/historyEntry";

export const HISTORY_KEY = "mcp-inspector.history.v1";

const MAX_ENTRIES_PER_CONNECTION = 100;
const MAX_STORE_SIZE_BYTES = 500 * 1024; // 500 KB
const MAX_BODY_LENGTH = 10 * 1024; // 10 KB per request/response string
const TRUNCATION_MARKER = "...[truncated]";

/**
 * Detect a store written by a different (e.g. newer) schema version.
 * readStore() exposes an empty view of such a store, and the writers
 * (appendHistory/clearHistory) refuse to touch it: overwriting a newer
 * store with v1 data would destroy that data on a downgrade.
 */
export function isForeignSchemaVersion(): boolean {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(HISTORY_KEY);
  } catch {
    return false;
  }
  if (!raw) {
    return false;
  }
  try {
    const parsed = JSON.parse(raw) as { schemaVersion?: unknown };
    return (
      typeof parsed.schemaVersion === "number" && parsed.schemaVersion !== 1
    );
  } catch {
    return false;
  }
}

/**
 * Read the full history store from localStorage.
 * Returns empty store on corrupt or denied storage.
 */
export function readStore(): HistoryStoreV1 {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(HISTORY_KEY);
  } catch {
    console.warn("[persistentHistory] Failed to read localStorage, returning empty store");
    return { schemaVersion: 1, byConnection: {} };
  }
  if (!raw) {
    return { schemaVersion: 1, byConnection: {} };
  }
  try {
    const parsed = JSON.parse(raw) as Partial<HistoryStoreV1>;
    if (parsed.schemaVersion === 1 && typeof parsed.byConnection === "object" && parsed.byConnection !== null) {
      // Validate every bucket is an array and every entry passes
      // isValidEntry; drop invalid buckets/entries with console.warn.
      const validated: Record<string, HistoryEntry[]> = {};
      for (const [connId, bucket] of Object.entries(
        parsed.byConnection as Record<string, unknown>,
      )) {
        if (!Array.isArray(bucket)) {
          console.warn(
            `[persistentHistory] Dropping non-array bucket for connection "${connId}"`,
          );
          continue;
        }
        const validEntries = bucket.filter(isValidEntry);
        if (validEntries.length !== bucket.length) {
          console.warn(
            `[persistentHistory] Dropped ${bucket.length - validEntries.length} invalid entries from connection "${connId}"`,
          );
        }
        if (validEntries.length > 0) {
          validated[connId] = validEntries;
        }
      }
      return { schemaVersion: 1, byConnection: validated };
    }
    return { schemaVersion: 1, byConnection: {} };
  } catch {
    console.warn("[persistentHistory] Failed to parse stored history, resetting");
    return { schemaVersion: 1, byConnection: {} };
  }
}

/**
 * Write the full store to localStorage.
 * On any storage error, logs a warning and does not persist.
 */
function writeStore(store: HistoryStoreV1): void {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(store));
  } catch (error) {
    console.warn(
      `[persistentHistory] Failed to write history to localStorage: ${error instanceof DOMException ? error.name : error}`,
    );
  }
}

/**
 * Get the serialized byte size of the store.
 */
function storeSize(store: HistoryStoreV1): number {
  return JSON.stringify(store).length;
}

/**
 * Enforce per-connection FIFO eviction (max 100 entries).
 * Drops oldest `at` entries until the bucket is within limit.
 */
function trimBucket(bucket: HistoryEntry[]): HistoryEntry[] {
  if (bucket.length <= MAX_ENTRIES_PER_CONNECTION) {
    return bucket;
  }
  // Sort by timestamp ascending, keep newest MAX_ENTRIES_PER_CONNECTION
  const sorted = [...bucket].sort((a, b) => a.at - b.at);
  return sorted.slice(sorted.length - MAX_ENTRIES_PER_CONNECTION);
}

/**
 * Enforce global size limit (500 KB).
 * Evicts entire non-current connection buckets (by their newest entry
 * timestamp) until the store is under the limit. The CURRENT connection
 * bucket is never evicted wholesale: instead its oldest entries are
 * trimmed, so an active session never loses the entry it just wrote
 * (review blocker 1 on PR #218: with a single connection, crossing the
 * limit used to zero the whole history).
 */
function evictGlobal(store: HistoryStoreV1, currentConnectionId?: string): void {
  while (storeSize(store) > MAX_STORE_SIZE_BYTES) {
    const connectionIds = Object.keys(store.byConnection);
    if (connectionIds.length === 0) {
      break;
    }

    // Candidate buckets for full eviction: everything except the current
    // connection. If only the current bucket remains, trim its oldest
    // entries instead of dropping it.
    const evictable = connectionIds.filter((id) => id !== currentConnectionId);
    if (evictable.length === 0) {
      const current = store.byConnection[currentConnectionId ?? ""];
      if (!Array.isArray(current) || current.length === 0) {
        break;
      }
      // Drop the oldest entry of the active bucket and re-check the size.
      const sorted = [...current].sort((a, b) => a.at - b.at);
      sorted.shift();
      store.byConnection[currentConnectionId ?? ""] = sorted;
      continue;
    }

    // Among evictable buckets, drop the least recently used one: the
    // bucket whose newest entry is the oldest.
    let oldestBucketId = evictable[0];
    let oldestBucketLastAt = Infinity;

    for (const id of evictable) {
      const bucket = store.byConnection[id];
      if (!Array.isArray(bucket) || bucket.length === 0) {
        // Non-array or empty bucket, remove it
        delete store.byConnection[id];
        continue;
      }
      const lastAt = bucket[bucket.length - 1].at;
      if (lastAt < oldestBucketLastAt) {
        oldestBucketLastAt = lastAt;
        oldestBucketId = id;
      }
    }

    // Evict the oldest bucket entirely
    delete store.byConnection[oldestBucketId];
  }
}

/**
 * Load history entries for a specific connection.
 * Returns empty array if no history exists for that connection.
 * Validates each entry before returning: rejects null, malformed
 * request/response, and missing/invalid at fields.
 */
export function loadHistory(connectionId: string): HistoryEntry[] {
  const store = readStore();
  const bucket = store.byConnection[connectionId];
  if (!Array.isArray(bucket)) {
    return [];
  }
  return bucket.filter(isValidEntry);
}

function isValidEntry(entry: unknown): entry is HistoryEntry {
  if (entry === null || typeof entry !== "object") {
    return false;
  }
  const e = entry as Record<string, unknown>;
  if (typeof e.request !== "string") {
    return false;
  }
  try {
    JSON.parse(e.request);
  } catch {
    return false;
  }
  if (e.response !== undefined && typeof e.response !== "string") {
    return false;
  }
  if (e.response !== undefined) {
    try {
      JSON.parse(e.response);
    } catch {
      return false;
    }
  }
  if (typeof e.at !== "number" || isNaN(e.at)) {
    return false;
  }
  return true;
}

/**
 * Append a history entry for a connection.
 * Respects per-connection cap (100 entries) and global store limit (500 KB).
 * Truncates request and response strings to 10 KB before storage.
 * On localStorage quota error, entry is dropped with a console.warn.
 */
export function appendHistory(
  connectionId: string,
  entry: HistoryEntry,
): void {
  // A store written by a different schema version is not ours to rewrite:
  // appending would replace the whole store with v1 data and destroy it.
  if (isForeignSchemaVersion()) {
    console.warn(
      "[persistentHistory] Refusing to write: store schema version is newer than supported",
    );
    return;
  }
  const store = readStore();

  // Initialize bucket if needed
  if (!store.byConnection[connectionId]) {
    store.byConnection[connectionId] = [];
  }

  // Truncate request and response to MAX_BODY_LENGTH, appending a marker
  // when truncation occurs so the UI shows the body was cut.
  // Parse the JSON and truncate inner content so the result remains valid JSON.
  const truncate = (s: string): string => {
    if (s.length <= MAX_BODY_LENGTH) return s;
    const cut = s.slice(0, MAX_BODY_LENGTH - TRUNCATION_MARKER.length) + TRUNCATION_MARKER;
    try {
      JSON.parse(cut);
      return cut;
    } catch {
      // Invalid JSON after truncation: parse the original body, truncate
      // inner content, re-stringify. A non-JSON body cannot occur from the
      // app path (pushHistory stores JSON.stringify output), and storing
      // the raw cut for one would only produce an entry that isValidEntry
      // discards on read, so there is no fallback for it (PR #218 review
      // minor).
      const obj = JSON.parse(s);
      // Truncate large string fields inside the parsed structure
      const truncateField = (val: unknown): unknown => {
        if (typeof val === "string" && val.length > MAX_BODY_LENGTH / 2) {
          return val.slice(0, MAX_BODY_LENGTH / 2 - TRUNCATION_MARKER.length) + TRUNCATION_MARKER;
        }
        if (Array.isArray(val)) {
          return val.map(truncateField);
        }
        if (val !== null && typeof val === "object") {
          const result: Record<string, unknown> = {};
          for (const [k, v] of Object.entries(val as Record<string, unknown>)) {
            result[k] = truncateField(v);
          }
          return result;
        }
        return val;
      };
      const truncated = truncateField(obj);
      const result = JSON.stringify(truncated);
      if (result.length <= MAX_BODY_LENGTH) return result;
      // Still too long: keep the JSON-RPC envelope fields (jsonrpc, method,
      // id) so the History panel can still render a label for the entry,
      // and mark the payload as truncated. Dropping method/id outright made
      // reloaded entries render without a label (PR #218 review).
      const envelope: Record<string, unknown> = {
        jsonrpc: "2.0",
        truncated: true,
      };
      if (typeof obj === "object" && obj !== null && !Array.isArray(obj)) {
        const candidate = obj as Record<string, unknown>;
        if (typeof candidate.method === "string") {
          envelope.method = candidate.method;
        }
        if (
          typeof candidate.id === "string" ||
          typeof candidate.id === "number"
        ) {
          envelope.id = candidate.id;
        }
      }
      return JSON.stringify(envelope);
    }
  };
  const truncated: HistoryEntry = {
    request: truncate(entry.request),
    response: entry.response !== undefined
      ? truncate(entry.response)
      : undefined,
    at: entry.at,
  };

  // Append entry
  store.byConnection[connectionId].push(truncated);

  // Trim per-connection FIFO
  store.byConnection[connectionId] = trimBucket(
    store.byConnection[connectionId],
  );

  // Evict globally if over limit. The current connection's bucket is
  // spared from full eviction (its oldest entries are trimmed instead).
  evictGlobal(store, connectionId);

  // Persist
  writeStore(store);
}

/**
 * Clear history for a specific connection.
 * Refuses to touch a store written by a different schema version.
 */
export function clearHistory(connectionId: string): void {
  if (isForeignSchemaVersion()) {
    console.warn(
      "[persistentHistory] Refusing to clear: store schema version is newer than supported",
    );
    return;
  }
  const store = readStore();
  delete store.byConnection[connectionId];
  writeStore(store);
}