# Contributing to spring-ai-mcp-inspector

Thanks for your interest in improving this project. This guide covers how to
set up a local development environment, run the test suites, and submit
changes.

## Prerequisites

- JDK 17 or newer (JDK 21 verified)
- Maven Wrapper is bundled (`./mvnw`) — no separate Maven install required
- Node.js 20+ and npm — only needed when working on the bundled React UI; the
  `frontend-maven-plugin` will otherwise download a private Node toolchain
  during the `spring-ai-mcp-inspector-ui` build
- A modern Chromium browser if you plan to run the Selenide end-to-end tests

## Repository layout

```
spring-ai-mcp-inspector-core              # transport-agnostic core: properties, bootstrap, controllers
spring-ai-mcp-inspector-starter-webmvc    # Spring Boot starter (servlet stack)
spring-ai-mcp-inspector-starter-webflux   # Spring Boot starter (reactive stack)
spring-ai-mcp-inspector-ui                # Vendored upstream React/Vite bundle
spring-ai-mcp-inspector-demo              # Runnable demo application
```

## Building

```
./mvnw clean install
```

This builds all modules, runs unit and integration tests, and assembles the
demo as a runnable jar.

For a faster inner loop while iterating on Java code:

```
./mvnw -pl spring-ai-mcp-inspector-core -am install -DskipTests
```

## Running the tests

The project uses a three-layer test pyramid. Each layer can be run
independently.

### Unit tests (Surefire)

```
./mvnw test
```

Fast, no external dependencies. Live in `src/test/java/.../*Test.java`.

### Integration tests (Failsafe)

```
./mvnw verify
```

Boot a Spring application context against a random port and exercise the
full controller/router chain. Live in `src/test/java/.../it/*IT.java` in the
starter modules.

### End-to-end (Selenide)

```
./mvnw verify -pl spring-ai-mcp-inspector-demo
```

Runs the demo on a random port and drives a real Chromium browser through the
inspector UI. Live in `*E2ETest.java` files under the demo module.

### Code coverage — mandatory ≥ 80%

Every production module must keep **unit-test coverage at 80% or higher**. The
build enforces this automatically: a change that drops any module below the
threshold fails `./mvnw verify`.

- **Threshold:** ≥ **80%** for **LINE**, **BRANCH**, and **INSTRUCTION**.
- **Per module** (`<element>BUNDLE</element>`): `spring-ai-mcp-inspector-core`,
  `spring-ai-mcp-inspector-starter-webmvc`, `spring-ai-mcp-inspector-starter-webflux`.
  (`-demo` and `-ui` opt out via `jacoco.skip`.)
- **Unit tests only.** The gate reads `target/jacoco-unit.exec` (the Surefire
  run). Integration tests write a separate `jacoco-it.exec` that is **not**
  counted — coverage must be earned with real unit tests, not integration tests.
- **Enforced by** `jacoco:check` during `mvn verify`. Thresholds live in the
  root `pom.xml` (`jacoco.{line,branch,instruction}.minimum`) and must not be
  lowered.

Coverage is a stability contract, not a number to game — tests must assert real
behaviour (status codes, mapping, delegation, error branches, state), never
assert-free or tautological. A class that is genuinely pure glue (a static/HTML
serving handler, a constants-only class) may be excluded **pointwise** in the
root `pom.xml` JaCoCo `<excludes>` with a one-line justification; always prefer
a real test over an exclude.

Check coverage locally — open `target/site/jacoco/index.html` per module:

```
./mvnw -pl spring-ai-mcp-inspector-core test
./mvnw -pl spring-ai-mcp-inspector-starter-webmvc test
./mvnw -pl spring-ai-mcp-inspector-starter-webflux test
```

New tests follow `method_condition_expectedResult` naming with `// given` /
`// when` / `// then` comments, AssertJ assertions, and Allure annotations
(`@Epic`/`@Feature` on the class; `@Story`/`@Severity`/`@Description` per test).
Mirror the existing test classes.

## Running the demo manually

```
./mvnw -pl spring-ai-mcp-inspector-demo package -DskipTests
java -jar spring-ai-mcp-inspector-demo/target/spring-ai-mcp-inspector-demo-*-exec.jar \
    --spring.profiles.active=streamable
```

