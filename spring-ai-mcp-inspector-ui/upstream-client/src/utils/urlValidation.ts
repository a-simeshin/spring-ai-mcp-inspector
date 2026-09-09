/**
 * Validates that a URL is safe for redirection.
 * Only allows HTTP and HTTPS protocols to prevent XSS attacks.
 *
 * @param url - The URL string to validate
 * @throws Error if the URL has an unsafe protocol
 */
export function validateRedirectUrl(url: string | URL): void {
  try {
    const parsedUrl = new URL(url);
    if (parsedUrl.protocol !== "http:" && parsedUrl.protocol !== "https:") {
      throw new Error("Authorization URL must be HTTP or HTTPS");
    }
  } catch (error) {
    if (
      error instanceof Error &&
      error.message === "Authorization URL must be HTTP or HTTPS"
    ) {
      throw error;
    }
    // If URL parsing fails, it's also invalid
    throw new Error(`Invalid URL: ${url}`);
  }
}

// [spring-ai-mcp-inspector PATCH] validateServerUrl: client-side URL format validation for the Connect field (see NOTICE.d/url-validation.txt).
const ALLOWED_SCHEMES = ["http:", "https:", "ws:", "wss:"];

export interface UrlValidationResult {
  isValid: boolean;
  errorMessage: string | null;
}

/**
 * Validates an MCP server URL for the Connect field.
 *
 * Valid formats:
 * - http://, https://, ws://, wss:// with a non-empty host
 * - Absolute path starting with '/'
 *
 * @param url - The URL string to validate
 * @returns An object with `isValid` and `errorMessage` (null when valid)
 */
export function validateServerUrl(url: string): UrlValidationResult {
  const trimmed = url.trim();

  if (!trimmed) {
    return { isValid: false, errorMessage: "URL must not be empty" };
  }

  // Absolute path (starts with '/') is always valid
  if (trimmed.startsWith("/")) {
    return { isValid: true, errorMessage: null };
  }

  // Try to parse as a URL
  try {
    const parsed = new URL(trimmed);

    if (!ALLOWED_SCHEMES.includes(parsed.protocol)) {
      return {
        isValid: false,
        errorMessage: `URL must start with http://, https://, ws://, wss://, or /`,
      };
    }

    return { isValid: true, errorMessage: null };
  } catch {
    return {
      isValid: false,
      errorMessage: `URL must start with http://, https://, ws://, wss://, or /`,
    };
  }
}
