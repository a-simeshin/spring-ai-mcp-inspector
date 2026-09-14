// [spring-ai-mcp-inspector PATCH] JSON-aware structural diff for Replay & diff view.
// Produces a flat list of leaf-path differences between two JSON values.

export type DiffEntryType = "added" | "removed" | "changed";

export interface DiffEntry {
  path: string;
  type: DiffEntryType;
  oldValue?: unknown;
  newValue?: unknown;
}

/**
 * Recursively compares two JSON values and returns leaf-level differences.
 * - Objects: keys present in only one side are added/removed; common keys recurse.
 * - Arrays: compared element-by-element up to the shorter length; extra elements
 *   on either side are added/removed.  No reorder detection.
 * - Primitives: strict inequality is a change.
 */
export function diffJson(oldVal: unknown, newVal: unknown, basePath = ""): DiffEntry[] {
  const entries: DiffEntry[] = [];

  if (oldVal === newVal) return entries;

  const oldIsObj = oldVal !== null && typeof oldVal === "object" && !Array.isArray(oldVal);
  const newIsObj = newVal !== null && typeof newVal === "object" && !Array.isArray(newVal);
  const oldIsArr = Array.isArray(oldVal);
  const newIsArr = Array.isArray(newVal);

  if (oldIsObj && newIsObj) {
    const oldObj = oldVal as Record<string, unknown>;
    const newObj = newVal as Record<string, unknown>;
    const allKeys = new Set([...Object.keys(oldObj), ...Object.keys(newObj)]);
    for (const key of allKeys) {
      const path = basePath ? `${basePath}.${key}` : key;
      if (!(key in oldObj)) {
        entries.push({ path, type: "added", newValue: newObj[key] });
      } else if (!(key in newObj)) {
        entries.push({ path, type: "removed", oldValue: oldObj[key] });
      } else {
        entries.push(...diffJson(oldObj[key], newObj[key], path));
      }
    }
    return entries;
  }

  if (oldIsArr && newIsArr) {
    const oldArr = oldVal as unknown[];
    const newArr = newVal as unknown[];
    const minLen = Math.min(oldArr.length, newArr.length);
    for (let i = 0; i < minLen; i++) {
      const path = basePath ? `${basePath}[${i}]` : `[${i}]`;
      entries.push(...diffJson(oldArr[i], newArr[i], path));
    }
    for (let i = minLen; i < oldArr.length; i++) {
      const path = basePath ? `${basePath}[${i}]` : `[${i}]`;
      entries.push({ path, type: "removed", oldValue: oldArr[i] });
    }
    for (let i = minLen; i < newArr.length; i++) {
      const path = basePath ? `${basePath}[${i}]` : `[${i}]`;
      entries.push({ path, type: "added", newValue: newArr[i] });
    }
    return entries;
  }

  // One side changed type, or both are primitives with different values.
  entries.push({
    path: basePath || "(root)",
    type: "changed",
    oldValue: oldVal,
    newValue: newVal,
  });
  return entries;
}

/** Format a value for display in the diff view. */
export function formatDiffValue(value: unknown): string {
  if (value === undefined) return "undefined";
  if (value === null) return "null";
  if (typeof value === "string") return `"${value}"`;
  try {
    return JSON.stringify(value, null, 0);
  } catch {
    return String(value);
  }
}
