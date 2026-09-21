# Concurrency probe: execution model and data contract

Design doc for issue #237. Defines the contract that the two independent
implementations (engine and UI) must satisfy. Anything marked MUST is a
requirement; SHOULD is a strong default a deviating implementation must justify
in the PR body.

## 1. Placement decision: engine lives in the browser

The probe engine is a TypeScript module inside
`spring-ai-mcp-inspector-ui/upstream-client/src/lib/concurrency/`, not a new
server-side endpoint.

Rationale:

- The inspector already owns a connected MCP `Client` (see
  `lib/hooks/useConnection.ts`, `mcpClient.request(...)` at line ~265) bound to
  the current proxy session. A server-side probe endpoint would need its own
  MCP client to the target server, doubling session state and drifting from
  what the user actually sees in the UI.
- The bug class this feature hunts is "tool breaks under concurrent calls from
  one client session" (singleton-bean state capture in `@McpTool`). Probing
  through the same session the UI uses is the honest reproduction.
- The proxy (`McpProxy`, `StreamableHttpProxyController` POST `/mcp-inspector-api/mcp`)
  already relays concurrent frames; `ConcurrentToolCallsIT` proves 100 parallel
  `tools/call` on one session complete without server-side serialization. No
  backend change is needed to support the probe.

The engine MUST NOT introduce new endpoints in
`spring-ai-mcp-inspector-starter-webmvc` / `-webflux`. If a future need
(headless CI probe) appears, it will be a separate card reusing the same
schema.

## 2. Execution model

### 2.1 Request shape

A single probe run is described by:

| field | type | constraints |
|---|---|---|
| `toolName` | string | must match a tool from the latest `tools/list` |
| `arguments` | object | validated against the tool `inputSchema` before launch |
| `concurrency` (N) | integer | 1..64, default 10 |
| `iterations` | integer | 1..100, default 1 |
| `perCallTimeoutMs` | integer | 1000..120000, default 30000 |
| `metadata` | object | optional, merged into `_meta` of each call |

Total calls executed = `concurrency * iterations`. Hard cap: 640 calls per run
(enforced by the limits above; the UI MUST reject configurations whose product
exceeds 640 even if individual fields are in range).

### 2.2 Schedule

The engine executes iterations SEQUENTIALLY. Within one iteration, it fires N
calls CONCURRENTLY via `Promise.allSettled`, reusing the existing `callTool`
path (`App.tsx:1069`) which routes through `mcpClient.request` on the current
session.

- Iteration `i+1` MUST NOT start until every call of iteration `i` has settled
  (fulfilled, rejected, timed out, or aborted). Sequential iterations keep the
  "current form values" snapshot semantics and make per-iteration aggregates
  meaningful.
- Within an iteration, all N calls SHOULD be dispatched in the same event-loop
  turn (a plain `for` loop building the promise array, then one
  `Promise.allSettled`). Staggered dispatch would blur the race window the
  probe exists to expose.
- Each call gets its own `AbortController`; a per-call timeout of
  `perCallTimeoutMs` aborts that controller. A timed-out call is recorded with
  status `timeout`, not `error`.
- The MCP SDK supports `signal` in `RequestOptions` (see
  `useConnection.ts:238-239`); the engine MUST pass the per-call signal
  through. `callTool` currently does not accept a signal: the engine card must
  extend the `callTool` signature with an optional `signal?: AbortSignal`
  parameter, threaded into `sendMCPRequest` options. This is an additive,
  backward-compatible change.

### 2.3 Session strategy

One shared MCP session: the probe runs on the `mcpClient` the UI is already
connected with. Per-call sessions are explicitly out of scope.

The engine MUST refuse to start when `mcpClient === null` (not connected) and
MUST NOT mutate connection state.

### 2.4 Cancellation

The UI exposes a Cancel button while a probe is running. Cancellation:

1. Aborts every in-flight per-call `AbortController`.
2. Marks all unsettled calls with status `cancelled` (they are NOT counted as
   failures in aggregates).
3. Stops scheduling further iterations.
4. Returns a partial result object (see schema: `completed: false`,
   `cancelledAt` set) so the panel can render what was measured before the
   cancel.

After cancellation the engine MUST be re-runnable without reconnecting: no
leaked controllers, no pending promises held by the engine state.

### 2.5 Threading / event-loop notes

JavaScript is single-threaded; concurrency here means N in-flight HTTP/JSON-RPC
exchanges, not N OS threads. The wall-clock parallelism argument is still
valid: the existing `ConcurrentToolCallsIT` shows 10 concurrent 2-second
`slowEcho` calls on one session finish in under 6 s through the same proxy the
browser uses, so the transport does not serialize.

