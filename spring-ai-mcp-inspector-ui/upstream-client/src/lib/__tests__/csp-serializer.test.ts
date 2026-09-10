import { serializeCsp, McpUiResourceCsp } from "../csp-serializer";

describe("serializeCsp", () => {
  it("produces all-'none' baseline for null", () => {
    const csp = serializeCsp(null);
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("connect-src 'none'");
    expect(csp).toContain("img-src 'none'");
    expect(csp).toContain("frame-src 'none'");
    expect(csp).toContain("form-action 'none'");
    expect(csp).toContain("base-uri 'self'");
  });

  it("produces all-'none' baseline for undefined", () => {
    const csp = serializeCsp(undefined);
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("connect-src 'none'");
  });

  it("produces all-'none' baseline for empty object", () => {
    const csp = serializeCsp({});
    expect(csp).toContain("connect-src 'none'");
    expect(csp).toContain("img-src 'none'");
    expect(csp).toContain("frame-src 'none'");
  });

  it("maps connectDomains to connect-src", () => {
    const csp: McpUiResourceCsp = {
      connectDomains: ["https://api.example.com", "https://ws.example.com"],
    };
    const result = serializeCsp(csp);
    expect(result).toContain("connect-src https://api.example.com https://ws.example.com");
  });

  it("maps resourceDomains to img/script/style/font/media-src", () => {
    const csp: McpUiResourceCsp = {
      resourceDomains: ["https://cdn.example.com"],
    };
    const result = serializeCsp(csp);
    expect(result).toContain("img-src https://cdn.example.com");
    expect(result).toContain("script-src 'unsafe-inline' https://cdn.example.com");
    expect(result).toContain("style-src 'unsafe-inline' https://cdn.example.com");
    expect(result).toContain("font-src https://cdn.example.com");
    expect(result).toContain("media-src https://cdn.example.com");
  });

  it("maps frameDomains to frame-src", () => {
    const csp: McpUiResourceCsp = {
      frameDomains: ["https://frames.example.com"],
    };
    const result = serializeCsp(csp);
    expect(result).toContain("frame-src https://frames.example.com");
  });

  it("maps baseUriDomains to base-uri", () => {
    const csp: McpUiResourceCsp = {
      baseUriDomains: ["https://example.com"],
    };
    const result = serializeCsp(csp);
    expect(result).toContain("base-uri https://example.com");
  });

  it("uses defaults for omitted directives", () => {
    const csp: McpUiResourceCsp = {
      connectDomains: ["https://api.example.com"],
    };
    const result = serializeCsp(csp);
    expect(result).toContain("connect-src https://api.example.com");
    expect(result).toContain("img-src 'none'");
    expect(result).toContain("frame-src 'none'");
    expect(result).toContain("base-uri 'self'");
    expect(result).toContain("form-action 'none'");
  });

  it("handles all fields populated", () => {
    const csp: McpUiResourceCsp = {
      connectDomains: ["https://api.example.com"],
      resourceDomains: ["https://cdn.example.com"],
      frameDomains: ["https://frames.example.com"],
      baseUriDomains: ["https://example.com"],
    };
    const result = serializeCsp(csp);
    expect(result).toContain("default-src 'none'");
    expect(result).toContain("connect-src https://api.example.com");
    expect(result).toContain("img-src https://cdn.example.com");
    expect(result).toContain("frame-src https://frames.example.com");
    expect(result).toContain("base-uri https://example.com");
    expect(result).toContain("form-action 'none'");
  });
});