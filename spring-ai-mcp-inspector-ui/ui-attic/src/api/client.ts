/**
 * REST API client for the MCP Inspector backend.
 *
 * All requests send the inspector auth token via the
 * `X-MCP-Inspector-Auth` header. The token is injected at index.html load time
 * into `window.__INSPECTOR_AUTH_TOKEN__`.
 */

declare global {
  interface Window {
    __INSPECTOR_AUTH_TOKEN__?: string;
  }
}

const API_BASE = '/mcp-inspector/api';

/** Detected transport types reported by the backend. */
export type TransportType = 'SSE' | 'STREAMABLE' | 'STATELESS' | 'STDIO_NO_HTTP' | 'UNKNOWN';

/** Stack flavor of the embedding application. */
export type StackType = 'WEBMVC' | 'WEBFLUX' | 'NONE';

/** Inspector runtime configuration returned by `/api/config`. */
export interface ConfigDto {
  transport: TransportType;
  endpoint: string | null;
  messageEndpoint?: string | null;
  stack: StackType;
  serverName: string;
  version: string;
  authToken: string;
  capabilities?: Record<string, unknown>;
}

/** JSON-RPC 2.0 envelope shape used by the relay endpoint. */
export interface JsonRpcResponse<T = unknown> {
  jsonrpc: '2.0';
  id: number | string;
  result?: T;
  error?: { code: number; message: string; data?: unknown };
}

/** Active loopback session metadata. */
export interface SessionDto {
  sessionId: string;
  transport: TransportType;
  serverName: string;
  serverVersion: string;
}

function authToken(): string {
  return window.__INSPECTOR_AUTH_TOKEN__ ?? '';
}

/**
 * Performs a fetch against the inspector REST API with auth header attached.
 *
 * @throws Error when the response status is non-2xx.
 */
export async function fetchJson<T>(
  url: string,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET',
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-MCP-Inspector-Auth': authToken(),
  };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`${method} ${url} → ${response.status} ${response.statusText}`);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** Fetches inspector configuration (transport, stack, auth token). */
export function getConfig(): Promise<ConfigDto> {
  return fetchJson<ConfigDto>(`${API_BASE}/config`);
}

/** Lightweight in-process event emitter that mirrors every JSON-RPC roundtrip. */
export interface RpcEvent {
  direction: 'out' | 'in';
  timestamp: number;
  method?: string;
  payload: unknown;
}

const rpcListeners = new Set<(event: RpcEvent) => void>();

/**
 * Subscribes to JSON-RPC traffic emitted by {@link jsonRpc}. Returns an
 * unsubscribe handle. Used by the Console tab to render live raw RPC traces.
 */
export function onRpcEvent(fn: (event: RpcEvent) => void): () => void {
  rpcListeners.add(fn);
  return () => {
    rpcListeners.delete(fn);
  };
}

function emitRpcEvent(event: RpcEvent): void {
  for (const fn of rpcListeners) {
    try {
      fn(event);
    } catch {
      // Listeners must not break the request pipeline.
    }
  }
}

let rpcId = 1;

/**
 * Cached loopback session promise so concurrent jsonRpc() calls share one
 * `/api/connect` round-trip. Cleared whenever the backend reports the session
 * is unknown (e.g., after a server restart).
 */
let sessionPromise: Promise<SessionDto> | null = null;

function ensureSession(): Promise<SessionDto> {
  if (sessionPromise === null) {
    sessionPromise = fetchJson<SessionDto>(`${API_BASE}/connect`, 'POST', {}).catch((err) => {
      sessionPromise = null;
      throw err;
    });
  }
  return sessionPromise;
}

/**
 * Sends a JSON-RPC request through the loopback relay endpoint.
 *
 * Lazily opens an inspector session on first use and attaches its id as a
 * `sessionId` query parameter (the relay endpoint requires it — see
 * {@code InspectorRestController#jsonrpc}). If the backend reports the
 * session is gone (HTTP 404), the cached id is dropped and one retry is
 * attempted to recover transparently from server restarts.
 */
export async function jsonRpc<T = unknown>(
  method: string,
  params?: unknown,
): Promise<JsonRpcResponse<T>> {
  const payload = {
    jsonrpc: '2.0' as const,
    id: rpcId++,
    method,
    params: params ?? {},
  };
  const send = async (): Promise<JsonRpcResponse<T>> => {
    const session = await ensureSession();
    return fetchJson<JsonRpcResponse<T>>(
      `${API_BASE}/jsonrpc?sessionId=${encodeURIComponent(session.sessionId)}`,
      'POST',
      payload,
    );
  };
  emitRpcEvent({ direction: 'out', timestamp: Date.now(), method, payload: { method, params: payload.params } });
  try {
    const response = await send();
    emitRpcEvent({
      direction: 'in',
      timestamp: Date.now(),
      method,
      payload: response.error ? { error: response.error } : { result: response.result },
    });
    return response;
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    if (message.includes('404')) {
      sessionPromise = null;
      const response = await send();
      emitRpcEvent({
        direction: 'in',
        timestamp: Date.now(),
        method,
        payload: response.error ? { error: response.error } : { result: response.result },
      });
      return response;
    }
    emitRpcEvent({ direction: 'in', timestamp: Date.now(), method, payload: { error: { code: -1, message } } });
    throw err;
  }
}

