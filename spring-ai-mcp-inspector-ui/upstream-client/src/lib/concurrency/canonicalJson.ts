// [spring-ai-mcp-inspector PATCH] canonical JSON + SHA-256 for concurrency probe (issue #237)
/**
 * Canonical JSON serialization and SHA-256 body hash for the concurrency
 * probe (spec section 3.3). Object keys sorted lexicographically recursively,
 * no insignificant whitespace, arrays in order, numbers per JSON rules.
 *
 * No new dependency: uses Web Crypto (crypto.subtle) in the browser and
 * node:crypto webcrypto in jest/Node environments.
 */

const encoder = new TextEncoder();

async function sha256Hex(data: Uint8Array): Promise<string> {
  const subtle = crypto.subtle;
  const hashBuffer = await subtle.digest("SHA-256", data as BufferSource);
  const hashArray = new Uint8Array(hashBuffer);
  let hex = "";
  for (let i = 0; i < hashArray.length; i++) {
    hex += hashArray[i].toString(16).padStart(2, "0");
  }
  return hex;
}

export function canonicalSerialize(value: unknown): string {
  if (value === null || value === undefined) return "null";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (!isFinite(value)) return "null";
    return String(value);
  }
  if (typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) {
    const items = value.map(canonicalSerialize);
    return "[" + items.join(",") + "]";
  }
  if (typeof value === "object") {
    const keys = Object.keys(value).sort();
    const pairs = keys.map(
      (k) =>
        JSON.stringify(k) +
        ":" +
        canonicalSerialize((value as Record<string, unknown>)[k]),
    );
    return "{" + pairs.join(",") + "}";
  }
  return "null";
}

const TRUNCATE_PREFIX_BYTES = 1024 * 1024; // 1 MB

export interface BodyHashResult {
  hash: string;
  truncated: boolean;
}

/**
 * Compute SHA-256 of the canonical JSON form of the value.
 * If the canonical form exceeds 1 MB serialized, hash only the first 1 MB
 * and set `truncated: true`.
 */
export async function computeBodyHash(value: unknown): Promise<BodyHashResult> {
  const canonical = canonicalSerialize(value);
  const bytes = encoder.encode(canonical);
  if (bytes.length > TRUNCATE_PREFIX_BYTES) {
    const prefix = bytes.subarray(0, TRUNCATE_PREFIX_BYTES);
    const hash = await sha256Hex(prefix);
    return { hash, truncated: true };
  }
  const hash = await sha256Hex(bytes);
  return { hash, truncated: false };
}
