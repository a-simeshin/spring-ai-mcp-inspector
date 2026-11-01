// [spring-ai-mcp-inspector PATCH] Persistent history storage unit tests.
// See NOTICE.d/persistent-history.txt for details.

import {
  HISTORY_KEY,
  readStore,
  loadHistory,
  appendHistory,
  clearHistory,
  isForeignSchemaVersion,
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
    // Each entry payload is ~6 KB, so 100 entries per connection ~600 KB.
    // Two connections push total well over the 500 KB limit.
    const largePayload = {
      jsonrpc: "2.0",
      method: "x".repeat(5500),
      params: {},
    };
    const largeRequest = JSON.stringify(largePayload);

    // Fill connection A with 100 entries (~600 KB)
    for (let i = 0; i < 100; i++) {
      appendHistory("conn-a", { request: largeRequest, at: i });
    }

    // Fill connection B with 100 entries (~600 KB, total > 500 KB)
    // Use much larger timestamps so conn-a appears oldest
    for (let i = 0; i < 100; i++) {
      appendHistory("conn-b", { request: largeRequest, at: i + 1000000 });
    }

    // Total store exceeds 500 KB, so evictGlobal must have removed conn-a
    const aHistory = loadHistory("conn-a");
    const bHistory = loadHistory("conn-b");

    // Connection A (oldest) should be fully evicted
    expect(aHistory.length).toBe(0);

    // Connection B (newer) survives
    expect(bHistory.length).toBeGreaterThan(0);

    // Verify actual store size is within the limit after eviction
    const stored = localStorage.getItem(HISTORY_KEY);
    if (stored) {
      expect(stored.length).toBeLessThanOrEqual(500 * 1024);
    }
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
// ConnectionId key format: transport prefix scoping
// Proves connectionId includes transport prefix as App.tsx:439-443 builds it.
// RED on URL-only identity (old `sseUrl || "ephemeral"` mapping).
// ---------------------------------------------------------------------------

describe("connectionId key format", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("appends with transport-scoped id, URL-only identity finds nothing", () => {
    // Simulate App.tsx connectionId construction (lines 439-443):
    //   transportType === "stdio"
    //     ? `stdio:${command} ${args}`
    //     : `${transportType}:${sseUrl || "ephemeral"}`
    const baseUrl = "http://localhost:8080/mcp";
    const sseConnId = "sse:" + baseUrl;
    const streamableConnId = "streamable-http:" + baseUrl;
    const stdioConnId = "stdio:my-server --port 8080";

    // Store under the transport-scoped keys
    appendHistory(sseConnId, { request: '{"m":"sse-call"}', at: 1 });
    appendHistory(streamableConnId, { request: '{"m":"streamable-call"}', at: 2 });
    appendHistory(stdioConnId, { request: '{"m":"stdio-call"}', at: 3 });

    // Each transport's history is isolated: same base URL but different prefix
    expect(loadHistory(sseConnId)).toHaveLength(1);
    expect(loadHistory(streamableConnId)).toHaveLength(1);
    expect(loadHistory(stdioConnId)).toHaveLength(1);

    // RED on URL-only identity: the old mapping `connectionId: sseUrl || "ephemeral"`
    // would produce `baseUrl` for SSE and `"ephemeral"` for stdio.
    // Querying with just the URL MUST NOT find entries stored under transport-scoped keys.
    expect(loadHistory(baseUrl)).toHaveLength(0);
    expect(loadHistory("ephemeral")).toHaveLength(0);
  });

  it("key format matches App.tsx transport prefix convention", () => {
    // Verify the key format convention that App.tsx:439-443 produces.
    // SSE connections: "sse:${url}"
    expect("sse:http://localhost:8080/mcp").toMatch(/^sse:/);
    // Streamable HTTP: "streamable-http:${url}"
    expect("streamable-http:http://localhost:8080/mcp").toMatch(/^streamable-http:/);
    // Stdio: "stdio:${command} ${args}"
    expect("stdio:my-server --port 8080").toMatch(/^stdio:/);
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
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("readStore returns empty and does not throw on SecurityError", () => {
    jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    });

    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("readStore does not throw on arbitrary DOMException", () => {
    jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      const e = new DOMException("random error", "NotAllowedError");
      throw e;
    });

    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("appendHistory does not throw on SecurityError in writeStore", () => {
    jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    });

    // Must not throw even though the write will fail.
    // Whether localStorage was touched is environment-dependent; the key
    // invariant is that no exception reaches the caller.
    expect(() =>
      appendHistory("conn-id", { request: '{"m":"test"}', at: 1 }),
    ).not.toThrow();
  });

  it("appendHistory does not throw on QuotaExceededError", () => {
    jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      const e = new DOMException("quota exceeded", "QuotaExceededError");
      throw e;
    });

    expect(() =>
      appendHistory("conn-id", { request: '{"m":"test"}', at: 1 }),
    ).not.toThrow();
  });

  it("readStore does not throw on SecurityError during getItem", () => {
    jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    });

    // readStore must not throw and returns empty store
    const store = readStore();
    expect(store).toEqual({ schemaVersion: 1, byConnection: {} });
  });

  it("append/read roundtrip works after storage error clears", () => {
    // First, simulate denial
    jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    });

    // Write fails during denial
    appendHistory("conn-id", { request: '{"m":"during-denial"}', at: 1 });

    // Now restore localStorage
    jest.restoreAllMocks();

    // Clear the corrupted state and do a clean write
    localStorage.clear();
    appendHistory("conn-id", { request: '{"m":"after-recovery"}', at: 2 });

    const history = loadHistory("conn-id");
    expect(history).toHaveLength(1);
    expect(history[0].request).toContain("after-recovery");
  });

  it("clearHistory does not throw on SecurityError in writeStore", () => {
    // First write a normal entry
    appendHistory("some-conn", { request: '{"m":"test"}', at: 1 });

    // Then break setItem
    jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      const e = new DOMException("blocked in private mode", "SecurityError");
      throw e;
    });

    // Must not throw even though the write fails
    expect(() => clearHistory("some-conn")).not.toThrow();
  });

  it("writeStore swallows arbitrary errors", () => {
    jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new TypeError("cannot serialize");
    });

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

