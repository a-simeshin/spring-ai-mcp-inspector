import {
  generateDefaultValue,
  formatFieldLabel,
  normalizeUnionType,
  cacheToolOutputSchemas,
  getToolOutputValidator,
  validateToolOutput,
  hasOutputSchema,
} from "../schemaUtils";
import type { JsonSchemaType } from "../jsonUtils";
import type { Tool } from "@modelcontextprotocol/sdk/types.js";

describe("generateDefaultValue", () => {
  test("generates default string", () => {
    const parentSchema = { type: "object" as const, required: ["testProp"] };
    expect(
      generateDefaultValue({ type: "string" }, "testProp", parentSchema),
    ).toBe("");
  });

  test("generates default number", () => {
    const parentSchema = { type: "object" as const, required: ["testProp"] };
    expect(
      generateDefaultValue({ type: "number" }, "testProp", parentSchema),
    ).toBe(0);
  });

  test("generates default integer", () => {
    const parentSchema = { type: "object" as const, required: ["testProp"] };
    expect(
      generateDefaultValue({ type: "integer" }, "testProp", parentSchema),
    ).toBe(0);
  });

  test("generates default boolean", () => {
    const parentSchema = { type: "object" as const, required: ["testProp"] };
    expect(
      generateDefaultValue({ type: "boolean" }, "testProp", parentSchema),
    ).toBe(false);
  });

  test("generates undefined for optional array", () => {
    expect(generateDefaultValue({ type: "array" })).toBe(undefined);
  });

  test("generates empty object for optional root object", () => {
    expect(generateDefaultValue({ type: "object" })).toEqual({});
  });

  test("generates undefined for nested optional object", () => {
    // When called WITH propertyName and parentSchema, and the property is NOT required,
    // nested optional objects should return undefined
    const parentSchema = {
      type: "object" as const,
      required: ["otherField"],
      properties: {
        optionalObject: { type: "object" as const },
        otherField: { type: "string" as const },
      },
    };
    expect(
      generateDefaultValue({ type: "object" }, "optionalObject", parentSchema),
    ).toBe(undefined);
  });

  test("generates empty object for root-level object with all optional properties", () => {
    // Root-level schema with properties but no required array
    // This is the exact scenario from PR #926 - elicitation with all optional fields
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        optionalField1: { type: "string" },
        optionalField2: { type: "number" },
      },
      // No required array - all fields are optional
    };
    expect(generateDefaultValue(schema)).toEqual({});
  });

  test("generates default null for unknown types", () => {
    // @ts-expect-error Testing with invalid type
    expect(generateDefaultValue({ type: "unknown" })).toBe(undefined);
  });

  test("generates empty array for required array", () => {
    const parentSchema = { required: ["testArray"] };
    expect(
      generateDefaultValue({ type: "array" }, "testArray", parentSchema),
    ).toEqual([]);
  });

  test("generates undefined for non-required array", () => {
    const parentSchema = { required: ["otherField"] };
    expect(
      generateDefaultValue({ type: "array" }, "testArray", parentSchema),
    ).toBe(undefined);
  });

  test("generates empty object for required object", () => {
    const parentSchema = { required: ["testObject"] };
    expect(
      generateDefaultValue({ type: "object" }, "testObject", parentSchema),
    ).toEqual({});
  });

  test("generates undefined for non-required object", () => {
    const parentSchema = { required: ["otherField"] };
    expect(
      generateDefaultValue({ type: "object" }, "testObject", parentSchema),
    ).toBe(undefined);
  });

  test("generates undefined for non-required primitive types", () => {
    expect(generateDefaultValue({ type: "string" })).toBe(undefined);
    expect(generateDefaultValue({ type: "number" })).toBe(undefined);
    expect(generateDefaultValue({ type: "boolean" })).toBe(undefined);
  });

  test("generates object with properties", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["name", "age", "isActive"],
      properties: {
        name: { type: "string" },
        age: { type: "number" },
        isActive: { type: "boolean" },
      },
    };
    expect(generateDefaultValue(schema)).toEqual({
      name: "",
      age: 0,
      isActive: false,
    });
  });

  test("handles nested objects", () => {
    const schema: JsonSchemaType = {
      type: "object",
      required: ["user"],
      properties: {
        user: {
          type: "object",
          required: ["name", "address"],
          properties: {
            name: { type: "string" },
            address: {
              type: "object",
              required: ["city"],
              properties: {
                city: { type: "string" },
              },
            },
          },
        },
      },
    };
    expect(generateDefaultValue(schema)).toEqual({
      user: {
        name: "",
        address: {
          city: "",
        },
      },
    });
  });

  test("uses schema default value when provided", () => {
    expect(generateDefaultValue({ type: "string", default: "test" })).toBe(
      "test",
    );
  });
});

