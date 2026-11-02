# Changelog

Notable user-facing changes per release of `spring-ai-mcp-inspector`.
The `1.x` line ended with `1.2.0`; active development continues on the `2.x` line.

## 1.2.0 (November 2026), the final 1.x release

`1.2.0` is the last release of the `1.x` line (Spring Boot 3.5 / Spring AI 1.1 /
Java MCP SDK 0.18). After it the 1.x line is end of life: no further 1.x releases
or fixes. Move to the 2.x line (Spring Boot 4 / Spring AI 2); the README
compatibility section has the version map.

Stack: Spring Boot `3.5.15`, Spring AI `1.1.8`, Java MCP SDK `0.18.3`, bundled
MCP Inspector UI `0.21.2`, Java 17+.

### Breaking changes

- `ProxyTransportFactory.openSse(URI)` is renamed to `buildSse(URI)`, and the SSE
  transport it returns now performs a single header-only preflight probe before
  connecting, to keep the shared `HttpClient` free of the pooled-connection race
  (#259). Only code that calls the factory directly is affected; the starters
  wire the factory internally.

### Features

- Tasks tab: list and cancel tasks with auto-polling, per-task manual refresh,
  a live TTL countdown, and an explicit empty state; the disabled state explains
  itself with a tooltip (#213, #282, #64).
- Client-side validation of tool and prompt argument forms: required and type
  checks run before submit (#186).
- Tool schema robustness: unresolved `$ref` pointers in tool schemas are detected
  and reported instead of rendering broken forms (#211); JSON Pointer fragment
  tokens are decoded percent-first, then `~1`/`~0` (#273).
- Connect field: URL format is validated client-side before a connect attempt (#174).
- Upstream liveness probe: idle proxied sessions are pinged with a JSON-RPC
  `ping`, so a dead upstream closes the downstream stream instead of hanging
  until the next user action. Transport-level failures only; disable with
  `spring.ai.mcp.inspector.upstream-liveness-probe-enabled: false` (#178).
- webmvc starter: the `initialize` handshake result is relayed through the
  JSON-RPC dispatch, matching the webflux behavior (#133).
- Structured connect errors: `MCP_CONNECT_FAILED` carries a machine-readable
  cause (timeout, connection_refused, dns, unknown) from both stacks (#72, #179).

### Fixes

- Reconnect after silent connection drop: the proxy detects a silently dead
  pooled JDK `HttpClient` connection and reconnects instead of stalling until
  the request timeout (#177).
- Contended SSE session emits no longer drop frames: concurrent frame emits are
  serialized per proxy session (#284), and the webflux inspector SSE endpoint
  retries contended session emits (#286).
- Failed connects no longer leave stale session state in the UI (#277); connect
  failures surface with a reason and a Retry button (#84, #153).
- Mobile and narrow layouts: the tab bar wraps below 640px so every tab stays
  reachable (#73); sidebar, content, and History stack below 1024px (#75); the
  Tools grid is usable at 375px (#95, #274); compact panes get definite heights
  and a full-height root background (#88); History and Notifications stack below
  the md breakpoint (#257).
- Tab routing: `hashchange` is the single tab state source (#210), and an
  unknown or invalid hash falls back to the default tab (#228).
- History rows keep their error styling instead of flashing back to normal (#208);
  the ping outcome shows inline in the Ping pane (#234); tool results are
  scrollable with a working Copy button (#139).
- The reactive `/fetch` stays off the event loop and closes the orphaned session (#50).

### Internal

- Demo server: explicit honest hint annotations on the demo tools; spec-default
  chips marked in the Tools tab (#96, #97, #100, #101).
- Vendored-patch registry split into `NOTICE.d` with a strengthened gate
  (#92, #146, #175).
- Build and tooling: Node 22 LTS toolchain (#106), selenide/spotbugs/jacoco and
  friends bumped (#144), vite pinned to ^7.3.5 (#193), blocking ESLint gate (#68).
- Docs: AGENTS.md conventions (#125, #128, #132, #148), ADR on retiring the
  manual slowEcho workaround (#161), orphaned-session note in the README (#192).
- Test hardening: e2e fails loudly on missing preconditions (#65), chip
  assertions unconditional (#159), tool-row click state IT (#165).
