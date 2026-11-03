// [spring-ai-mcp-inspector PATCH] D3 structured error contract + connect failure
// contract merged into one module (issue #54). Previously split across
// connectionAuthErrors.ts (ProxyErrorDto parsing from SSE `error` events / HTTP
// 401/403 bodies) and connectErrors.ts (MCP_CONNECT_FAILED structured error
// parsing from the proxy transport, ConnectFailedError class).
//
// When the MCP Inspector Proxy cannot reach the upstream MCP server, the
// POST /mcp-inspector-api/mcp handler answers with a non-2xx status and a
// JSON body of the shape:
//
//   {"error":{"code":"MCP_CONNECT_FAILED","reason":"<timeout|connection_refused|dns|unauthorized|not_found|unknown>","message":"<human-readable>","retryable":true}}
//
// The proxy also returns a richer shape for per-phase connection timeouts
// (ProxyConnectionBudgets.timeoutBody on the server), on the SAME
// MCP_CONNECT_FAILED code with a "timeout" reason:
//
//   {"error":{"code":"MCP_CONNECT_FAILED","reason":"timeout","phase":"<connect|initialize>","elapsedMs":<long>,"budgetMs":<long>,"message":"<human-readable>","retryable":true}}
//
// There is no separate wire code for it (the server javadoc says so and
// ProxyConnectionBudgetsTests asserts the absence of a connection_timeout
// key). The parser below therefore reads phase/elapsedMs/budgetMs on the
// MCP_CONNECT_FAILED branch; the connection_timeout code branch is kept
// only as future-proofing in case a dedicated code is ever introduced.
//
// This module parses that body out of transport responses, carries it as an
// error through the SDK transport layer, and maps arbitrary connect failures
// to the same shape so the UI can always show a reason + Retry.
//
// NOTE: only `McpError` (types.js) is imported from the SDK - the SDK client
// modules (sse.js / streamableHttp.js / auth.js) transitively require the
// ESM-only `pkce-challenge` package, which jest's CJS resolver cannot load.
// The SDK error classes are therefore matched structurally (code / class
// name), which is stable because the SDK does not minify its dist output.
import { McpError, ErrorCode } from "@modelcontextprotocol/sdk/types.js";
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
export function parseProxyErrorDto(json: string): ProxyErrorDto | null {
  try {
    const parsed: unknown = JSON.parse(json);
    return isProxyErrorDto(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

// ===================================================================
// [spring-ai-mcp-inspector PATCH] block2: terminal SSE `retry` contract
// (dead upstream, owner review 2026-10-30 on PR #154)
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

export function isConnectFailedError(
  error: unknown,
): error is ConnectFailedError {
  return error instanceof ConnectFailedError;
}

/**
 * Reads the structured connect-failure error out of a non-2xx transport
 * response. Parses the `MCP_CONNECT_FAILED` contract, including the
 * per-phase timeout detail (reason "timeout" plus phase/elapsedMs/budgetMs)
 * the proxy sends on that same code. A dedicated `connection_timeout` code
 * is accepted as future-proofing only; the server does not send it.
 * Consumes only a clone of the body, so callers can still hand the original
 * response to the SDK when the body does not match the contract.
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

  // Legacy MCP_CONNECT_FAILED payload. Per-phase timeouts also arrive on
  // this branch (ProxyConnectionBudgets.timeoutBody: same code, reason
  // "timeout", plus phase/elapsedMs/budgetMs), so those fields must be
  // carried through here; without them the sidebar cannot render the
  // structured timeout banner.
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
    phase: typeof error.phase === "string" ? error.phase : undefined,
    elapsedMs:
      typeof error.elapsedMs === "number" ? error.elapsedMs : undefined,
    budgetMs: typeof error.budgetMs === "number" ? error.budgetMs : undefined,
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
  // [spring-ai-mcp-inspector PATCH] Classify SDK request-timeout errors
  // (ErrorCode.RequestTimeout = -32001) as 'timeout' so the UI renders
  // the Reset session button (it only shows for reason === "timeout").
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
    case "timeout":
      return "POST /mcp request timed out";
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
