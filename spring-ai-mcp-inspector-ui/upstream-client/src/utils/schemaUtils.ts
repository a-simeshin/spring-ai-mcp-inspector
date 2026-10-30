import type { JsonValue, JsonSchemaType, JsonObject } from "./jsonUtils";
import Ajv from "ajv";
import type { ValidateFunction } from "ajv";
import type { Tool, JSONRPCMessage } from "@modelcontextprotocol/sdk/types.js";
import { isJSONRPCRequest } from "@modelcontextprotocol/sdk/types.js";

const ajv = new Ajv();

// Cache for compiled validators
const toolOutputValidators = new Map<string, ValidateFunction>();

/**
 * Compiles and caches output schema validators for a list of tools
 * Following the same pattern as SDK's Client.cacheToolOutputSchemas
 * @param tools Array of tools that may have output schemas
 */
export function cacheToolOutputSchemas(tools: Tool[]): void {
  toolOutputValidators.clear();
  for (const tool of tools) {
    if (advertisesOutputSchema(tool.outputSchema)) {
      try {
        const validator = ajv.compile(tool.outputSchema);
        toolOutputValidators.set(tool.name, validator);
      } catch (error) {
        console.warn(
          `Failed to compile output schema for tool ${tool.name}:`,
          error,
        );
      }
    }
  }
}

/**
 * Gets the cached output schema validator for a tool
 * Following the same pattern as SDK's Client.getToolOutputValidator
 * @param toolName Name of the tool
 * @returns The compiled validator function, or undefined if not found
 */
export function getToolOutputValidator(
  toolName: string,
): ValidateFunction | undefined {
  return toolOutputValidators.get(toolName);
}

/**
 * Validates structured content against a tool's output schema
 * Returns validation result with detailed error messages
 * @param toolName Name of the tool
 * @param structuredContent The structured content to validate
 * @returns An object with isValid boolean and optional error message
 */
export function validateToolOutput(
  toolName: string,
  structuredContent: unknown,
): { isValid: boolean; error?: string } {
  const validator = getToolOutputValidator(toolName);
  if (!validator) {
    return { isValid: true }; // No validator means no schema to validate against
  }

  const isValid = validator(structuredContent);
  if (!isValid) {
    return {
      isValid: false,
      error: ajv.errorsText(validator.errors),
    };
  }

  return { isValid: true };
}

/**
 * Checks if a tool has an output schema
 * @param toolName Name of the tool
 * @returns true if the tool has an output schema
 */
export function hasOutputSchema(toolName: string): boolean {
  return toolOutputValidators.has(toolName);
}

/**
 * Generates a default value based on a JSON schema type
 * @param schema The JSON schema definition
 * @param propertyName Optional property name for checking if it's required in parent schema
 * @param parentSchema Optional parent schema to check required array
 * @returns A default value matching the schema type
 */
export function generateDefaultValue(
  schema: JsonSchemaType,
  propertyName?: string,
  parentSchema?: JsonSchemaType,
): JsonValue {
  if ("default" in schema && schema.default !== undefined) {
    return schema.default;
  }

  // Check if this property is required in the parent schema
  const isRequired =
    propertyName && parentSchema
      ? isPropertyRequired(propertyName, parentSchema)
      : false;
  const isRootSchema = propertyName === undefined && parentSchema === undefined;

  switch (schema.type) {
    case "string":
      return isRequired ? "" : undefined;
    case "number":
    case "integer":
      return isRequired ? 0 : undefined;
    case "boolean":
      return isRequired ? false : undefined;
    case "array":
      return isRequired ? [] : undefined;
    case "object": {
      if (!schema.properties) {
        return isRequired || isRootSchema ? {} : undefined;
      }

      const obj: JsonObject = {};
      // Include required properties OR optional properties that declare a default
      Object.entries(schema.properties).forEach(([key, prop]) => {
        const hasExplicitDefault =
          "default" in prop && (prop as JsonSchemaType).default !== undefined;
        if (isPropertyRequired(key, schema) || hasExplicitDefault) {
          const value = generateDefaultValue(prop, key, schema);
          if (value !== undefined) {
            obj[key] = value;
          }
        }
      });

      if (Object.keys(obj).length === 0) {
        return isRequired || isRootSchema ? {} : undefined;
      }
      return obj;
    }
    case "null":
      return null;
    default:
      return undefined;
  }
}

