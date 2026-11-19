# Changelog

Notable changes per release, grouped as features, fixes and internal work.
Every line references the pull request it landed through.

## 2.4.0

Stack: Spring Boot `4.1.0`, Spring AI `2.0.1`, Java MCP SDK `2.0.1`, bundled
MCP Inspector UI `0.22.0`, Java 17+.

### Features

- Outgoing MCP client traffic is captured into the timeline (#406): with
  `spring.ai.mcp.inspector.timeline.client-capture-enabled=true` (default
  `false`) an app that also acts as an MCP client records its outgoing
  JSON-RPC requests, responses and notifications with client provenance
  (`side=client`).
- Server-initiated exchanges (sampling, elicitation, logging, progress)
  carry the `beanName#method` identity of the `@Mcp*` handler that consumed
  them, and startup warns about silent wiring mistakes, when client capture
  is enabled (#407).
- A client connections snapshot API (`GET
  ${spring.ai.mcp.inspector.path}/api/client-connections`) returns the live
  MCP client connection registry on both stacks, and the vendored SPA gains
  a Clients tab (#408).
- MCP Apps tool resources (`ui://` URIs advertising
  `text/html;profile=mcp-app`) render in a sandboxed iframe chain served by
  the proxy, with a lifecycle and traffic panel (#398).
- The connect zone shows a version badge with the protocol revision the
  server actually negotiated, color-coded by compatibility severity (#399),
  and the legacy `2024-11-05` revision is registered, so a live downgrade
  renders the notice instead of UNKNOWN (#416).
- Task rows show `createdAt`, and `keepAlive` passes through the proxy
  instead of rendering `N/A` (#397).
- The bundled demo app runs its own outbound loopback MCP client pointing
  at the demo server, so the UI shows a live client connection and
  client-side timeline traffic out of the box (#413).

### Fixes

- `tasks/*` RPC calls are gated on the capabilities the server actually
  advertises: the Tasks tab renders a self-explained capability-gap body
  instead of a raw `-32601` error toast on servers without tasks support
  (#405).
- Demo tool bodies on the reactive stack run off the Netty event loop, so
  blocking calls such as elicitation no longer settle with an error before
  the user can answer (#380).
- The app iframe stays usable at 1280px next to the traffic panel (#410).

### Internal, docs and build

- The OAuth-refresh retry pins the fresh Authorization header, with tests
  across core, webflux and webmvc (#387); flaky tests fixed:
  ResourceRacePaneUiIT gates the delayed reply behind the fast one (#388),
  the McpProxyTests warm-up wait handles the empty initial state (#384).
- The demo E2E suite drives through `data-testid` anchors (#389), and
  fragile E2E selectors are gated in CI (#390).
- Vendored-client governance: every vendored patch file requires its own
  NOTICE.d entry (#392); the 23 unbound marker files are bound to their
  entries (#395) and the re-vendor drill gaps are registered (#393).
- Docs: the stale `mcpSessionId` claim in the keep-alive timeline entry is
  corrected (#414).
- CI caches the Maven wrapper distribution and retries its download (#412).

## 2.3.0

Stack: Spring Boot `4.1.0`, Spring AI `2.0.1`, Java MCP SDK `2.0.1`, bundled
MCP Inspector UI `0.22.0`, Java 17+.

### Features

- Keep-alive tracking (#239): inbound JSON-RPC pings on loopback SSE and
  streamable-http sessions are recorded per session, the rolling median
  interval and staleness are exposed live through `mcp:keepalive-ping` SSE
  events and a `GET /api/keepalive` polling snapshot, and the Timeline and
  the sidebar surface the state.
- SEP-2322 input-required round trip (#313, #315, #317): when a tool call
  comes back with an input-required request, the UI collects the follow-up
  input and resumes the call in the same Timeline chain; the demo app ships
  a `multiRoundTripEcho` tool exercising the flow end to end.
- Connection timeout in the Connect form (#241, #244): an optional
  per-connect timeout (whole seconds, 1..600) forwarded as
  `connectionTimeout`, with inline validation and persistence in saved
  connections.
- The connect error banner shows the timeout phase and the budget
  breakdown (#243).
- Primitive tool results that advertise no outputSchema are warned about in
  the tool panel (#202).
- A hint panel lists common Spring AI 2.0 pitfalls (#203).
- History persists per connection in localStorage buckets, frozen at
  connect time and restored on reconnect (#218).
- Exception-leak badge in the Timeline and a verbatim note in Tool
  Results (#292).
- Markdown resource preview (#354): `text/markdown` renders as sanitized
  HTML (marked + DOMPurify) with a View source toggle for the raw payload.
- Root URI validation in the Roots tab (#355): Save Changes blocks empty,
  scheme-less or otherwise malformed root URIs with an inline reason, so
  servers no longer receive junk roots.

### Fixes

- Concurrent tool calls on one session overlap instead of serializing
  (#379): the proxy opens spare keep-alive connections to the upstream at
  session open (`spring.ai.mcp.inspector.proxy.upstream-warm-connections`,
  default `10`, `0` restores the old behavior) so a burst of calls no
  longer trails one tool duration per accept wave on WebFlux servers.
- Custom header names the JDK HTTP client refuses (restricted names such as
  `host`, or names containing a colon) fail the connect up front with one
  non-retryable `invalid_request` error listing every offender, never
  echoing the header value (#188).
- A non-numeric `tasks/list` cursor returns a `-32602` invalid params
  error instead of a silent empty page (#320).
- The reactive proxy shifts the upstream send off the emitting thread (#358).
- The timeline API auth sends the raw inspector token header (#365).
- Elicitation cards expire when their `tools/call` settles (#361).
- The Resources pane is guarded by the selected uri (#345) and re-issued on
  Refresh and row re-select (#342), so a stale pane no longer masquerades
  as the selection.
- `text/*` resource payloads render decoded, without JSON quoting (#353).
- `x-custom-auth-headers` parses as a JSON array on every endpoint (#189).
- Get Prompt is blocked on blank required args and blank optional args are
  dropped from the payload (#359).
- `prompts/get` MCP errors surface without an unhandled rejection (#339).
- The selected prompt row keeps its highlight (#338).
- The ListPane hover no longer hides the selected row marker; the row is
  announced with `aria-current` (#350).
- The Resources panes stack into one column below the md breakpoint (#346).
- The tab bar wraps at every viewport width (#337).
- The cache-hit pane commit is guarded by the live selection (#351).
- OAuth token acquisition on WebFlux runs on boundedElastic instead of the
  Netty event loop (#187).

### Internal, docs and build

- Browser E2E for the Resources pane race with a delayed resource (#357),
  the SEP-2322 input-required round trip (#318) and whitespace-only prompt
  validation stories with a PromptsTab blur hint test (#368).
- Two flaky test causes fixed on `develop/2.x`: the SSE helper buffer race
  and the `/config` URL clobber (#376); test hardening (#322, #331, #348,
  #363) and unit-test pins (#280, #287).
- Dead proxy code dropped: the unreachable session-header branch in the
  tasks intercept (#319) and the dead budget-timeout fallback in
  SseProxyController (#360).
- npm dependency security bump across the vendored client (#314).
- Docs: why the `1.x` line is no longer supported (#312); vendored
  tasks-tab notice wording (#340).

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
