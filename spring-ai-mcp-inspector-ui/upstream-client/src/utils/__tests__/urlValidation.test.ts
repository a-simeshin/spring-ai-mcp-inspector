import { validateRedirectUrl, validateServerUrl } from "../urlValidation";

describe("validateRedirectUrl", () => {
  describe("valid URLs", () => {
    it("should allow HTTP URLs", () => {
      expect(() => validateRedirectUrl("http://example.com")).not.toThrow();
    });

    it("should allow HTTPS URLs", () => {
      expect(() => validateRedirectUrl("https://example.com")).not.toThrow();
    });

    it("should allow URLs with ports", () => {
      expect(() =>
        validateRedirectUrl("https://example.com:8080"),
      ).not.toThrow();
    });

    it("should allow URLs with paths", () => {
      expect(() =>
        validateRedirectUrl("https://example.com/path/to/auth"),
      ).not.toThrow();
    });

    it("should allow URLs with query parameters", () => {
      expect(() =>
        validateRedirectUrl("https://example.com?param=value"),
      ).not.toThrow();
    });
  });

  describe("invalid URLs - XSS vectors", () => {
    it("should block javascript: protocol", () => {
      expect(() => validateRedirectUrl("javascript:alert('XSS')")).toThrow(
        "Authorization URL must be HTTP or HTTPS",
      );
    });

    it("should block javascript: with encoded characters", () => {
      expect(() =>
        validateRedirectUrl("javascript:alert%28%27XSS%27%29"),
      ).toThrow("Authorization URL must be HTTP or HTTPS");
    });

    it("should block data: protocol", () => {
      expect(() =>
        validateRedirectUrl("data:text/html,<script>alert('XSS')</script>"),
      ).toThrow("Authorization URL must be HTTP or HTTPS");
    });

    it("should block vbscript: protocol", () => {
      expect(() => validateRedirectUrl("vbscript:msgbox")).toThrow(
        "Authorization URL must be HTTP or HTTPS",
      );
    });

    it("should block file: protocol", () => {
      expect(() => validateRedirectUrl("file:///etc/passwd")).toThrow(
        "Authorization URL must be HTTP or HTTPS",
      );
    });

    it("should block about: protocol", () => {
      expect(() => validateRedirectUrl("about:blank")).toThrow(
        "Authorization URL must be HTTP or HTTPS",
      );
    });

    it("should block custom protocols", () => {
      expect(() => validateRedirectUrl("custom://example")).toThrow(
        "Authorization URL must be HTTP or HTTPS",
      );
    });
  });

  describe("edge cases", () => {
    it("should handle malformed URLs", () => {
      expect(() => validateRedirectUrl("not a url")).toThrow(
        "Invalid URL: not a url",
      );
    });

    it("should handle empty string", () => {
      expect(() => validateRedirectUrl("")).toThrow("Invalid URL: ");
    });

    it("should handle URLs with unicode characters", () => {
      expect(() => validateRedirectUrl("https://例え.jp")).not.toThrow();
    });

    it("should handle URLs with case variations", () => {
      expect(() => validateRedirectUrl("HTTPS://EXAMPLE.COM")).not.toThrow();
      expect(() => validateRedirectUrl("HtTpS://example.com")).not.toThrow();
    });

    it("should handle protocol-relative URLs as invalid", () => {
      expect(() => validateRedirectUrl("//example.com")).toThrow(
        "Invalid URL: //example.com",
      );
    });

    it("should handle URLs with authentication", () => {
      expect(() =>
        validateRedirectUrl("https://user:pass@example.com"),
      ).not.toThrow();
    });
  });

  describe("security considerations", () => {
    it("should not be fooled by whitespace", () => {
      expect(() => validateRedirectUrl(" javascript:alert('XSS')")).toThrow();
      expect(() => validateRedirectUrl("javascript:alert('XSS') ")).toThrow();
    });

    it("should handle null bytes", () => {
      expect(() =>
        validateRedirectUrl("java\x00script:alert('XSS')"),
      ).toThrow();
    });

    it("should handle tab characters", () => {
      expect(() => validateRedirectUrl("java\tscript:alert('XSS')")).toThrow();
    });

    it("should handle newlines", () => {
      expect(() => validateRedirectUrl("java\nscript:alert('XSS')")).toThrow();
    });

    it("should handle mixed case protocols", () => {
      expect(() => validateRedirectUrl("JaVaScRiPt:alert('XSS')")).toThrow(
        "Authorization URL must be HTTP or HTTPS",
      );
    });
  });
});

describe("validateServerUrl", () => {
  describe("valid URLs", () => {
    it("should accept http:// URLs with host", () => {
      expect(validateServerUrl("http://127.0.0.1:8080/mcp")).toEqual({
        isValid: true,
        errorMessage: null,
      });
    });

    it("should accept https:// URLs with host", () => {
      expect(validateServerUrl("https://example.com/mcp")).toEqual({
        isValid: true,
        errorMessage: null,
      });
    });

    it("should accept ws:// URLs with host", () => {
      expect(validateServerUrl("ws://localhost:3000/mcp")).toEqual({
        isValid: true,
        errorMessage: null,
      });
    });

    it("should accept wss:// URLs with host", () => {
      expect(validateServerUrl("wss://host.example/mcp")).toEqual({
        isValid: true,
        errorMessage: null,
      });
    });

    it("should accept absolute paths starting with /", () => {
      expect(validateServerUrl("/mcp")).toEqual({
        isValid: true,
        errorMessage: null,
      });
    });

    it("should accept absolute paths with slashes", () => {
      expect(validateServerUrl("/path/to/endpoint")).toEqual({
        isValid: true,
        errorMessage: null,
      });
    });
  });

  describe("invalid URLs", () => {
    it("should reject empty string", () => {
      const result = validateServerUrl("");
      expect(result.isValid).toBe(false);
      expect(result.errorMessage).toBe("URL must not be empty");
    });

    it("should reject whitespace-only string", () => {
      const result = validateServerUrl("   ");
      expect(result.isValid).toBe(false);
      expect(result.errorMessage).toBe("URL must not be empty");
    });

    it("should reject single character", () => {
      const result = validateServerUrl("h");
      expect(result.isValid).toBe(false);
      expect(result.errorMessage).toBe("URL is too short");
    });

    it("should reject URL with typo scheme (htp://)", () => {
      const result = validateServerUrl("htp://typo-scheme.example/mcp");
      expect(result.isValid).toBe(false);
      expect(result.errorMessage).toContain("http://");
    });

    it("should reject URL without scheme (example.com)", () => {
      const result = validateServerUrl("example.com/mcp");
      expect(result.isValid).toBe(false);
      expect(result.errorMessage).toContain("http://");
    });

    it("should reject scheme with no host (http://)", () => {
      const result = validateServerUrl("http://");
      expect(result.isValid).toBe(false);
      expect(result.errorMessage).toContain("http://");
    });

    it("should reject ftp:// scheme", () => {
      const result = validateServerUrl("ftp://example.com/mcp");
      expect(result.isValid).toBe(false);
      expect(result.errorMessage).toContain("http://");
    });

    it("should reject file:// scheme", () => {
      const result = validateServerUrl("file:///etc/passwd");
      expect(result.isValid).toBe(false);
      expect(result.errorMessage).toContain("http://");
    });
  });
});
