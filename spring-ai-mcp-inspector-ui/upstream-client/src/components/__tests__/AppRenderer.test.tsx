// [spring-ai-mcp-inspector PATCH] AppRenderer onCallTool visibility check tests.
// These test the visibility logic that AppRenderer implements via its
// handleCallTool callback (passed to @mcp-ui/client AppRenderer as onCallTool).

import { Tool } from "@modelcontextprotocol/sdk/types.js";
import { checkToolVisibility } from "../AppRenderer";

describe("AppRenderer.handleCallTool visibility", () => {
  const makeTool = (name: string, visibility?: string[]): Tool => {
    const tool: Record<string, unknown> = {
      name,
      inputSchema: { type: "object", properties: {} },
    };
    if (visibility) {
      tool._meta = { ui: { visibility } };
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
    const result = checkToolVisibility(
      [makeTool("defaultTool")],
      "defaultTool",
    );
    expect(result.allowed).toBe(true);
  });

  it("rejects unknown tool", () => {
    const result = checkToolVisibility(
      [makeTool("knownTool", ["app"])],
      "unknownTool",
    );
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe("unknown tool");
  });
});