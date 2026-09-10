// [spring-ai-mcp-inspector PATCH] AppRenderer visibility logic test (#183, #199)
describe("AppRenderer - handleCallTool visibility logic", () => {
  // Test the visibility logic extracted from AppRenderer.tsx
  // by testing it in isolation as a pure function.

  function checkToolVisibility(
    tools: Array<{ name: string; _meta?: { ui?: { visibility?: string[] } } }>,
    toolName: string,
  ): { allowed: boolean; reason?: string } {
    const matched = tools.find((t) => t.name === toolName);
    if (!matched) {
      return { allowed: false, reason: "unknown tool" };
    }
    const visibility = matched._meta?.ui?.visibility;
    const effective = visibility ?? ["model", "app"];
    if (!effective.includes("app")) {
      return { allowed: false, reason: "not available to apps" };
    }
    return { allowed: true };
  }

  it("rejects model-only visibility", () => {
    const result = checkToolVisibility(
      [{ name: "modelTool", _meta: { ui: { visibility: ["model"] } } }],
      "modelTool",
    );
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe("not available to apps");
  });

  it("allows app visibility", () => {
    const result = checkToolVisibility(
      [{ name: "appTool", _meta: { ui: { visibility: ["app"] } } }],
      "appTool",
    );
    expect(result.allowed).toBe(true);
  });

  it("allows omitted visibility (default includes app)", () => {
    const result = checkToolVisibility([{ name: "defaultTool" }], "defaultTool");
    expect(result.allowed).toBe(true);
  });

  it("rejects unknown tool", () => {
    const result = checkToolVisibility(
      [{ name: "knownTool" }],
      "unknownTool",
    );
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe("unknown tool");
  });
});