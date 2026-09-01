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
              { request: JSON.stringify({}), at: 100 },
              { request: JSON.stringify({}), response: "ok", at: 200 },
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
  });
});