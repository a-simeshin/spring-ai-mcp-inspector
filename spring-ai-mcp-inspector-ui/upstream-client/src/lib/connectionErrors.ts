// [spring-ai-mcp-inspector PATCH] D3 structured error contract + connect failure
// contract merged into one module (issue #54). Previously split across
// connectionAuthErrors.ts (ProxyErrorDto parsing from SSE `error` events / HTTP
// 401/403 bodies) and connectErrors.ts (MCP_CONNECT_FAILED structured error
// parsing from the proxy transport, ConnectFailedError class).
//
// NOTE: only `McpError` (types.js) is imported from the SDK - the SDK client
// modules (sse.js / streamableHttp.js / auth.js) transitively require the
// ESM-only `pkce-challenge` package, which jest's CJS resolver cannot load.
// The SDK error classes are therefore matched structurally (code / class
// name), which is stable because the SDK does not minify its dist output.
import { McpError } from "@modelcontextprotocol/sdk/types.js";
import { MCP_PROXY_TRANSPORT_ERROR_CODE } from "./constants";

// ===================================================================
// ProxyErrorDto - D3 structured error contract (from connectionAuthErrors.ts)
// ===================================================================

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

// ===================================================================
// ConnectFailure - structured connect-failure contract (from connectErrors.ts)
// ===================================================================

export type ConnectFailureReason =
  | "timeout"
  | "connection_refused"
  | "dns"
  | "unauthorized"
  | "unknown";

export interface ConnectFailure {
  code: string;
  reason: ConnectFailureReason;
  message: string;
  retryable: boolean;
}

export const CONNECT_FAILED_ERROR_CODE = "MCP_CONNECT_FAILED";

const CONNECT_FAILURE_REASONS: readonly ConnectFailureReason[] = [
  "timeout",
  "connection_refused",
  "dns",
  "unauthorized",
  "unknown",
];

/**
 * Error thrown from the transport's fetch wrapper when the proxy answers
 * with the structured `MCP_CONNECT_FAILED` contract (see module doc).
 */
export class ConnectFailedError extends Error {
  readonly code: string;
  readonly reason: ConnectFailureReason;
  readonly retryable: boolean;

  constructor(failure: ConnectFailure) {
    super(failure.message);
    this.name = "ConnectFailedError";
    this.code = failure.code;
    this.reason = failure.reason;
    this.retryable = failure.retryable;
  }
}

export function isConnectFailedError(error: unknown): error is ConnectFailedError {
  return error instanceof ConnectFailedError;
}

/**
 * Reads the structured `MCP_CONNECT_FAILED` error out of a non-2xx transport
 * response. Consumes only a clone of the body, so callers can still hand the
 * original response to the SDK when the body does not match the contract.
 */
export async function parseConnectFailureResponse(
  response: Response,
): Promise<ConnectFailure | null> {
  let data: unknown;
  try {
    data = await response.clone().json();
  } catch {
    return null;
  }

  if (!isPlainObject(data)) {
    return null;
  }
  const error = data.error;
  if (!isPlainObject(error)) {
    return null;
  }
  if (error.code !== CONNECT_FAILED_ERROR_CODE) {
    return null;
  }
  const reason = error.reason;
  if (
    typeof reason !== "string" ||
    !CONNECT_FAILURE_REASONS.includes(reason as ConnectFailureReason)
  ) {
    return null;
  }

  return {
    code: CONNECT_FAILED_ERROR_CODE,
    reason: reason as ConnectFailureReason,
    message:
      typeof error.message === "string"
        ? error.message
        : "Failed to connect to the MCP server",
    retryable: typeof error.retryable === "boolean" ? error.retryable : true,
  };
}

/**
 * Checks whether an unknown error represents an HTTP-level 401 from the SDK.
 * Covered SDK error types: SseError (SSE), StreamableHTTPError (streamable HTTP),
 * UnauthorizedError (OAuth). Other error types with .code === 401 are also
 * treated as auth failures for the purpose of displaying the correct banner.
 */
export function isHttp401Error(error: unknown): boolean {
  if (error && typeof error === "object") {
    const err = error as Record<string, unknown>;
    const code = err.code;
    return typeof code === "number" && code === 401;
  }
  return false;
}

/**
 * Maps any connect failure to the structured contract so the UI always has a
 * reason + message to show. Structured failures keep their classification;
 * everything else (network errors, SDK errors, unexpected shapes) is
 * reported as `unknown` with the error's own message.
 *
 * SDK-level HTTP 401 errors (SseError, StreamableHTTPError, etc.) are mapped
 * to the `unauthorized` reason so the UI can show a dedicated auth banner.
 */
export function connectionFailureFromError(error: unknown): ConnectFailure {
  if (isConnectFailedError(error)) {
    return {
      code: error.code,
      reason: error.reason,
      message: error.message,
      retryable: error.retryable,
    };
  }
  if (isHttp401Error(error)) {
    return {
      code: CONNECT_FAILED_ERROR_CODE,
      reason: "unauthorized",
      message: error instanceof Error ? error.message : String(error),
      retryable: true,
    };
  }
  return {
    code: CONNECT_FAILED_ERROR_CODE,
    reason: "unknown",
    message: error instanceof Error ? error.message : String(error),
    retryable: true,
  };
}

/** Human-readable label for a failure reason (shown in the sidebar alert). */
export function humanReadableReason(reason: ConnectFailureReason): string {
  switch (reason) {
    case "timeout":
      return "Connection timed out (timeout)";
    case "connection_refused":
      return "Connection refused (connection_refused)";
    case "dns":
      return "Could not resolve the host (DNS)";
    case "unauthorized":
      return "Authentication required (unauthorized)";
    case "unknown":
      return "Unknown error (unknown)";
  }
}