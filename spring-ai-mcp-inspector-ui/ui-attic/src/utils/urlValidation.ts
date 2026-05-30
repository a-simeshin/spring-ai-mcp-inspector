// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

/**
 * URL validation helpers used by transport configuration UIs.
 */

/** Returns true when `s` parses as an absolute `http:` or `https:` URL. */
export function isValidHttpUrl(s: string): boolean {
  if (typeof s !== 'string' || s.trim() === '') return false;
  try {
    const u = new URL(s);
    return u.protocol === 'http:' || u.protocol === 'https:';
  } catch {
    return false;
  }
}

/** Returns true when `s` is a valid HTTP(S) URL whose host is loopback. */
export function isLocalhostUrl(s: string): boolean {
  if (!isValidHttpUrl(s)) return false;
  try {
    const u = new URL(s);
    const host = u.hostname.toLowerCase();
    return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '[::1]';
  } catch {
    return false;
  }
}
