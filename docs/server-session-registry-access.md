# Host app MCP server session registry: access from the inspector (spike)

Refs #369. Upstream defect class: [spring-projects/spring-ai#6860](https://github.com/spring-projects/spring-ai/issues/6860):
server-side Streamable HTTP sessions that never receive `DELETE /mcp` stay in the
transport's session map (and their sockets stay open) for the whole process
lifetime. The inspector currently shows only its own proxy sessions (#327), so
leaked host sessions are invisible.

This document records where the session registry lives in every Spring AI
version the inspector supports, which access strategy the collector must use,
the data contract shared by the collector and the UI, the degraded mode, and
the reflection risk assessment.

## Where the session registry lives

| Inspector line | Spring AI | Transport artifacts | Provider FQCN |
|---|---|---|---|
| develop/1.x | 1.1.8 | `io.modelcontextprotocol.sdk:mcp-spring-webmvc/-webflux:0.18.3` | `io.modelcontextprotocol.server.transport.{WebMvc,WebFlux}StreamableServerTransportProvider` |
| develop/2.x | 2.0.1 | `org.springframework.ai:mcp-spring-webmvc/-webflux:2.0.1` | `org.springframework.ai.mcp.server.{webmvc,webflux}.transport.{WebMvc,WebFlux}StreamableServerTransportProvider` |

In all four combinations the registry is the same field:

```java
private final ConcurrentHashMap<String, McpStreamableServerSession> sessions;
```

Spring AI 2.0.1 adds two fields to each provider:

```java
private final ConcurrentHashMap<String, Instant> sessionLastAccessTimes; // updated on init and on each request
// plus idle eviction: maxSessions, sessionIdleTimeout (builder properties)
```

Per-session state lives in `io.modelcontextprotocol.spec.McpStreamableServerSession`
(same package in both lines, verified 0.18.3 and 2.0.1):

| Member | Visibility | Notes |
|---|---|---|
| `getId()` | public | session id |
| `clientCapabilities`, `clientInfo` | private `AtomicReference` | client name/version readable via reflection |
| `listeningStreamRef` | private `AtomicReference` | `MissingMcpTransportSession` until the client opens the SSE stream, `McpStreamableServerSessionStream` (public final inner class) after |
| `sendNotification(String, Object)` | public | probe helper: returns `Mono.error(IllegalStateException "Stream unavailable...")` on a never-connected session without mutating state |
| creation timestamp | absent | not stored by the SDK in either line; the collector must track `firstSeenAt` itself |

The bean is exposed by both auto-configurations as
`@Bean @ConditionalOnMissingBean webMvcStreamableServerTransportProvider` /
`webFluxStreamableServerTransportProvider` in
`McpServerStreamableHttpWebMvcAutoConfiguration` / `...WebFlux...`, gated by
`@Conditional({McpServerStdioDisabledCondition, EnabledStreamableServerCondition})`
(i.e. when the host enables the STREAMABLE server transport). The bean method
chain and conditions are identical in both lines; only packages differ.

## Why there is no official API

The PoC enumerates all public methods of the provider instance and looks for
any zero-argument accessor returning a session collection. Result on both
lines: `ABSENT`. The public surface of the provider is `getRouterFunction`,
`protocolVersions`, `setSessionFactory`, `notifyClients`, `notifyClient`,
`closeGracefully`, `close` plus builder construction; nothing exposes the map.
No MCP java-sdk or Spring AI release note in the supported ranges mentions a
session registry API, and spring-ai#6867/#6871 (proposed fixes for #6860) do
not add one.

Priority order from the card: official API (does not exist) → Spring bean
(exists: the provider bean) → reflection (needed for the map only).

## Chosen access strategy

1. Get the provider beans from the host `ApplicationContext`
   (`getBeansOfType`), never construct them. Collector code refers to the
   provider types by FQCN strings only, same pattern as the existing
   `McpBeanIntrospector` in inspector-core: no compile dependency on Spring AI,
   because the provider package changes between lines.
2. Read the `sessions` field of each provider bean via a cached
   `java.lang.invoke.MethodHandle`/`Field` (setAccessible once, reuse).
3. Read per-session data through the public `McpStreamableServerSession`
   API where possible (`getId`, `sendNotification`), reflection for the
   private `AtomicReference` fields.
4. Fail-open everywhere: any missing class, bean or field means
   `registryExposed=false` plus a reason string, never an exception out of
   the collector.

The SSE transport family (`{WebMvc,WebFlux}SseServerTransportProvider`)
follows the same pattern with a private session map and is intentionally out
of scope for the first collector iteration (issue #369 targets the
Streamable HTTP leak).

## PoC evidence

A standalone probe (`SessionRegistryPoc.java`, attached to the kanban card)
builds the real provider via its public builder, reads the private map with
reflection, inserts a session created through the public constructor, and
reads it back. Run with Java 21, plain classpath jars (no JVM flags, no
module-path): all four combinations pass.

| Mode | Line | Provider | Access result |
|---|---|---|---|
| 118 | 1.1.x | WebMvc 0.18.3 | ACCESS OK: map read, session injected and read back, `getId()`, private `clientInfo`, `listeningStreamRef=MissingMcpTransportSession`, probe `Mono.error("Stream unavailable")`, `sessionLastAccessTimes` absent (as expected pre-2.0.1) |
| 118f | 1.1.x | WebFlux 0.18.3 | ACCESS OK (same assertions) |
| 201 | 2.0.x | WebMvc 2.0.1 | ACCESS OK (same assertions) plus `sessionLastAccessTimes` read |
| 201f | 2.0.x | WebFlux 2.0.1 | ACCESS OK (same assertions) plus `sessionLastAccessTimes` read |

Why reflection is safe here: the SDK and Spring AI jars carry no
`module-info.class`, so on a normal Spring Boot host (classpath, unnamed
module) `setAccessible` needs no `--add-opens`. Verified by `unzip -l` on
`mcp-core`, `mcp-spring-webmvc`, `mcp-spring-webflux` 0.18.3 and 2.0.1.

## Data contract (final, single source for collector and UI)

```java
/** Immutable snapshot taken by the collector on each poll. */
public record ServerSessionRegistrySnapshot(
        Instant takenAt,
        boolean registryExposed,
        String degradedReason,          // null when registryExposed is true
        long liveCount,                 // total sessions in the map(s)
        List<ServerSessionView> sessions) {}

public record ServerSessionView(
        String sessionIdHash,           // SHA-256 of the raw id, hex, truncated to 12 chars
        String transport,               // "streamable-http-webmvc" | "streamable-http-webflux"
        Instant firstSeenAt,            // first observation by the collector (SDK stores no creation time)
        long ageMs,                     // takenAt - firstSeenAt
        String clientName,              // nullable; from private clientInfo (reflection)
        String clientVersion,           // nullable
        String streamState,             // "never-connected" | "has-stream" | "unknown"
        Instant lastAccessTime,         // nullable; SDK 2.0.1+ only
        String lastKeepAliveResult,     // "not-probed" | "ok" | "failed" | "unavailable"
        Instant lastKeepAliveAt)        // nullable; set when probed
```

Field decisions:

- `sessionIdHash`: raw session ids are client-visible in HTTP traffic and logs,
  the UI gets only the hash.
- `firstSeenAt`/`ageMs`: honest semantics is "age as observed by the
  collector". If the inspector is enabled after the host has leaked sessions,
  their age starts at first observation; the UI must not claim SDK-side
  creation time. `lastAccessTime` (2.0.1+) is the SDK-side signal that anchors
  true staleness on that line.
- `streamState`: `never-connected` when `listeningStreamRef` holds a
  `MissingMcpTransportSession` (the classic #6860 leak: the client sent
  initialize and died before opening the SSE stream), `has-stream` when it
  holds `McpStreamableServerSessionStream`, `unknown` when reflection or a
  future SDK change breaks the detection. Stream closure after opening is not
  observable from a field (the SDK has no closed flag on the stream); detect it
  with the probe and, on 2.0.1+, a stale `lastAccessTime`.
- `lastKeepAliveResult`: the probe sends a custom notification
  (`notifications/mcp-inspector/probe`, clients ignore unknown notifications
  per MCP spec) through the public `sendNotification`. `unavailable` means the
  probe threw reflectively or the session object is of an unexpected type.
  Probing is configurable, default on, interval shared with the poll interval.

## Degraded mode (decision)

`registryExposed=false` covers: no known provider bean in the context (host
runs another transport or disables the inspector's scope), FQCN mismatch
(future SDK rename), reflection failure, and the type-fallback guard below.
The UI then shows a live count of `-` (not 0, to avoid implying an empty
registry), a `registry not exposed` badge, and this hint:

> Server sessions are not available in this JVM. The inspector did not find
> MCP server transport provider beans (spring-ai-starter-mcp-server-webmvc or
> -webflux, Streamable HTTP), or reading the session registry failed. See
> inspector issue #369 and [spring-ai#6860](https://github.com/spring-projects/spring-ai/issues/6860).

Partial degradation: if at least one provider bean is found and read, the
registry is exposed with the sessions from the beans that worked; per-bean
failures are logged (throttled) and add to `degradedReason` without flipping
the flag, unless every bean failed.

## Reflection risk assessment

- **Package renames between lines**: proven (1.1.x
  `io.modelcontextprotocol.server.transport` vs 2.0.x
  `org.springframework.ai.mcp.server.*.transport`). The collector must try the
  FQCN list, not one FQCN. `McpStreamableServerSession` stayed in
  `io.modelcontextprotocol.spec` across both lines, but the collector should
  resolve it through the session object's own class, not a hard-coded FQCN.
- **Field rename (`sessions`)**: name is stable across both lines today; if a
  future major renames it, the collector degrades (`registryExposed=false`)
  instead of guessing field names.
- **Module system**: safe while the SDK jars stay automatic modules / classpath.
  If Spring AI ships `module-info` for transports, `setAccessible` from the
  inspector module would throw `InaccessibleObjectException`; fail-open keeps
  the inspector alive, and the degraded hint tells the user why. Risk is low
  for 2.3.x (2.0.1 has no module descriptors) and must be re-checked on each
  Spring AI major bump.
- **Structural drift (2.0.1 idle eviction, future additions)**: collectors read
  additive fields defensively (`sessionLastAccessTimes` guarded by
  NoSuchFieldException) so newer and older SDK versions share one code path.
- **Mutating the SDK state**: the PoC proves the collector can also write to
  the map. The collector must never do that: read-only access, insertions only
  happen through the SDK's own HTTP handlers.
