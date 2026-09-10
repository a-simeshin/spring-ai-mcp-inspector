import type { JsonValue, JsonSchemaType, JsonObject } from "./jsonUtils";
import Ajv from "ajv";
import type { ValidateFunction } from "ajv";
import type { Tool, JSONRPCMessage } from "@modelcontextprotocol/sdk/types.js";
import { isJSONRPCRequest } from "@modelcontextprotocol/sdk/types.js";

// [spring-ai-mcp-inspector PATCH] missing-output-schema-warning (#6773):
// schema generation in spring-ai silently skips primitive/simple return types,
// so a @McpTool(generateOutputSchema=true) that returns String/int/... ends up
// advertising no output schema. We detect the case client-side once the tool
// has been called and the observed result is a primitive (string/number/
// boolean). Structured objects without a schema are NOT flagged here.
const PRIMITIVE_CONTENT_TYPES = new Set(["string", "number", "boolean"]);

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
    if (tool.outputSchema) {
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

    const path = ref.substring(2).split("/");
    let current: unknown = rootSchema;

    for (const segment of path) {
      if (
        current &&
        typeof current === "object" &&
        current !== null &&
        segment in current
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
// minimal shape of a CallToolResult we need for detection. We avoid pulling in
// the full SDK union type here so the helper stays easy to unit-test.
export type ToolCallResultLike = {
  content?: Array<{
    type?: string;
    text?: string;
    [key: string]: unknown;
  }>;
  structuredContent?: unknown;
  isError?: boolean;
};

/**
 * Returns true when the observed tool result looks like a primitive (string,
 * number, boolean) rather than a structured object. We look at the text
 * content blocks first (this is where spring-ai places String/int/boolean
 * return values), then at structuredContent for the rare case where a
 * primitive was placed there.
 */
function isPrimitiveResult(result: ToolCallResultLike): boolean {
  if (result.structuredContent !== undefined && result.structuredContent !== null) {
    return PRIMITIVE_CONTENT_TYPES.has(typeof result.structuredContent);
  }

  if (!Array.isArray(result.content)) {
    return false;
  }

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
    // Try JSON first: a bare "foo" / 42 / true parses as a primitive, while
    // structured objects parse to { ... } / [ ... ]. If JSON.parse fails, the
    // text content is a plain string, which IS the primitive case (#6773).
    try {
      const parsed: unknown = JSON.parse(trimmed);
      if (PRIMITIVE_CONTENT_TYPES.has(typeof parsed)) {
        return true;
      }
    } catch {
      // Plain string content: this is exactly what a String-returning tool
      // produces when schema generation skipped the return type.
      return true;
    }
  }

  return false;
}

/**
 * Detects the spring-ai#6773 case: a tool was advertised without an output
 * schema (or with an empty one), and an observed call result returns a
 * primitive value (string / number / boolean). Schema generation silently
 * skips primitive return types, so the tool likely was annotated
 * `@McpTool(generateOutputSchema=true)` but no schema was emitted.
 *
 * Returns a short human-readable warning string when the detector fires, or
 * null when no warning applies. Structured objects without a schema are NOT
 * flagged: they are out of scope for this detector.
 */
export function findMissingOutputSchemaWarning(
  tool: { name?: string; outputSchema?: JsonSchemaType | null },
  toolResult: ToolCallResultLike | null,
): string | null {
  if (!tool || !toolResult) {
    return null;
  }

  // If the tool DID advertise an output schema, there is nothing to detect.
  const advertised = tool.outputSchema;
  if (advertised && typeof advertised === "object") {
    const hasShape =
      advertised.type !== undefined ||
      (advertised.properties !== undefined &&
        advertised.properties !== null &&
        Object.keys(advertised.properties).length > 0) ||
      advertised.$ref !== undefined;
    if (hasShape) {
      return null;
    }
  }

  // Errors are not representative: skip them so the warning does not fire on
  // a tool that legitimately returns String but happened to error here.
  if (toolResult.isError === true) {
    return null;
  }

  if (!isPrimitiveResult(toolResult)) {
    return null;
  }

  return (
    `Tool '${tool.name ?? "unknown"}' returned a primitive value but ` +
    `advertises no output schema. Schema generation likely skipped the ` +
    `primitive return type. See https://github.com/spring-projects/spring-ai/issues/6773.`
  );
}
