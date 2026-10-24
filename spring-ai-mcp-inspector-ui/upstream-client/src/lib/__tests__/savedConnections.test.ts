// [spring-ai-mcp-inspector PATCH] Unit tests for saved connections storage layer.
// See NOTICE.d/saved-connections.txt for details.

import {
  SAVED_CONNECTIONS_KEY,
  loadSavedConnections,
  saveConnection,
  deleteSavedConnection,
  touchSavedConnection,
  migrateSavedConnections,
  findConnectionByName,
  isValidConnection,
  filterValidConnections,
  stripSecrets,
} from "../savedConnections";
import type { SavedConnection } from "../types/savedConnection";

const mockHeaders = () => [
  { name: "Authorization", value: "Bearer test-token", enabled: true },
];

const mockDraft = () => ({
  name: "My Server",
  transport: "sse" as const,
  connectionType: "proxy" as const,
  url: "http://localhost:8080/sse",
  customHeaders: mockHeaders(),
});

const mockStdioDraft = () => ({
  name: "Local Server",
  transport: "stdio" as const,
  connectionType: "direct" as const,
  command: "node",
  args: "server.js",
  env: { FOO: "bar" },
  customHeaders: [],
});

const mockConnection = (overrides?: Partial<SavedConnection>): SavedConnection => ({
  ...mockDraft(),
  id: "test-id",
  createdAt: 1000,
  lastUsedAt: 2000,
  ...overrides,
});