Available profiles: `stdio`, `sse`, `streamable`, `stateless`. Open
`http://localhost:8080/mcp-inspector/` after startup.

## Code style

Java code follows the
[`spring-javaformat`](https://github.com/spring-io/spring-javaformat) style. If
you have IntelliJ IDEA, install the plugin of the same name and let it format
on save.

To check formatting from the command line:

```
./mvnw spring-javaformat:validate
```

To auto-format:

```
./mvnw spring-javaformat:apply
```

### Checkstyle — classic Spring ruleset

On top of the formatter, the build runs **Checkstyle** with the classic Spring
ruleset (`io.spring.javaformat.checkstyle.SpringChecks` — the same one Spring
Boot uses), plus a few project rules. It is bound to the `validate` phase, so a
violation fails `./mvnw verify` before tests even run. Config lives in
`config/checkstyle/` (`checkstyle.xml` + `checkstyle-suppressions.xml`).

What it enforces, beyond the formatter:

- **`final` on locals and parameters** that are never reassigned
  (`FinalLocalVariable`). This is a project house rule.
- **Apache 2.0 license header** on every `.java` file and a `package-info.java`
  in every package.
- **Javadoc** on public types (`@author`), public fields, and complete
  `@param`/`@return`/`@throws` on documented methods.
- **BDDMockito** style in tests (`given(...).willReturn(...)`, not
  `when(...).thenReturn(...)`) and **AssertJ** assertions only.
- Spring conventions: import order, lambda/ternary parentheses, inner types
  last, etc.

Run it on its own:

```
./mvnw checkstyle:check                       # whole reactor, fails on violation
./mvnw -pl spring-ai-mcp-inspector-core checkstyle:check
./mvnw checkstyle:check -Dcheckstyle.failOnViolation=false   # list without failing
```

`-demo` and `-ui` opt out via `<checkstyle.skip>true</checkstyle.skip>`. Test
classes are named `*Tests.java` (Spring convention); integration tests are
`*IT.java`, end-to-end `*E2ETest.java`.

### SpotBugs — static bytecode analysis

The build also runs **SpotBugs** on the compiled production classes with
`effort=Max` and `threshold=Medium` — the Spring-convention balance that catches
real defects (null derefs, resource leaks, broken contracts) without
low-confidence noise. It is bound to the `verify` phase (it needs compiled
bytecode), so a finding fails `./mvnw verify`. The exclusion filter lives in
`config/spotbugs/spotbugs-exclude.xml`; keep it small and justify every entry.

`EI_EXPOSE_REP` / `EI_EXPOSE_REP2` are excluded project-wide: in DI code,
constructors store container-managed collaborators by reference and internal
DTO/config carriers return their collections by reference — both are deliberate,
so defensive copies would only add cost and noise.

Run it on its own:

```
./mvnw -pl spring-ai-mcp-inspector-core compile spotbugs:check   # analyze + fail
./mvnw -pl spring-ai-mcp-inspector-core spotbugs:gui             # browse findings
```

Tests are not scanned (`includeTests=false`); `-demo` and `-ui` opt out via
`<spotbugs.skip>true</spotbugs.skip>`.

## Submitting changes

1. Fork the repository and create a topic branch off `main`.
2. Make your change. Keep commits focused — one logical change per commit.
3. Add or update tests. New behavior needs at least one integration test that
   would have failed before the change, and unit-test coverage for the touched
   modules must stay **≥ 80%** (LINE/BRANCH/INSTRUCTION).
4. Make sure `./mvnw verify` passes locally — including the JaCoCo 80% gate,
   `spring-javaformat:validate`, the Checkstyle gate (classic Spring ruleset;
   `final` locals/params, Javadoc, BDDMockito/AssertJ), and the SpotBugs gate
   (`effort=Max`, `threshold=Medium`).
5. Open a pull request with a clear description of **what** changed and
   **why**. Link any related issues.

## Reporting bugs

Open a GitHub issue with:

- the version of `spring-ai-mcp-inspector`, Spring Boot, and Spring AI in use
- a minimal reproduction (a stripped-down project or `application.yml` snippet
  is ideal)
- the actual vs. expected behavior, including stack traces if available

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE).
