// [spring-ai-mcp-inspector PATCH] Added for the tablet (<1024px) responsive
// layout (#60): mirrors the CSS lg breakpoint so React can drop desktop-only
// inline geometry (sidebar width, fixed pane height) when the viewport is
// compact. Covered by src/lib/hooks/__tests__/useIsCompactLayout.test.tsx.
import { useEffect, useState } from "react";

const COMPACT_QUERY = "(max-width: 1023.98px)";

/**
 * {@link window.matchMedia} guarded for non-browser environments: jsdom (the
 * jest suite renders the full {@code App}, which calls this hook) does not
 * implement it. Real browsers always provide it.
 */
function compactMediaQuery(): MediaQueryList | null {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return null;
  }
  return window.matchMedia(COMPACT_QUERY);
}

/**
 * True when the viewport is below the Tailwind `lg` breakpoint (1024px).
 * Subscribes to the underlying media query so the value tracks resizes.
 * Environments without {@link window.matchMedia} (jsdom) resolve to the
 * desktop layout.
 */
export function useIsCompactLayout(): boolean {
  const [isCompact, setIsCompact] = useState<boolean>(
    () => compactMediaQuery()?.matches ?? false,
  );

  useEffect(() => {
    const mql = compactMediaQuery();
    if (!mql) {
      return;
    }
    const handleChange = (event: MediaQueryListEvent) => {
      setIsCompact(event.matches);
    };
    // Sync in case the initial state was computed before the query existed.
    setIsCompact(mql.matches);
    mql.addEventListener("change", handleChange);
    return () => {
      mql.removeEventListener("change", handleChange);
    };
  }, []);

  return isCompact;
}
