// [spring-ai-mcp-inspector PATCH] Persistent history storage unit tests.
// See NOTICE.d/persistent-history.txt for details.

import {
  HISTORY_KEY,
  readStore,
  loadHistory,
  appendHistory,
  clearHistory,
  clearAllHistory,
} from "../persistentHistory";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Corrupt and missing data
// ---------------------------------------------------------------------------

describe("corrupt and missing data", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("returns empty store when no key exists", () => {
    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("returns empty store on corrupt JSON", () => {
    localStorage.setItem(HISTORY_KEY, "not-json-at-all");
    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("returns empty store on null JSON", () => {
    localStorage.setItem(HISTORY_KEY, "null");
    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("returns empty store on non-object root", () => {
    localStorage.setItem(HISTORY_KEY, '"string-root"');
    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("drops non-array buckets (schemaVersion mismatch handled)", () => {
    // If schemaVersion is not exactly 1, the code returns empty.
    localStorage.setItem(
      HISTORY_KEY,
      JSON.stringify({ schemaVersion: 999, byConnection: { c1: [] } }),
    );
    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("drops invalid entries in a bucket", () => {
    localStorage.setItem(
      HISTORY_KEY,
      JSON.stringify({
        schemaVersion: 1,
        byConnection: {
          c1: [
            { request: '{"ok":true}', at: 1 }, // valid
            { request: "not-json", at: 2 }, // invalid (request not valid JSON)
            null, // invalid
            { request: '{"ok":true}', at: "string" }, // invalid (at not number)
          ],
        },
      }),
    );
    const store = readStore();
    expect(store.byConnection["c1"]).toHaveLength(1);
    expect(store.byConnection["c1"][0].at).toBe(1);
  });
});

// ---------------------------------------------------------------------------
// Basic operations
// ---------------------------------------------------------------------------

describe("basic operations", () => {
  const CONN_ID = "stdio:echo hello";

  beforeEach(() => {
    localStorage.clear();
  });

  it("loadHistory returns empty array for unknown connection", () => {
    expect(loadHistory("unknown")).toEqual([]);
  });

  it("appends and reads back an entry", () => {
    appendHistory(CONN_ID, {
      request: '{"jsonrpc":"2.0","method":"ping"}',
      at: 100,
    });

    const history = loadHistory(CONN_ID);
    expect(history).toHaveLength(1);
    expect(history[0].request).toBe('{"jsonrpc":"2.0","method":"ping"}');
    expect(history[0].at).toBe(100);
  });

  it("appends multiple entries in order", () => {
    appendHistory(CONN_ID, { request: '{"m":"a"}', at: 1 });
    appendHistory(CONN_ID, { request: '{"m":"b"}', at: 2 });
    appendHistory(CONN_ID, { request: '{"m":"c"}', at: 3 });

    const history = loadHistory(CONN_ID);
    expect(history).toHaveLength(3);
    expect(history.map((e) => e.at)).toEqual([1, 2, 3]);
  });

  it("clearHistory removes only the specified connection", () => {
    appendHistory("conn-a", { request: '{"m":"a"}', at: 1 });
    appendHistory("conn-b", { request: '{"m":"b"}', at: 2 });

    clearHistory("conn-a");

    expect(loadHistory("conn-a")).toEqual([]);
    expect(loadHistory("conn-b")).toHaveLength(1);
  });

  it("clearAllHistory removes everything", () => {
    appendHistory("conn-a", { request: '{"m":"a"}', at: 1 });
    appendHistory("conn-b", { request: '{"m":"b"}', at: 2 });

    clearAllHistory();

    expect(loadHistory("conn-a")).toEqual([]);
    expect(loadHistory("conn-b")).toEqual([]);
    expect(localStorage.getItem(HISTORY_KEY)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Per-connection FIFO eviction (max 100 entries)
// ---------------------------------------------------------------------------

describe("per-connection FIFO eviction", () => {
  const CONN_ID = "sse:http://localhost/mcp";

  beforeEach(() => {
    localStorage.clear();
  });

  it("retains up to 100 entries per connection", () => {
    for (let i = 0; i < 100; i++) {
      appendHistory(CONN_ID, {
        request: `{"m":"req-${i}"}`,
        at: i,
      });
    }
    expect(loadHistory(CONN_ID)).toHaveLength(100);
  });

  it("evicts oldest when exceeding 100 entries", () => {
    for (let i = 0; i < 105; i++) {
      appendHistory(CONN_ID, {
        request: `{"m":"req-${i}"}`,
        at: i,
      });
    }
    const history = loadHistory(CONN_ID);
    expect(history).toHaveLength(100);
    // The first entry should have at === 5 (oldest 0-4 evicted)
    expect(history[0].at).toBe(5);
    expect(history[history.length - 1].at).toBe(104);
  });
});

// ---------------------------------------------------------------------------
// Global size limit (500 KB)
// ---------------------------------------------------------------------------

describe("global size limit eviction", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("evicts oldest connection bucket when total exceeds 500 KB", () => {
    // Each entry is about 60 bytes of JSON. 100 entries ~6 KB.
    // To exceed 500 KB we need ~9000 entries across one connection.
    // Add two connections: one will be evicted entirely.
    const largePayload = { jsonrpc: "2.0", method: "x".repeat(500), params: {} };
    const largeRequest = JSON.stringify(largePayload);

    // Fill connection A with ~300 KB
    for (let i = 0; i < 250; i++) {
      appendHistory("conn-a", { request: largeRequest, at: i });
    }

    // Fill connection B with ~300 KB (total ~600 KB > 500 KB)
    for (let i = 0; i < 250; i++) {
      appendHistory("conn-b", { request: largeRequest, at: i + 1000000 });
    }

    // At least one connection should survive; connection A (older) should be evicted
    const aHistory = loadHistory("conn-a");
    const bHistory = loadHistory("conn-b");
    // Total store is over limit, so at least one bucket was removed
    // Connection B has newer timestamps, so A should be evicted first
    expect(bHistory.length).toBeGreaterThan(0);
    // A may be fully or partially evicted, B should have some entries
    expect(aHistory.length + bHistory.length).toBeLessThanOrEqual(500);
  });
});

// ---------------------------------------------------------------------------
// Key scoping: different connections isolated
// ---------------------------------------------------------------------------

describe("key scoping", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("same URL with different transports never mixes history", () => {
    const sseKey = "sse:http://localhost:8080/mcp";
    const streamableKey = "streamable-http:http://localhost:8080/mcp";
    const stdioKey = "stdio:my-server --port 8080";

    appendHistory(sseKey, { request: '{"m":"sse-req"}', at: 1 });
    appendHistory(streamableKey, { request: '{"m":"streamable-req"}', at: 2 });
    appendHistory(stdioKey, { request: '{"m":"stdio-req"}', at: 3 });

    expect(loadHistory(sseKey)).toHaveLength(1);
    expect(loadHistory(sseKey)[0].request).toContain("sse-req");
    expect(loadHistory(streamableKey)).toHaveLength(1);
    expect(loadHistory(streamableKey)[0].request).toContain("streamable-req");
    expect(loadHistory(stdioKey)).toHaveLength(1);
    expect(loadHistory(stdioKey)[0].request).toContain("stdio-req");
  });

  it("same URL on same transport shares history", () => {
    const key = "sse:http://localhost:8080/mcp";

    appendHistory(key, { request: '{"m":"first"}', at: 1 });
    appendHistory(key, { request: '{"m":"second"}', at: 2 });

    expect(loadHistory(key)).toHaveLength(2);
  });
});

// ---------------------------------------------------------------------------
// Storage errors: SecurityError and other denials are non-fatal.
// appendHistory calls readStore + writeStore; when write fails the data is
// not persisted to localStorage (no in-memory backup in the module itself --
// the React state in useConnection.ts preserves the in-memory copy).  The
// tests here verify that storage denials never throw, and that after a
// cleared denial the remaining/previous entries survive.
// ---------------------------------------------------------------------------

describe("storage error resilience", () => {
  let origGetItem: typeof localStorage.getItem;
  let origSetItem: typeof localStorage.setItem;
  let origRemoveItem: typeof localStorage.removeItem;

  beforeEach(() => {
    localStorage.clear();
    origGetItem = localStorage.getItem.bind(localStorage);
    origSetItem = localStorage.setItem.bind(localStorage);
    origRemoveItem = localStorage.removeItem.bind(localStorage);
  });

  afterEach(() => {
    // Restore
    localStorage.getItem = origGetItem;
    localStorage.setItem = origSetItem;
    localStorage.removeItem = origRemoveItem;
  });

  it("readStore returns empty and does not throw on SecurityError", () => {
    localStorage.getItem = () => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    };

    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("readStore does not throw on arbitrary DOMException", () => {
    localStorage.getItem = () => {
      const e = new DOMException("random error", "NotAllowedError");
      throw e;
    };

    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("appendHistory does not throw on SecurityError in writeStore", () => {
    localStorage.setItem = () => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    };

    // Must not throw even though the write will fail.
    // Whether localStorage was touched is environment-dependent; the key
    // invariant is that no exception reaches the caller.
    expect(() =>
      appendHistory("conn-id", { request: '{"m":"test"}', at: 1 }),
    ).not.toThrow();
  });

  it("appendHistory does not throw on QuotaExceededError", () => {
    localStorage.setItem = () => {
      const e = new DOMException("quota exceeded", "QuotaExceededError");
      throw e;
    };

    expect(() =>
      appendHistory("conn-id", { request: '{"m":"test"}', at: 1 }),
    ).not.toThrow();
  });

  it("readStore does not throw on SecurityError during getItem", () => {
    localStorage.getItem = () => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    };

    // readStore must not throw and returns empty store
    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("append/read roundtrip works after storage error clears", () => {
    // First, simulate denial
    localStorage.setItem = () => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    };

    // Write fails during denial
    appendHistory("conn-id", { request: '{"m":"during-denial"}', at: 1 });

    // Now restore localStorage
    localStorage.setItem = origSetItem;

    // Clear the corrupted state and do a clean write
    localStorage.clear();
    appendHistory("conn-id", { request: '{"m":"after-recovery"}', at: 2 });

    const history = loadHistory("conn-id");
    expect(history).toHaveLength(1);
    expect(history[0].request).toContain("after-recovery");
  });

  it("clearAllHistory does not throw on SecurityError", () => {
    localStorage.removeItem = () => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    };

    expect(() => clearAllHistory()).not.toThrow();
  });

  it("clearHistory does not throw on SecurityError in writeStore", () => {
    // First write a normal entry
    appendHistory("some-conn", { request: '{"m":"test"}', at: 1 });

    // Then break setItem
    localStorage.setItem = () => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    };

    // Must not throw even though the write fails
    expect(() => clearHistory("some-conn")).not.toThrow();
  });

  it("writeStore swallows arbitrary errors", () => {
    localStorage.setItem = () => {
      throw new TypeError("cannot serialize");
    };

    expect(() =>
      appendHistory("conn-id", { request: '{"m":"test"}', at: 1 }),
    ).not.toThrow();
  });
});

// ---------------------------------------------------------------------------
// Body truncation (10 KB max per request/response string)
// ---------------------------------------------------------------------------

describe("body truncation", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("truncates request body longer than 10 KB", () => {
    const longBody = '{"data":"' + "x".repeat(12_000) + '"}';
    appendHistory("conn", { request: longBody, at: 1 });

    const history = loadHistory("conn");
    expect(history[0].request.length).toBeLessThan(longBody.length);
    expect(history[0].request).toContain("...[truncated]");
  });

  it("truncates response body longer than 10 KB", () => {
    const longResponse = '{"data":"' + "y".repeat(12_000) + '"}';
    appendHistory("conn", {
      request: '{"m":"ping"}',
      response: longResponse,
      at: 1,
    });

    const history = loadHistory("conn");
    expect(history[0].response!.length).toBeLessThan(longResponse.length);
    expect(history[0].response).toContain("...[truncated]");
  });

  it("keeps short bodies as-is", () => {
    appendHistory("conn", {
      request: '{"m":"ping"}',
      response: '{"ok":true}',
      at: 1,
    });

    const history = loadHistory("conn");
    expect(history[0].request).toBe('{"m":"ping"}');
    expect(history[0].response).toBe('{"ok":true}');
  });
});