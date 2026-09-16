# Stateless proxy auth-binding semantics

## What changed

Under the legacy MCP protocol (2025-11-25 and earlier), the proxy binds every session
to a random UUID session id. The browser carries this id in the `Mcp-Session-Id`
header on every request; the proxy uses it to look up the upstream transport and to
scope any cached auth state. Two logical connections to the same server never share a
session.

Under the stateless MCP protocol (2026-07-28, SEP-2567/SEP-2575), the session id
header is gone. The proxy therefore binds state to the pair
`(serverUrl, authProfileFingerprint)` instead. Two requests with the same URL and the
same auth profile (Authorization header + custom-auth headers) share one
`ProxySession` and one upstream transport. The auth profile fingerprint is an opaque
SHA-256 hash of the auth material, so no credential is ever stored in the key.

## Token lifecycle

- **Acquisition**: on the first request for a given `(serverUrl, fingerprint)` pair,
  the proxy creates a new upstream transport with the request's auth headers and
  registers a `ProxySession` under the stateless key.
- **Sharing**: subsequent requests with the same key reuse the same session and the
  same upstream transport, including any token already negotiated with the target
  server. This means a token is shared across logical connections to the same
  `(url, profile)`.
- **Refresh**: token refresh is delegated to the underlying transport / auth
  provider. The proxy does not manage refresh tokens itself.
- **Eviction**: a session is evicted when it is closed, when the upstream transport
  terminates, or when it has been idle longer than the configured inactivity budget
  (default 30 minutes). Changing the auth profile (different Authorization header or
  different custom headers) produces a different fingerprint and therefore a
  different session; the old session is not shared and is eventually reaped.

## Cross-profile leakage guard

Two auth profiles against the same URL **never** share a session or a cached token,
because the profile fingerprint is part of the binding key. A request with
`Authorization: Bearer A` and a request with `Authorization: Bearer B` to the same
URL are bound to two different sessions, even when they arrive concurrently.

## What this means for horizontal scaling

Removing the session-id requirement for stateless targets allows the proxy to run
behind a load balancer without sticky sessions. Any replica can serve any request
because the binding key is derived from the request itself (URL + auth profile), not
from server-side state.

## Legacy path unchanged

When the target server speaks the legacy protocol (2025-11-25 or earlier), the proxy
continues to bind sessions by the `Mcp-Session-Id` header. The stateless code path is
only entered when the `MCP-Protocol-Version: 2026-07-28` header is present (or when
the `_meta` fallback detects a stateless request with no session header).
