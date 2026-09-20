// [spring-ai-mcp-inspector PATCH] useKeepAlive test: keep-alive hook coverage (#235).
import { renderHook, act, waitFor } from "@testing-library/react";
import { useKeepAlive } from "../useKeepAlive";

// Mock EventSource
class MockEventSource {
  url: string;
  onerror: ((event: Event) => void) | null = null;
  private listeners: Map<string, ((event: MessageEvent) => void)[]> = new Map();

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, []);
    }
    this.listeners.get(type)!.push(listener);
  }

  close() {
    MockEventSource.closed.push(this.url);
  }

  simulateEvent(type: string, data: string) {
    const listeners = this.listeners.get(type) || [];
    const event = { data } as MessageEvent;
    listeners.forEach((listener) => listener(event));
  }

  static instances: MockEventSource[] = [];
  static closed: string[] = [];
  static reset() {
    MockEventSource.instances = [];
    MockEventSource.closed = [];
  }
}

// Mock fetch
const mockFetch = jest.fn();
global.fetch = mockFetch;

// Mock sessionStorage
const mockSessionStorage: Record<string, string> = {};
Object.defineProperty(window, "sessionStorage", {
  value: {
    getItem: (key: string) => mockSessionStorage[key] || null,
    setItem: (key: string, value: string) => {
      mockSessionStorage[key] = value;
    },
    removeItem: (key: string) => {
      delete mockSessionStorage[key];
    },
    clear: () => {
      Object.keys(mockSessionStorage).forEach((key) => delete mockSessionStorage[key]);
    },
  },
  writable: true,
});

// Mock window.__MCP_INSPECTOR_BOOTSTRAP
const originalBootstrap = (window as unknown as Record<string, unknown>)[
  "__MCP_INSPECTOR_BOOTSTRAP"
];

describe("useKeepAlive", () => {
  beforeEach(() => {
    MockEventSource.reset();
    mockFetch.mockReset();
    Object.keys(mockSessionStorage).forEach((key) => delete mockSessionStorage[key]);
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = undefined;
    // Mock EventSource globally
    (global as unknown as { EventSource: typeof MockEventSource }).EventSource =
      MockEventSource;
  });

  afterEach(() => {
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = originalBootstrap;
  });

  it("returns empty state when sessionId is null", () => {
    const { result } = renderHook(() => useKeepAlive(null));
    expect(result.current.observed).toBe(false);
    expect(result.current.recentPings).toEqual([]);
    expect(result.current.lastPingAt).toBeNull();
  });

  it("fetches initial snapshot on mount", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        observed: true,
        lastPingAt: "2026-09-20T12:00:00Z",
        recentPings: ["2026-09-20T12:00:00Z"],
        estimatedIntervalMillis: 10000,
        isStale: false,
      }),
    });

    const { result } = renderHook(() => useKeepAlive("test-session"));

    await waitFor(() => {
      expect(result.current.observed).toBe(true);
    });

    expect(mockFetch).toHaveBeenCalledWith(
      "/mcp-inspector/api/keepalive?sessionId=test-session",
      expect.objectContaining({ headers: {} }),
    );
    expect(result.current.recentPings).toEqual(["2026-09-20T12:00:00Z"]);
  });

  it("subscribes to SSE events", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ observed: false }),
    });

    renderHook(() => useKeepAlive("test-session"));

    await waitFor(() => {
      expect(MockEventSource.instances).toHaveLength(1);
    });

    const eventSource = MockEventSource.instances[0];
    expect(eventSource.url).toBe(
      "/mcp-inspector/api/events?sessionId=test-session",
    );
  });

  it("adds ping on SSE event", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ observed: false }),
    });

    const { result } = renderHook(() => useKeepAlive("test-session"));

    await waitFor(() => {
      expect(MockEventSource.instances).toHaveLength(1);
    });

    const eventSource = MockEventSource.instances[0];

    act(() => {
      eventSource.simulateEvent("mcp:keepalive-ping", "2026-09-20T12:00:01Z");
    });

    expect(result.current.recentPings).toContain("2026-09-20T12:00:01Z");
    expect(result.current.lastPingAt).toBe("2026-09-20T12:00:01Z");
    expect(result.current.observed).toBe(true);
  });

  it("limits recentPings to MAX_KEEPALIVE_PINGS", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ observed: false }),
    });

    const { result } = renderHook(() => useKeepAlive("test-session"));

    await waitFor(() => {
      expect(MockEventSource.instances).toHaveLength(1);
    });

    const eventSource = MockEventSource.instances[0];

    // Add 12 pings
    for (let i = 0; i < 12; i++) {
      act(() => {
        eventSource.simulateEvent(
          "mcp:keepalive-ping",
          `2026-09-20T12:00:${String(i).padStart(2, "0")}Z`,
        );
      });
    }

    expect(result.current.recentPings).toHaveLength(10);
    expect(result.current.recentPings[0]).toBe("2026-09-20T12:00:02Z");
    expect(result.current.recentPings[9]).toBe("2026-09-20T12:00:11Z");
  });

  it("cleans up on unmount", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ observed: false }),
    });

    const { unmount } = renderHook(() => useKeepAlive("test-session"));

    await waitFor(() => {
      expect(MockEventSource.instances).toHaveLength(1);
    });

    unmount();

    expect(MockEventSource.closed).toContain(
      "/mcp-inspector/api/events?sessionId=test-session",
    );
  });

  it("uses custom inspector path from bootstrap", async () => {
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = { inspectorPath: "/custom/inspector" };

    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ observed: false }),
    });

    renderHook(() => useKeepAlive("test-session"));

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalledWith(
        "/custom/inspector/api/keepalive?sessionId=test-session",
        expect.anything(),
      );
    });
  });
});
