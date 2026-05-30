import '@testing-library/jest-dom/vitest';

// Provide auth token placeholder for tests.
declare global {
  interface Window {
    __INSPECTOR_AUTH_TOKEN__?: string;
  }
}

window.__INSPECTOR_AUTH_TOKEN__ = 'test-token';

// jsdom lacks `matchMedia`; provide a minimal stub used by useTheme and friends.
if (typeof window.matchMedia !== 'function') {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (window as any).matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  });
}
