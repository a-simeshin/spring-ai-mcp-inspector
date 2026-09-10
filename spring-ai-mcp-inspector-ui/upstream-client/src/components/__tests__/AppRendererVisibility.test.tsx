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
});