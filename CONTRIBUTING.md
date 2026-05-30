# Contributing to spring-ai-mcp-inspector

Thanks for your interest in improving this project. This guide covers how to
set up a local development environment, run the test suites, and submit
changes.

All contributors are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

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

## Submitting changes

1. Fork the repository and create a topic branch off `main`.
2. Make your change. Keep commits focused — one logical change per commit.
3. Add or update tests. New behavior needs at least one integration test that
   would have failed before the change.
4. Make sure `./mvnw verify` passes locally.
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
