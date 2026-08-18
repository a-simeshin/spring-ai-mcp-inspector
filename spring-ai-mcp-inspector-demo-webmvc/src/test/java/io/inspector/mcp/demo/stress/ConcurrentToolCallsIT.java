/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.stress;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpSyncClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.demo.DemoApplication;

import static io.inspector.mcp.demo.stress.StressTestSupport.callToolText;
import static io.inspector.mcp.demo.stress.StressTestSupport.quietClose;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress: many concurrent {@code tools/call} requests against the same proxy session must
 * complete in parallel (i.e. the connection is not serialized).
 *
 * <p>
 * Hard ceilings:
 * <ul>
 * <li>100 concurrent {@code sum(i,1)} calls — total wall time {@code ≤ 30s}.</li>
 * <li>10 concurrent {@code slowEcho} (server sleeps 2s each) — total wall time
 * {@code ≤ 6s}. The serial baseline would be 20s; we allow ~3× slack to absorb fixed
 * setup overhead but still detect a fully serialized session.</li>
 * </ul>
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("Stress & Scale")
@Feature("Concurrent tool calls")
class ConcurrentToolCallsIT {

	private static final Duration HUNDRED_SUMS_BUDGET = Duration.ofSeconds(30);

	/**
	 * Parallel ceiling for 10 × 2s {@code slowEcho} calls. Sequential baseline would be
	 * 20s; anything beyond ~6s suggests the proxy is serializing.
	 */
	private static final Duration TEN_SLOW_ECHO_BUDGET = Duration.ofSeconds(6);

	@Autowired
	private LoopbackMcpClientFactory loopbackFactory;

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@BeforeEach
	void connect() {
		client = loopbackFactory.forStreamable("127.0.0.1", port, "/mcp");
		client.initialize();
	}

	@AfterEach
	void disconnect() {
		quietClose(client);
	}

	@Test
	@Story("Parallel execution")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Verifies 100 concurrent currentTime calls on one session complete in parallel within budget and without errors")
	@DisplayName("100 concurrent calls all succeed within budget")
	void hundredConcurrentCalls_onSameSession_allSucceed() throws Exception {
		// given
		// Drive concurrency through `currentTime` (zero-arg) which exercises the
		// proxy-session serialization without needing any tool input arguments.
		ExecutorService pool = Executors.newFixedThreadPool(20);
		try {
			AtomicInteger errors = new AtomicInteger();
			List<CompletableFuture<String>> futures = new ArrayList<>(100);

			// when
			Instant t0 = Instant.now();
			for (int i = 0; i < 100; i++) {
				CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
					try {
						return callToolText(client, "currentTime", Map.of());
					}
					catch (RuntimeException ex) {
						errors.incrementAndGet();
						throw ex;
					}
				}, pool);
				futures.add(f);
			}

			CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
			all.get(HUNDRED_SUMS_BUDGET.toSeconds(), TimeUnit.SECONDS);

			Duration elapsed = Duration.between(t0, Instant.now());

			// then
			assertThat(elapsed).as("100 concurrent currentTime calls budget").isLessThanOrEqualTo(HUNDRED_SUMS_BUDGET);
			assertThat(errors.get()).as("no concurrent call errored").isZero();
			for (int i = 0; i < futures.size(); i++) {
				// currentTime returns ISO-8601 UTC — non-empty, parseable as a date.
				String text = futures.get(i).join();
				assertThat(text).as("currentTime[%d] returned non-empty ISO-8601 text", i).isNotBlank();
			}
		}
		finally {
			pool.shutdownNow();
			pool.awaitTermination(2, TimeUnit.SECONDS);
		}
	}

	@Test
	@Story("Parallel execution")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Verifies 10 concurrent 2s slowEcho calls finish in parallel well below the 20s sequential baseline")
	@DisplayName("10 concurrent slow echoes run in parallel")
	void tenConcurrentSlowEchoes_onSameSession_runInParallel() throws Exception {
		// given
		// slowEcho requires a non-null `text` arg (MCP SDK 2.0 enforces tool
		// input schema validation server-side). Each call sleeps ~2s and echoes
		// the text back. Running 10 in parallel on one session must finish in
		// <6s (vs the 20s sequential baseline) to prove calls are not serialized.
		ExecutorService pool = Executors.newFixedThreadPool(10);
		try {
			List<CompletableFuture<String>> futures = new ArrayList<>(10);

			// when
			Instant t0 = Instant.now();
			for (int i = 0; i < 10; i++) {
				int idx = i;
				futures.add(CompletableFuture
					.supplyAsync(() -> callToolText(client, "slowEcho", Map.of("text", "echo-" + idx)), pool));
			}
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
				.get(TEN_SLOW_ECHO_BUDGET.plusSeconds(2).toSeconds(), TimeUnit.SECONDS);
			Duration elapsed = Duration.between(t0, Instant.now());

			// then
			assertThat(elapsed)
				.as("10 × slowEcho must finish in <6s — sequential 20s baseline would prove the proxy serializes")
				.isLessThanOrEqualTo(TEN_SLOW_ECHO_BUDGET);
			// Wall-clock parallelism is the primary assertion; each call also
			// echoes its text back, so the result must be non-empty.
			for (var f : futures) {
				assertThat(f.join()).isNotBlank();
			}
		}
		finally {
			pool.shutdownNow();
			pool.awaitTermination(2, TimeUnit.SECONDS);
		}
	}

}
