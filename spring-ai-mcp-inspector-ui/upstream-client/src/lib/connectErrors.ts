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
    case "unknown":
      return "Unknown error (unknown)";
  }
}