describe("formatFieldLabel", () => {
  test("formats camelCase", () => {
    expect(formatFieldLabel("firstName")).toBe("First Name");
  });

  test("formats snake_case", () => {
    expect(formatFieldLabel("first_name")).toBe("First name");
  });

  test("formats single word", () => {
    expect(formatFieldLabel("name")).toBe("Name");
  });

  test("formats mixed case with underscores", () => {
    expect(formatFieldLabel("user_firstName")).toBe("User first Name");
  });

  test("handles empty string", () => {
    expect(formatFieldLabel("")).toBe("");
  });
});

describe("normalizeUnionType", () => {
  test("normalizes anyOf with string and null to string type", () => {
    const schema: JsonSchemaType = {
      anyOf: [{ type: "string" }, { type: "null" }],
      description: "Optional string parameter",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("string");
    expect(normalized.anyOf).toBeUndefined();
    expect(normalized.description).toBe("Optional string parameter");
  });

  test("normalizes anyOf with boolean and null to boolean type", () => {
    const schema: JsonSchemaType = {
      anyOf: [{ type: "boolean" }, { type: "null" }],
      description: "Optional boolean parameter",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("boolean");
    expect(normalized.anyOf).toBeUndefined();
    expect(normalized.description).toBe("Optional boolean parameter");
    expect(normalized.nullable).toBeTruthy();
  });

  test("normalizes array type with string and null to string type", () => {
    const schema: JsonSchemaType = {
      type: ["string", "null"],
      description: "Optional string parameter",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("string");
    expect(normalized.description).toBe("Optional string parameter");
    expect(normalized.nullable).toBeTruthy();
  });

  test("normalizes array type with boolean and null to boolean type", () => {
    const schema: JsonSchemaType = {
      type: ["boolean", "null"],
      description: "Optional boolean parameter",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("boolean");
    expect(normalized.description).toBe("Optional boolean parameter");
    expect(normalized.nullable).toBeTruthy();
  });

  test("normalizes anyOf with number and null to number type", () => {
    const schema: JsonSchemaType = {
      anyOf: [{ type: "number" }, { type: "null" }],
      description: "Optional number parameter",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("number");
    expect(normalized.anyOf).toBeUndefined();
    expect(normalized.description).toBe("Optional number parameter");
    expect(normalized.nullable).toBeTruthy();
  });

  test("normalizes anyOf with integer and null to integer type", () => {
    const schema: JsonSchemaType = {
      anyOf: [{ type: "integer" }, { type: "null" }],
      description: "Optional integer parameter",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("integer");
    expect(normalized.anyOf).toBeUndefined();
    expect(normalized.description).toBe("Optional integer parameter");
    expect(normalized.nullable).toBeTruthy();
  });

  test("normalizes array type with number and null to number type", () => {
    const schema: JsonSchemaType = {
      type: ["number", "null"],
      description: "Optional number parameter",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("number");
    expect(normalized.description).toBe("Optional number parameter");
    expect(normalized.nullable).toBeTruthy();
  });

  test("normalizes array type with integer and null to integer type", () => {
    const schema: JsonSchemaType = {
      type: ["integer", "null"],
      description: "Optional integer parameter",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("integer");
    expect(normalized.description).toBe("Optional integer parameter");
    expect(normalized.nullable).toBeTruthy();
  });

  test("handles anyOf with reversed order (null first)", () => {
    const schema: JsonSchemaType = {
      anyOf: [{ type: "null" }, { type: "string" }],
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("string");
    expect(normalized.anyOf).toBeUndefined();
    expect(normalized.nullable).toBeTruthy();
  });

  test("leaves non-union schemas unchanged", () => {
    const schema: JsonSchemaType = {
      type: "string",
      description: "Regular string parameter",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized).toEqual(schema);
  });

  test("leaves anyOf with non-matching types unchanged", () => {
    const schema: JsonSchemaType = {
      anyOf: [{ type: "string" }, { type: "number" }],
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized).toEqual(schema);
    expect(normalized.nullable).toBeFalsy();
  });

  test("leaves anyOf with more than two types unchanged", () => {
    const schema: JsonSchemaType = {
      anyOf: [{ type: "string" }, { type: "number" }, { type: "null" }],
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized).toEqual(schema);
  });

  test("leaves array type with non-matching types unchanged", () => {
    const schema: JsonSchemaType = {
      type: ["string", "number"],
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized).toEqual(schema);
  });

  test("handles schemas without type or anyOf", () => {
    const schema: JsonSchemaType = {
      description: "Schema without type",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized).toEqual(schema);
  });

  test("preserves other properties when normalizing", () => {
    const schema: JsonSchemaType = {
      anyOf: [{ type: "string" }, { type: "null" }],
      description: "Optional string",
      minLength: 1,
      maxLength: 100,
      pattern: "^[a-z]+$",
    };

    const normalized = normalizeUnionType(schema);

    expect(normalized.type).toBe("string");
    expect(normalized.anyOf).toBeUndefined();
    expect(normalized.description).toBe("Optional string");
    expect(normalized.minLength).toBe(1);
    expect(normalized.maxLength).toBe(100);
    expect(normalized.pattern).toBe("^[a-z]+$");
    expect(normalized.nullable).toBeTruthy();
  });
});

describe("Output Schema Validation", () => {
  const mockTools: Tool[] = [
    {
      name: "weatherTool",
      description: "Get weather information",
      inputSchema: {
        type: "object",
        properties: {
          city: { type: "string" },
        },
      },
      outputSchema: {
        type: "object",
        properties: {
          temperature: { type: "number" },
          humidity: { type: "number" },
        },
        required: ["temperature", "humidity"],
      },
    },
    {
      name: "noOutputSchema",
      description: "Tool without output schema",
      inputSchema: {
        type: "object",
        properties: {},
      },
    },
    {
      name: "complexOutputSchema",
      description: "Tool with complex output schema",
      inputSchema: {
        type: "object",
        properties: {},
      },
      outputSchema: {
        type: "object",
        properties: {
          user: {
            type: "object",
            properties: {
              name: { type: "string" },
              age: { type: "number" },
            },
            required: ["name"],
          },
          tags: {
            type: "array",
            items: { type: "string" },
          },
        },
        required: ["user"],
      },
    },
  ];

  beforeEach(() => {
    // Clear cache before each test
    cacheToolOutputSchemas([]);
  });

  describe("cacheToolOutputSchemas", () => {
    test("caches validators for tools with output schemas", () => {
      cacheToolOutputSchemas(mockTools);

      expect(hasOutputSchema("weatherTool")).toBe(true);
      expect(hasOutputSchema("complexOutputSchema")).toBe(true);
      expect(hasOutputSchema("noOutputSchema")).toBe(false);
    });

    test("clears existing cache when called", () => {
      cacheToolOutputSchemas(mockTools);
      expect(hasOutputSchema("weatherTool")).toBe(true);

      cacheToolOutputSchemas([]);
      expect(hasOutputSchema("weatherTool")).toBe(false);
    });

    test("handles invalid output schemas gracefully", () => {
      const toolsWithInvalidSchema: Tool[] = [
        {
          name: "invalidSchemaTool",
          description: "Tool with invalid schema",
          inputSchema: { type: "object", properties: {} },
          outputSchema: {
            // @ts-expect-error Testing with invalid type
            type: "invalid-type",
          },
        },
      ];

      // Should not throw
      expect(() =>
        cacheToolOutputSchemas(toolsWithInvalidSchema),
      ).not.toThrow();
      expect(hasOutputSchema("invalidSchemaTool")).toBe(false);
    });
  });

  describe("validateToolOutput", () => {
    beforeEach(() => {
      cacheToolOutputSchemas(mockTools);
    });

    test("validates correct structured content", () => {
      const result = validateToolOutput("weatherTool", {
        temperature: 25.5,
        humidity: 60,
      });

      expect(result.isValid).toBe(true);
      expect(result.error).toBeUndefined();
    });

    test("rejects invalid structured content", () => {
      const result = validateToolOutput("weatherTool", {
        temperature: "25.5", // Should be number
        humidity: 60,
      });

      expect(result.isValid).toBe(false);
      expect(result.error).toContain("should be number");
    });

    test("rejects missing required fields", () => {
      const result = validateToolOutput("weatherTool", {
        temperature: 25.5,
        // Missing humidity
      });

      expect(result.isValid).toBe(false);
      expect(result.error).toContain("required");
    });

    test("validates complex nested structures", () => {
      const validResult = validateToolOutput("complexOutputSchema", {
        user: {
          name: "John",
          age: 30,
        },
        tags: ["tag1", "tag2"],
      });

      expect(validResult.isValid).toBe(true);

      const invalidResult = validateToolOutput("complexOutputSchema", {
        user: {
          // Missing required 'name'
          age: 30,
        },
      });

      expect(invalidResult.isValid).toBe(false);
    });

    test("returns valid for tools without validators", () => {
      const result = validateToolOutput("nonExistentTool", { any: "data" });

      expect(result.isValid).toBe(true);
      expect(result.error).toBeUndefined();
    });

    test("validates additional properties restriction", () => {
      const result = validateToolOutput("weatherTool", {
        temperature: 25.5,
        humidity: 60,
        extraField: "should not be here",
      });

      // This depends on whether additionalProperties is set to false in the schema
      // If it is, this should fail
      expect(result.isValid).toBe(true); // By default, additional properties are allowed
    });
  });

  describe("getToolOutputValidator", () => {
    beforeEach(() => {
      cacheToolOutputSchemas(mockTools);
    });

    test("returns validator for cached tool", () => {
      const validator = getToolOutputValidator("weatherTool");
      expect(validator).toBeDefined();
      expect(typeof validator).toBe("function");
    });

    test("returns undefined for tool without output schema", () => {
      const validator = getToolOutputValidator("noOutputSchema");
      expect(validator).toBeUndefined();
    });

    test("returns undefined for non-existent tool", () => {
      const validator = getToolOutputValidator("nonExistentTool");
      expect(validator).toBeUndefined();
    });
  });

  describe("hasOutputSchema", () => {
    beforeEach(() => {
      cacheToolOutputSchemas(mockTools);
    });

    test("returns true for tools with output schemas", () => {
      expect(hasOutputSchema("weatherTool")).toBe(true);
      expect(hasOutputSchema("complexOutputSchema")).toBe(true);
    });

    test("returns false for tools without output schemas", () => {
      expect(hasOutputSchema("noOutputSchema")).toBe(false);
    });

    test("returns false for non-existent tools", () => {
      expect(hasOutputSchema("nonExistentTool")).toBe(false);
    });
  });
});

// [spring-ai-mcp-inspector PATCH] $ref schema warnings (Spring AI #5888 detector)
import {
  findUnresolvedRefs,
  findToolSchemaWarnings,
  resolveRef,
} from "../schemaUtils";

describe("findUnresolvedRefs", () => {
  test("returns empty array for schema without $ref", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        name: { type: "string" },
        age: { type: "number" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });

  test("returns empty array for valid internal $ref", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        source: { type: "string" },
        alias: { $ref: "#/properties/source" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });

  test("returns the exact pointer path for an unresolvable $ref", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        missing: { $ref: "#/properties/nonexistent" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/properties/nonexistent"]);
  });

  test("returns pointer path for deeply nested unresolvable $ref", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        child: {
          type: "object",
          properties: {
            grandchild: { $ref: "#/$defs/MissingType" },
          },
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingType"]);
  });

  test("resolves $defs refs correctly", () => {
    const schema: JsonSchemaType & { $defs: Record<string, JsonSchemaType> } = {
      type: "object",
      properties: {
        user: { $ref: "#/$defs/User" },
      },
      $defs: {
        User: {
          type: "object",
          properties: {
            name: { type: "string" },
          },
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });

  test("detects unresolved ref inside properties", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        items: {
          type: "array",
          items: { $ref: "#/properties/nonexistent" },
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/properties/nonexistent"]);
  });

  test("detects unresolved ref inside anyOf", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        value: {
          anyOf: [{ type: "string" }, { $ref: "#/$defs/Missing" }],
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/Missing"]);
  });

  test("detects unresolved ref inside oneOf", () => {
    const schema: JsonSchemaType & { $defs: Record<string, JsonSchemaType> } = {
      type: "object",
      properties: {
        result: {
          oneOf: [{ $ref: "#/$defs/Success" }, { $ref: "#/$defs/Error" }],
        },
      },
      $defs: {
        Success: { type: "object", properties: { data: { type: "string" } } },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/Error"]);
  });

  test("collects multiple unresolved refs", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        a: { $ref: "#/properties/missingA" },
        b: { $ref: "#/properties/missingB" },
      },
    };
    const result = findUnresolvedRefs(schema);
    expect(result).toContain("#/properties/missingA");
    expect(result).toContain("#/properties/missingB");
    expect(result).toHaveLength(2);
  });

  test("does NOT mutate the stored schema", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        broken: { $ref: "#/properties/nonexistent" },
      },
    };
    const copy = JSON.parse(JSON.stringify(schema));
    findUnresolvedRefs(schema);
    expect(schema).toEqual(copy);
  });

  test("handles null/undefined input gracefully", () => {
    expect(findUnresolvedRefs(null as unknown as JsonSchemaType)).toEqual([]);
    expect(findUnresolvedRefs(undefined as unknown as JsonSchemaType)).toEqual(
      [],
    );
  });
});

describe("findToolSchemaWarnings", () => {
  test("returns empty for tool without $ref", () => {
    const tool = {
      inputSchema: {
        type: "object" as const,
        properties: { name: { type: "string" as const } },
      } as JsonSchemaType,
    };
    expect(findToolSchemaWarnings(tool)).toEqual([]);
  });

  test("returns warning for unresolved ref in inputSchema", () => {
    const tool = {
      inputSchema: {
        type: "object" as const,
        properties: {
          broken: { $ref: "#/properties/missing" },
        },
      } as JsonSchemaType,
    };
    expect(findToolSchemaWarnings(tool)).toEqual([
      { ref: "#/properties/missing", location: "inputSchema" },
    ]);
  });

  test("returns warning for unresolved ref in outputSchema", () => {
    const tool = {
      inputSchema: {
        type: "object" as const,
        properties: { name: { type: "string" as const } },
      } as JsonSchemaType,
      outputSchema: {
        type: "object" as const,
        properties: {
          result: { $ref: "#/$defs/MissingResult" },
        },
      } as JsonSchemaType,
    };
    expect(findToolSchemaWarnings(tool)).toEqual([
      { ref: "#/$defs/MissingResult", location: "outputSchema" },
    ]);
  });
});

// Regression tests for PR #204 review findings
// (escaped pointer, allOf, $defs traversal)
describe("findUnresolvedRefs - RFC 6901 and traversal regression", () => {
  test("resolves escaped pointer ~1 to /", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        "a/b": { type: "string" },
        alias: { $ref: "#/properties/a~1b" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });

  test("resolves escaped pointer ~0 to ~", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        "a~b": { type: "string" },
        alias: { $ref: "#/properties/a~0b" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });

  test("detects broken ref under allOf", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        value: {
          allOf: [{ $ref: "#/properties/missing" }],
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/properties/missing"]);
  });

  test("detects broken ref inside nested $defs", () => {
    const schema: JsonSchemaType & { $defs: Record<string, JsonSchemaType> } = {
      type: "object",
      properties: {
        user: { $ref: "#/$defs/User" },
      },
      $defs: {
        User: {
          type: "object",
          properties: {
            profile: { $ref: "#/$defs/Profile" },
          },
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/Profile"]);
  });

  test("resolves valid ref inside $defs", () => {
    const schema: JsonSchemaType & { $defs: Record<string, JsonSchemaType> } = {
      type: "object",
      properties: {
        user: { $ref: "#/$defs/User" },
      },
      $defs: {
        User: {
          type: "object",
          properties: {
            profile: { $ref: "#/$defs/Profile" },
          },
        },
        Profile: {
          type: "object",
          properties: {
            name: { type: "string" },
          },
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });
});

// Regression tests for full schema keyword traversal (PR #204 review)
describe("findUnresolvedRefs - schema keyword traversal", () => {
  test("detects unresolved ref inside not", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        value: {
          not: { $ref: "#/$defs/MissingFromNot" },
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingFromNot"]);
  });

  test("detects unresolved ref inside if", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        value: {
          if: { $ref: "#/$defs/MissingIf" },
          then: { properties: { x: { type: "string" } } },
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingIf"]);
  });

  test("detects unresolved ref inside then", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        value: {
          if: { properties: { x: { type: "string" } } },
          then: { $ref: "#/$defs/MissingThen" },
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingThen"]);
  });

  test("detects unresolved ref inside else", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        value: {
          if: { properties: { x: { type: "string" } } },
          else: { $ref: "#/$defs/MissingElse" },
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingElse"]);
  });

  test("detects unresolved ref inside contains", () => {
    const schema: JsonSchemaType = {
      type: "array",
      items: { type: "object" },
      contains: { $ref: "#/$defs/MissingContains" },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingContains"]);
  });

  test("detects unresolved ref inside prefixItems", () => {
    const schema: JsonSchemaType = {
      type: "array",
      prefixItems: [{ type: "string" }, { $ref: "#/$defs/MissingPrefixItem" }],
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingPrefixItem"]);
  });

  test("detects unresolved ref inside additionalProperties", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: { name: { type: "string" } },
      additionalProperties: { $ref: "#/$defs/MissingAdditionalProp" },
    };
    expect(findUnresolvedRefs(schema)).toEqual([
      "#/$defs/MissingAdditionalProp",
    ]);
  });

  test("detects unresolved ref inside dependentSchemas", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: { credit_card: { type: "string" } },
      dependentSchemas: {
        credit_card: { $ref: "#/$defs/MissingDependent" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingDependent"]);
  });

  test("detects unresolved ref inside patternProperties", () => {
    const schema: JsonSchemaType = {
      type: "object",
      patternProperties: {
        "^S_": { $ref: "#/$defs/MissingPattern" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingPattern"]);
  });

  test("resolves valid ref inside not", () => {
    const schema: JsonSchemaType & { $defs: Record<string, JsonSchemaType> } = {
      type: "object",
      properties: {
        value: {
          not: { $ref: "#/$defs/Allowed" },
        },
      },
      $defs: {
        Allowed: { type: "string" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });

  test("still does NOT mutate the stored schema with new keywords", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        value: {
          not: { $ref: "#/$defs/MissingFromNot" },
        },
      },
    };
    const copy = JSON.parse(JSON.stringify(schema));
    findUnresolvedRefs(schema);
    expect(schema).toEqual(copy);
  });
});

// Regression tests for PR #204 review findings:
// sibling keywords on $ref, tuple items, legacy definitions/dependencies,
// inherited Object.prototype guard, and root fragment #
describe("findUnresolvedRefs - $ref sibling, legacy, and JSON Pointer fixes", () => {
  test("traverses sibling allOf on a schema with $ref", () => {
    const schema: JsonSchemaType & { $defs: Record<string, JsonSchemaType> } = {
      type: "object",
      $defs: {
        Base: { type: "object", properties: { x: { type: "string" } } },
      },
      properties: {
        derived: {
          $ref: "#/$defs/Base",
          allOf: [{ $ref: "#/$defs/MissingSibling" }],
        },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingSibling"]);
  });

  test("traverses items as array (tuple items)", () => {
    const schema: JsonSchemaType & { $defs: Record<string, JsonSchemaType> } = {
      type: "array",
      items: [{ type: "string" }, { $ref: "#/$defs/MissingTupleItem" }],
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingTupleItem"]);
  });

  test("traverses legacy definitions keyword", () => {
    const schema: JsonSchemaType & {
      definitions: Record<string, JsonSchemaType>;
    } = {
      type: "object",
      definitions: {
        RealDef: {
          type: "object",
          properties: {
            inner: { $ref: "#/definitions/AlsoMissing" },
          },
        },
      },
      properties: {
        value: { $ref: "#/definitions/RealDef" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/definitions/AlsoMissing"]);
  });

  test("traverses legacy dependencies schema form", () => {
    const schema: JsonSchemaType & {
      dependencies: Record<string, JsonSchemaType | string[]>;
    } = {
      type: "object",
      properties: {
        a: { type: "string" },
      },
      dependencies: {
        a: { $ref: "#/$defs/MissingDepSchema" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/$defs/MissingDepSchema"]);
  });

  test("skips property-name form of dependencies", () => {
    const schema: JsonSchemaType & {
      dependencies: Record<string, JsonSchemaType | string[]>;
    } = {
      type: "object",
      properties: {
        a: { type: "string" },
        b: { type: "string" },
      },
      dependencies: {
        a: ["b"], // property-name form only -- no schema to traverse
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });

  test("does not match inherited Object.prototype members", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        bad: { $ref: "#/toString" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/toString"]);
  });

  test("resolves root fragment #", () => {
    const schema: JsonSchemaType = {
      $ref: "#",
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });

  test("detects unresolved ref inside contentSchema", () => {
    const schema: JsonSchemaType & { contentSchema: JsonSchemaType } = {
      type: "string",
      contentSchema: { $ref: "#/$defs/MissingContentSchema" },
    };
    expect(findUnresolvedRefs(schema)).toEqual([
      "#/$defs/MissingContentSchema",
    ]);
  });

  test("detects unresolved ref inside unevaluatedProperties", () => {
    const schema: JsonSchemaType & {
      unevaluatedProperties: JsonSchemaType;
    } = {
      type: "object",
      properties: { name: { type: "string" } },
      unevaluatedProperties: { $ref: "#/$defs/MissingUnevaluatedProps" },
    };
    expect(findUnresolvedRefs(schema)).toEqual([
      "#/$defs/MissingUnevaluatedProps",
    ]);
  });

  test("detects unresolved ref inside unevaluatedItems", () => {
    const schema: JsonSchemaType & { unevaluatedItems: JsonSchemaType } = {
      type: "array",
      items: { type: "string" },
      unevaluatedItems: { $ref: "#/$defs/MissingUnevaluatedItems" },
    };
    expect(findUnresolvedRefs(schema)).toEqual([
      "#/$defs/MissingUnevaluatedItems",
    ]);
  });

  test("detects unresolved ref inside propertyNames", () => {
    const schema: JsonSchemaType & { propertyNames: JsonSchemaType } = {
      type: "object",
      propertyNames: { $ref: "#/$defs/MissingPropertyNames" },
    };
    expect(findUnresolvedRefs(schema)).toEqual([
      "#/$defs/MissingPropertyNames",
    ]);
  });

  test("detects unresolved ref inside additionalItems", () => {
    const schema: JsonSchemaType & { additionalItems: JsonSchemaType } = {
      type: "array",
      items: [{ type: "string" }],
      additionalItems: { $ref: "#/$defs/MissingAdditionalItems" },
    };
    expect(findUnresolvedRefs(schema)).toEqual([
      "#/$defs/MissingAdditionalItems",
    ]);
  });
});

// Regression tests for the URIError crash on malformed percent escapes
// (PR #211 owner review): decodePointerToken called decodeURIComponent
// without a guard, so a property named "a%" with {"$ref": "#/properties/a%"}
// threw URIError: URI malformed from the render-phase scan and blanked
// the whole UI. An undecodable token must read as unresolvable instead.
describe("findUnresolvedRefs - malformed percent escapes", () => {
  test('reports ref with malformed escape ("a%", "#/properties/a%") as unresolvable instead of throwing', () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        "a%": { type: "string" },
        broken: { $ref: "#/properties/a%" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual(["#/properties/a%"]);
  });

  test("reports malformed escape in a nested/escaped-path ref as unresolvable instead of throwing", () => {
    const schema: JsonSchemaType & { $defs: Record<string, JsonSchemaType> } = {
      type: "object",
      properties: {
        child: {
          type: "object",
          properties: {
            "tilde~100%": { $ref: "#/properties/child/properties/a~1b%zz" },
          },
        },
      },
      $defs: {
        "%zz": { type: "string" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([
      "#/properties/child/properties/a~1b%zz",
    ]);
  });

  test("a valid percent-escaped ref still resolves (no false positive from the guard)", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        "a b": { type: "string" },
        ok: { $ref: "#/properties/a%20b" },
      },
    };
    expect(findUnresolvedRefs(schema)).toEqual([]);
  });

  test("resolveRef returns the original schema for a malformed-escape $ref instead of throwing", () => {
    const schema: JsonSchemaType = {
      type: "object",
      properties: {
        "a%": { type: "string" },
        broken: { $ref: "#/properties/a%" },
      },
    };
    const refSchema = (schema.properties as Record<string, JsonSchemaType>)[
      "broken"
    ];
    expect(() => resolveRef(refSchema, schema)).not.toThrow();
    expect(resolveRef(refSchema, schema)).toBe(refSchema);
  });
});

// [spring-ai-mcp-inspector PATCH] missing-output-schema-warning (#6773)
import { findMissingOutputSchemaWarning } from "../schemaUtils";

describe("findMissingOutputSchemaWarning", () => {
  const schemalessTool = { name: "echo" };

  const textResult = (text: string) => ({
    content: [{ type: "text", text }],
  });

  // ---- positive: the result itself encodes a JSON primitive ----

  test("fires for a JSON-encoded string result on a schema-less tool", () => {
    const warning = findMissingOutputSchemaWarning(
      schemalessTool,
      textResult('"hello"'),
    );
    expect(warning).not.toBeNull();
    expect(warning).toContain("echo");
    expect(warning).toContain("6773");
  });

  test("fires for a JSON integer result", () => {
    expect(
      findMissingOutputSchemaWarning({ name: "sum" }, textResult("42")),
    ).not.toBeNull();
  });

  test("fires for a JSON float result with surrounding whitespace", () => {
    expect(
      findMissingOutputSchemaWarning({ name: "sum" }, textResult("  8.5  ")),
    ).not.toBeNull();
  });

  test("fires for JSON true", () => {
    expect(
      findMissingOutputSchemaWarning({ name: "toggle" }, textResult("true")),
    ).not.toBeNull();
  });

  test("fires for JSON false", () => {
    expect(
      findMissingOutputSchemaWarning({ name: "toggle" }, textResult("false")),
    ).not.toBeNull();
  });

  test("treats JSON null as a primitive", () => {
    expect(
      findMissingOutputSchemaWarning({ name: "echo" }, textResult("null")),
    ).not.toBeNull();
  });

  test("fires for primitive structuredContent", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, {
        structuredContent: 42,
        content: [],
      }),
    ).not.toBeNull();
  });

  test("falls back to text blocks when structuredContent is null", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, {
        structuredContent: null,
        content: [{ type: "text", text: '"hello"' }],
      }),
    ).not.toBeNull();
  });

  // ---- negative: plain text is the normal case, never flagged ----

  test("does NOT fire for plain text (regression: over-trigger on every String tool)", () => {
    expect(
      findMissingOutputSchemaWarning(
        schemalessTool,
        textResult("hello primitive world"),
      ),
    ).toBeNull();
  });

  test("does NOT fire for the task-polling status text", () => {
    expect(
      findMissingOutputSchemaWarning(
        schemalessTool,
        textResult("Polling task status for task 123..."),
      ),
    ).toBeNull();
  });

  test("does NOT fire for prose with punctuation that is not valid JSON", () => {
    expect(
      findMissingOutputSchemaWarning(
        schemalessTool,
        textResult("User: Alice (admin)"),
      ),
    ).toBeNull();
  });

  test("does NOT fire for empty text blocks or missing content", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, textResult("   ")),
    ).toBeNull();
    expect(
      findMissingOutputSchemaWarning(schemalessTool, { content: [] }),
    ).toBeNull();
    expect(findMissingOutputSchemaWarning(schemalessTool, {})).toBeNull();
  });

  test("does NOT fire when content only has non-text blocks", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, {
        content: [{ type: "image", data: "aGk=", mimeType: "image/png" }],
      }),
    ).toBeNull();
  });

  // ---- negative: structured results are out of scope ----

  test("does NOT fire for a JSON object text block", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, textResult('{"a":1}')),
    ).toBeNull();
  });

  test("does NOT fire for a JSON array text block", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, textResult("[1,2]")),
    ).toBeNull();
  });

  test("does NOT fire when another block is a JSON object", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, {
        content: [
          { type: "text", text: '"str"' },
          { type: "text", text: '{"a":1}' },
        ],
      }),
    ).toBeNull();
  });

  test("does NOT fire when another block is a JSON array", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, {
        content: [
          { type: "text", text: "42" },
          { type: "text", text: "[1,2]" },
        ],
      }),
    ).toBeNull();
  });

  test("does NOT fire for object structuredContent", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, {
        structuredContent: { a: 1 },
        content: [],
      }),
    ).toBeNull();
  });

  // ---- advertised schemas suppress the warning ----

  test("does NOT fire when outputSchema declares a type", () => {
    expect(
      findMissingOutputSchemaWarning(
        { name: "echo", outputSchema: { type: "object" } as JsonSchemaType },
        textResult("42"),
      ),
    ).toBeNull();
  });

  test("does NOT fire when outputSchema has properties but no type", () => {
    expect(
      findMissingOutputSchemaWarning(
        {
          name: "echo",
          outputSchema: {
            properties: { result: { type: "string" } },
          } as JsonSchemaType,
        },
        textResult("42"),
      ),
    ).toBeNull();
  });

  test("does NOT fire when outputSchema is an anyOf without type", () => {
    expect(
      findMissingOutputSchemaWarning(
        {
          name: "echo",
          outputSchema: {
            anyOf: [{ type: "string" }, { type: "number" }],
          } as JsonSchemaType,
        },
        textResult("42"),
      ),
    ).toBeNull();
  });

  test("does NOT fire when outputSchema is a $ref", () => {
    expect(
      findMissingOutputSchemaWarning(
        {
          name: "echo",
          outputSchema: { $ref: "#/$defs/Result" } as JsonSchemaType,
        },
        textResult("42"),
      ),
    ).toBeNull();
  });

  test("fires when outputSchema is a shapeless empty object", () => {
    expect(
      findMissingOutputSchemaWarning(
        { name: "echo", outputSchema: {} as JsonSchemaType },
        textResult('"hello"'),
      ),
    ).not.toBeNull();
  });

  // ---- error and null guards ----

  test("does NOT fire for error results", () => {
    expect(
      findMissingOutputSchemaWarning(schemalessTool, {
        content: [{ type: "text", text: '"boom"' }],
        isError: true,
      }),
    ).toBeNull();
  });

  test("returns null for null tool or null result", () => {
    expect(findMissingOutputSchemaWarning(null, textResult("42"))).toBeNull();
    expect(
      findMissingOutputSchemaWarning(
        null as unknown as { name: string },
        textResult("42"),
      ),
    ).toBeNull();
    expect(findMissingOutputSchemaWarning(schemalessTool, null)).toBeNull();
  });

  // ---- shared predicate: cache and detector must agree ----

  test("cacheToolOutputSchemas and the detector agree on anyOf (no type)", () => {
    const tools = [
      {
        name: "unionTool",
        outputSchema: { anyOf: [{ type: "string" }] },
      },
    ] as unknown as Tool[];
    cacheToolOutputSchemas(tools);
    expect(hasOutputSchema("unionTool")).toBe(true);
    expect(
      findMissingOutputSchemaWarning(
        tools[0] as { name: string },
        textResult("42"),
      ),
    ).toBeNull();
  });

  test("cacheToolOutputSchemas and the detector agree on shapeless {}", () => {
    const tools = [
      { name: "emptyTool", outputSchema: {} },
    ] as unknown as Tool[];
    cacheToolOutputSchemas(tools);
    expect(hasOutputSchema("emptyTool")).toBe(false);
    expect(
      findMissingOutputSchemaWarning(
        tools[0] as { name: string },
        textResult("42"),
      ),
    ).not.toBeNull();
  });
});
