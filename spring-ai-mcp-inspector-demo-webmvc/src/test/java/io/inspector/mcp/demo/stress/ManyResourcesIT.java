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
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.demo.DemoApplication;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourceTemplatesResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceContents;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static io.inspector.mcp.demo.stress.StressTestSupport.quietClose;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress: resource listing/reading at scale — the demo exposes 6 static resources
 * (greeting, large-text, readme.md, config.json, logo.png, clock) + 30 templated entries
 * under {@code demo://item/{id}} (ids 0..29).
 *
 * <p>
 * Hard ceiling: 30 sequential reads complete in {@code ≤ 15s}.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("Stress & Scale")
@Feature("Many resources at scale")
class ManyResourcesIT {

	/** Matches {@code STATIC_ITEM_COUNT} in {@code DemoAdvancedResourcesProvider}. */
	private static final int TEMPLATED_ITEM_COUNT = 30;

	private static final int STATIC_RESOURCE_COUNT = 6;

	private static final Duration THIRTY_READ_BUDGET = Duration.ofSeconds(15);

	private static final Duration FIFTY_CONCURRENT_BUDGET = Duration.ofSeconds(60);

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

	@Nested
	@DisplayName("listResources()")
	class ListResources {

		@Test
		@Story("Resource discovery")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Verifies all 6 static resources and both templates are advertised and every templated id resolves")
		@DisplayName("list returns all static and templated items")
		void list_atScale_returnsAllStaticAndTemplatedItems() {
			// given & when
			ListResourcesResult resources = client.listResources();
			ListResourceTemplatesResult templates = client.listResourceTemplates();

			// then
			assertThat(resources.resources()).as("6 static resources are advertised").hasSize(STATIC_RESOURCE_COUNT);
			// Templates are returned as a separate list — the inspector renders them
			// in their own section. The demo provider declares two: demo://item/{id}
			// and demo://users/{id}. We exercise demo://item below.
			assertThat(templates.resourceTemplates()).extracting(t -> t.uriTemplate())
				.contains("demo://item/{id}", "demo://users/{id}");

			// Bind 30 concrete URIs by walking 0..29 — proves the template returns
			// a fresh response for every id without any hidden cache cap.
			for (int id = 0; id < TEMPLATED_ITEM_COUNT; id++) {
				ReadResourceResult one = client.readResource(new ReadResourceRequest("demo://item/" + id));
				assertItemPayload(one, id);
			}
		}

	}

	@Nested
	@DisplayName("readResource()")
	class ReadResource {

		@Test
		@Story("Sequential reads")
		@Severity(SeverityLevel.NORMAL)
		@Description("Verifies 30 sequential templated resource reads complete within the time budget")
		@DisplayName("30 sequential reads complete within budget")
		void thirtySequentialReads_atScale_completeWithinBudget() {
			// given & when
			Instant t0 = Instant.now();
			for (int id = 0; id < TEMPLATED_ITEM_COUNT; id++) {
				ReadResourceResult one = client.readResource(new ReadResourceRequest("demo://item/" + id));
				assertItemPayload(one, id);
			}
			Duration elapsed = Duration.between(t0, Instant.now());

			// then
			assertThat(elapsed).as("30 sequential resources/read budget").isLessThanOrEqualTo(THIRTY_READ_BUDGET);
		}

		@Test
		@Story("Concurrent reads")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Verifies 50 concurrent reads on the same session all succeed without errors or deadlock")
		@DisplayName("50 concurrent reads against same session all succeed")
		void fiftyConcurrentReads_onSameSession_allSucceed() throws Exception {
			// given
			Random rng = new Random(42L);
			ExecutorService pool = Executors.newFixedThreadPool(20);
			try {
				AtomicInteger errors = new AtomicInteger();
				List<CompletableFuture<ReadResourceResult>> futures = new java.util.ArrayList<>();

				// when
				for (int i = 0; i < 50; i++) {
					int id = rng.nextInt(TEMPLATED_ITEM_COUNT);
					CompletableFuture<ReadResourceResult> f = CompletableFuture.supplyAsync(() -> {
						try {
							return client.readResource(new ReadResourceRequest("demo://item/" + id));
						}
						catch (RuntimeException ex) {
							errors.incrementAndGet();
							throw ex;
						}
					}, pool);
					futures.add(f);
				}

				CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
				// Joining gives us a clean assertion if proxy / SDK deadlocks: it'll
				// time out via the JUnit-level wall clock anyway, but the explicit
				// get(...) below trips first with a descriptive message.
				all.get(FIFTY_CONCURRENT_BUDGET.toSeconds(), TimeUnit.SECONDS);

				// then
				assertThat(errors.get()).as("none of the 50 reads errored").isZero();
				assertThat(futures).allSatisfy(f -> {
					assertThat(f).isCompleted();
					ReadResourceResult r = f.join();
					assertThat(r.contents()).isNotEmpty();
				});
			}
			finally {
				pool.shutdownNow();
				// Best-effort drain — JUnit will still kill the JVM if anything escapes.
				pool.awaitTermination(2, TimeUnit.SECONDS);
			}
		}

	}

	private static void assertItemPayload(ReadResourceResult r, int id) {
		List<ResourceContents> contents = r.contents();
		assertThat(contents).as("read returned at least one content fragment").isNotEmpty();
		ResourceContents first = contents.get(0);
		assertThat(first).isInstanceOf(TextResourceContents.class);
		assertThat(((TextResourceContents) first).text()).as("templated item content matches the demo provider")
			.isEqualTo("Item " + id);
	}

}