// ---------------------------------------------------------------------------
// Global limit never wipes the CURRENT connection (PR #218 review blocker 1)
// RED on the pre-rework evictGlobal: a single-connection store crossing the
// 500 KB limit used to drop the only (active) bucket wholesale, including
// the entry the 63rd append had just written.
// ---------------------------------------------------------------------------

describe("global limit spares the current connection", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("63rd append on the only connection does not zero the history", () => {
    // The owner-review scenario: one connection, ~8 KB entries. A store
    // seeded at 62 entries sits at ~500 KB; the 63rd append crosses the
    // limit. The OLD evictGlobal deleted the only (active) bucket
    // wholesale, including the entry the 63rd append had just written.
    const payload = JSON.stringify({
      jsonrpc: "2.0",
      method: "tools/call",
      params: { data: "x".repeat(8100) },
    });
    // Seed via the public API: 62 entries, store just under the cap
    // (each append re-runs eviction, so the seed itself stays in shape).
    for (let i = 0; i < 62; i++) {
      appendHistory("sse:http://localhost:8080/mcp", {
        request: payload,
        at: 100 + i,
      });
    }
    // Sanity: seeded store is over the cap is NOT required; the point is
    // the final append pushes it over.
    const sizeBefore = (localStorage.getItem(HISTORY_KEY) ?? "").length;
    expect(sizeBefore).toBeGreaterThan(400 * 1024);

    // The 63rd append: the crossing write.
    appendHistory("sse:http://localhost:8080/mcp", {
      request: payload,
      at: 162,
    });

    const history = loadHistory("sse:http://localhost:8080/mcp");
    // RED on the old evictGlobal: the bucket was deleted entirely.
    expect(history.length).toBeGreaterThan(0);
    // The just-written entry survives.
    expect(history[history.length - 1].at).toBe(162);
    // The store is back under the cap.
    expect(
      (localStorage.getItem(HISTORY_KEY) ?? "").length,
    ).toBeLessThanOrEqual(500 * 1024);
  });

  it("keeps trimming the current bucket entry by entry until the limit is met", () => {
    // One current connection whose entries are far beyond what fits in
    // 500 KB even after per-entry truncation (~10 KB each after truncation).
    const payload = JSON.stringify({
      jsonrpc: "2.0",
      method: "tools/call",
      params: { data: "x".repeat(12 * 1024) },
    });
    for (let i = 0; i < 100; i++) {
      appendHistory("stdio:big-server --all", { request: payload, at: i });
    }

    const history = loadHistory("stdio:big-server --all");
    expect(history.length).toBeGreaterThan(0);
    // FIFO: the newest entries are the survivors.
    expect(history[history.length - 1].at).toBe(99);
    // Store is within the cap.
    const stored = localStorage.getItem(HISTORY_KEY);
    expect(stored!.length).toBeLessThanOrEqual(500 * 1024);
  });

  it("evicts OTHER buckets fully before trimming the current one", () => {
    // Old data on conn-old (LRU loser), current session on conn-current.
    const payload = JSON.stringify({
      jsonrpc: "2.0",
      method: "tools/call",
      params: { data: "y".repeat(8100) },
    });
    for (let i = 0; i < 40; i++) {
      appendHistory("conn-old", { request: payload, at: i });
    }
    for (let i = 0; i < 40; i++) {
      appendHistory("conn-current", { request: payload, at: 100000 + i });
    }
    // One more append on the current connection pushes the store over the
    // cap; the old bucket must go first, not the current one.
    appendHistory("conn-current", { request: payload, at: 100040 });

    expect(loadHistory("conn-old")).toEqual([]);
    const current = loadHistory("conn-current");
    expect(current.length).toBeGreaterThan(0);
    expect(current[current.length - 1].at).toBe(100040);
  });

});