/**
 * Helper function to check if a property is required in a schema
 * @param propertyName The name of the property to check
 * @param schema The parent schema containing the required array
 * @returns true if the property is required, false otherwise
 */
export function isPropertyRequired(
  propertyName: string,
  schema: JsonSchemaType,
): boolean {
  return schema.required?.includes(propertyName) ?? false;
}

/**
 * Resolves $ref references in JSON schema
 * @param schema The schema that may contain $ref
 * @param rootSchema The root schema to resolve references against
 * @param visitedRefs Optional set of visited $ref paths to detect circular references
 * @returns The resolved schema without $ref
 */
export function resolveRef(
  schema: JsonSchemaType,
  rootSchema: JsonSchemaType,
  visitedRefs: Set<string> = new Set(),
): JsonSchemaType {
  if (!schema) return schema;

  if (!("$ref" in schema) || !schema.$ref) {
    // Recursively resolve $ref in anyOf (and other nested structures)
    if (schema.anyOf && Array.isArray(schema.anyOf)) {
      const resolvedAnyOf = schema.anyOf.map((item) => {
        if (typeof item === "object" && item !== null) {
          return resolveRef(item, rootSchema, visitedRefs);
        }
        return item;
      });
      return {
        ...schema,
        anyOf: resolvedAnyOf,
      };
    }
    return schema;
  }

  const ref = schema.$ref;

  // Handle all #/ formats (#/properties/, #/$defs/, etc.)
  if (ref.startsWith("#/")) {
    // Check for circular reference
    if (visitedRefs.has(ref)) {
      console.warn(`Circular reference detected: ${ref}`);
      return schema;
    }

    // Add current ref to visited set
    visitedRefs.add(ref);

    const path = ref.substring(2).split("/").map(decodePointerToken);
    let current: unknown = rootSchema;

    for (const segment of path) {
      if (
        segment !== null &&
        current &&
        typeof current === "object" &&
        current !== null &&
        Object.prototype.hasOwnProperty.call(current, segment)
      ) {
        current = (current as Record<string, unknown>)[segment];
      } else {
        // If reference cannot be resolved, return the original schema
        visitedRefs.delete(ref); // Clean up on failure
        console.warn(`Could not resolve $ref: ${ref}`);
        return schema;
      }
    }

    const resolved = current as JsonSchemaType;

    // Recursively resolve nested structures (anyOf, oneOf, items, properties)
    return resolveRef(resolved, rootSchema, visitedRefs);
  }

  // For other types of references, return the original schema
  console.warn(`Unsupported $ref format: ${ref}`);
  return schema;
}

/**
 * Normalizes union types (like string|null from FastMCP) to simple types for form rendering
 * @param schema The JSON schema to normalize
 * @returns A normalized schema or the original schema
 */
