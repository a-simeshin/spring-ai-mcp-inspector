// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { isPlainObject } from './jsonUtils';

/** Minimal JSON-Schema subset used by the inspector form renderer. */
export interface JsonSchema {
  type?: string | string[];
  title?: string;
  description?: string;
  required?: string[];
  default?: unknown;
  properties?: Record<string, JsonSchema>;
  items?: JsonSchema;
  enum?: unknown[];
  enumNames?: string[];
  const?: unknown;
  oneOf?: JsonSchema[];
  anyOf?: JsonSchema[];
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  format?: string;
  nullable?: boolean;
}

/** Infers a JSON-schema-style type string from a runtime value. */
export function inferTypeFromValue(v: unknown): string {
  if (Array.isArray(v)) return 'array';
  if (v === null) return 'null';
  return typeof v;
}

/** Returns the list of required property names for an object schema. */
export function getRequiredKeys(schema: JsonSchema | undefined): string[] {
  if (!schema || !Array.isArray(schema.required)) return [];
  return schema.required.slice();
}

/**
 * Builds an initial value tree for the given schema, populating only required
 * fields and fields that declare an explicit `default`.
 */
export function getSchemaDefaults(schema: JsonSchema | undefined): unknown {
  if (!schema) return undefined;
  if (schema.default !== undefined) return schema.default;
  const type = Array.isArray(schema.type) ? schema.type[0] : schema.type;
  switch (type) {
    case 'object': {
      const out: Record<string, unknown> = {};
      const props = schema.properties ?? {};
      const required = new Set(getRequiredKeys(schema));
      for (const [key, sub] of Object.entries(props)) {
        const hasDefault = sub.default !== undefined;
        if (required.has(key) || hasDefault) {
          const val = getSchemaDefaults(sub);
          if (val !== undefined) out[key] = val;
        }
      }
      return out;
    }
    case 'array':
      return [];
    case 'string':
      return '';
    case 'number':
    case 'integer':
      return 0;
    case 'boolean':
      return false;
    case 'null':
      return null;
    default:
      return undefined;
  }
}

/**
 * Walks the schema along a dotted/path-segment list, descending through
 * `properties` for objects and `items` for arrays. Returns `undefined` if
 * the path cannot be resolved.
 */
export function getNestedSchema(
  schema: JsonSchema | undefined,
  path: ReadonlyArray<string | number>,
): JsonSchema | undefined {
  let current: JsonSchema | undefined = schema;
  for (const seg of path) {
    if (!current) return undefined;
    if (typeof seg === 'number' || /^\d+$/.test(String(seg))) {
      current = current.items;
    } else if (current.properties && isPlainObject(current.properties)) {
      current = current.properties[String(seg)];
    } else {
      return undefined;
    }
  }
  return current;
}
