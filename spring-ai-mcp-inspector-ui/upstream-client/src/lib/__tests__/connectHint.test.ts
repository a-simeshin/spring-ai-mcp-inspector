// [spring-ai-mcp-inspector PATCH] Transport-switch hint parsing tests
// (issue #250): the additive hint parses when well-formed, degrades to null
// when absent or malformed, and never changes the legacy fields.
import { parseConnectFailureResponse } from "../connectionErrors";

const responseWithBody = (body: unknown, status = 502) => {
  const text = typeof body === "string" ? body : JSON.stringify(body);
  return new Response(text, {
    status,
    headers: { "Content-Type": "application/json" },
  });
};

describe("parseConnectFailureResponse hint", () => {
  it("parses a strong sse_only hint with refs", async () => {
    const failure = await parseConnectFailureResponse(
      responseWithBody({
        error: {
          code: "MCP_CONNECT_FAILED",
          reason: "not_found",
          message: "404",
          retryable: true,
          hint: {
            kind: "sse_only",
            probePath: "/sse",
            refs: {
              upstream: "spring-projects/spring-ai#7207",
              local: "#250",
            },
          },
        },
      }),
    );

    expect(failure?.hint).toEqual({
      kind: "sse_only",
      probePath: "/sse",
      refs: { upstream: "spring-projects/spring-ai#7207", local: "#250" },
    });
  });

  it("parses a weak maybe_sse hint", async () => {
    const failure = await parseConnectFailureResponse(
      responseWithBody({
        error: {
          code: "MCP_CONNECT_FAILED",
          reason: "not_found",
          hint: { kind: "maybe_sse", probePath: "/sse" },
        },
      }),
    );

    expect(failure?.hint).toEqual({
      kind: "maybe_sse",
      probePath: "/sse",
      refs: undefined,
    });
  });

  it("degrades malformed hints to null without failing the parse", async () => {
    for (const hint of [
      "sse_only",
      42,
      {},
      { kind: "bogus", probePath: "/sse" },
      { kind: "sse_only" },
      { kind: "sse_only", probePath: 7 },
      null,
    ]) {
      const failure = await parseConnectFailureResponse(
        responseWithBody({
          error: {
            code: "MCP_CONNECT_FAILED",
            reason: "not_found",
            message: "404",
            retryable: true,
            hint,
          },
        }),
      );
      expect(failure).not.toBeNull();
      expect(failure?.hint).toBeNull();
    }
  });

  it("keeps parsing legacy payloads without a hint unchanged", async () => {
    const failure = await parseConnectFailureResponse(
      responseWithBody({
        error: {
          code: "MCP_CONNECT_FAILED",
          reason: "connection_refused",
          message: "refused",
          retryable: true,
        },
      }),
    );

    expect(failure).toEqual({
      code: "MCP_CONNECT_FAILED",
      reason: "connection_refused",
      message: "refused",
      retryable: true,
      hint: null,
    });
  });
});
