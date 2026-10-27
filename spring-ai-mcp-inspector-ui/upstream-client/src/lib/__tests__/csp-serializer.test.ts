// [spring-ai-mcp-inspector PATCH] CSP serializer test (#183, #199): assertions parse
// the policy and check the EFFECTIVE directive (first occurrence wins under CSP3
// parsing), never substring presence, so a duplicated directive cannot pass.
import { serializeCsp, McpUiResourceCsp } from "../csp-serializer";

function effectiveDirectives(policy: string): Map<string, string> {
  const directives = new Map<string, string>();
  for (const token of policy.split(";")) {
    const trimmed = token.trim();
    if (!trimmed) continue;
    const [name, ...rest] = trimmed.split(/\s+/);
    if (!directives.has(name)) directives.set(name, rest.join(" "));
  }
  return directives;
}

function directiveCounts(policy: string): Map<string, number> {
  const counts = new Map<string, number>();
  for (const token of policy.split(";")) {
    const trimmed = token.trim();
    if (!trimmed) continue;
    const name = trimmed.split(/\s+/)[0];
    counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  return counts;
}

const APP_CSP: McpUiResourceCsp = {
  connectDomains: ["https://api.example.com", "https://ws.example.com"],
  resourceDomains: ["https://cdn.example.com"],
  frameDomains: ["https://frames.example.com"],
  baseUriDomains: ["https://base.example.com"],
};

describe("serializeCsp", () => {
  it("produces all-'none' baseline for null", () => {
    const effective = effectiveDirectives(serializeCsp(null));
    expect(effective.get("default-src")).toBe("'none'");
    expect(effective.get("connect-src")).toBe("'none'");
    expect(effective.get("img-src")).toBe("'none'");
    expect(effective.get("frame-src")).toBe("'none'");
    expect(effective.get("form-action")).toBe("'none'");
    expect(effective.get("base-uri")).toBe("'self'");
  });

  it("produces all-'none' baseline for undefined", () => {
    const effective = effectiveDirectives(serializeCsp(undefined));
    expect(effective.get("default-src")).toBe("'none'");
    expect(effective.get("connect-src")).toBe("'none'");
  });

  it("produces all-'none' baseline for empty object", () => {
    const effective = effectiveDirectives(serializeCsp({}));
    expect(effective.get("connect-src")).toBe("'none'");
    expect(effective.get("img-src")).toBe("'none'");
    expect(effective.get("frame-src")).toBe("'none'");
  });

  it("maps connectDomains to the single effective connect-src", () => {
    const effective = effectiveDirectives(serializeCsp(APP_CSP));
    expect(effective.get("connect-src")).toBe(
      "https://api.example.com https://ws.example.com"
    );
  });

  it("maps resourceDomains into the same img/script/style/font/media directives", () => {
    const effective = effectiveDirectives(serializeCsp(APP_CSP));
    expect(effective.get("img-src")).toBe("https://cdn.example.com");
    expect(effective.get("script-src")).toBe(
      "'unsafe-inline' https://cdn.example.com"
    );
    expect(effective.get("style-src")).toBe(
      "'unsafe-inline' https://cdn.example.com"
    );
    expect(effective.get("font-src")).toBe("https://cdn.example.com");
    expect(effective.get("media-src")).toBe("https://cdn.example.com");
  });

  it("maps frameDomains to frame-src and baseUriDomains to base-uri", () => {
    const effective = effectiveDirectives(serializeCsp(APP_CSP));
    expect(effective.get("frame-src")).toBe("https://frames.example.com");
    expect(effective.get("base-uri")).toBe("https://base.example.com");
  });

  it("emits object-src 'none' (SEP-1865 draft, Sandbox proxy, item 5)", () => {
    for (const input of [null, undefined, {}, APP_CSP]) {
      const effective = effectiveDirectives(serializeCsp(input as McpUiResourceCsp | null | undefined));
      expect(effective.get("object-src")).toBe("'none'");
    }
  });

  it("never emits a directive twice (CSP3 ignores repeats)", () => {
    const policy = serializeCsp(APP_CSP);
    for (const [name, count] of directiveCounts(policy)) {
      expect(count).toBe(1);
    }
  });
});
