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
  /**
   * Optional named auth profile this connection is bound to (issue #267).
   * Absent on entries saved before the binding existed; `undefined` and an
   * empty string both mean "no binding".
   */
  authProfileName?: string;
}

export interface SavedConnectionsV1 {
  schemaVersion: 1;
  connections: SavedConnection[];
}

export type SavedConnectionDraft = Omit<
  SavedConnection,
  "id" | "createdAt" | "lastUsedAt"
>;