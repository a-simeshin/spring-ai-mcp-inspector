# Changelog

Notable changes per release, grouped as features, fixes and internal work.
Every line references the pull request it landed through.

## 2.2.0

Released 2026-11-02, together with `1.2.0`, the final release of the `1.x`
line. The two releases are feature-parity twins: every fix that shipped on
the `1.x` line since `1.1.0` has its `2.x` counterpart in this release, and
from here on `2.x` is the only maintained line.

Stack: Spring Boot `4.1.0`, Spring AI `2.0.1`, Java MCP SDK `2.0.1`, bundled
MCP Inspector UI `0.22.0`, Java 17+.

### Features

- Auth profiles for target servers (#89): an owner-scoped store for OAuth2
  (client credentials and authorization code with PKCE), bearer, API key and
  custom-header profiles. Tokens live server-side, summaries never list
  secrets, and the proxy retries once on 401 after a token refresh.
- Client traffic timeline (#110, #111, #112, #130): an in-JVM JSON-RPC traffic
  recorder with correlation, a Spring log bridge (logback events, System.out
  and System.err sinks) and a Timeline tab backed by a REST query API. Opt-in
  via `spring.ai.mcp.inspector.timeline.enabled` (default `false`).
- MCP introspection endpoint (#90): `GET /mcp-inspector/api/introspection`
  reports every declared tool and resource with bean and method provenance,
  plus machine-readable warnings for client-incompatible JSON schemas (`$ref`,
  `anyOf`, `oneOf`).
- Tasks support (#196, #197, #281): when the upstream server does not
  advertise task capabilities, the proxy injects them and answers
  `tasks/get`, `tasks/list` and `tasks/cancel` locally. A Tasks tab lists
  tasks with auto-polling, per-task polling, TTL countdown and honest
  timestamps; a server with its own tasks capability keeps answering them.
- Per-phase connection timeout budget (#240): connect and initialize on
  streamable-http run under
  `spring.ai.mcp.inspector.timeouts.connection` (default 30 s) instead of the
  whole streamable-request budget. Overruns return a structured 504
  `MCP_CONNECT_FAILED` with reason `timeout`, `phase`, `elapsedMs` and
  `budgetMs`; `?connectionTimeout=` (1..600 s) overrides per request. SSE and
  stdio bring-up report the same payload as a proxy-error event.
- Upstream liveness probe (#154): idle proxy SSE sessions ping the upstream
  after `timeouts.upstream-probe-idle-threshold` (default 15 s), repeated
  every `timeouts.upstream-probe-interval` (default 10 s). A transport-level
  failure closes the downstream SSE with a structured payload instead of
  hanging forever. On by default; `upstream-liveness-probe-enabled: false`
  restores the old behaviour.
- Protocol revision compatibility (#129, #152): a pure `ProtocolRevision`
  compatibility matrix (no I/O) plus a Timeline indicator that shows whether
  the negotiated revision matches the requested one and which methods the
  mismatch costs.
- Resource previews by mimeType (#108): a shared `MediaContentView` renders
  `image/*` inline, plays `audio/*`, and offers other binaries as downloads;
  resources embedded in tool results use it too.
- Authentication Required banner (#109): a 401 from the inspector server
  surfaces as a dedicated banner explaining the `X-MCP-Inspector-Auth`
  header, instead of a generic connection error.
- Saved connections (#149): connection configs (transport, URL or command,
  args, headers, env vars) persist as named sidebar entries across reloads,
  capped at 20 and sorted by most recent use; one click refills the form.
- Client-side validation (#155, #181): the Connect field validates URL format
  before connecting; tool and prompt forms derive required fields and
  primitive types from the JSON schema, block Run Tool / Get Prompt while
  invalid, and parse numbers strictly (no silent `0x1F` or `Infinity`).
- Unresolvable `$ref` pointers (#204, #264, #272): tool schemas with `$ref`
  pointers the client cannot resolve (spring-projects/spring-ai#5888) are
  detected and warned about; pointer decoding follows RFC 6901
  (percent-escapes first, then `~1`/`~0`) and malformed escapes read as
  unresolvable instead of throwing.
- Initialize handshake through the JSON-RPC relay (#117): protocol version,
  serverInfo and capabilities are served from the captured `InitializeResult`
  snapshot on the existing relay endpoint.
- Skills extension groundwork (#222): SEP-2640
  `io.modelcontextprotocol/skills` capability detection read from the raw
  initialize frame (the SDK drops the declaration when deserializing), with
  `skills/list`, `skills/get` and `resources/directory/read` passing through
  the proxy verbatim. The UI arrives in a later release.
- Exception-leak detector (#291): tool results whose error text leaks a
  correlated application exception (message plus root cause) are marked in
  the timeline model.
- Timeline error badges (#201): `isError` `CallToolResult` rows are
  highlighted with an error badge.

### Fixes

- The reactive `/fetch` no longer runs on the event loop (#49).
- Connect failures carry a reason and a Retry button (#70, #71, #134, #135):
  structured `MCP_CONNECT_FAILED` errors from `POST /mcp`, classified as
  timeout, connection refused, DNS or unauthorized, with no `Error: Error:`
  duplication, and sidebar status text follows the error reason.
- Reconnect after a silent SSE drop resets stale session state first
  (#166, #205).
- Contended concurrent frame emits are retried per session on both stacks
  (#245, #285).
- Proxy and custom headers merge case-insensitively (#301): duplicate headers
  differing only by case no longer break Via Proxy connects with 401.
- The SSE preflight is a single cancelled GET on a shared HttpClient (#180).
- Responsive layout: the tab bar wraps below 640 px (#69), sidebar and
  content stack below 1024 px (#74, #83), the Tools grid and testid anchors
  work at 375 px (#94), and the below-sm sidebar cap is dropped pre-connect
  so URL and Connect stay reachable on phones (#258).
- The tool result panel is scrollable with a Copy button, scrollbar contained
  (#114).
- Resource preview security and completeness follow-up (#137).
- The ping outcome is shown inline in the Ping pane (#233).
- `hashchange` is the single tab state source and the router falls back to
  the default tab on unknown hashes (#209, #227).
- History rows keep persistent error styling for failed `tools/call` (#207).
- Disabled tabs explain themselves via tooltip (#63).
- Demo `errorTool` returns a proper error `CallToolResult`, so the failure
  text is not doubled (#289).
- Demo tools mark spec-default Read-only / Destructive hints as not declared
  (#77).

### Internal, docs and build

- NOTICE.d split from NOTICE.txt: one file per vendored patch, PATCH markers
  in place, enforced by a CI gate (#91, #115, #145).
- AGENTS.md conventions for coding agents (#124, #127, #131, #147).
- E2E and UI gates fail instead of self-skipping, all loopback transports
  covered (#66); blocking ESLint gate (#67); test hardening (#82, #85, #93,
  #103, #158, #163, #194, #231, #270).
- Node toolchain v22.23.2 LTS (#104); security advisories closed via
  overrides (#107); Spring AI 2.0.1 and tooling bumps (#143); vite pinned to
  `^7.3.5` (#173).
- Docs: ADR for the slowEcho retirement (#160); README orphaned-session note
  (#191); surface-status table for spec 2026-07-28 (#307).
