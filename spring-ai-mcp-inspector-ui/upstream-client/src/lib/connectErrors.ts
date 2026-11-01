import { McpError, ErrorCode } from "@modelcontextprotocol/sdk/types.js";

/**
 * Structured connect-failure contract shared with the backend.
 *
 * When the MCP Inspector Proxy cannot reach the upstream MCP server, the
 * POST /mcp-inspector-api/mcp handler answers with a non-2xx status and a
 * JSON body of the shape:
 *
 *   {"error":{"code":"MCP_CONNECT_FAILED","reason":"<timeout|connection_refused|dns|unknown>","message":"<human-readable>","retryable":true}}
 *
 * This module parses that body out of transport responses, carries it as an
 * error through the SDK transport layer, and maps arbitrary connect failures
 * to the same shape so the UI can always show a reason + Retry.
 */

export type ConnectFailureReason =
  | "timeout"
  | "connection_refused"
  | "dns"
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
  "unknown",
];

function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

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

  if (!isJsonObject(data)) {
    return null;
  }
  const error = data.error;
  if (!isJsonObject(error)) {
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
 * Maps any connect failure to the structured contract so the UI always has a
 * reason + message to show. Structured failures keep their classification;
 * everything else (network errors, SDK errors, unexpected shapes) is
 * reported as `unknown` with the error's own message.
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
  // [spring-ai-mcp-inspector PATCH] Classify SDK request-timeout errors
  // (ErrorCode.RequestTimeout = -32001) as 'timeout' so the UI renders
  // the Reset session button (it only shows for reason === "timeout").
  // Refs #157.
  if (error instanceof McpError && error.code === ErrorCode.RequestTimeout) {
    return {
      code: CONNECT_FAILED_ERROR_CODE,
      reason: "timeout",
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
    // [spring-ai-mcp-inspector PATCH] User-facing wording: say which
    // request timed out instead of the generic label. Refs #157.
    case "timeout":
      return "POST /mcp request timed out";
    case "connection_refused":
      return "Connection refused (connection_refused)";
    case "dns":
      return "Could not resolve the host (DNS)";
    case "unknown":
      return "Unknown error (unknown)";
  }
}

// ===================================================================
// [spring-ai-mcp-inspector PATCH] block2: terminal SSE `retry` contract
// (dead upstream, owner review 2026-10-30 on PR #154, twin port)
// ===================================================================

/**
 * The proxy precedes the stream close of a dead upstream with the SSE `retry`
 * field carrying ProxyReconnectPolicy.TERMINAL_RECONNECT_MILLIS (server side,
 * core module). Any retry delay of an hour or more is treated as terminal: the
 * browser must stop reconnecting instead of minting a doomed proxy session
 * every ~3s.
 */
export const TERMINAL_RECONNECT_MILLIS = 2_147_483_000;

const TERMINAL_RECONNECT_THRESHOLD_MILLIS = 3_600_000;

/**
 * Parses one raw SSE line: returns the retry delay in milliseconds when the
 * line is a `retry:` field with a terminal (>= 1h) value, otherwise null.
 * Ordinary short retry values (transport hiccups) stay null so the browser
 * keeps its normal reconnect cadence.
 */
export function parseTerminalReconnectMillis(line: string): number | null {
  if (!line.startsWith("retry:")) {
    return null;
  }
  const retryMillis = Number.parseInt(line.slice("retry:".length).trim(), 10);
  if (
    !Number.isFinite(retryMillis) ||
    retryMillis < TERMINAL_RECONNECT_THRESHOLD_MILLIS
  ) {
    return null;
  }
  return retryMillis;
}

// ===================================================================
// [spring-ai-mcp-inspector PATCH] block3: readable SSE `error` event
// (dead upstream, owner review 2026-10-30 on PR #154, twin port; this
// line has no ProxyErrorDto contract, so the payload is the simple
// message + reason object, see ProxyErrorFallback in the core module)
// ===================================================================

/**
 * Parses the data line of the proxy's terminal SSE `error` event. The 1.x
 * payload is the simple object `{"message": "...", "reason": "..."}` with the
 * reason taken from the same vocabulary as ConnectFailureReason; an unknown
 * reason still parses (mapped to "unknown"), while anything that is not such
 * an object returns null so the caller keeps its generic failure handling.
 */
export function parseProxyErrorEvent(data: string): ConnectFailure | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch {
    return null;
  }
  if (!isJsonObject(parsed)) {
    return null;
  }
  const message = typeof parsed.message === "string" && parsed.message.length > 0
    ? parsed.message
    : null;
  if (message === null) {
    return null;
  }
  const reason = (CONNECT_FAILURE_REASONS as readonly string[]).includes(parsed.reason as string)
    ? (parsed.reason as ConnectFailureReason)
    : "unknown";
  return {
    code: CONNECT_FAILED_ERROR_CODE,
    reason,
    message,
    retryable: true,
  };
}
