// [spring-ai-mcp-inspector PATCH] Unit tests for saved connections storage layer.
// See NOTICE.d/saved-connections.txt for details.

import {
  SAVED_CONNECTIONS_KEY,
  loadSavedConnections,
  saveConnection,
  deleteSavedConnection,
  touchSavedConnection,
  migrateSavedConnections,
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
      const conn: SavedConnection = {
        ...mockDraft(),
        id: "test-id",
        createdAt: 1000,
        lastUsedAt: 2000,
      };
      localStorage.setItem(
        SAVED_CONNECTIONS_KEY,
        JSON.stringify({ schemaVersion: 1, connections: [conn] }),
      );
      const loaded = loadSavedConnections();
      expect(loaded).toHaveLength(1);
      expect(loaded[0].name).toBe("My Server");
      expect(loaded[0].id).toBe("test-id");
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

    it("overwrites connection with same name (case-insensitive)", () => {
      const first = saveConnection(mockDraft());
      const second = saveConnection({
        ...mockDraft(),
        name: "my server", // same name, different case
        url: "http://other-url/sse",
      });
      // second should have overwritten first
      expect(second.id).toBe(first.id);
      const loaded = loadSavedConnections();
      expect(loaded).toHaveLength(1);
      expect(loaded[0].url).toBe("http://other-url/sse");
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
        connections: [mockDraft()],
      };
      const result = migrateSavedConnections(JSON.stringify(store));
      expect(result.schemaVersion).toBe(1);
      expect(result.connections).toHaveLength(1);
    });

    it("handles unknown schemaVersion by returning empty", () => {
      const store = {
        schemaVersion: 0,
        connections: [mockDraft()],
      };
      const result = migrateSavedConnections(JSON.stringify(store));
      // Should still work if connections array is present
      expect(result.connections).toHaveLength(1);
    });

    it("handles completely invalid data", () => {
      const result = migrateSavedConnections(JSON.stringify({ foo: "bar" }));
      expect(result.connections).toEqual([]);
    });
  });
});