// [spring-ai-mcp-inspector PATCH] Client-side required/type validation for
// tool and prompt forms. Pure functions validateToolParams() and
// validatePromptArgs() are imported by ToolsTab.tsx and PromptsTab.tsx to
// derive the disabled state of the Run Tool / Get Prompt buttons and to
// block submit on validation errors.
// [spring-ai-mcp-inspector PATCH] Unit tests for paramValidation.ts
// (see NOTICE.d/param-validation.txt).
import { describe, it, expect } from "@jest/globals";
import { validateToolParams, validatePromptArgs } from "../paramValidation";
import type { JsonSchemaType } from "../jsonUtils";

describe("validateToolParams", () => {
  it("returns no errors when schema has no required array", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        a: { type: "integer" },
        b: { type: "string" },
      },
    };
    expect(validateToolParams(schema, {})).toEqual([]);
  });

  it("returns no errors when required array is empty", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: [],
      properties: {
        a: { type: "integer" },
      },
    };
    expect(validateToolParams(schema, {})).toEqual([]);
  });

  it("reports required error for empty required string field", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["name"],
      properties: {
        name: { type: "string" },
      },
    };
    expect(validateToolParams(schema, { name: undefined })).toEqual([
      { field: "name", message: "required" },
    ]);
  });

  it("reports required error for empty string value", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["name"],
      properties: {
        name: { type: "string" },
      },
    };
    expect(validateToolParams(schema, { name: "" })).toEqual([
      { field: "name", message: "required" },
    ]);
  });

  it("reports required error for null value on non-nullable field", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["count"],
      properties: {
        count: { type: "integer" },
      },
    };
    expect(validateToolParams(schema, { count: null })).toEqual([
      { field: "count", message: "required" },
    ]);
  });

  it("accepts null value on nullable required field", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["count"],
      properties: {
        count: { type: "integer", nullable: true },
      },
    };
    expect(validateToolParams(schema, { count: null })).toEqual([]);
  });

  it("rejects non-numeric string for integer type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["count"],
      properties: {
        count: { type: "integer" },
      },
    };
    expect(validateToolParams(schema, { count: "abc" })).toEqual([
      { field: "count", message: "must be a number" },
    ]);
  });

  it("rejects 1.5 for integer type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["count"],
      properties: {
        count: { type: "integer" },
      },
    };
    expect(validateToolParams(schema, { count: 1.5 })).toEqual([
      { field: "count", message: "must be an integer" },
    ]);
  });

  it("accepts integer value for integer type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["count"],
      properties: {
        count: { type: "integer" },
      },
    };
    expect(validateToolParams(schema, { count: 42 })).toEqual([]);
  });

  it("accepts 1.5 for number type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["price"],
      properties: {
        price: { type: "number" },
      },
    };
    expect(validateToolParams(schema, { price: 1.5 })).toEqual([]);
  });

  it("rejects non-numeric string for number type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["price"],
      properties: {
        price: { type: "number" },
      },
    };
    expect(validateToolParams(schema, { price: "abc" })).toEqual([
      { field: "price", message: "must be a number" },
    ]);
  });

  it("rejects non-number value for number type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["price"],
      properties: {
        price: { type: "number" },
      },
    };
    expect(validateToolParams(schema, { price: true })).toEqual([
      { field: "price", message: "must be a number" },
    ]);
  });

  it("accepts boolean false for boolean type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["flag"],
      properties: {
        flag: { type: "boolean" },
      },
    };
    expect(validateToolParams(schema, { flag: false })).toEqual([]);
  });

  it("accepts boolean true for boolean type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["flag"],
      properties: {
        flag: { type: "boolean" },
      },
    };
    expect(validateToolParams(schema, { flag: true })).toEqual([]);
  });

  it("rejects non-boolean for boolean type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["flag"],
      properties: {
        flag: { type: "boolean" },
      },
    };
    expect(validateToolParams(schema, { flag: "yes" })).toEqual([
      { field: "flag", message: "must be a boolean" },
    ]);
  });

  it("skips type check for ambiguous schemas (no type)", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["meta"],
      properties: {
        meta: { description: "arbitrary data" },
      },
    };
    // No type means no client-side type check
    expect(validateToolParams(schema, { meta: {} })).toEqual([]);
  });

  it("skips type check for object type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["nested"],
      properties: {
        nested: { type: "object" },
      },
    };
    expect(validateToolParams(schema, { nested: {} })).toEqual([]);
  });

  it("skips type check for array type", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["items"],
      properties: {
        items: { type: "array" },
      },
    };
    expect(validateToolParams(schema, { items: [] })).toEqual([]);
  });

  it("handles multiple required fields with mixed errors", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["name", "count", "price"],
      properties: {
        name: { type: "string" },
        count: { type: "integer" },
        price: { type: "number" },
      },
    };
    const result = validateToolParams(schema, {
      name: "",
      count: 1.5,
      price: 100,
    });
    expect(result).toHaveLength(2);
    expect(result).toContainEqual({
      field: "name",
      message: "required",
    });
    expect(result).toContainEqual({
      field: "count",
      message: "must be an integer",
    });
  });

  it("skips unknown properties not in schema", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["unknown"],
      properties: {},
    };
    // The property "unknown" is not in properties, so skip
    expect(validateToolParams(schema, {})).toEqual([]);
  });

  it("passes through for optional fields even when empty", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["name"],
      properties: {
        name: { type: "string" },
        optionalField: { type: "string" },
      },
    };
    expect(
      validateToolParams(schema, { name: "hello", optionalField: "" }),
    ).toEqual([]);
  });

  it("handles union type with null (anyOf)", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["count"],
      properties: {
        count: {
          anyOf: [
            { type: "integer" },
            { type: "null" },
          ],
        },
      },
    };
    // After normalizeUnionType, this becomes nullable: true
    expect(validateToolParams(schema, { count: null })).toEqual([]);
    expect(validateToolParams(schema, { count: 42 })).toEqual([]);
    expect(validateToolParams(schema, { count: 1.5 })).toEqual([
      { field: "count", message: "must be an integer" },
    ]);
  });

  it("reports required error for undefined on required nullable field", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["count"],
      properties: {
        count: { type: "integer", nullable: true },
      },
    };
    // undefined means the field was never set - still blocks submit
    expect(
      validateToolParams(schema, {}),
    ).toEqual([{ field: "count", message: "required" }]);
  });

  it("reports required error for undefined on required nested object field", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["nested"],
      properties: {
        nested: { type: "object" },
      },
    };
    // undefined means the field was never set - blocks submit
    expect(
      validateToolParams(schema, {}),
    ).toEqual([{ field: "nested", message: "required" }]);
  });

  it("reports required error for undefined on required nested array field", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["items"],
      properties: {
        items: { type: "array" },
      },
    };
    // undefined means the field was never set - blocks submit
    expect(
      validateToolParams(schema, {}),
    ).toEqual([{ field: "items", message: "required" }]);
  });
});