The engine MUST NOT block the UI: all awaits happen inside async functions;
the per-iteration dispatch loop contains no synchronous heavy work (hashing is
done per call after settle, see 3.1).

## 3. Result schema

The canonical result of a probe run is the JSON document described by
`docs/concurrency-probe.schema.json`. Summary of the shape; the schema file is
normative.

### 3.1 Per-call record

One entry per executed call (including cancelled and timed-out):

- `index` (integer, 0-based, global across iterations:
  `iteration * concurrency + lane`)
- `iteration` (integer, 0-based)
- `lane` (integer, 0..N-1, position inside the iteration)
- `startedAt` / `endedAt` (ISO-8601 UTC strings with millisecond precision,
  from `new Date().toISOString()`)
- `latencyMs` (integer, `endedAt - startedAt`; for `cancelled` calls the time
  until the abort settled)
- `status` (`success` | `error` | `timeout` | `cancelled`)
- `errorMessage` (string, present iff `status` is `error` or `timeout`;
  `error` carries the thrown/rejected message, `timeout` carries a fixed
  string `timeout after <perCallTimeoutMs>ms`)
- `bodyHash` (string, present iff `status === "success"`: SHA-256 hex of the
  canonical JSON serialization of the `CompatibilityCallToolResult`, see 3.3)
- `timelineCorrelationId` (string, optional: the timeline correlation id of
  the request/response pair when timeline recording is enabled; lets the UI
  deep-link from a failure group into the Timeline tab)

### 3.2 Aggregates

Computed over the whole run (all iterations):

- `totalCalls`, `successCount`, `errorCount`, `timeoutCount`,
  `cancelledCount` (integers; `totalCalls = successCount + errorCount +
  timeoutCount + cancelledCount`)
- `latencyMs`: object with `p50`, `p95`, `p99`, `max` (numbers, computed over
  successful calls only; see 3.4 for the percentile definition; absent when
  `successCount === 0`)
- `bodyEquality`: object `{ kind: "all-equal" | "mixed" | "single-success" |
  "no-success", distinctHashes: integer }` computed over successful calls only
- `failureGroups`: array of `{ errorMessage, count, callIndices: integer[] }`
  grouping calls with `status` `error` or `timeout` by exact `errorMessage`
  string, sorted by `count` descending; `callIndices` references the global
  `index` values and is what the UI uses for the "show in Timeline" affordance
- `completed` (boolean, false iff the run was cancelled)
- `cancelledAt` (ISO-8601, present iff `completed === false`)

### 3.3 Body hash canonicalization

`bodyHash` is SHA-256 over the result of canonical JSON serialization of the
full `CompatibilityCallToolResult` object:

- object keys sorted lexicographically, recursively;
- no insignificant whitespace;
- arrays kept in order;
- numbers serialized per JSON rules (no trailing `.0`).

The engine MUST implement canonicalization locally (no new dependency;
~30 lines). Rationale: `structuredContent` and `content` blocks both
participate; a race in server-side state typically shows up as diverging
payloads, and hashing the whole result catches that regardless of which field
carries it.

### 3.4 Percentile definition

Percentiles use the nearest-rank method on the sorted latency array of
successful calls: `p_k = latencies[ceil(k/100 * len) - 1]` with 1-based rank
clamped to `[1, len]`. For a single success (`len = 1`), p50 = p95 = p99 = max
= that latency. The engine MUST ship unit tests covering `len = 1`,
all-failed runs (no `latencyMs` key), and mixed success/timeout runs.

## 4. UI ↔ engine contract

### 4.1 Engine API (TypeScript)

```ts
interface ProbeConfig {
  toolName: string;
  arguments: Record<string, unknown>;
  concurrency: number;      // 1..64
  iterations: number;       // 1..100
  perCallTimeoutMs: number; // 1000..120000
  metadata?: Record<string, unknown>;
}

interface ProbeHandle {
  /** Resolves with the final (or partial, when cancelled) ProbeResult. */
  readonly done: Promise<ProbeResult>;
  /** Abort all in-flight calls and stop scheduling. Idempotent. */
  cancel(): void;
  /** Progress callback, invoked after each settled call. */
  onProgress(cb: (settled: number, total: number) => void): void;
}

function runProbe(
  config: ProbeConfig,
  callTool: CallToolFn, // injected from App.tsx
): ProbeHandle;
```

The engine is a pure module: it receives `callTool` by injection, holds no
React state, and never touches the DOM. This keeps it unit-testable with a
stubbed `callTool` and keeps the PATCH surface in `App.tsx`/`ToolsTab.tsx`
minimal.

### 4.2 Delivery: final snapshot plus progress callbacks

