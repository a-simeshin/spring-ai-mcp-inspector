// [spring-ai-mcp-inspector PATCH] Unit specs for the root URI validator used
// by the Roots tab (see NOTICE.d/roots-uri-validation.txt).
import { validateRootUri } from "../rootsUriValidation";

describe("validateRootUri", () => {
  it.each([
    ["an empty string", ""],
    ["whitespace-only values", "   "],
    ["tabs and newlines only", "\t \n"],
  ])("rejects %s as empty", (_label, value) => {
    expect(validateRootUri(value)).toEqual({
      valid: false,
      reason: "empty",
    });
  });

  it.each([
    ["prose without a scheme", "not a uri"],
    ["a bare path", "/some/path"],
    ["a UNC path with backslashes", "\\\\server\\share"],
  ])("rejects %s as missing a scheme", (_label, value) => {
    expect(validateRootUri(value)).toEqual({
      valid: false,
      reason: "missingScheme",
    });
  });

  it.each([
    ["the seeded file:// default", "file://"],
    ["a host without a path", "file://localhost"],
  ])("rejects %s as a file URI without a path", (_label, value) => {
    expect(validateRootUri(value)).toEqual({
      valid: false,
      reason: "fileUriMissingPath",
    });
  });

  it.each([
    ["an encoded space inside the path", "file:///a b c"],
    ["a control character", "file:///a\u0007b"],
    ["unencoded non-ASCII characters", "file:///кириллица"],
    ["a stray percent sign", "file:///a%zz"],
    ["a leading space", " file:///x"],
    ["a trailing space", "file:///x "],
  ])("rejects %s as invalid characters", (_label, value) => {
    expect(validateRootUri(value)).toEqual({
      valid: false,
      reason: "invalidCharacters",
    });
  });

  it.each([
    ["a canonical file URI", "file:///valid/path"],
    ["a file URI with a host", "file://localhost/share"],
    ["a file URI with host and port", "file://localhost:8080/share"],
    ["a single-slash file URI", "file:/tmp"],
    ["an uppercase scheme", "FILE:///X"],
    ["a mixed-case scheme", "File:///Reports"],
    ["a trailing slash", "file:///path/to/dir/"],
    ["dot segments", "file:///a/./b"],
    ["an empty path naming the root directory", "file:///"],
    ["an http URI", "http://example.com/x"],
    ["an https URI with query and fragment", "https://example.com/path?q=1#frag"],
    ["a custom scheme", "s3://bucket/key"],
    ["a urn", "urn:isbn:0451450523"],
    ["a mailto URI", "mailto:user@example.com"],
    ["percent-encoded non-ASCII characters", "file:///%D0%B0%D0%BB"],
    ["percent-encoded spaces", "file:///path%20with%20space"],
  ])("accepts %s", (_label, value) => {
    expect(validateRootUri(value)).toEqual({ valid: true });
  });

  it("treats javascript: URIs as syntactically valid: roots are data for the server, not code the client executes", () => {
    expect(validateRootUri("javascript:alert(1)")).toEqual({ valid: true });
  });
});
