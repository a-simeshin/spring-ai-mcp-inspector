/**
 * [spring-ai-mcp-inspector PATCH] Unit tests for D3 error-DTO parsing
 * (issue #54): the proxy's `ProxyErrorDto` must be parsed exactly and
 * non-DTO payloads must never produce a fabricated DTO.
 */
import {
  isProxyErrorDto,
  parseProxyErrorDto,
  parseTerminalReconnectMillis,
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
    expect(
      isProxyErrorDto({
        status: "401",
        code: "unauthorized",
        reason: "r",
        guidance: "g",
      }),
    ).toBe(false);
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

  it("parses a 302 redirect DTO (SSE preflight 3xx contract)", () => {
    const dto = parseProxyErrorDto(
      JSON.stringify({
        status: 302,
        code: "redirect",
        reason:
          "The MCP server redirected the request and it was not followed.",
        guidance:
          "Check the server URL; redirects are not followed automatically.",
        url: "https://server/mcp",
      }),
    );
    expect(dto).not.toBeNull();
    expect(dto).toMatchObject({
      status: 302,
      code: "redirect",
      reason: "The MCP server redirected the request and it was not followed.",
      guidance:
        "Check the server URL; redirects are not followed automatically.",
      url: "https://server/mcp",
    });
  });
});
describe("[spring-ai-mcp-inspector PATCH] parseTerminalReconnectMillis (block2 terminal SSE retry)", () => {
  it("parses the terminal retry the proxy sends for a dead upstream", () => {
    expect(parseTerminalReconnectMillis("retry:2147483000")).toBe(2147483000);
  });

  it("ignores ordinary short retry values (transport hiccups keep reconnecting)", () => {
    expect(parseTerminalReconnectMillis("retry:3000")).toBeNull();
    expect(parseTerminalReconnectMillis("retry:10000")).toBeNull();
    expect(parseTerminalReconnectMillis("retry:3599999")).toBeNull();
  });

  it("ignores malformed values and non-retry lines", () => {
    expect(parseTerminalReconnectMillis("retry:not-a-number")).toBeNull();
    expect(parseTerminalReconnectMillis("retry:")).toBeNull();
    expect(parseTerminalReconnectMillis("event: error")).toBeNull();
    expect(parseTerminalReconnectMillis("data: {}")).toBeNull();
    expect(parseTerminalReconnectMillis("")).toBeNull();
  });
});
