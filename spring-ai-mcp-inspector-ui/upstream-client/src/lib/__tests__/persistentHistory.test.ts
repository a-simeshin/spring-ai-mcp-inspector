// [spring-ai-mcp-inspector PATCH] Unit tests for persistent history storage layer.
// See NOTICE.d/persistent-history.txt for details.

import {
  HISTORY_KEY,
  loadHistory,
  appendHistory,
  clearHistory,
  clearAllHistory,
} from "../persistentHistory";
import type { HistoryEntry } from "../types/historyEntry";

function makeEntry(overrides?: Partial<HistoryEntry>): HistoryEntry {
  return {
    request: JSON.stringify({ method: "tools/list", params: {} }),
    response: JSON.stringify({ tools: [] }),
    at: Date.now(),
    ...overrides,
  };
}

describe("persistentHistory", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe("loadHistory", () => {
    it("returns empty array when no data stored", () => {
      expect(loadHistory("conn-1")).toEqual([]);
    });

    it("returns empty array for unknown connection", () => {
      appendHistory("conn-1", makeEntry());
      expect(loadHistory("nonexistent")).toEqual([]);
    });

    it("loads entries for a specific connection", () => {
      appendHistory("conn-1", makeEntry({ at: 100 }));
      appendHistory("conn-1", makeEntry({ at: 200 }));
      const entries = loadHistory("conn-1");
      expect(entries).toHaveLength(2);
    });

    it("does not mix entries from different connections", () => {
      appendHistory("conn-1", makeEntry({ at: 100 }));
      appendHistory("conn-2", makeEntry({ at: 200 }));
      expect(loadHistory("conn-1")).toHaveLength(1);
      expect(loadHistory("conn-2")).toHaveLength(1);
    });
  });

  describe("appendHistory", () => {
    it("persists an entry to localStorage", () => {
      appendHistory("conn-1", makeEntry({ at: 100 }));
      const raw = localStorage.getItem(HISTORY_KEY);
      expect(raw).toBeTruthy();
      const parsed = JSON.parse(raw!);
      expect(parsed.schemaVersion).toBe(1);
      expect(parsed.byConnection["conn-1"]).toHaveLength(1);
    });

    it("preserves existing entries when appending", () => {
      appendHistory("conn-1", makeEntry({ at: 100 }));
      appendHistory("conn-1", makeEntry({ at: 200 }));
      const entries = loadHistory("conn-1");
      expect(entries).toHaveLength(2);
    });

    it("evicts oldest entries when per-connection cap is exceeded", () => {
      // Fill up to cap + 1
      for (let i = 0; i < 101; i++) {
        appendHistory("conn-1", makeEntry({ at: i }));
      }
      const entries = loadHistory("conn-1");
      expect(entries).toHaveLength(100);
      // The oldest entry (at=0) should be evicted
      const atValues = entries.map((e) => e.at).sort((a, b) => a - b);
      expect(atValues[0]).toBe(1); // at=0 was evicted
      expect(atValues[atValues.length - 1]).toBe(100); // at=100 is present
    });

    it("evicts oldest connection bucket when global store exceeds 500KB", () => {
      // Create a large entry to push global size
      const largeRequest = JSON.stringify({
        method: "tools/call",
        params: { name: "big-tool", arguments: { data: "x".repeat(5000) } },
      });
      const largeResponse = JSON.stringify({
        content: [{ type: "text", text: "y".repeat(5000) }],
      });

      // Add entries to conn-1 (oldest, should be evicted first)
      for (let i = 0; i < 10; i++) {
        appendHistory(
          "conn-old",
          makeEntry({
            request: largeRequest,
            response: largeResponse,
            at: i,
          }),
        );
      }

      // Verify conn-old has entries
      expect(loadHistory("conn-old").length).toBeGreaterThan(0);

      // Add many more entries to conn-2 to push over global limit
      for (let i = 0; i < 50; i++) {
        appendHistory(
          "conn-2",
          makeEntry({
            request: largeRequest,
            response: largeResponse,
            at: 1000 + i,
          }),
        );
      }

      // conn-old should have been evicted entirely (it's the oldest)
      // conn-2 should still have entries
      expect(loadHistory("conn-old")).toHaveLength(0);
      expect(loadHistory("conn-2").length).toBeGreaterThan(0);

      // Total store should be under 500KB
      const raw = localStorage.getItem(HISTORY_KEY);
      expect(raw!.length).toBeLessThanOrEqual(500 * 1024);
    });

    it("handles localStorage QuotaExceededError gracefully", () => {
      // Simulate a very large entry that would exceed quota
      const hugeRequest = JSON.stringify({
        method: "tools/call",
        params: { data: "x".repeat(1024 * 100) }, // ~100KB per entry
      });

      // We can't reliably trigger QuotaExceededError in jsdom,
      // but we can verify the function doesn't throw
      expect(() => {
        appendHistory("conn-1", makeEntry({ request: hugeRequest }));
      }).not.toThrow();
    });

    // [spring-ai-mcp-inspector PATCH] Truncation: request/response strings
    // are truncated to 10 KB before storage (#121). Verify at the raw
    // localStorage level because the truncated string may be invalid JSON
    // and would be filtered out by isValidEntry on load.
    it("truncates request and response strings to 10 KB with marker", () => {
      const longRequest = JSON.stringify({
        method: "tools/call",
        params: { data: "x".repeat(15 * 1024) },
      });
      const longResponse = JSON.stringify({
        content: [{ type: "text", text: "y".repeat(15 * 1024) }],
      });
      // Both are longer than 10 KB
      expect(longRequest.length).toBeGreaterThan(10 * 1024);
      expect(longResponse.length).toBeGreaterThan(10 * 1024);

      appendHistory("conn-1", makeEntry({ request: longRequest, response: longResponse, at: 100 }));
      // Read raw localStorage to verify truncation at storage level
      const raw = JSON.parse(localStorage.getItem(HISTORY_KEY)!);
      const stored = raw.byConnection["conn-1"][0];
      expect(stored.request.length).toBeLessThanOrEqual(10 * 1024);
      expect(stored.request.length).toBe(10 * 1024);
      expect(stored.request.endsWith("...[truncated]")).toBe(true);
      expect(stored.response.length).toBeLessThanOrEqual(10 * 1024);
      expect(stored.response.length).toBe(10 * 1024);
      expect(stored.response.endsWith("...[truncated]")).toBe(true);
    });

    // [spring-ai-mcp-inspector PATCH] Truncation: entries under 10 KB
    // are stored unchanged (#121).
    it("does not truncate entries under 10 KB", () => {
      const shortRequest = JSON.stringify({ method: "ping" });
      const shortResponse = JSON.stringify({ ok: true });
      appendHistory("conn-1", makeEntry({ request: shortRequest, response: shortResponse, at: 100 }));
      const entries = loadHistory("conn-1");
      expect(entries).toHaveLength(1);
      expect(entries[0].request).toBe(shortRequest);
      expect(entries[0].response).toBe(shortResponse);
    });

    // [spring-ai-mcp-inspector PATCH] Truncation: entries without response
    // preserve undefined (#121). Verify at raw localStorage level.
    it("preserves undefined response after truncation", () => {
      const longRequest = JSON.stringify({
        method: "tools/call",
        params: { data: "x".repeat(15 * 1024) },
      });
      appendHistory("conn-1", makeEntry({ request: longRequest, response: undefined, at: 100 }));
      // Read raw localStorage to verify undefined response is preserved
      const raw = JSON.parse(localStorage.getItem(HISTORY_KEY)!);
      const stored = raw.byConnection["conn-1"];
      // Find the entry with the truncated request
      expect(stored.length).toBeGreaterThanOrEqual(1);
      const entry = stored.find((e: { request: string }) => e.request.length === 10 * 1024);
      expect(entry).toBeDefined();
      expect(entry.response).toBeUndefined();
    });
  });

  describe("clearHistory", () => {
    it("removes a connection bucket", () => {
      appendHistory("conn-1", makeEntry({ at: 100 }));
      appendHistory("conn-2", makeEntry({ at: 200 }));
      clearHistory("conn-1");
      expect(loadHistory("conn-1")).toHaveLength(0);
      expect(loadHistory("conn-2")).toHaveLength(1);
    });

    it("does nothing for unknown connection", () => {
      // Should not throw
      clearHistory("nonexistent");
    });

    it("does not affect other connections", () => {
      appendHistory("conn-1", makeEntry({ at: 100 }));
      appendHistory("conn-2", makeEntry({ at: 200 }));
      clearHistory("conn-1");
      const raw = localStorage.getItem(HISTORY_KEY);
      const parsed = JSON.parse(raw!);
      expect(parsed.byConnection["conn-2"]).toHaveLength(1);
      expect(parsed.byConnection["conn-1"]).toBeUndefined();
    });
  });

  describe("clearAllHistory", () => {
    it("removes the entire history key", () => {
      appendHistory("conn-1", makeEntry({ at: 100 }));
      clearAllHistory();
      expect(localStorage.getItem(HISTORY_KEY)).toBeNull();
      expect(loadHistory("conn-1")).toEqual([]);
    });
  });

  describe("ephemeral connections", () => {
    it("supports the 'ephemeral' key for unsaved sessions", () => {
      appendHistory("ephemeral", makeEntry({ at: 100 }));
      appendHistory("ephemeral", makeEntry({ at: 200 }));
      expect(loadHistory("ephemeral")).toHaveLength(2);
    });

    it("separates ephemeral from saved connection history", () => {
      appendHistory("ephemeral", makeEntry({ at: 100 }));
      appendHistory("conn-1", makeEntry({ at: 200 }));
      expect(loadHistory("ephemeral")).toHaveLength(1);
      expect(loadHistory("conn-1")).toHaveLength(1);
    });
  });

  describe("malformed localStorage", () => {
    it("rejects entries with null values", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: { "conn-1": [null] },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
    });

    it("rejects entries with missing request", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: {
            "conn-1": [{ response: JSON.stringify({}), at: 100 }],
          },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
    });

    it("rejects entries with non-string request", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: {
            "conn-1": [{ request: 42, at: 100 }],
          },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
    });

    it("rejects entries with non-number at", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: {
            "conn-1": [
              { request: JSON.stringify({}), at: "not-a-number" },
            ],
          },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
    });

    it("rejects entries with NaN at", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: {
            "conn-1": [{ request: JSON.stringify({}), at: NaN }],
          },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
    });

    it("rejects entries with non-string response", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: {
            "conn-1": [
              { request: JSON.stringify({}), response: 42, at: 100 },
            ],
          },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
    });

    it("filters out invalid entries but keeps valid ones in the same bucket", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: {
            "conn-1": [
              null,
              { request: JSON.stringify({ method: "ping" }), at: 100 },
              { request: JSON.stringify({ method: "ping" }), response: JSON.stringify({ ok: true }), at: 200 },
            ],
          },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(2);
    });

    it("returns empty array when bucket is not an array", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: { "conn-1": "not-an-array" },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
    });

    it("preserves valid entries from other connections when one bucket is malformed", () => {
      appendHistory("conn-2", makeEntry({ at: 300 }));
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: {
            "conn-1": [null],
            "conn-2": JSON.parse(
              localStorage.getItem(HISTORY_KEY)!
            ).byConnection["conn-2"],
          },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
      expect(loadHistory("conn-2")).toHaveLength(1);
    });

    // [spring-ai-mcp-inspector PATCH] Regression: reject entries whose
    // request string is not valid JSON (prevents HistoryAndNotifications
    // crash on JSON.parse) (#121).
    it("rejects entries with non-JSON request string", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: {
            "conn-1": [{ request: "not-json", at: 100 }],
          },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
    });

    // [spring-ai-mcp-inspector PATCH] Regression: reject entries with
    // non-JSON response string (#121).
    it("rejects entries with non-JSON response string", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: {
            "conn-1": [
              {
                request: JSON.stringify({ method: "ping" }),
                response: "not-json",
                at: 100,
              },
            ],
          },
        }),
      );
      expect(loadHistory("conn-1")).toHaveLength(0);
    });

    // [spring-ai-mcp-inspector PATCH] Regression: non-array bucket at
    // the store level is silently dropped (prevents TypeError in
    // evictGlobal and appendHistory) (#121).
    it("silently drops non-array buckets at store level", () => {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: { "conn-1": {} },
        }),
      );
      // loadHistory should return empty; the store should have been
      // cleaned up by readStore validation
      const result = loadHistory("conn-1");
      expect(result).toEqual([]);
      // Verify the malformed bucket was dropped from the re-serialized
      // store (appendHistory will re-write via readStore validation)
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({
          schemaVersion: 1,
          byConnection: { "conn-1": {} },
        }),
      );
      appendHistory("conn-2", makeEntry({ at: 100 }));
      const raw = JSON.parse(localStorage.getItem(HISTORY_KEY)!);
      expect(raw.byConnection["conn-1"]).toBeUndefined();
      expect(raw.byConnection["conn-2"]).toBeDefined();
    });
  });
});