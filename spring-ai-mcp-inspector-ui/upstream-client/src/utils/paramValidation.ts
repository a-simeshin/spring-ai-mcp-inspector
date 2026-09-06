import type { JsonSchemaType } from "./jsonUtils";
import { resolveRef, normalizeUnionType } from "./schemaUtils";

/**
 * A single field-level validation error.
 */
export interface FieldError {
  field: string;
  message: string;
}

/**
 * Validates tool-call parameters against the tool's inputSchema.
 *
 * Rules:
 * - Only fields listed in `schema.required` are checked.
 * - A required field is empty (error: "required") when its value is
 *   undefined, null, or an empty string.
 * - For `integer` properties: the value must be a whole number
 *   (Number.isInteger).  `1.5` is rejected; `1` and `0` pass.
 * - For `number` properties: the value must be a number (typeof === "number").
 *   `1.5` passes, `"abc"` is rejected.
 * - For `boolean` properties: the value must be a boolean.
 * - Nullable fields (nullable: true) accept `null` as valid.
 * - Schemas without a `required` array return no errors.
 * - Ambiguous schemas (no clear primitive type) are skipped — the server
 *   round-trip handles them.
 *
 * @param schema  The tool's inputSchema (JSON Schema).
 * @param params  The current parameter values as stored in state.
 * @returns An array of FieldError — empty when valid.
 */
export function validateToolParams(
  schema: JsonSchemaType,
  params: Record<string, unknown>,
): FieldError[] {
  const errors: FieldError[] = [];
  const required = schema.required;

  // No required array → nothing to validate
  if (!required || required.length === 0) return errors;

  for (const key of required) {
    const rawProp = schema.properties?.[key];
    if (!rawProp) continue;

    const resolved = resolveRef(rawProp as JsonSchemaType, schema);
    const normalized = normalizeUnionType(resolved);
    const value = params[key];

    const isNullable = normalized.nullable === true;
    const isEmpty =
      value === undefined ||
      value === null ||
      (typeof value === "string" && value === "");

    if (isEmpty) {
      if (!isNullable) {
        errors.push({ field: key, message: "required" });
      }
      continue;
    }

    // Type-check: only for clear primitive types
    const propType = Array.isArray(normalized.type)
      ? normalized.type.find((t) => t !== "null")
      : normalized.type;

    switch (propType) {
      case "integer":
        if (typeof value !== "number" || !Number.isInteger(value)) {
          errors.push({ field: key, message: "must be an integer" });
        }
        break;
      case "number":
        if (typeof value !== "number") {
          errors.push({ field: key, message: "must be a number" });
        }
        break;
      case "boolean":
        if (typeof value !== "boolean") {
          errors.push({ field: key, message: "must be a boolean" });
        }
        break;
      // string, array, object, null — no client-side type check
      default:
        break;
    }
  }

  return errors;
}

/**
 * Validates prompt arguments against the prompt's argument definitions.
 *
 * Prompt arguments are simpler than JSON Schema — each argument has a
 * `required?: boolean` flag.  Required arguments with empty values block
 * submission.
 *
 * @param args   The prompt's argument definitions.
 * @param promptArgs  The current argument values as stored in state.
 * @returns An array of FieldError — empty when valid.
 */
export function validatePromptArgs(
  args: { name: string; required?: boolean }[] | undefined,
  promptArgs: Record<string, string>,
): FieldError[] {
  const errors: FieldError[] = [];

  if (!args || args.length === 0) return errors;

  for (const arg of args) {
    if (!arg.required) continue;
    const value = promptArgs[arg.name];
    if (value === undefined || value === "") {
      errors.push({ field: arg.name, message: "required" });
    }
  }

  return errors;
}