export function normalizeUnionType(schema: JsonSchemaType): JsonSchemaType {
  // Handle anyOf with exactly 2 items (type and null) - unified handling
  // Preserves enum and other properties automatically
  if (
    schema.anyOf &&
    schema.anyOf.length === 2 &&
    schema.anyOf.some((t) => (t as JsonSchemaType).type === "null")
  ) {
    const nonNullItem = schema.anyOf.find((t) => {
      const item = t as JsonSchemaType;
      return item?.type !== "null";
    }) as JsonSchemaType;

    // Only process if non-null item has type or enum
    if (nonNullItem?.type || nonNullItem?.enum) {
      return {
        ...schema,
        ...nonNullItem,
        type: nonNullItem?.type || (nonNullItem?.enum ? "string" : undefined),
        nullable: true,
        anyOf: undefined,
      };
    }
  }

  // Handle array type with exactly string and null
  if (
    Array.isArray(schema.type) &&
    schema.type.length === 2 &&
    schema.type.includes("string") &&
    schema.type.includes("null")
  ) {
    return { ...schema, type: "string", nullable: true };
  }

  // Handle array type with exactly boolean and null
  if (
    Array.isArray(schema.type) &&
    schema.type.length === 2 &&
    schema.type.includes("boolean") &&
    schema.type.includes("null")
  ) {
    return { ...schema, type: "boolean", nullable: true };
  }

  // Handle array type with exactly number and null
  if (
    Array.isArray(schema.type) &&
    schema.type.length === 2 &&
    schema.type.includes("number") &&
    schema.type.includes("null")
  ) {
    return { ...schema, type: "number", nullable: true };
  }

  // Handle array type with exactly integer and null
  if (
    Array.isArray(schema.type) &&
    schema.type.length === 2 &&
    schema.type.includes("integer") &&
    schema.type.includes("null")
  ) {
    return { ...schema, type: "integer", nullable: true };
  }

  return schema;
}

// [spring-ai-mcp-inspector PATCH] $ref schema warnings (Spring AI #5888 detector)
/**
 * Decodes a single JSON Pointer token per RFC 6901.
 * Order matters: ~1 -> / first, then ~0 -> ~.
 * Returns null for malformed percent escapes (e.g. "a%"): server input is
 * untrusted, an undecodable token must read as unresolvable, never throw
 * (URIError would blank the whole UI: the scan runs in a render-phase useMemo).
 */
function decodePointerToken(token: string): string | null {
  try {
    return decodeURIComponent(token.replace(/~1/g, "/").replace(/~0/g, "~"));
  } catch {
    return null;
  }
}

/**
 * Checks whether a $ref pointer can be resolved within a root schema.
 * Only handles #/-prefixed paths (e.g. #/properties/foo, #/$defs/Bar).
 * Segments are decoded per RFC 6901 before lookup.
 */
function canResolveRef(ref: string, rootSchema: JsonSchemaType): boolean {
  if (ref === "#") return true; // RFC 6901: empty fragment means the root document

  // External URIs and plain-name ($anchor) refs are not local JSON Pointers
  // and are NOT the spring-ai#5888 bug. Exclude them from this detector:
  // they are not "unresolvable within the schema document".
  if (!ref.startsWith("#/")) return true;

  const path = ref.substring(2).split("/").map(decodePointerToken);
  let current: unknown = rootSchema;

  for (const segment of path) {
    if (
      segment !== null &&
      current &&
      typeof current === "object" &&
      current !== null &&
      Object.prototype.hasOwnProperty.call(current, segment)
    ) {
      current = (current as Record<string, unknown>)[segment];
    } else {
      return false;
    }
  }

  return true;
}

/**
 * Recursively traverses a schema subtree collecting $ref pointers that
 * cannot be resolved within the root schema. Does NOT mutate the schema.
 */
