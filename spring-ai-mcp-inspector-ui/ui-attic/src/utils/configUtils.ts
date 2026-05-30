// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import type { ConnectionConfig, TransportType } from '../lib/types';

const STORAGE_KEY = 'mcp-inspector.connection';

interface SerializedConnection {
  transport?: TransportType;
  command?: string;
  args?: string;
  url?: string;
  env?: Record<string, string>;
  headers?: Record<string, string>;
}

/** Serializes a connection config into a JSON-ready plain object. */
export function serializeConnection(config: ConnectionConfig): SerializedConnection {
  return {
    transport: config.transport,
    command: config.command,
    args: config.args,
    url: config.url,
    env: config.env,
    headers: config.headers,
  };
}

/** Re-hydrates a serialized connection blob into a typed `ConnectionConfig`. */
export function deserializeConnection(raw: unknown): Partial<ConnectionConfig> {
  if (!raw || typeof raw !== 'object') return {};
  const obj = raw as SerializedConnection;
  const out: Partial<ConnectionConfig> = {};
  if (obj.transport === 'stdio' || obj.transport === 'sse' || obj.transport === 'streamable-http') {
    out.transport = obj.transport;
  }
  if (typeof obj.command === 'string') out.command = obj.command;
  if (typeof obj.args === 'string') out.args = obj.args;
  if (typeof obj.url === 'string') out.url = obj.url;
  if (obj.env && typeof obj.env === 'object') out.env = { ...obj.env };
  if (obj.headers && typeof obj.headers === 'object') out.headers = { ...obj.headers };
  return out;
}

/** Persists the connection config to `localStorage` (best-effort). */
export function saveConnection(config: ConnectionConfig): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(serializeConnection(config)));
  } catch {
    // ignore quota / private-mode errors
  }
}

/** Loads a previously stored connection config, if present. */
export function loadConnection(): Partial<ConnectionConfig> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    return deserializeConnection(JSON.parse(raw));
  } catch {
    return {};
  }
}
