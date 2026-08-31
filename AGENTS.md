# AGENTS.md

Project context for coding agents. Keep it short: it is read at the start of
every session, so it is a map, not a manual.

## What this is

A Spring AI MCP Inspector: a Java library plus starters that embed a Model
Context Protocol inspector UI into a Spring Boot application. Java 17 target,
Spring Boot 3.5, Spring AI 1.1, built with `./mvnw`.

## Module layout

| Module | Contains |
|---|---|
| `spring-ai-mcp-inspector-core` | Proxy, transports, timeline, shared model |
| `spring-ai-mcp-inspector-starter-webmvc` | Auto-configuration for the servlet stack |
| `spring-ai-mcp-inspector-starter-webflux` | Auto-configuration for the reactive stack |
| `spring-ai-mcp-inspector-ui` | Frontend, including vendored upstream client |
| `spring-ai-mcp-inspector-demo-webmvc` | Demo app and browser E2E for the servlet stack |
| `spring-ai-mcp-inspector-demo-webflux` | Demo app and browser E2E for the reactive stack |
| `spring-ai-mcp-inspector-demo-app` | Shared demo application code |

Both stacks are first-class. A contract implemented in one starter and
forgotten in the other is an incomplete change, and CI runs both.

## Branches

**There is no `main`.** The only pull request bases are:

- `develop/1.x`: this line, the maintained 1.x release line;
- `develop/2.x`: the default branch, the 2.x release line.

Targeting anything else is a mistake, and so is assuming the repository default
without checking. Before opening a pull request, look at where recent merges
went:

```bash
gh pr list --state merged --limit 15 --json baseRefName,title
```

**Twin pull requests.** A defect that exists on both lines needs a fix on both,
and an unfinished twin blocks the merge of its pair. Both pull requests link the
issue with `Refs #N` rather than `Closes #N`: closing an issue stays a human
decision, taken once the work has landed on every line that owes it.

## Build, test, gates

CI runs the gates in the `verify` phase. `install -DskipTests` runs none of
them, so "green locally" from that command means nothing.

```bash
# What the build job runs (JDK 17)
./mvnw -B -ntp verify \
  -pl '!:spring-ai-mcp-inspector-demo-webmvc,!:spring-ai-mcp-inspector-demo-webflux'

# Before pushing, at minimum, for the modules you touched
./mvnw -B -ntp verify -pl :<module> -am
```

The gates are spring-javaformat, Checkstyle, SpotBugs and JaCoCo coverage.

**Gates are closed with code, never weakened.** Do not lower a JaCoCo
threshold, widen `<excludes>`, add a suppression annotation, or edit gate
configuration to make a build pass. If a gate cannot be satisfied, say so in
the pull request instead of disabling it.

Infrastructure changes (`.github/**`, `config/**`, `pom.xml`, `mvnw*`) travel in
their own pull request, never bundled with a product change.

## CI jobs

| Job | What it proves |
|---|---|
| `build + test (JDK 17)` | Compiles, unit and integration tests, all quality gates |
| `demo E2E webmvc (JDK 17)` | Browser E2E against the servlet demo |
| `demo E2E webflux (JDK 17)` | Browser E2E against the reactive demo |
| `notice registry` | Every change to vendored UI code is registered |

A red run that finishes in twenty to thirty seconds is a build, style or
configuration failure: no test ran in it. Read the first failing job before
forming a theory about a test.

## Vendored UI code

`spring-ai-mcp-inspector-ui/upstream-client/` is a vendored copy of the upstream
inspector client. Two rules, only one of which CI can currently catch:

1. **Register the patch.** Add one file per patch under
   `upstream-client/NOTICE.d/` describing what it does. Do not append to a
   single shared list: parallel pull requests conflict on it, and renumbering
   during conflict resolution breaks references from the code. The
   `notice registry` job enforces this for changes under `upstream-client/src/`.
2. **Mark the patch in place.** Put a comment containing
   `[spring-ai-mcp-inspector PATCH]` at every local change. **No CI job checks
   this today.** It holds by convention, and it matters most exactly where
   nothing is watching: an unmarked change is invisible to whoever raises the
   upstream version next, and it disappears silently on re-vendor. Treat a
   missing marker as a defect even though the build stays green.

Editing inside an already-marked block needs no new marker: extend the comment
on the existing one.

Frontend commands run from `upstream-client/`: `npm test` (Jest),
`npm run lint`, `npm run build`.

## Pull requests

- **Language: English** for everything that lands in the repository: commit
  messages, pull request title and body, code comments, test names, issue and
  review comments.
- **Link the issue with a keyword**: `Refs #N`. A bare `#N` in prose renders as
  a cross-reference but is not read as work on that issue. The tooling matches
  `closes`, `fixes`, `resolves` and `refs` only. Prefer `Refs` over `Closes`
  whenever the change has a twin, so merging one line cannot close an issue the
  other line still owes.
- **Describe what changed for the user**, not only what changed in the code.
  For a UI change, attach before and after screenshots; for anything else, a
  worked example with its real output.
- **Explain every deleted test.** Removing a test removes a guarantee, and in a
  diff it looks like an ordinary deleted line. Check what you are dropping:

  ```bash
  git diff --diff-filter=D --name-only <base>...<branch>
  ```

- **A pull request is not done until CI is green on it**, and green means the
  run on the pull request, not a local run.

- **A review verdict lives in a comment, not in GitHub's review state.** Every
  pull request here is opened by the same GitHub App that reviews it, and
  GitHub refuses a review from the author of a pull request. `reviewDecision`
  is therefore always empty on this repository, and a pull request with no
  formal review is not an unreviewed one. Look for a top-level comment headed
  `## Review verdict`:

  ```bash
  # unreviewed pull requests, the only reliable way here
  for n in $(gh pr list --json number --jq '.[].number'); do
    gh pr view "$n" --json comments \
      --jq 'if any(.comments[]; .body | startswith("## Review verdict")) then empty else "'"$n"' unreviewed" end'
  done
  ```

  Reviewers: keep that exact heading, it is the only machine-readable marker
  the repository has. Anyone scanning by `reviewDecision` will duplicate the
  review that already exists.

## Conventions

- Java code follows spring-javaformat; Checkstyle enforces the rest.
- A test that does not run in CI is not a test.
- Integration tests are `**/*IT.java` (Failsafe); unit tests are
  `**/*Tests.java` (Surefire).
- In vendored frontend code, the surrounding upstream style wins over local
  preference: it keeps re-vendoring diffs readable.
