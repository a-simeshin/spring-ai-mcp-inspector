/**
 * Structured connect-failure contract shared with the backend.
 *
 * When the MCP Inspector Proxy cannot reach the upstream MCP server, the
 * POST /mcp-inspector-api/mcp handler answers with a non-2xx status and a
 * JSON body of the shape:
 *
 *   {"error":{"code":"MCP_CONNECT_FAILED","reason":"<timeout|connection_refused|dns|unauthorized|not_found|unknown>","message":"<human-readable>","retryable":true}}
 *
 * The proxy also returns a richer shape for per-phase connection timeouts:
 *
 *   {"error":{"code":"connection_timeout","phase":"<connect|initialize>","elapsedMs":<long>,"budgetMs":<long>,"message":"<human-readable>","retryable":true}}
 *
 * This module parses that body out of transport responses, carries it as an
 * error through the SDK transport layer, and maps arbitrary connect failures
 * to the same shape so the UI can always show a reason + Retry.
 */

export type ConnectFailureReason =
  | "timeout"
  | "connection_refused"
  | "dns"
  | "unauthorized"
  | "not_found"
  | "unknown";

export interface ConnectFailure {
  code: string;
  reason: ConnectFailureReason;
  message: string;
  retryable: boolean;
  /** Per-phase timeout detail: which phase exceeded its budget. */
  phase?: string;
  /** Wall-clock ms spent in the phase when the budget fired. */
  elapsedMs?: number;
  /** Per-phase budget in ms. */
  budgetMs?: number;
}

export const CONNECT_FAILED_ERROR_CODE = "MCP_CONNECT_FAILED";
export const CONNECTION_TIMEOUT_ERROR_CODE = "connection_timeout";

const CONNECT_FAILURE_REASONS: readonly ConnectFailureReason[] = [
  "timeout",
  "connection_refused",
  "dns",
  "unauthorized",
  "not_found",
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
  readonly phase?: string;
  readonly elapsedMs?: number;
  readonly budgetMs?: number;

  constructor(failure: ConnectFailure) {
    super(failure.message);
    this.name = "ConnectFailedError";
    this.code = failure.code;
    this.reason = failure.reason;
    this.retryable = failure.retryable;
    this.phase = failure.phase;
    this.elapsedMs = failure.elapsedMs;
    this.budgetMs = failure.budgetMs;
  }
}

export function isConnectFailedError(error: unknown): error is ConnectFailedError {
  return error instanceof ConnectFailedError;
}

/**
 * Reads the structured connect-failure error out of a non-2xx transport
 * response. Handles both the legacy `MCP_CONNECT_FAILED` code and the
 * per-phase `connection_timeout` code. Consumes only a clone of the body,
 * so callers can still hand the original response to the SDK when the body
 * does not match the contract.
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

  // Per-phase connection_timeout payload from the proxy
  if (error.code === CONNECTION_TIMEOUT_ERROR_CODE) {
    const phase = typeof error.phase === "string" ? error.phase : undefined;
    const elapsedMs = typeof error.elapsedMs === "number" ? error.elapsedMs : undefined;
    const budgetMs = typeof error.budgetMs === "number" ? error.budgetMs : undefined;
    return {
      code: CONNECT_FAILED_ERROR_CODE,
      reason: "timeout",
      message:
        typeof error.message === "string"
          ? error.message
          : "Connection timed out",
      retryable: typeof error.retryable === "boolean" ? error.retryable : true,
      phase,
      elapsedMs,
      budgetMs,
    };
  }

  // Legacy MCP_CONNECT_FAILED payload
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
      phase: error.phase,
      elapsedMs: error.elapsedMs,
      budgetMs: error.budgetMs,
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
      return "Connection timed out";
    case "connection_refused":
      return "Connection refused";
    case "dns":
      return "Cannot resolve host";
    case "unauthorized":
      return "Authentication required";
    case "not_found":
      return "Server responded 404: check the URL path";
    default:
      return "";
  }
}

/**
 * Human-readable phase name shown in the timeout error banner.
 * Maps internal phase wire values to user-facing labels.
 */
const PHASE_LABELS: Record<string, string> = {
  connect: "Transport connect",
  initialize: "MCP initialize",
};

export function humanReadablePhase(phase: string): string {
  return PHASE_LABELS[phase] ?? phase;
}

/**
 * Formats elapsed/budget ms as a short "X.Xs of Ys" string.
 * Values below 1000ms are shown as "<1s".
 */
export function formatBudgetBreakdown(elapsedMs: number, budgetMs: number): string {
  const elapsed = elapsedMs < 1000 ? "<1" : (elapsedMs / 1000).toFixed(1);
  const budget = budgetMs < 1000 ? "<1" : (budgetMs / 1000).toFixed(1);
  return `${elapsed}s of ${budget}s`;
}