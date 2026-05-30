/**
 * Shared inspector UI types.
 */

export type TransportType = 'stdio' | 'sse' | 'streamable-http';

export interface ConnectionConfig {
  transport: TransportType;
  command?: string;
  args?: string;
  url?: string;
  env?: Record<string, string>;
  headers?: Record<string, string>;
}

export interface InspectorConfig {
  transport: string;
  endpoint?: string;
  messageEndpoint?: string;
  stack: string;
  serverName: string;
  version: string;
  authToken: string;
  capabilities: Record<string, unknown>;
}

export type ConnectionStatus = 'idle' | 'connecting' | 'connected' | 'error';

export interface ConnectionState {
  config: ConnectionConfig;
  sessionId: string | null;
  status: ConnectionStatus;
  error?: string;
}

/** A filesystem root exposed to MCP servers (RFC-3986 URI). */
export interface Root {
  uri: string;
  name?: string;
}

/** Inputs required to kick off an OAuth 2.1 authorization-code flow. */
export interface OAuthInitiateRequest {
  authorizationEndpoint: string;
  tokenEndpoint: string;
  clientId: string;
  redirectUri: string;
  scope?: string;
  pkce?: boolean;
}

/** Token-endpoint response payload (RFC 6749 §5.1). */
export interface OAuthTokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn?: number;
  refreshToken?: string;
  scope?: string;
}
