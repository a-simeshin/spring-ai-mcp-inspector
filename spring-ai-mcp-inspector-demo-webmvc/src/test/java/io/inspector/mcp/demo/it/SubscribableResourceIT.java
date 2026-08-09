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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.SubscribeRequest;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import io.inspector.mcp.demo.DemoApplication;
import io.inspector.mcp.demo.resources.DemoSubscribableResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code notifications/resources/updated} server-to-client notification path
 * introduced by {@link io.inspector.mcp.demo.resources.DemoSubscribableResource}.
 *
 * <p>
 * <strong>SDK 2.0 subscription gating:</strong> the MCP Java SDK 2.0 wires the
 * {@code resources/subscribe} and {@code resources/unsubscribe} request handlers and
 * gates {@code notifyResourcesUpdated(...)} on the per-session subscription set — the
 * server only fans the notification out to sessions that have an active subscription for
 * the exact URI. The client must therefore call
 * {@link McpSyncClient#subscribeResource(SubscribeRequest)} for {@code demo://clock}
 * before it can observe any update; registering a {@code resourcesUpdateConsumer} alone
 * is not enough.
 *
 * <p>
 * This IT verifies the full round-trip: a client subscribes to {@code demo://clock} and
 * receives at least one update notification within the polling window. The scheduled tick
 * interval is dialed down to 1.5s via {@code demo.clock-tick-millis} so the test stays
 * under 8s without flake.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
		"spring.ai.mcp.inspector.auth-enabled=false", "demo.clock-tick-millis=1500" })
@Epic("MCP Resources")
@Feature("Subscribable resources")
class SubscribableResourceIT {

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("client receives at least one resources/updated notification within the polling window")
	@Story("resources/updated notifications")
	@Severity(SeverityLevel.NORMAL)
	@Description("Verifies the server-to-client notifications/resources/updated fanout: a client registering a "
			+ "resourcesUpdateConsumer receives at least one update notification for demo://clock within 8s.")
	void resourceUpdateNotifications_whenConsumerRegistered_clientReceivesAtLeastOne() {
		// given
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
			// when
			client.initialize();
			// SDK 2.0 fans notifications/resources/updated out only to sessions with an
			// active subscription for the exact URI, so subscribe before awaiting.
			client.subscribeResource(new SubscribeRequest(DemoSubscribableResource.CLOCK_URI));

			// then
			// Server emits notifications/resources/updated for demo://clock every
			// demo.clock-tick-millis (1.5s here); we should see at least one within 8s.
			Awaitility.await()
				.atMost(Duration.ofSeconds(8))
				.pollInterval(Duration.ofMillis(200))
				.untilAsserted(() -> assertThat(updateCount.get()).isGreaterThanOrEqualTo(1));
		}
	}

}
