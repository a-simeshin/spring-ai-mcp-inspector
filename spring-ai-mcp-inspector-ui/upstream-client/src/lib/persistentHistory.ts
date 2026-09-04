// [spring-ai-mcp-inspector PATCH] Persistent history storage layer.
// Persists inspector request/response history in localStorage under a single
// schema-versioned key, bucketed by connection id.
// See NOTICE.d/persistent-history.txt for details.

import type { HistoryEntry, HistoryStoreV1 } from "./types/historyEntry";

export const HISTORY_KEY = "mcp-inspector.history.v1";

const MAX_ENTRIES_PER_CONNECTION = 100;
const MAX_STORE_SIZE_BYTES = 500 * 1024; // 500 KB
const MAX_BODY_LENGTH = 10 * 1024; // 10 KB per request/response string

/**
 * Read the full history store from localStorage.
 * Returns empty store on corrupt data.
 */
function readStore(): HistoryStoreV1 {
  const raw = localStorage.getItem(HISTORY_KEY);
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
 * On QuotaExceededError, logs a warning and does not persist.
 */
function writeStore(store: HistoryStoreV1): void {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(store));
  } catch (error) {
    if (
      error instanceof DOMException &&
      error.name === "QuotaExceededError"
    ) {
      console.warn(
        "[persistentHistory] localStorage quota exceeded, history not persisted",
      );
    } else {
      throw error;
    }
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
 * Evicts entire oldest connection buckets (by their oldest entry) until
 * the store is under the limit.
 */
function evictGlobal(store: HistoryStoreV1): void {
  while (storeSize(store) > MAX_STORE_SIZE_BYTES) {
    const connectionIds = Object.keys(store.byConnection);
    if (connectionIds.length === 0) {
      break;
    }

    // Find the bucket with the oldest youngest entry (or smallest at)
    // This is a heuristic: drop the bucket with the oldest *most recent* entry
    // i.e., the least recently used connection
    let oldestBucketId = connectionIds[0];
    let oldestBucketLastAt = Infinity;

    for (const id of connectionIds) {
      const bucket = store.byConnection[id];
      if (!Array.isArray(bucket) || bucket.length === 0) {
        // Non-array or empty bucket, remove it
        delete store.byConnection[id];
        continue;
      }
      // Use the newest entry's timestamp as the "freshness" marker
      // The oldest bucket = the one with the smallest newest-entry timestamp
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
  const store = readStore();

  // Initialize bucket if needed
  if (!store.byConnection[connectionId]) {
    store.byConnection[connectionId] = [];
  }

  // Truncate request and response to MAX_BODY_LENGTH
  const truncated: HistoryEntry = {
    request: entry.request.slice(0, MAX_BODY_LENGTH),
    response: entry.response !== undefined
      ? entry.response.slice(0, MAX_BODY_LENGTH)
      : undefined,
    at: entry.at,
  };

  // Append entry
  store.byConnection[connectionId].push(truncated);

  // Trim per-connection FIFO
  store.byConnection[connectionId] = trimBucket(
    store.byConnection[connectionId],
  );

  // Evict globally if over limit
  evictGlobal(store);

  // Persist
  writeStore(store);
}

/**
 * Clear history for a specific connection.
 */
export function clearHistory(connectionId: string): void {
  const store = readStore();
  delete store.byConnection[connectionId];
  writeStore(store);
}

/**
 * Clear all history across all connections.
 */
export function clearAllHistory(): void {
  localStorage.removeItem(HISTORY_KEY);
}