// [spring-ai-mcp-inspector PATCH] Persisted saved connections model.
// See NOTICE.d/saved-connections.txt for details.

import type { CustomHeaders } from "./customHeaders";

export interface SavedConnection {
  id: string;
  name: string;
  transport: "stdio" | "sse" | "streamable-http";
  connectionType: "proxy" | "direct";
  url?: string;
  command?: string;
  args?: string;
  env?: Record<string, string>;
  customHeaders: CustomHeaders;
  lastUsedAt: number;
  createdAt: number;
}

export interface SavedConnectionsV1 {
  schemaVersion: 1;
  connections: SavedConnection[];
}

export type SavedConnectionDraft = Omit<
  SavedConnection,
  "id" | "createdAt" | "lastUsedAt"
>;