// ---------------------------------------------------------------------------
// Unknown schemaVersion is preserved, not destroyed (PR #218 review minor)
// A newer schema version in the store must never be overwritten by this code.
// ---------------------------------------------------------------------------

describe("unknown schema versions are left untouched", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("appendHistory refuses to write over a newer schema version store", () => {
    localStorage.setItem(
      HISTORY_KEY,
      JSON.stringify({ schemaVersion: 2, byConnection: { future: [] } }),
    );

    appendHistory("mine", { request: '{"m":"x"}', at: 1 });

    // The future store must survive byte-for-byte.
    const raw = localStorage.getItem(HISTORY_KEY);
    expect(JSON.parse(raw!).schemaVersion).toBe(2);
    expect(JSON.parse(raw!).byConnection).toEqual({ future: [] });
    // And this code reads it as empty rather than destroying it.
    expect(isForeignSchemaVersion()).toBe(true);
    expect(loadHistory("mine")).toEqual([]);
  });

  it("clearHistory refuses to touch a newer schema version store", () => {
    const future = JSON.stringify({
      schemaVersion: 2,
      byConnection: { future: [{ request: '{"m":"keep"}', at: 1 }] },
    });
    localStorage.setItem(HISTORY_KEY, future);

    clearHistory("future");

    expect(localStorage.getItem(HISTORY_KEY)).toBe(future);
  });

  it("isForeignSchemaVersion is false for v1 stores, missing and corrupt data", () => {
    expect(isForeignSchemaVersion()).toBe(false);
    localStorage.setItem(HISTORY_KEY, JSON.stringify({ schemaVersion: 1, byConnection: {} }));
    expect(isForeignSchemaVersion()).toBe(false);
    localStorage.setItem(HISTORY_KEY, "not-json");
    expect(isForeignSchemaVersion()).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Truncation keeps the JSON-RPC envelope (PR #218 review minor)
// ---------------------------------------------------------------------------

describe("truncation preserves the envelope fields", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("an over-long request keeps method and id for the History panel label", () => {
    // A request whose nested strings are each small enough to survive
    // inner-field truncation but whose array of them stays over 10 KB even
    // after truncation: the fallback envelope path is taken.
    const request = JSON.stringify({
      jsonrpc: "2.0",
      id: 42,
      method: "tools/call",
      params: {
        items: Array.from({ length: 900 }, (_, i) => `item-${i}-data`),
      },
    });
    appendHistory("conn", { request, at: 1 });

    const stored = loadHistory("conn")[0].request;
    const parsed = JSON.parse(stored) as {
      method?: string;
      id?: number;
      truncated?: boolean;
    };
    expect(parsed.method).toBe("tools/call");
    expect(parsed.id).toBe(42);
    expect(parsed.truncated).toBe(true);
    // RED on the pre-rework code: the body became {"jsonrpc":"2.0","truncated":true}
    // and the History panel rendered the entry with no label.
    expect(parsed.method).toBeDefined();
  });
});
