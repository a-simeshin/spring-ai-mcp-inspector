# ADR 0001: Manual `ToolCallback` registration for the `slowEcho` demo tool

- Status: Accepted
- Date: 2026-09-02
- Release lines: `develop/2.x`, `develop/1.x` (this ADR lives on both; the line
  specific paragraphs are marked below)
- Tracking: issue
  [#81](https://github.com/a-simeshin/spring-ai-mcp-inspector/issues/81),
  PR [#76](https://github.com/a-simeshin/spring-ai-mcp-inspector/pull/76) (2.x,
  merged 2026-08-26), PR [#96](https://github.com/a-simeshin/spring-ai-mcp-inspector/pull/96)
  (1.x, merged 2026-08-28)

## Context

The demo server is the fixture for the inspector UI "honest hints" path
(issue #57): when a tool entry in the MCP `tools/list` response carries no
`annotations` field at all, the UI must render muted "(default)" chips with the
"Spec default, not declared by server" tooltip instead of presenting spec
defaults as if the server had declared them.

That fixture is unreachable through annotation-based registration. The Spring AI
MCP annotation scanner always synthesizes a fully populated `annotations` object
(the MCP spec defaults, e.g. `readOnlyHint=false, destructiveHint=true`) for
`@McpTool` methods that do not declare annotations explicitly. As a result every
`@McpTool`-registered tool advertises an `annotations` object on the wire and
the "absent annotations" rendering branch of the inspector UI can never be
exercised end to end against a real server.

Upstream verification at the time this ADR was written (see "Upstream tracking"
below): `spring-ai` main still defaults `@McpTool.annotations` to a fully
populated `@McpAnnotations` instance (`McpTool.java`), while a raw
`ToolCallback` is converted by the MCP server auto-configuration from a
`ToolDefinition` via `McpToolUtils`, which carries no annotations, so the SDK
serializer (Jackson with non-absent inclusion) omits the `annotations` field
from the wire entry entirely.

## Decision

Register the `slowEcho` demo tool as a hand-written `ToolCallback` bean in
`SlowEchoToolConfiguration`, deliberately NOT as an `@McpTool` method. The bean
builds a plain `ToolDefinition` (name `slowEcho`, description "Echo text after
a ~2 second delay", JSON input schema mirroring the historical `text`
parameter) and implements `call(String)` with the same behavior the
annotation-based version had, including the sleep-based contract used by the
concurrency and task ITs (missing or unparseable `text` returns `null`, exactly
like the historical unbound `@McpToolParam`).

Locations of the workaround and its rationale Javadoc:

- 2.x: `spring-ai-mcp-inspector-demo-app/src/main/java/io/inspector/mcp/demo/tools/SlowEchoToolConfiguration.java`,
  Javadoc lines 30-39, bean method lines 60-94.
- 1.x: `spring-ai-mcp-inspector-demo-app/src/main/java/io/inspector/mcp/demo/tools/SlowEchoToolConfiguration.java`,
  Javadoc lines 31-41, bean method lines 62-95.

All other demo tools keep `@McpTool` registration with explicitly declared
`@McpAnnotations`, so the demo still covers the "declared annotations" rendering
branches on both lines.

## Consequences

- Positive: `tools/list` for the demo server contains exactly one entry without
  an `annotations` field (`slowEcho`), so the honest-hints UI path is
  demonstrable end to end and pinned by `ToolsListContractIT` on both release
  lines.
- Negative: `slowEcho` drifts from the annotation-based registration style used
  by the other 21 demo tools; behavior parity (input schema, description,
  call semantics) is maintained by hand and verified by the contract ITs.
- Negative: the input schema JSON is duplicated as a text block instead of being
  generated from method parameters; the historical reason (parameter binding
  required the `-parameters` compile flag) is preserved in a code comment.
- Follow-up (out of scope for this ADR change): add a link to this ADR from the
  `SlowEchoToolConfiguration` Javadoc on both lines. Any `.java` edit,
  including a Javadoc-only link, is a separate decision and change.

## Upstream tracking

There is no dedicated upstream issue in `spring-projects/spring-ai` covering
"allow `@McpTool` methods to omit the synthesized `annotations` object on the
wire" as of 2026-09-02. Searches performed on 2026-09-02 against
`spring-projects/spring-ai` (GitHub issue search):

- `ToolCallback annotations`
- `McpTool annotations` (issues and PRs)
- `McpAnnotations default`
- `omit annotations tool`

Closest related upstream items, none of which covers this behavior:

- [spring-ai#2234](https://github.com/spring-projects/spring-ai/issues/2234)
  "Add ToolCallbackResolver implementation that supports @Tool annotations"
  (open): client-side tool resolution, unrelated to server wire serialization.
- [spring-ai#4743](https://github.com/spring-projects/spring-ai/issues/4743)
  "add metadata in tool/list" (open): about adding fields to `tools/list`, not
  omitting `annotations`.
- [spring-ai#6801](https://github.com/spring-projects/spring-ai/issues/6801) and
  [spring-ai#6749](https://github.com/spring-projects/spring-ai/issues/6749)
  concern `McpResource.annotations` propagation, not `@McpTool` tools.

If a dedicated upstream issue or PR appears (or one is filed by this project),
replace this paragraph with its exact URL.

## Removal condition

Remove the manual registration and re-register `slowEcho` as an `@McpTool`
method when ALL of the following hold, verified on the same day on both release
lines:

1. A Spring AI release ships in which a `@McpTool` method without declared
   annotations produces a `tools/list` wire entry with the `annotations` field
   ABSENT (not a synthesized spec-default object). The release must be
   consumable as:
   - 2.x line: `spring-ai-bom` version `> 2.0.0` containing the fix;
   - 1.x line: `spring-ai-bom` version `> 1.1.8` containing the fix.
2. The fix and its shipped version are confirmed by the Spring AI release notes
   or by the closing entry of the upstream issue that introduced the fix (see
   "Upstream tracking"; if no upstream issue exists at removal time, confirm by
   inspecting `McpTool`/`McpToolUtils` in the released artifact).
3. Both release lines have been upgraded to such a version in their root
   `pom.xml` (`<spring-ai.version>`), and the verification procedure below
   passes on both lines WITHOUT `SlowEchoToolConfiguration`.

Until all three hold, the workaround stays.

## Verification after the upstream fix

Run on EACH release line after upgrading `spring-ai-bom` and deleting
`SlowEchoToolConfiguration.java` plus re-registering `slowEcho` as `@McpTool`:

1. Focused contract suite (2.x path shown; on 1.x the class lives at
   `spring-ai-mcp-inspector-demo-webmvc/src/test/java/io/inspector/mcp/demo/it/ToolsListContractIT.java`):

   ```
   ./mvnw -B -ntp verify -pl :spring-ai-mcp-inspector-demo-webmvc \
     -Dit.test=ToolsListContractIT -Dtest=none \
     -Dsurefire.failIfNoSpecifiedTests=false \
     -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
   ```

2. Wire-level expectations for `tools/list` through the inspector proxy:
   - exactly 22 tool entries (no duplicates of `slowEcho`, no missing tools);
   - `slowEcho` is present and its entry has NO `annotations` field (the
     fixture property this ADR exists to preserve);
   - the 17 read-only tools (`echo`, `sum`, `currentTime`, `addNumbers`,
     `concatenate`, `lookupUser`, `chooseColor`, `toggleFlag`,
     `optionalGreeting`, `errorTool`, `largeOutput`, `structuredOutput`,
     `multiContent`, `deepJson`, `blobAttachment`, `findFiles`, `listMyRoots`)
     still declare `readOnlyHint=true` and `destructiveHint=false`;
   - the 4 interactive tools (`askLlm`, `askUser`, `deployService`,
     `authorizeViaUrl`) still declare `readOnlyHint=false` and
     `destructiveHint=false`;
   - no entry declares `destructiveHint=true`;
   - every entry keeps the `name` / `description` / `inputSchema` shape.
3. UI equivalence: with the demo running, the Tools tab renders `slowEcho` with
   muted "(default)" chips and the "Spec default, not declared by server"
   tooltip, exactly as with the manual `ToolCallback` registration. Focused
   check on 2.x: `InspectorUiIT$Tools#connectListToolsHonestChips`.
4. Calling `slowEcho` with a `text` argument still echoes it after the ~2
   second delay, and calling it with empty arguments returns `null` (the
   sleep-based contract used by the task/concurrency ITs).

## Rollback procedure

If any verification step fails after the removal attempt:

1. Revert the removal commit on the affected release line
   (`git revert <removal-commit>`); this restores
   `SlowEchoToolConfiguration.java` and the manual bean.
2. Re-run the focused contract suite from step 1 of the verification procedure
   on the affected line; it must be green again with the manual registration
   restored.
3. Re-run the UI focused check (2.x: `InspectorUiIT$Tools#connectListToolsHonestChips`)
   to confirm the "(default)" chips rendering is back.
4. Record the failed upstream version in this ADR (amend "Removal condition"
   with "version X.Y.Z does NOT satisfy the condition, reason: ...") so the
   next attempt does not repeat the same upgrade blindly.
5. Repeat on the other release line if the removal was attempted there too;
   the lines are independent, a failed removal on one line does not force a
   rollback on the other.
