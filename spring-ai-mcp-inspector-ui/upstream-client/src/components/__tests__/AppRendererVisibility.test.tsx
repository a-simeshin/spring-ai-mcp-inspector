// [spring-ai-mcp-inspector PATCH] AppRenderer visibility logic test (#183, #199)
import { checkToolVisibility } from "../AppRenderer";
import { Tool } from "@modelcontextprotocol/sdk/types.js";

describe("AppRenderer - handleCallTool visibility logic", () => {
  const makeTool = (name: string, visibility?: string[]): Tool => {
    const tool: Record<string, unknown> = {
      name,
      description: "test",
      inputSchema: { type: "object" as const, properties: {} },
    };
    if (visibility !== undefined) {
      (tool as Record<string, unknown>)._meta = {
        ui: { visibility },
      };
    }
    return tool as unknown as Tool;
  };

  it("rejects model-only visibility", () => {
    const result = checkToolVisibility(
      [makeTool("modelTool", ["model"])],
      "modelTool",
    );
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe("not available to apps");
  });

  it("allows app visibility", () => {
    const result = checkToolVisibility(
      [makeTool("appTool", ["app"])],
      "appTool",
    );
    expect(result.allowed).toBe(true);
  });

  it("allows omitted visibility (default includes app)", () => {
    const result = checkToolVisibility([makeTool("defaultTool")], "defaultTool");
    expect(result.allowed).toBe(true);
  });

  it("rejects unknown tool", () => {
    const result = checkToolVisibility(
      [makeTool("knownTool")],
      "unknownTool",
    );
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe("unknown tool");
  });

  it("rejects a malformed non-array visibility value (bare string)", () => {
    // A string like "happy" must NOT pass: includes("app") would be true for it.
    const tool: Record<string, unknown> = {
      name: "stringVisibilityTool",
      description: "test",
      inputSchema: { type: "object", properties: {} },
      _meta: { ui: { visibility: "happy" } },
    };
    const result = checkToolVisibility(
      [tool as unknown as Tool],
      "stringVisibilityTool",
    );
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe("not available to apps");
  });

  it("rejects a malformed object visibility value instead of throwing", () => {
    const tool: Record<string, unknown> = {
      name: "objectVisibilityTool",
      description: "test",
      inputSchema: { type: "object", properties: {} },
      _meta: { ui: { visibility: { app: true } } },
    };
    expect(() =>
      checkToolVisibility([tool as unknown as Tool], "objectVisibilityTool"),
    ).not.toThrow();
    const result = checkToolVisibility(
      [tool as unknown as Tool],
      "objectVisibilityTool",
    );
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe("not available to apps");
  });

  it("rejects an array visibility without 'app' among mixed values", () => {
    const tool: Record<string, unknown> = {
      name: "mixedTool",
      description: "test",
      inputSchema: { type: "object", properties: {} },
      _meta: { ui: { visibility: ["model", "user"] } },
    };
    const result = checkToolVisibility(
      [tool as unknown as Tool],
      "mixedTool",
    );
    expect(result.allowed).toBe(false);
  });
});