function collectUnresolvedRefs(
  schema: JsonSchemaType,
  rootSchema: JsonSchemaType,
  result: string[],
): void {
  if (!schema || typeof schema !== "object") return;

  if ("$ref" in schema && typeof schema.$ref === "string") {
    const ref = schema.$ref;
    if (!canResolveRef(ref, rootSchema) && !result.includes(ref)) {
      result.push(ref);
    }
    // Do NOT return early -- sibling keywords (e.g. allOf on a schema with
    // $ref) must still be traversed.
  }

  // Descend into nested schema containers.
  if (schema.properties) {
    for (const prop of Object.values(schema.properties)) {
      collectUnresolvedRefs(prop as JsonSchemaType, rootSchema, result);
    }
  }
  if (schema.items) {
    if (Array.isArray(schema.items)) {
      for (const item of schema.items) {
        collectUnresolvedRefs(item as JsonSchemaType, rootSchema, result);
      }
    } else {
      collectUnresolvedRefs(schema.items as JsonSchemaType, rootSchema, result);
    }
  }
  if (schema.anyOf) {
    for (const item of schema.anyOf) {
      collectUnresolvedRefs(item as JsonSchemaType, rootSchema, result);
    }
  }
  if (schema.oneOf) {
    for (const item of schema.oneOf) {
      collectUnresolvedRefs(item as JsonSchemaType, rootSchema, result);
    }
  }
  // allOf, $defs, not, if/then/else, contains, prefixItems,
  // patternProperties, dependentSchemas and other standard sub-schema
  // keywords are not declared on JsonSchemaType; access via type assertion.
  const extended = schema as JsonSchemaType & {
    allOf?: JsonSchemaType[];
    $defs?: Record<string, JsonSchemaType>;
    definitions?: Record<string, JsonSchemaType>;
    dependencies?: Record<string, JsonSchemaType | string[]>;
    not?: JsonSchemaType;
    if?: JsonSchemaType;
    then?: JsonSchemaType;
    else?: JsonSchemaType;
    contains?: JsonSchemaType;
    prefixItems?: JsonSchemaType[];
    additionalProperties?: JsonSchemaType;
    additionalItems?: JsonSchemaType;
    propertyNames?: JsonSchemaType;
    dependentSchemas?: Record<string, JsonSchemaType>;
    patternProperties?: Record<string, JsonSchemaType>;
    contentSchema?: JsonSchemaType;
    unevaluatedProperties?: JsonSchemaType;
    unevaluatedItems?: JsonSchemaType;
  };
  if (extended.allOf) {
    for (const item of extended.allOf) {
      collectUnresolvedRefs(item, rootSchema, result);
    }
  }
  if (extended.$defs) {
    for (const def of Object.values(extended.$defs)) {
      collectUnresolvedRefs(def, rootSchema, result);
    }
  }
  if (extended.definitions) {
    for (const def of Object.values(extended.definitions)) {
      collectUnresolvedRefs(def, rootSchema, result);
    }
  }
  if (extended.dependencies) {
    for (const dep of Object.values(extended.dependencies)) {
      // dependencies entries may be a schema (object) or a string[]
      // (property-name dependency). Only traverse the schema form.
      if (dep && typeof dep === "object" && !Array.isArray(dep)) {
        collectUnresolvedRefs(dep as JsonSchemaType, rootSchema, result);
      }
    }
  }
  if (extended.not) {
    collectUnresolvedRefs(extended.not, rootSchema, result);
  }
  if (extended.if) {
    collectUnresolvedRefs(extended.if, rootSchema, result);
  }
  if (extended.then) {
    collectUnresolvedRefs(extended.then, rootSchema, result);
  }
  if (extended.else) {
    collectUnresolvedRefs(extended.else, rootSchema, result);
  }
  if (extended.contains) {
    collectUnresolvedRefs(extended.contains, rootSchema, result);
  }
  if (extended.prefixItems) {
    for (const item of extended.prefixItems) {
      collectUnresolvedRefs(item, rootSchema, result);
    }
  }
  if (extended.additionalProperties) {
    collectUnresolvedRefs(extended.additionalProperties, rootSchema, result);
  }
  if (extended.additionalItems) {
    collectUnresolvedRefs(extended.additionalItems, rootSchema, result);
  }
  if (extended.propertyNames) {
    collectUnresolvedRefs(extended.propertyNames, rootSchema, result);
  }
  if (extended.dependentSchemas) {
    for (const dep of Object.values(extended.dependentSchemas)) {
      collectUnresolvedRefs(dep, rootSchema, result);
    }
  }
  if (extended.patternProperties) {
    for (const pat of Object.values(extended.patternProperties)) {
      collectUnresolvedRefs(pat, rootSchema, result);
    }
  }
  if (extended.contentSchema) {
    collectUnresolvedRefs(extended.contentSchema, rootSchema, result);
  }
  if (extended.unevaluatedProperties) {
    collectUnresolvedRefs(extended.unevaluatedProperties, rootSchema, result);
  }
  if (extended.unevaluatedItems) {
    collectUnresolvedRefs(extended.unevaluatedItems, rootSchema, result);
  }
}

