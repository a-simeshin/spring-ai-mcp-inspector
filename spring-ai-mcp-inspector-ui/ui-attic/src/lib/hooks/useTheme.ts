import { useCallback, useEffect, useState } from 'react';

export type Theme = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

const STORAGE_KEY = 'mcp-inspector.theme';

function readStored(): Theme {
  if (typeof window === 'undefined') return 'system';
  const v = window.localStorage.getItem(STORAGE_KEY);
  if (v === 'light' || v === 'dark' || v === 'system') return v;
  return 'system';
}

function systemPrefersDark(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false;
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

function applyTheme(resolved: ResolvedTheme): void {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  if (resolved === 'dark') {
    root.classList.add('dark');
  } else {
    root.classList.remove('dark');
  }
}

export interface UseThemeResult {
  theme: Theme;
  resolvedTheme: ResolvedTheme;
  setTheme: (t: Theme) => void;
}

/**
 * Theme hook. Persists choice in localStorage under `mcp-inspector.theme`.
 * Toggles the `dark` class on `<html>` (shadcn convention).
 */
export function useTheme(): UseThemeResult {
  const [theme, setThemeState] = useState<Theme>(() => readStored());
  const [resolvedTheme, setResolved] = useState<ResolvedTheme>(() =>
    theme === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : theme,
  );

  // Apply theme + listen to system changes.
  useEffect(() => {
    const next: ResolvedTheme =
      theme === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : theme;
    setResolved(next);
    applyTheme(next);

    if (theme !== 'system') return;

    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = (e: MediaQueryListEvent) => {
      const r: ResolvedTheme = e.matches ? 'dark' : 'light';
      setResolved(r);
      applyTheme(r);
    };
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, [theme]);

  const setTheme = useCallback((t: Theme) => {
    window.localStorage.setItem(STORAGE_KEY, t);
    setThemeState(t);
  }, []);

  return { theme, resolvedTheme, setTheme };
}

export default useTheme;
