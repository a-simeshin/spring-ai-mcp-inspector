/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.it;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.inspector.mcp.demo.DemoApplication;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code notifications/resources/updated} server-to-client notification path
 * introduced by {@link io.inspector.mcp.demo.resources.DemoSubscribableResource}.
 *
 * <p>
 * <strong>SDK 0.18.2 limitation:</strong> the MCP Java SDK 0.18.2 does <em>not</em> wire
 * {@code resources/subscribe} and {@code resources/unsubscribe} request handlers in
 * {@code McpAsyncServer.prepareRequestHandlers()} — see the disassembled bytecode of
 * {@code McpAsyncServer.class}, where only {@code resources/list}, {@code resources/read}
 * and {@code resources/templates/list} are registered. A direct call to
 * {@link McpSyncClient#subscribeResource} would therefore fail with
 * {@code Method not found: resources/subscribe}. The server-side
 * {@code notifyResourcesUpdated(...)} fanout <em>is</em> implemented, however, and is
 * what powers the inspector UI's resource-update panel.
 *
 * <p>
 * This IT therefore verifies the achievable half of the round-trip: a client that
 * registers a {@code resourcesUpdateConsumer} receives at least one update notification
 * within the polling window. The scheduled tick interval is dialed down to 1.5s via
 * {@code demo.clock-tick-millis} so the test stays under 8s without flake.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
		"spring.ai.mcp.inspector.auth-enabled=false", "demo.clock-tick-millis=1500",
		"spring.autoconfigure.exclude="
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebFluxAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebFluxAutoConfiguration,"
				+ "io.inspector.mcp.webflux.McpInspectorWebFluxAutoConfiguration" })
class SubscribableResourceIT {

	@LocalServerPort
	private int port;

	@Test
	void clientReceivesResourceUpdateNotifications() {
		AtomicInteger updateCount = new AtomicInteger();

		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
			.builder("http://127.0.0.1:" + port)
			.endpoint("/mcp")
			.build();

		try (McpSyncClient client = McpClient.sync(transport)
			.requestTimeout(Duration.ofSeconds(10))
			.initializationTimeout(Duration.ofSeconds(10))
			.resourcesUpdateConsumer(updates -> updateCount.incrementAndGet())
			.build()) {
			client.initialize();
			// Server emits notifications/resources/updated for demo://clock every
			// demo.clock-tick-millis (1.5s here); we should see at least one within 8s.
			Awaitility.await()
				.atMost(Duration.ofSeconds(8))
				.pollInterval(Duration.ofMillis(200))
				.untilAsserted(() -> assertThat(updateCount.get()).isGreaterThanOrEqualTo(1));
		}
	}

}