/**
 * Finds all unresolvable $ref pointers within a JSON schema.
 * Returns an array of the exact $ref strings (e.g. "#/properties/missing")
 * that could not be resolved against the schema document itself.
 */
export function findUnresolvedRefs(rootSchema: JsonSchemaType): string[] {
  if (!rootSchema || typeof rootSchema !== "object") return [];

  const result: string[] = [];
  collectUnresolvedRefs(rootSchema, rootSchema, result);
  return result;
}

/**
 * Result of scanning a tool's schemas for unresolved $ref pointers.
 */
export type UnresolvedRefInfo = {
  ref: string;
  location: "inputSchema" | "outputSchema";
};

/**
 * Scans both inputSchema and outputSchema of a Tool (if present) for
 * unresolvable $ref pointers. Returns the list of findings.
 */
export function findToolSchemaWarnings(tool: {
  inputSchema?: JsonSchemaType;
  outputSchema?: JsonSchemaType;
}): UnresolvedRefInfo[] {
  const warnings: UnresolvedRefInfo[] = [];

  if (tool.inputSchema) {
    for (const ref of findUnresolvedRefs(tool.inputSchema)) {
      warnings.push({ ref, location: "inputSchema" });
    }
  }
  if (tool.outputSchema) {
    for (const ref of findUnresolvedRefs(tool.outputSchema)) {
      warnings.push({ ref, location: "outputSchema" });
    }
  }

  return warnings;
}

/**
 * Formats a field key into a human-readable label
 * @param key The field key to format
 * @returns A formatted label string
 */
export function formatFieldLabel(key: string): string {
  return key
    .replace(/([A-Z])/g, " $1") // Insert space before capital letters
    .replace(/_/g, " ") // Replace underscores with spaces
    .replace(/^\w/, (c) => c.toUpperCase()); // Capitalize first letter
}

/**
 * Resolves `$ref` references in a JSON-RPC "elicitation/create" message's `requestedSchema` field
 * @param message The JSON-RPC message that may contain $ref references
 * @returns A new message with resolved $ref references, or the original message if no resolution is needed
 */
export function resolveRefsInMessage(message: JSONRPCMessage): JSONRPCMessage {
  if (!isJSONRPCRequest(message) || !message.params?.requestedSchema) {
    return message;
  }

  const requestedSchema = message.params.requestedSchema as JsonSchemaType;

  if (!requestedSchema?.properties) {
    return message;
  }

  const resolvedMessage = {
    ...message,
    params: {
      ...message.params,
      requestedSchema: {
        ...requestedSchema,
        properties: Object.fromEntries(
          Object.entries(requestedSchema.properties).map(
            ([key, propSchema]) => {
              const resolved = resolveRef(propSchema, requestedSchema);
              const normalized = normalizeUnionType(resolved);
              return [key, normalized];
            },
          ),
        ),
      },
    },
  };

  return resolvedMessage;
}

// [spring-ai-mcp-inspector PATCH] missing-output-schema-warning (#6773):
// schema generation in spring-ai silently skips primitive/simple return types,
// so a @McpTool(generateOutputSchema=true) method returning String/int/...
// ends up advertising no outputSchema. The client cannot observe the
// annotation, so detection is best-effort and result-shaped: warn only when
// the observed result itself encodes a JSON primitive (a quoted JSON string,
// number, boolean, or null). Plain text output is the normal case for a
// schema-less String tool and is never flagged. Known tradeoff: a
// numeric/boolean result without the annotation is indistinguishable from
// the #6773 shape client-side and still warns. Structured results (any JSON
// object or array block, object structuredContent) are out of scope.
const PRIMITIVE_CONTENT_TYPES = new Set(["string", "number", "boolean"]);

/**
 * Minimal shape of a CallToolResult needed for #6773 detection. We avoid
 * pulling in the full SDK union type here so the helper stays easy to test.
 */
