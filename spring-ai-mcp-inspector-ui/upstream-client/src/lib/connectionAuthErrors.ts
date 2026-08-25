// [spring-ai-mcp-inspector PATCH] D3 structured error contract (issue #54):
// the backend proxy emits `ProxyErrorDto` as a named SSE `error` event (SSE
// path) or as the JSON response body (streamable 401/403 path). The UI parses
// it here so the exact code/reason/guidance/url surface in the error banner.
//
// NOTE: only `McpError` (types.js) is imported from the SDK — the SDK client
// modules (sse.js / streamableHttp.js / auth.js) transitively require the
// ESM-only `pkce-challenge` package, which jest's CJS resolver cannot load.
// The SDK error classes are therefore matched structurally (code / class
// name), which is stable because the SDK does not minify its dist output.
import { McpError } from "@modelcontextprotocol/sdk/types.js";
import { MCP_PROXY_TRANSPORT_ERROR_CODE } from "./constants";

export interface ProxyErrorDto {
  status: number;
  code: string;
  reason: string;
  guidance: string;
  url?: string;
}

/** Whether an unknown value is a well-formed D3 `ProxyErrorDto`. */
export function isProxyErrorDto(value: unknown): value is ProxyErrorDto {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const rec = value as Record<string, unknown>;
  return (
    typeof rec.status === "number" &&
    typeof rec.code === "string" &&
    typeof rec.reason === "string" &&
    typeof rec.guidance === "string"
  );
}

/** Parses a D3 error DTO from a JSON string (SSE event data / response body). */
export function parseProxyErrorDto(
  json: string,
): ProxyErrorDto | null {
  try {
    const parsed: unknown = JSON.parse(json);
    return isProxyErrorDto(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** `McpError.data` from server `mcpProxy` `serializeProxyTransportError`. */
export function mcpProxyTransportErrorDataIndicatesUnauthorized(
  data: Record<string, unknown>,
): boolean {
  if ("upstream401" in data) {
    const snapshot = data.upstream401;
    if (snapshot != null) return true;
  }
  const status = data.httpStatus;
  return typeof status === "number" && status === 401;
}

/**
 * Whether `handleAuthError` / OAuth recovery should run for this failure.
 *
 * SDK error classes are matched structurally (see module header): SseError and
 * StreamableHTTPError carry the HTTP status in `code`; UnauthorizedError is
 * identified by its class name; McpError (transport envelope) carries the
 * proxy's `upstream401`/`httpStatus` snapshot in `data`.
 */
export function isConnectionAuthError(error: unknown): boolean {
  if (
    typeof error === "object" &&
    error !== null &&
    (error as { code?: unknown }).code === 401
  ) {
    return true;
  }

  if (
    typeof error === "object" &&
    error !== null &&
    (error as { constructor?: { name?: string } }).constructor?.name ===
      "UnauthorizedError"
  ) {
    return true;
  }

  if (
    error instanceof McpError &&
    error.code === MCP_PROXY_TRANSPORT_ERROR_CODE &&
    isPlainObject(error.data) &&
    mcpProxyTransportErrorDataIndicatesUnauthorized(error.data)
  ) {
    return true;
  }

  return false;
}