The UI receives the result as ONE final `ProbeResult` object when
`handle.done` resolves (or a partial result after `cancel()`). Progress during
the run is delivered via `onProgress(settled, total)` after every settled
call; the UI uses it for a progress bar and a live success/failure counter,
not for incremental rendering of the result panel.

Rationale: at the cap of 640 calls the full result is small (per-call records
are ~200 bytes each plus hashes), so streaming per-call records over a channel
buys nothing and complicates the contract. The Timeline tab already shows
in-flight requests live through the traffic recorder, which covers the "watch
interleaving" need.

### 4.3 UI surface

- Each tool row in the Tools list gets a "Concurrency probe" affordance
  (button or overflow-menu item).
- A dialog collects `concurrency`, `iterations`, `perCallTimeoutMs` and shows
  the computed total call count. Arguments come from the existing tool-detail
  form (`params` state in `ToolsTab.tsx:232`); the dialog MUST reuse those
  values rather than duplicate the form.
- The result panel renders: success/failure counts, the four percentiles,
  `bodyEquality` as an explicit badge ("All N responses identical" / "K
  distinct response bodies across N calls" / "No successful calls"), the
  grouped failures list (each row clickable, scrolling the Timeline tab to the
  first `callIndex` of the group when timeline correlation ids are present),
  and a per-call table (index, iteration, latency, status, error message).
- While the probe runs, the dialog shows progress and a Cancel button; the
  rest of the UI MUST stay interactive.

## 5. Edge cases (normative behavior)

| case | required behavior |
|---|---|
| Partial failures inside one iteration | remaining calls continue; failed calls recorded with their `errorMessage`; aggregates include them; the run completes normally |
| All calls in a run fail | result is valid: `successCount = 0`, `latencyMs` absent, `bodyEquality.kind = "no-success"`, failures still grouped |
| Connection drop mid-probe | the in-flight calls reject with the transport error and are recorded as `error`; the engine detects the session-closed signal (the existing `useConnection` error path) and stops scheduling further iterations; result returned with `completed: false` and the transport error surfaced as a top-level `abortedReason` |
| Per-call timeout exceeded | that call is aborted via its controller and recorded with `status = "timeout"`; other calls unaffected |
| Server that serializes requests on one session | nothing special: calls still all succeed, latencies pile up (p95 ≈ N × per-call time); this IS the signal the probe exists to show, and the per-call timeline plus percentiles make it visible. The spec does NOT treat slow-but-successful as failure |
| `concurrency = 1` | valid; degenerates to sequential calls, percentiles computed normally |
| `successCount = 1` | `bodyEquality.kind = "single-success"`, p50 = p95 = p99 = max = that latency |
| Cancel before any call settles | result has `totalCalls = 0`, all counts 0, `completed: false` |
| Body hashing of huge responses | canonicalization operates on the parsed JSON value, not the raw wire text; responses larger than 5 MB serialized MAY be hashed over a truncated prefix (first 1 MB of canonical form) with `bodyHashTruncated: true` on the record |
| Tool requiring `task` execution (`taskSupport = "required"`) | probe refuses to launch with a clear message: task-augmented calls are out of scope for the probe (they poll asynchronously and would break the latency semantics) |

## 6. Verification anchors

Facts this spec relies on, with the check a reviewer can rerun:

- `callTool` signature and shared `mcpClient`:
  `spring-ai-mcp-inspector-ui/upstream-client/src/App.tsx:1069`,
  `lib/hooks/useConnection.ts:265`.
- AbortSignal support in the SDK request path:
  `lib/hooks/useConnection.ts:238-239`.
- Proxy relays concurrent frames on one session without serialization:
  `spring-ai-mcp-inspector-demo-webmvc/src/test/java/io/inspector/mcp/demo/stress/ConcurrentToolCallsIT.java`
  (`tenConcurrentSlowEchoes_onSameSession_runInParallel`, 10 × 2 s in ≤ 6 s).
- Demo server ships a thread-safe `slowEcho` (2 s sleep, echoes input):
  `spring-ai-mcp-inspector-demo-app/src/main/java/io/inspector/mcp/demo/tools/SlowEchoToolConfiguration.java`.
  A deliberately racy tool for the negative test case will be added by the
  engine card if absent on the target line (see tester card t_ae111481).
- Result object hashed by the probe is `CompatibilityCallToolResult` from
  `@modelcontextprotocol/sdk/types.js` (consumed today by
  `components/ToolResults.tsx:78`).

## 7. Out of scope

- Server-side probe endpoint (see section 1).
- Per-call fresh MCP sessions.
- Ramping / closed-load models, arrival-rate control, custom think times:
  this is a race-condition probe, not a load generator. JMeter/k6 remain the
  tools for throughput questions.
- Persisting probe results across page reloads (History integration is a
  separate discussion).
