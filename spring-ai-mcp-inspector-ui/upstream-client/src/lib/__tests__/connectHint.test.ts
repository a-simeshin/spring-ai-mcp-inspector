// [spring-ai-mcp-inspector PATCH] Transport-switch hint parsing tests
// (issue #250): the additive hint parses when well-formed, degrades to null
// when malformed, stays ABSENT (not null) on legacy payloads so the existing
// strict deep-equality expectations in connectErrors.test.ts keep passing,
// and the S1/S2 copy constants match the spec verbatim.
import {
  parseConnectFailureResponse,
  CONNECT_HINT_STRONG_TEXT,
  CONNECT_HINT_WEAK_TEXT,
  CONNECT_HINT_UPSTREAM_REF_LABEL,
  CONNECT_HINT_UPSTREAM_REF_URL,
  CONNECT_HINT_LOCAL_REF_LABEL,
  CONNECT_HINT_LOCAL_REF_URL,
} from "../connectionErrors";

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

  it("keeps parsing legacy payloads without a hint unchanged (field absent, not null)", async () => {
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

    // Legacy payloads must stay byte-identical for the existing strict
    // deep-equality expectations: no hint key at all.
    expect(failure).toEqual({
      code: "MCP_CONNECT_FAILED",
      reason: "connection_refused",
      message: "refused",
      retryable: true,
    });
    expect(failure && "hint" in failure).toBe(false);
  });
});

// The verbatim copy contract (spec section 6): these asserts pin the exact
// strings so a wording drift fails the build, not an eyeball review.
describe("transport-switch hint verbatim copy", () => {
  it("matches the spec S1 string byte for byte", () => {
    expect(CONNECT_HINT_STRONG_TEXT).toBe(
      "This server appears to serve the legacy SSE transport only. For Spring AI servers set `spring.ai.mcp.server.protocol=STREAMABLE`, or connect with the SSE transport.",
    );
  });

  it("matches the spec S2 string byte for byte", () => {
    expect(CONNECT_HINT_WEAK_TEXT).toBe(
      "If this is a Spring AI server, its SSE transport may be active instead: try Transport Type SSE or set `spring.ai.mcp.server.protocol=STREAMABLE` on the server (#250).",
    );
  });

  it("carries the spec ref labels and URLs", () => {
    expect(CONNECT_HINT_UPSTREAM_REF_LABEL).toBe(
      "spring-projects/spring-ai#7207",
    );
    expect(CONNECT_HINT_UPSTREAM_REF_URL).toBe(
      "https://github.com/spring-projects/spring-ai/issues/7207",
    );
    expect(CONNECT_HINT_LOCAL_REF_LABEL).toBe("#250");
    expect(CONNECT_HINT_LOCAL_REF_URL).toBe(
      "https://github.com/a-simeshin/spring-ai-mcp-inspector/issues/250",
    );
  });
});
