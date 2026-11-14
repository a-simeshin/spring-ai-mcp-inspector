// [spring-ai-mcp-inspector PATCH] Unit tests for mcpErrorHandling.ts
// (see NOTICE.d/mcp-error-handling.txt).
import { describe, it, expect } from "@jest/globals";
import { swallowLoggedMcpError } from "../mcpErrorHandling";

describe("swallowLoggedMcpError", () => {
  it("does nothing for a resolved promise", () => {
    expect(() => swallowLoggedMcpError(Promise.resolve(42))).not.toThrow();
  });

  it("swallows a rejection so it never reaches unhandledRejection", async () => {
    const unhandled: unknown[] = [];
    const onUnhandled = (reason: unknown) => {
      unhandled.push(reason);
    };
    process.on("unhandledRejection", onUnhandled);
    try {
      swallowLoggedMcpError(
        Promise.reject(new Error("MCP error -32602: text must not be null")),
      );
      // A rejection becomes "unhandled" once the microtask queue drains;
      // one macrotask later the registry would have fired.
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(unhandled).toEqual([]);
    } finally {
      process.off("unhandledRejection", onUnhandled);
    }
  });
});
