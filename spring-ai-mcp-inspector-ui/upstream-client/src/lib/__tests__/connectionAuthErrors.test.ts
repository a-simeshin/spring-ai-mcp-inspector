/**
 * [spring-ai-mcp-inspector PATCH] Unit tests for D3 error-DTO parsing
 * (issue #54): the proxy's `ProxyErrorDto` must be parsed exactly and
 * non-DTO payloads must never produce a fabricated DTO.
 */
import {
  isProxyErrorDto,
  parseProxyErrorDto,
} from "../connectionErrors";

describe("isProxyErrorDto", () => {
  it("accepts a well-formed DTO", () => {
    expect(
      isProxyErrorDto({
        status: 401,
        code: "unauthorized",
        reason: "The MCP server rejected the request as unauthenticated.",
        guidance: "Verify the token/API key.",
        url: "https://server:8443/mcp",
      }),
    ).toBe(true);
  });

  it("accepts a DTO without url (null-omitted field)", () => {
    expect(
      isProxyErrorDto({
        status: 403,
        code: "forbidden",
        reason: "r",
        guidance: "g",
      }),
    ).toBe(true);
  });

  it("rejects non-objects, arrays and partial shapes", () => {
    expect(isProxyErrorDto(null)).toBe(false);
    expect(isProxyErrorDto("401")).toBe(false);
    expect(isProxyErrorDto([1, 2])).toBe(false);
    expect(isProxyErrorDto({ status: 401 })).toBe(false);
    expect(isProxyErrorDto({ status: "401", code: "unauthorized", reason: "r", guidance: "g" })).toBe(false);
  });
});

describe("parseProxyErrorDto", () => {
  it("parses the exact D3 literal fields from JSON", () => {
    const dto = parseProxyErrorDto(
      JSON.stringify({
        status: 401,
        code: "unauthorized",
        reason: "The MCP server rejected the request as unauthenticated.",
        guidance:
          "Verify the token/API key. OAuth2 profiles refresh and retry once automatically.",
        url: "https://server/mcp",
      }),
    );
    expect(dto).not.toBeNull();
    expect(dto).toMatchObject({
      status: 401,
      code: "unauthorized",
      reason: "The MCP server rejected the request as unauthenticated.",
      guidance:
        "Verify the token/API key. OAuth2 profiles refresh and retry once automatically.",
      url: "https://server/mcp",
    });
  });

  it("returns null for non-JSON input", () => {
    expect(parseProxyErrorDto("not json")).toBeNull();
  });

  it("returns null for JSON that is not a DTO (never fabricates)", () => {
    expect(parseProxyErrorDto('{"error": "boom"}')).toBeNull();
    expect(parseProxyErrorDto('{"status": "401"}')).toBeNull();
    expect(parseProxyErrorDto("[]")).toBeNull();
  });

  it("parses a 302 redirect DTO (streamable 3xx contract, owner review round 2)", () => {
    const dto = parseProxyErrorDto(
      JSON.stringify({
        status: 302,
        code: "redirect",
        reason: "The MCP server redirected the request and it was not followed.",
        guidance: "Check the server URL; redirects are not followed automatically.",
        url: "https://server/mcp",
      }),
    );
    expect(dto).not.toBeNull();
    expect(dto).toMatchObject({
      status: 302,
      code: "redirect",
      reason: "The MCP server redirected the request and it was not followed.",
      guidance: "Check the server URL; redirects are not followed automatically.",
      url: "https://server/mcp",
    });
  });
});