type ToolCallResultLike = {
  content?: Array<{
    type?: string;
    text?: string;
    [key: string]: unknown;
  }>;
  structuredContent?: unknown;
  isError?: boolean;
};

/**
 * True when an outputSchema actually describes a value: a declared type, at
 * least one property, a $ref, or a compositor keyword (anyOf/oneOf). Absent,
 * null, and shapeless ({} / {not: {}}) schemas read as "no schema
 * advertised". Shared by cacheToolOutputSchemas and the #6773 detector so
 * the validators cache and the detector can never disagree about whether a
 * tool advertised an output schema.
 */
function advertisesOutputSchema(
  schema: JsonSchemaType | null | undefined,
): schema is JsonSchemaType {
  if (!schema || typeof schema !== "object") {
    return false;
  }
  return (
    schema.type !== undefined ||
    (schema.properties !== undefined &&
      schema.properties !== null &&
      Object.keys(schema.properties).length > 0) ||
    schema.$ref !== undefined ||
    schema.anyOf !== undefined ||
    schema.oneOf !== undefined
  );
}

/**
 * True when the observed result looks like an encoded JSON primitive:
 * structuredContent is itself a primitive, or at least one text block parses
 * as a JSON primitive (quoted string, number, boolean, null) while no text
 * block parses as a JSON object or array. Plain text that fails JSON.parse
 * is NOT a #6773 signal: it is the normal output of a schema-less String
 * tool, so it neither triggers nor vetoes.
 */
function isPrimitiveResult(result: ToolCallResultLike): boolean {
  const structured = result.structuredContent;
  if (structured !== undefined && structured !== null) {
    return PRIMITIVE_CONTENT_TYPES.has(typeof structured);
  }

  if (!Array.isArray(result.content)) {
    return false;
  }

  let sawPrimitive = false;
  for (const block of result.content) {
    if (!block || block.type !== "text") {
      continue;
    }
    const text = block.text;
    if (typeof text !== "string") {
      continue;
    }
    const trimmed = text.trim();
    if (trimmed === "") {
      continue;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(trimmed);
    } catch {
      // Plain (non-JSON) text: the normal case for a schema-less String
      // tool. Not a #6773 signal; other blocks may still decide.
      continue;
    }
    if (parsed === null || PRIMITIVE_CONTENT_TYPES.has(typeof parsed)) {
      sawPrimitive = true;
    } else {
      // A JSON object or array in any block means the result is structured
      // and out of scope for #6773, even if another block is a primitive.
      return false;
    }
  }
  return sawPrimitive;
}

/**
 * Detects the spring-ai#6773 shape from the observed result: the tool
 * advertises no meaningful outputSchema while the result encodes a JSON
 * primitive. Best-effort only, because the generateOutputSchema annotation
 * is server-side; see the block comment above for the exact tradeoffs.
 *
 * @returns the warning text with a link to the upstream issue, or null when
 * the result does not look like the #6773 case.
 */
export function findMissingOutputSchemaWarning(
  tool: { name: string; outputSchema?: JsonSchemaType | null } | null,
  toolResult: ToolCallResultLike | null,
): string | null {
  if (!tool || !toolResult) {
    return null;
  }

  // Same predicate the validators cache uses, so a schema the UI treats as
  // advertised is never reported here as missing.
  if (advertisesOutputSchema(tool.outputSchema)) {
    return null;
  }

  // Error results say nothing about the success-path output shape.
  if (toolResult.isError === true) {
    return null;
  }

  if (!isPrimitiveResult(toolResult)) {
    return null;
  }

  return (
    `Tool '${tool.name ?? "unknown"}' returned a JSON primitive but ` +
    `advertises no output schema. If @McpTool(generateOutputSchema=true) ` +
    `was intended, spring-ai skipped schema generation for the primitive ` +
    `return type. Plain-text results are not flagged. See ` +
    `https://github.com/spring-projects/spring-ai/issues/6773.`
  );
}
