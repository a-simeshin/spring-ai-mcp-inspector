// [spring-ai-mcp-inspector PATCH] AppRenderer onCallTool visibility check tests.
// These test the visibility logic that AppRenderer implements via its
// handleCallTool callback (passed to @mcp-ui/client AppRenderer as onCallTool).

import { Tool, CallToolResult } from "@modelcontextprotocol/sdk/types.js";

// Replicates the handleCallTool logic from AppRenderer.tsx
function createHandleCallTool(tools: Tool[], mcpClient: { request: jest.Mock }) {
  return async (
    params: { name: string; arguments?: Record<string, unknown> },
    extra?: { signal?: AbortSignal },
  ): Promise<CallToolResult> => {
    const toolName = params.name;
    const matchedTool = tools.find((t) => t.name === toolName);
    if (!matchedTool) {
      return {
        isError: true,
        content: [
          {
            type: "text",
            text: `Tool '${toolName}' is not available to apps`,
          },
        ],
      };
    }
    const meta = (matchedTool as Record<string, unknown>)._meta as
      | Record<string, unknown>
      | undefined;
    const ui = meta?.ui as Record<string, unknown> | undefined;
    const toolVisibility = ui?.visibility as string[] | undefined;
    const effectiveVisibility = toolVisibility ?? ["model", "app"];
    if (!effectiveVisibility.includes("app")) {
      return {
        isError: true,
        content: [
          {
            type: "text",
            text: `Tool '${toolName}' is not available to apps`,
          },
        ],
      };
    }
    return mcpClient.request(
      {
        method: "tools/call",
        params: { name: toolName, arguments: params.arguments },
      },
      { signal: extra?.signal },
    );
  };
}

describe("AppRenderer.handleCallTool visibility", () => {
  const mockRequest = jest.fn();
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

  beforeEach(() => {
    mockRequest.mockClear();
  });

  it("rejects model-only visibility", async () => {
    const handler = createHandleCallTool([makeTool("modelTool", ["model"])], {
      request: mockRequest,
    });
    const result = await handler({ name: "modelTool", arguments: {} });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not available to apps");
    expect(mockRequest).not.toHaveBeenCalled();
  });

  it("allows app visibility", async () => {
    mockRequest.mockResolvedValue({ content: [] } as never);
    const handler = createHandleCallTool([makeTool("appTool", ["app"])], {
      request: mockRequest,
    });
    const result = await handler({ name: "appTool", arguments: {} });
    expect(result.isError).toBeUndefined();
    expect(mockRequest).toHaveBeenCalledWith(
      { method: "tools/call", params: { name: "appTool", arguments: {} } },
      { signal: undefined },
    );
  });

  it("allows omitted visibility (default includes app)", async () => {
    mockRequest.mockResolvedValue({ content: [] } as never);
    const handler = createHandleCallTool([makeTool("defaultTool")], {
      request: mockRequest,
    });
    const result = await handler({ name: "defaultTool", arguments: {} });
    expect(result.isError).toBeUndefined();
  });

  it("rejects unknown tool", async () => {
    const handler = createHandleCallTool([makeTool("knownTool", ["app"])], {
      request: mockRequest,
    });
    const result = await handler({ name: "unknownTool", arguments: {} });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not available to apps");
    expect(mockRequest).not.toHaveBeenCalled();
  });
});