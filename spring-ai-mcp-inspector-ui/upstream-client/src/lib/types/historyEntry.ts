// [spring-ai-mcp-inspector PATCH] Persistent history storage model.
// See NOTICE.d/persistent-history.txt for details.

export interface HistoryEntry {
  request: string;  // JSON.stringify of the JSON-RPC request
  response?: string; // JSON.stringify of the response, or undefined
  at: number;        // epoch ms, for eviction ordering and display
}

export interface HistoryStoreV1 {
  schemaVersion: 1;
  byConnection: Record<string, HistoryEntry[]>;
}