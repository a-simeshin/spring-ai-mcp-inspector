// [spring-ai-mcp-inspector PATCH] Client-side validation of root URIs for the
// Roots tab: Save Changes must not accept values that are empty, lack a
// scheme, contain characters that are not allowed in a URI (RFC 3986), or are
// file:// URIs without a path. Pure and synchronous so it is unit-testable.

export type RootUriIssue =
  | "empty"
  | "missingScheme"
  | "invalidCharacters"
  | "fileUriMissingPath";

export interface RootUriValidationResult {
  valid: boolean;
  reason?: RootUriIssue;
}

const SCHEME_PATTERN = /^[A-Za-z][A-Za-z0-9+.-]*:/;
const FILE_SCHEME_PATTERN = /^file:/i;
const URI_CHARACTER_PATTERN = /^[A-Za-z0-9\-._~:/?#[\]@!$&'()*+,;=]$/;

const containsInvalidUriCharacters = (value: string): boolean => {
  for (let i = 0; i < value.length; i += 1) {
    const char = value[i];
    if (char === "%") {
      // A percent sign is only legal as part of a percent-encoded octet.
      if (!/^[0-9A-Fa-f]{2}/.test(value.slice(i + 1))) {
        return true;
      }
      i += 2;
      continue;
    }
    if (!URI_CHARACTER_PATTERN.test(char)) {
      return true;
    }
  }
  return false;
};

const fileUriHasPath = (value: string): boolean => {
  const afterScheme = value.slice("file:".length);
  if (!afterScheme.startsWith("//")) {
    return afterScheme.length > 0;
  }
  // After the double slash the URI has an authority; a path, query or
  // fragment must follow it, otherwise the root names no location.
  return /[/?#]/.test(afterScheme.slice(2));
};

export const validateRootUri = (value: string): RootUriValidationResult => {
  if (value.trim().length === 0) {
    return { valid: false, reason: "empty" };
  }
  if (/^\s|\s$/.test(value)) {
    return { valid: false, reason: "invalidCharacters" };
  }
  if (!SCHEME_PATTERN.test(value)) {
    return { valid: false, reason: "missingScheme" };
  }
  if (containsInvalidUriCharacters(value)) {
    return { valid: false, reason: "invalidCharacters" };
  }
  if (FILE_SCHEME_PATTERN.test(value) && !fileUriHasPath(value)) {
    return { valid: false, reason: "fileUriMissingPath" };
  }
  return { valid: true };
};