describe("validatePromptArgs", () => {
  it("returns no errors when args is undefined", () => {
    expect(validatePromptArgs(undefined, {})).toEqual([]);
  });

  it("returns no errors when args is empty", () => {
    expect(validatePromptArgs([], {})).toEqual([]);
  });

  it("reports required error for empty required arg", () => {
    const args = [{ name: "name", required: true }];
    expect(validatePromptArgs(args, { name: "" })).toEqual([
      { field: "name", message: "required" },
    ]);
  });

  it("reports required error for undefined required arg", () => {
    const args = [{ name: "name", required: true }];
    expect(validatePromptArgs(args, {})).toEqual([
      { field: "name", message: "required" },
    ]);
  });

  it("accepts filled required arg", () => {
    const args = [{ name: "name", required: true }];
    expect(validatePromptArgs(args, { name: "World" })).toEqual([]);
  });

  it("skips optional args even when empty", () => {
    const args = [
      { name: "name", required: true },
      { name: "suffix", required: false },
    ];
    expect(validatePromptArgs(args, { name: "Hello" })).toEqual([]);
  });

  it("skips args without required flag", () => {
    const args = [{ name: "suffix" }];
    expect(validatePromptArgs(args, {})).toEqual([]);
  });

  it("handles multiple required args with one empty", () => {
    const args = [
      { name: "a", required: true },
      { name: "b", required: true },
    ];
    expect(validatePromptArgs(args, { a: "hello", b: "" })).toEqual([
      { field: "b", message: "required" },
    ]);
  });
});
