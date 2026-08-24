// [spring-ai-mcp-inspector PATCH] Unit coverage for the compact-layout hook
// (#60): the hook mirrors the CSS lg breakpoint so App.tsx can drop
// desktop-only inline geometry below 1024px. jsdom has no window.matchMedia,
// so every scenario installs a minimal stub except the fallback one.
import { renderHook, act } from "@testing-library/react";
import { useIsCompactLayout } from "../useIsCompactLayout";

const COMPACT_QUERY = "(max-width: 1023.98px)";

type ChangeListener = (event: { matches: boolean }) => void;

interface MatchMediaStub {
  matches: boolean;
  addEventListener: jest.Mock;
  removeEventListener: jest.Mock;
}

/** Install a minimal window.matchMedia stub with captured change listeners. */
function installMatchMedia(initialMatches: boolean) {
  const listeners = new Set<ChangeListener>();
  const mql: MatchMediaStub = {
    matches: initialMatches,
    addEventListener: jest.fn((_type: string, listener: ChangeListener) => {
      listeners.add(listener);
    }),
    removeEventListener: jest.fn((_type: string, listener: ChangeListener) => {
      listeners.delete(listener);
    }),
  };
  window.matchMedia = jest.fn().mockReturnValue(mql);
  return {
    mql,
    /** Flip the query result and notify subscribers, like a real resize. */
    emit(matches: boolean) {
      mql.matches = matches;
      listeners.forEach((listener) => listener({ matches }));
    },
  };
}

afterEach(() => {
  const win = window as unknown as { matchMedia?: unknown };
  delete win.matchMedia;
});

describe("useIsCompactLayout", () => {
  it("returns true when the viewport is compact (query matches)", () => {
    // given
    installMatchMedia(true);

    // when
    const { result } = renderHook(() => useIsCompactLayout());

    // then
    expect(result.current).toBe(true);
  });

  it("returns false at the desktop width (query does not match)", () => {
    // given
    installMatchMedia(false);

    // when
    const { result } = renderHook(() => useIsCompactLayout());

    // then
    expect(result.current).toBe(false);
  });

  it("queries the CSS lg breakpoint expression", () => {
    // given
    const stub = installMatchMedia(false);

    // when
    renderHook(() => useIsCompactLayout());

    // then
    expect(window.matchMedia).toHaveBeenCalledWith(COMPACT_QUERY);
    expect(stub.mql.addEventListener).toHaveBeenCalled();
  });

  it("re-renders on media query change across the breakpoint both ways", () => {
    // given
    const stub = installMatchMedia(false);
    const { result } = renderHook(() => useIsCompactLayout());
    expect(result.current).toBe(false);

    // when — resize down to a tablet width
    act(() => {
      stub.emit(true);
    });

    // then
    expect(result.current).toBe(true);

    // when — resize back to desktop
    act(() => {
      stub.emit(false);
    });

    // then
    expect(result.current).toBe(false);
  });

  it("treats 1023px as compact and 1024px as desktop across the breakpoint", () => {
    // given — desktop at the smallest desktop width (1024px)
    const stub = installMatchMedia(false);
    const { result } = renderHook(() => useIsCompactLayout());
    expect(result.current).toBe(false);

    // The query must flip strictly between 1023px and 1024px so the React
    // value mirrors the CSS `lg` breakpoint exactly (Tailwind lg = 64rem).
    const [query] = jest.mocked(window.matchMedia).mock.calls[0];
    const match = /^\(max-width:\s*([\d.]+)px\)$/.exec(query);
    expect(match).not.toBeNull();
    expect(parseFloat(match![1])).toBeGreaterThanOrEqual(1023);
    expect(parseFloat(match![1])).toBeLessThan(1024);

    // when — the viewport shrinks to 1023px (query starts matching)
    act(() => {
      stub.emit(true);
    });

    // then — 1023px is still compact
    expect(result.current).toBe(true);

    // when — the viewport grows back to 1024px (query stops matching)
    act(() => {
      stub.emit(false);
    });

    // then — 1024px is desktop again
    expect(result.current).toBe(false);
  });

  it("unsubscribes from the media query on unmount", () => {
    // given
    const stub = installMatchMedia(false);
    const { unmount } = renderHook(() => useIsCompactLayout());

    // when
    unmount();

    // then
    expect(stub.mql.removeEventListener).toHaveBeenCalled();
    expect(() =>
      act(() => {
        stub.emit(true);
      }),
    ).not.toThrow();
  });

  it("falls back to the desktop layout without window.matchMedia (jsdom)", () => {
    // given — no matchMedia installed

    // when
    const { result } = renderHook(() => useIsCompactLayout());

    // then
    expect(result.current).toBe(false);
  });
});