describe("savedConnections", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe("loadSavedConnections", () => {
    it("returns empty array when no data stored", () => {
      expect(loadSavedConnections()).toEqual([]);
    });

    it("returns empty array on corrupt JSON", () => {
      localStorage.setItem(SAVED_CONNECTIONS_KEY, "not-json");
      const consoleWarn = jest.spyOn(console, "warn").mockImplementation();
      expect(loadSavedConnections()).toEqual([]);
      expect(consoleWarn).toHaveBeenCalled();
      consoleWarn.mockRestore();
    });

    it("loads stored connections", () => {
      const conn = mockConnection();
      localStorage.setItem(
        SAVED_CONNECTIONS_KEY,
        JSON.stringify({ schemaVersion: 1, connections: [conn] }),
      );
      const loaded = loadSavedConnections();
      expect(loaded).toHaveLength(1);
      expect(loaded[0].name).toBe("My Server");
      expect(loaded[0].id).toBe("test-id");
    });

    it("filters out null entries from stored data", () => {
      const conn = mockConnection();
      localStorage.setItem(
        SAVED_CONNECTIONS_KEY,
        JSON.stringify({ schemaVersion: 1, connections: [null, conn] }),
      );
      const consoleWarn = jest.spyOn(console, "warn").mockImplementation();
      const loaded = loadSavedConnections();
      expect(loaded).toHaveLength(1);
      expect(loaded[0].id).toBe("test-id");
      expect(consoleWarn).toHaveBeenCalled();
      consoleWarn.mockRestore();
    });
  });

  describe("saveConnection", () => {
    it("creates a new connection with generated id", () => {
      const saved = saveConnection(mockDraft());
      expect(saved.id).toBeTruthy();
      expect(saved.name).toBe("My Server");
      expect(saved.createdAt).toBeGreaterThan(0);
      expect(saved.lastUsedAt).toBeGreaterThan(0);
    });

    it("persists the connection to localStorage", () => {
      saveConnection(mockDraft());
      const loaded = loadSavedConnections();
      expect(loaded).toHaveLength(1);
      expect(loaded[0].name).toBe("My Server");
    });

    it("updates an existing connection by id", () => {
      const saved = saveConnection(mockDraft());
      const updated = saveConnection(
        { ...mockDraft(), name: "Renamed Server" },
        saved.id,
      );
      expect(updated.id).toBe(saved.id);
      expect(updated.name).toBe("Renamed Server");
      const loaded = loadSavedConnections();
      expect(loaded).toHaveLength(1);
      expect(loaded[0].name).toBe("Renamed Server");
    });

    it("creates separate entry for duplicate name (no longer silently overwrites)", () => {
      const first = saveConnection(mockDraft());
      const second = saveConnection({
        ...mockDraft(),
        name: "my server", // same name, different case
        url: "http://other-url/sse",
      });
      // second should be a NEW entry with a different id
      expect(second.id).not.toBe(first.id);
      const loaded = loadSavedConnections();
      expect(loaded).toHaveLength(2);
    });

    it("saves stdio connections with env", () => {
      const saved = saveConnection(mockStdioDraft());
      expect(saved.transport).toBe("stdio");
      expect(saved.command).toBe("node");
      expect(saved.env).toEqual({ FOO: "bar" });
    });

    it("evicts oldest when over cap of 20", () => {
      // Save 20 connections
      const savedIds: string[] = [];
      for (let i = 0; i < 20; i++) {
        const saved = saveConnection({
          ...mockDraft(),
          name: `Server ${i}`,
          url: `http://server-${i}/sse`,
        });
        savedIds.push(saved.id);
      }

      // Save a 21st connection
      const last = saveConnection({
        ...mockDraft(),
        name: "Newest Server",
        url: "http://newest/sse",
      });

      const loaded = loadSavedConnections();
      expect(loaded).toHaveLength(20);
      // The 21st should be in the list
      expect(loaded.some((c) => c.id === last.id)).toBeTruthy();
    });
  });

  describe("findConnectionByName", () => {
    it("finds a connection by name (case-insensitive)", () => {
      saveConnection(mockDraft());
      const found = findConnectionByName("my server");
      expect(found).toBeDefined();
      expect(found!.name).toBe("My Server");
    });

    it("returns undefined for non-existent name", () => {
      saveConnection(mockDraft());
      expect(findConnectionByName("nope")).toBeUndefined();
    });

    it("returns undefined when no connections exist", () => {
      expect(findConnectionByName("anything")).toBeUndefined();
    });
  });

  describe("isValidConnection", () => {
    it("returns true for a valid connection object", () => {
      expect(isValidConnection(mockConnection())).toBe(true);
    });

    it("returns false for null", () => {
      expect(isValidConnection(null)).toBe(false);
    });

    it("returns false for undefined", () => {
      expect(isValidConnection(undefined)).toBe(false);
    });

    it("returns false for a plain object missing fields", () => {
      expect(isValidConnection({})).toBe(false);
    });

    it("returns false when id is missing", () => {
      const rest = { ...mockConnection() };
      delete (rest as Record<string, unknown>).id;
      expect(isValidConnection(rest)).toBe(false);
    });

    it("returns false when id is empty string", () => {
      expect(isValidConnection(mockConnection({ id: "" }))).toBe(false);
    });

    it("returns false when name is empty string", () => {
      expect(isValidConnection(mockConnection({ name: "" }))).toBe(false);
    });

    it("returns false for invalid transport type", () => {
      expect(
        isValidConnection(
          mockConnection({ transport: "invalid" as "sse" }),
        ),
      ).toBe(false);
    });

    it("returns false when customHeaders is not an array", () => {
      expect(
        isValidConnection(
          mockConnection({ customHeaders: undefined as unknown as [] }),
        ),
      ).toBe(false);
    });

    it("returns false when customHeaders contains null", () => {
      expect(
        isValidConnection(mockConnection({ customHeaders: [null as unknown as { name: string; value: string; enabled: boolean }] })),
      ).toBe(false);
    });

    it("returns false when customHeaders element has non-string name", () => {
      expect(
        isValidConnection(mockConnection({ customHeaders: [{ name: null, value: "v", enabled: true }] as unknown as { name: string; value: string; enabled: boolean }[] })),
      ).toBe(false);
    });

    it("returns false when customHeaders element has non-boolean enabled", () => {
      expect(
        isValidConnection(mockConnection({ customHeaders: [{ name: "X", value: "v", enabled: "yes" }] as unknown as { name: string; value: string; enabled: boolean }[] })),
      ).toBe(false);
    });

    it("returns false when env has non-string value", () => {
      expect(
        isValidConnection(mockConnection({ env: { TOKEN: null } as unknown as Record<string, string> })),
      ).toBe(false);
    });

    it("returns false when env is not an object", () => {
      expect(
        isValidConnection(mockConnection({ env: "not-an-object" as unknown as Record<string, string> })),
      ).toBe(false);
    });

    it("returns false when url is not a string", () => {
      expect(
        isValidConnection(mockConnection({ url: 123 as unknown as string })),
      ).toBe(false);
    });

    it("returns false when command is not a string", () => {
      expect(
        isValidConnection(mockConnection({ command: 123 as unknown as string })),
      ).toBe(false);
    });

    it("returns true when optional fields are absent", () => {
      expect(
        isValidConnection(
          mockConnection({ url: undefined, command: undefined, args: undefined, env: undefined }),
        ),
      ).toBe(true);
    });
  });

  describe("filterValidConnections", () => {
    it("filters out null entries", () => {
      const conn = mockConnection();
      const result = filterValidConnections([null, conn, undefined]);
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("test-id");
    });

    it("returns empty array when all entries are invalid", () => {
      expect(filterValidConnections([null, undefined, {}])).toEqual([]);
    });

    it("returns all entries when all are valid", () => {
      const conns = [mockConnection({ id: "a" }), mockConnection({ id: "b" })];
      expect(filterValidConnections(conns)).toHaveLength(2);
    });
  });

  describe("deleteSavedConnection", () => {
    it("removes a connection by id", () => {
      const saved = saveConnection(mockDraft());
      expect(loadSavedConnections()).toHaveLength(1);
      deleteSavedConnection(saved.id);
      expect(loadSavedConnections()).toHaveLength(0);
    });

    it("does nothing if id not found", () => {
      saveConnection(mockDraft());
      deleteSavedConnection("nonexistent-id");
      expect(loadSavedConnections()).toHaveLength(1);
    });
  });

  describe("touchSavedConnection", () => {
    it("updates lastUsedAt", () => {
      const saved = saveConnection(mockDraft());
      const originalLastUsed = saved.lastUsedAt;

      // use Date.now mock
      const realNow = Date.now;
      Date.now = jest.fn(() => originalLastUsed + 5000);

      touchSavedConnection(saved.id);
      const loaded = loadSavedConnections();
      expect(loaded[0].lastUsedAt).toBe(originalLastUsed + 5000);

      Date.now = realNow;
    });

    it("does nothing if id not found", () => {
      // Should not throw
      touchSavedConnection("nonexistent-id");
    });
  });

  describe("migrateSavedConnections", () => {
    it("returns empty store for null input", () => {
      const result = migrateSavedConnections(null);
      expect(result.schemaVersion).toBe(1);
      expect(result.connections).toEqual([]);
    });

    it("parses v1 store correctly", () => {
      const store = {
        schemaVersion: 1,
        connections: [mockConnection()],
      };
      const result = migrateSavedConnections(JSON.stringify(store));
      expect(result.schemaVersion).toBe(1);
      expect(result.connections).toHaveLength(1);
    });

    it("handles unknown schemaVersion by returning valid connections", () => {
      const store = {
        schemaVersion: 0,
        connections: [mockConnection()],
      };
      const result = migrateSavedConnections(JSON.stringify(store));
      // Should still work if connections array is present
      expect(result.connections).toHaveLength(1);
    });

    it("handles completely invalid data", () => {
      const result = migrateSavedConnections(JSON.stringify({ foo: "bar" }));
      expect(result.connections).toEqual([]);
    });

    it("filters out null entries during migration", () => {
      const conn = mockConnection();
      const store = {
        schemaVersion: 1,
        connections: [null, conn],
      };
      const consoleWarn = jest.spyOn(console, "warn").mockImplementation();
      const result = migrateSavedConnections(JSON.stringify(store));
      expect(result.connections).toHaveLength(1);
      expect(result.connections[0].id).toBe("test-id");
      expect(consoleWarn).toHaveBeenCalled();
      consoleWarn.mockRestore();
    });
  });

  describe("stripSecrets", () => {
    it("clears all header values, keeping names and enabled state", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "X-Custom", value: "ok", enabled: true },
          { name: "Authorization", value: "Bearer secret", enabled: true },
        ],
      };
      const result = stripSecrets(draft);
      expect(result.customHeaders).toHaveLength(2);
      expect(result.customHeaders[0].name).toBe("X-Custom");
      expect(result.customHeaders[0].value).toBe("");
      expect(result.customHeaders[0].enabled).toBe(true);
      expect(result.customHeaders[1].name).toBe("Authorization");
      expect(result.customHeaders[1].value).toBe("");
    });

    it("clears X-API-Key header value", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "X-Custom", value: "ok", enabled: true },
          { name: "X-API-Key", value: "secret-key-123", enabled: true },
        ],
      };
      const result = stripSecrets(draft);
      expect(result.customHeaders).toHaveLength(2);
      expect(result.customHeaders[0].name).toBe("X-Custom");
      expect(result.customHeaders[0].value).toBe("");
      expect(result.customHeaders[1].name).toBe("X-API-Key");
      expect(result.customHeaders[1].value).toBe("");
    });

    it("clears Proxy-Authorization header value", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "X-Custom", value: "ok", enabled: true },
          { name: "Proxy-Authorization", value: "Basic creds", enabled: true },
        ],
      };
      const result = stripSecrets(draft);
      expect(result.customHeaders).toHaveLength(2);
      expect(result.customHeaders[0].value).toBe("");
      expect(result.customHeaders[1].value).toBe("");
    });

    it("clears whitespace-padded Authorization header value", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "X-Custom", value: "ok", enabled: true },
          { name: " Authorization ", value: "Bearer secret", enabled: true },
        ],
      };
      const result = stripSecrets(draft);
      expect(result.customHeaders).toHaveLength(2);
      expect(result.customHeaders[0].value).toBe("");
      expect(result.customHeaders[1].value).toBe("");
      expect(result.customHeaders[1].name).toBe(" Authorization ");
    });

    it("clears whitespace-padded X-API-Key header value", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "X-Custom", value: "ok", enabled: true },
          { name: " X-API-Key ", value: "secret-key", enabled: true },
        ],
      };
      const result = stripSecrets(draft);
      expect(result.customHeaders).toHaveLength(2);
      expect(result.customHeaders[0].value).toBe("");
      expect(result.customHeaders[1].value).toBe("");
    });

    it("clears all header values in one draft", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "X-Custom", value: "ok", enabled: true },
          { name: "Authorization", value: "Bearer tok", enabled: true },
          { name: "X-API-Key", value: "key-123", enabled: true },
          { name: "Proxy-Authorization", value: "Basic creds", enabled: true },
        ],
      };
      const result = stripSecrets(draft);
      expect(result.customHeaders).toHaveLength(4);
      result.customHeaders.forEach((h) => {
        expect(h.value).toBe("");
      });
    });

    it("clears arbitrary header values like X-Token and Cookie", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "X-Token", value: "dummy-token", enabled: true },
          { name: "Cookie", value: "dummy-cookie", enabled: true },
          { name: "X-Session-Id", value: "sess-123", enabled: true },
        ],
      };
      const result = stripSecrets(draft);
      expect(result.customHeaders).toHaveLength(3);
      result.customHeaders.forEach((h) => {
        expect(h.value).toBe("");
      });
      expect(result.customHeaders[0].name).toBe("X-Token");
      expect(result.customHeaders[1].name).toBe("Cookie");
      expect(result.customHeaders[2].name).toBe("X-Session-Id");
    });

    it("preserves all headers with their names and enabled state", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "X-Custom", value: "ok", enabled: true },
          { name: "X-Request-Id", value: "req-123", enabled: false },
        ],
      };
      const result = stripSecrets(draft);
      expect(result.customHeaders).toHaveLength(2);
      expect(result.customHeaders[0].name).toBe("X-Custom");
      expect(result.customHeaders[0].value).toBe("");
      expect(result.customHeaders[0].enabled).toBe(true);
      expect(result.customHeaders[1].name).toBe("X-Request-Id");
      expect(result.customHeaders[1].value).toBe("");
      expect(result.customHeaders[1].enabled).toBe(false);
    });

    it("clears env values while preserving keys", () => {
      const draft = {
        ...mockDraft(),
        env: { API_KEY: "secret-value", FOO: "bar" },
      };
      const result = stripSecrets(draft);
      expect(result.env).toEqual({ API_KEY: "", FOO: "" });
    });

    it("handles draft without env", () => {
      const draft = { ...mockDraft() };
      delete (draft as Record<string, unknown>).env;
      const result = stripSecrets(draft);
      expect(result.env).toBeUndefined();
    });

    it("handles draft without customHeaders", () => {
      const draft = { ...mockDraft() };
      (draft as Record<string, unknown>).customHeaders = undefined;
      const result = stripSecrets(draft);
      expect(result.customHeaders).toBeUndefined();
    });

    it("does not mutate the original draft", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "Authorization", value: "Bearer secret", enabled: true },
        ],
        env: { KEY: "val" },
      };
      const originalHeaders = draft.customHeaders;
      const originalEnv = { ...draft.env };
      stripSecrets(draft);
      expect(draft.customHeaders).toBe(originalHeaders);
      expect(draft.customHeaders).toHaveLength(1);
      expect(draft.env).toEqual(originalEnv);
    });

    it("verifies actual localStorage JSON contains no secret header values after save", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "Authorization", value: "Bearer live-token", enabled: true },
          { name: "X-API-Key", value: "live-api-key", enabled: true },
          { name: "Proxy-Authorization", value: "Basic creds", enabled: true },
          { name: "X-Custom", value: "safe-value", enabled: true },
        ],
      };
      const stripped = stripSecrets(draft);
      saveConnection(stripped);
      const storedJson = localStorage.getItem(SAVED_CONNECTIONS_KEY)!;
      const parsed = JSON.parse(storedJson) as {
        schemaVersion: number;
        connections: Array<{
          customHeaders: Array<{ name: string; value: string }>;
        }>;
      };
      // All headers are preserved, but all values are empty
      const headers = parsed.connections[0].customHeaders;
      expect(headers).toHaveLength(4);
      headers.forEach((h) => {
        expect(h.value).toBe("");
      });
      // The raw JSON string contains none of the original secret values
      expect(storedJson).not.toContain("live-token");
      expect(storedJson).not.toContain("live-api-key");
      expect(storedJson).not.toContain("Basic creds");
      expect(storedJson).not.toContain("safe-value");
    });

    it("verifies persisted JSON contains no secrets for X-API-Key, Proxy-Authorization, whitespace-padded Authorization, and X-Token", () => {
      // This test verifies the raw localStorage JSON after persistence,
      // covering header cases that stripSecrets result alone cannot prove:
      // 1. X-API-Key  (known secret header name)
      // 2. Proxy-Authorization (known secret header name)
      // 3. Authorization with whitespace padding around the name
      //    (server may trim or not - stripSecrets must clear regardless)
      // 4. X-Token (arbitrary header name that could carry a secret)
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "Authorization", value: "Bearer live-token", enabled: true },
          { name: " X-API-Key ", value: "leaked-api-key", enabled: true },
          { name: "Proxy-Authorization", value: "Basic creds", enabled: true },
          { name: " Authorization ", value: "padded-bearer", enabled: true },
          { name: "X-Token", value: "arbitrary-token", enabled: true },
        ],
      };
      const stripped = stripSecrets(draft);
      saveConnection(stripped);
      const storedJson = localStorage.getItem(SAVED_CONNECTIONS_KEY)!;
      const parsed = JSON.parse(storedJson) as {
        schemaVersion: number;
        connections: Array<{
          customHeaders: Array<{ name: string; value: string }>;
        }>;
      };
      // All headers are preserved, but all values are empty
      const headers = parsed.connections[0].customHeaders;
      expect(headers).toHaveLength(5);
      headers.forEach((h) => {
        expect(h.value).toBe("");
      });
      // The raw JSON string contains none of the original secret values
      expect(storedJson).not.toContain("live-token");
      expect(storedJson).not.toContain("leaked-api-key");
      expect(storedJson).not.toContain("Basic creds");
      expect(storedJson).not.toContain("padded-bearer");
      expect(storedJson).not.toContain("arbitrary-token");
      // But header names ARE preserved (including whitespace-padded ones)
      expect(storedJson).toContain("Authorization");
      expect(storedJson).toContain(" X-API-Key ");
      expect(storedJson).toContain("Proxy-Authorization");
      expect(storedJson).toContain(" Authorization ");
      expect(storedJson).toContain("X-Token");
      // Verify the original values are not present even in the draft
      // (the save path stripsSecrets before saveConnection, so the
      // raw JSON should never contain the original values)
      expect(storedJson).not.toContain("Bearer live-token");
    });

    it("verifies restore does not return secret values", () => {
      const draft = {
        ...mockDraft(),
        customHeaders: [
          { name: "Authorization", value: "Bearer tok", enabled: true },
          { name: "X-API-Key", value: "key-abc", enabled: true },
          { name: "X-Custom", value: "visible", enabled: true },
        ],
      };
      const stripped = stripSecrets(draft);
      saveConnection(stripped);
      const loaded = loadSavedConnections();
      expect(loaded).toHaveLength(1);
      const conn = loaded[0];
      const headerNames = conn.customHeaders.map((h) => h.name);
      // All headers preserved (names not removed)
      expect(headerNames).toContain("Authorization");
      expect(headerNames).toContain("X-API-Key");
      expect(headerNames).toContain("X-Custom");
      const headerValues = conn.customHeaders.map((h) => h.value);
      // All values are empty after restore
      expect(headerValues).not.toContain("Bearer tok");
      expect(headerValues).not.toContain("key-abc");
      expect(headerValues).not.toContain("visible");
      expect(headerValues.every((v) => v === "")).toBe(true);
    });
  });
});