/** Opens a loopback MCP session against the embedding application. */
export function connect(): Promise<SessionDto> {
  sessionPromise = null;
  return ensureSession();
}

/** Closes a previously opened MCP session. */
export function disconnect(sessionId: string): Promise<void> {
  return fetchJson<void>(`${API_BASE}/session/${encodeURIComponent(sessionId)}`, 'DELETE');
}

import type {
  ConnectionConfig,
  InspectorConfig,
  OAuthInitiateRequest,
  OAuthTokenResponse,
  Root,
} from '../lib/types';

/** Connects to an external stdio MCP server by command (legacy signature). */
export function connectExternal(command: string[]): Promise<SessionDto>;
/** Connects to an external MCP server using a transport-aware connection config. */
export function connectExternal(config: ConnectionConfig): Promise<{ sessionId: string }>;
export function connectExternal(
  arg: string[] | ConnectionConfig,
): Promise<SessionDto> | Promise<{ sessionId: string }> {
  if (Array.isArray(arg)) {
    return fetchJson<SessionDto>(`${API_BASE}/connect`, 'POST', {
      target: 'external-stdio',
      command: arg,
    });
  }
  return fetchJson<{ sessionId: string }>(`${API_BASE}/connect`, 'POST', arg);
}

/**
 * Closes a session by id (alias matching {@link disconnect} for the new sidebar
 * action naming convention).
 */
export function disconnectSession(id: string): Promise<void> {
  return disconnect(id);
}

/**
 * Fetches the list of filesystem roots advertised to the connected MCP server.
 *
 * @see {@code InspectorRestController#getRoots}
 */
export function getRoots(sessionId: string): Promise<{ roots: Root[] }> {
  return fetchJson<{ roots: Root[] }>(
    `${API_BASE}/roots?sessionId=${encodeURIComponent(sessionId)}`,
  );
}

/**
 * Replaces the server-visible roots list and broadcasts a
 * `notifications/roots/list_changed` event to the MCP server.
 */
export function putRoots(sessionId: string, roots: Root[]): Promise<void> {
  return fetchJson<void>(
    `${API_BASE}/roots?sessionId=${encodeURIComponent(sessionId)}`,
    'PUT',
    { roots },
  );
}

/**
 * Sends a user response to a server-initiated request (sampling/elicitation).
 *
 * Either {@code result} or {@code error} must be provided. The backend
 * forwards the response back to the MCP server using the original requestId.
 */
export function respondToServerRequest(
  sessionId: string,
  requestId: string,
  body: { result?: unknown; error?: { code: number; message: string } },
): Promise<void> {
  return fetchJson<void>(
    `${API_BASE}/jsonrpc/respond?sessionId=${encodeURIComponent(sessionId)}&requestId=${encodeURIComponent(requestId)}`,
    'POST',
    body,
  );
}

/** Initiates an OAuth 2.1 authorization-code flow via the inspector relay. */
export function oauthInitiate(req: OAuthInitiateRequest): Promise<{ authUrl: string; state: string }> {
  return fetchJson<{ authUrl: string; state: string }>(`${API_BASE}/oauth/initiate`, 'POST', req);
}

/** Exchanges an OAuth authorization code for tokens via the inspector relay. */
export function oauthCallback(code: string, state: string): Promise<OAuthTokenResponse> {
  return fetchJson<OAuthTokenResponse>(
    `${API_BASE}/oauth/callback?code=${encodeURIComponent(code)}&state=${encodeURIComponent(state)}`,
  );
}

/**
 * Returns the inspector default runtime configuration in the shape consumed by
 * the new Sidebar.
 *
 * Wraps {@link getConfig} and normalizes optional fields.
 */
export async function getDefaultConfig(): Promise<InspectorConfig> {
  const dto = await getConfig();
  return {
    transport: dto.transport,
    endpoint: dto.endpoint ?? undefined,
    messageEndpoint: dto.messageEndpoint ?? undefined,
    stack: dto.stack,
    serverName: dto.serverName,
    version: dto.version,
    authToken: dto.authToken,
    capabilities: dto.capabilities ?? {},
  };
}
