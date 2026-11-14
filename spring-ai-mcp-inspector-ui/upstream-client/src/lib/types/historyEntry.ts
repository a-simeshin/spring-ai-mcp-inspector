// [spring-ai-mcp-inspector PATCH] Persistent history storage model.
// See NOTICE.d/persistent-history.txt for details.

export interface HistoryEntry {
  request: string;  // JSON.stringify of the JSON-RPC request
  response?: string; // JSON.stringify of the response, or undefined
  at: number;        // epoch ms, for eviction ordering and display
  isError?: boolean; // true when the response is an error result or a JSON-RPC error; persisted so failed calls keep their error styling after a reload (#195)
}

export interface HistoryStoreV1 {
  schemaVersion: 1;
  byConnection: Record<string, HistoryEntry[]>;
}