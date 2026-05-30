// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

/**
 * Metadata helpers aligned with the official MCP specification.
 *
 * @see https://modelcontextprotocol.io/specification/2025-06-18/basic/index#meta
 */

const META_PREFIX_LABEL_REGEX = /^[a-z](?:[a-z\d-]*[a-z\d])?$/i;
const META_NAME_REGEX = /^[a-z\d](?:[a-z\d._-]*[a-z\d])?$/i;
const RESERVED_NAMESPACE_LABELS = ['modelcontextprotocol', 'mcp'];

function getPrefixSegment(key: string): string | null {
  const trimmed = key.trim();
  const slash = trimmed.indexOf('/');
  return slash === -1 ? null : trimmed.slice(0, slash);
}

function normalizeSegment(segment: string): string | null {
  if (!segment) return null;
  let normalized = segment.trim().toLowerCase();
  if (!normalized) return null;
  const schemeIdx = normalized.indexOf('://');
  if (schemeIdx !== -1) normalized = normalized.slice(schemeIdx + 3);
  let endIdx = normalized.length;
  for (const stop of ['?', '#', ':']) {
    const idx = normalized.indexOf(stop);
    if (idx !== -1 && idx < endIdx) endIdx = idx;
  }
  return normalized.slice(0, endIdx) || null;
}

function splitLabels(segment: string): string[] | null {
  const normalized = normalizeSegment(segment);
  if (!normalized) return null;
  const labels = normalized.split('.');
  if (labels.length === 0 || labels.some((l) => !l || !META_PREFIX_LABEL_REGEX.test(l))) {
    return null;
  }
  return labels;
}

/** Returns true when the key belongs to one of the MCP-reserved namespaces. */
export function isReservedMetaKey(key: string): boolean {
  const trimmed = key.trim();
  if (!trimmed) return false;
  const candidate = getPrefixSegment(trimmed) ?? trimmed;
  const labels = splitLabels(candidate);
  if (!labels || labels.length < 2) return false;
  for (let i = 0; i < labels.length - 1; i += 1) {
    if (
      RESERVED_NAMESPACE_LABELS.includes(labels[i]) &&
      META_PREFIX_LABEL_REGEX.test(labels[i + 1])
    ) {
      return true;
    }
  }
  return false;
}

/** Validates the optional prefix portion of a metadata key. */
export function hasValidMetaPrefix(key: string): boolean {
  const seg = getPrefixSegment(key);
  if (seg === null) return true;
  return splitLabels(seg) !== null;
}

/** Validates the "name" portion of a metadata key. */
export function hasValidMetaName(key: string): boolean {
  const trimmed = key.trim();
  if (!trimmed) return false;
  const slash = trimmed.lastIndexOf('/');
  const name = slash === -1 ? trimmed : trimmed.slice(slash + 1);
  if (!name) return false;
  return META_NAME_REGEX.test(name);
}

/** Convenience accessor: prefers `_meta.title` then `title` then `name`. */
export function pickDisplayName(item: {
  name?: string;
  title?: string;
  _meta?: { title?: string };
}): string {
  return item._meta?.title ?? item.title ?? item.name ?? '';
}
