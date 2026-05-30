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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.inspector.mcp.demo.DemoApplication;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static io.inspector.mcp.demo.stress.StressTestSupport.WEBMVC_EXCLUDES;
import static io.inspector.mcp.demo.stress.StressTestSupport.quietClose;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress: invoking {@code largeOutput} emits a {@code notifications/message} (the
 * {@code demo.largeOutput} logger). The inspector's notifications panel relies on at
 * least one such frame arriving between the {@code tools/call} request and the final
 * response.
 *
 * <p>
 * Notifications are only forwarded if the client has called {@code logging/setLevel}; if
 * the SDK skips that handshake, the server drops them silently (see
 * {@code DemoAdvancedToolsProvider#largeOutput}). We build a dedicated
 * {@code McpSyncClient} here with a {@code loggingConsumer} wired up and explicitly call
 * {@link McpSyncClient#setLoggingLevel(LoggingLevel)} — the existing
 * {@link io.inspector.mcp.core.client.LoopbackMcpClientFactory} does not expose this
 * builder seam.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "spring.ai.mcp.server.protocol=STREAMABLE",
		"spring.ai.mcp.inspector.auth-enabled=false", "spring.autoconfigure.exclude=" + WEBMVC_EXCLUDES })
class LoggingNotificationsIT {

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	private CopyOnWriteArrayList<LoggingMessageNotification> received;

	@BeforeEach
	void connect() {
		received = new CopyOnWriteArrayList<>();
		McpClientTransport transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
			.endpoint("/mcp")
			.build();
		client = McpClient.sync(transport).loggingConsumer(received::add).build();
		client.initialize();
		// Tell the server we want INFO+ notifications. Without this the
		// server-side `loggingNotification(...)` is silently dropped.
		client.setLoggingLevel(LoggingLevel.INFO);
	}

	@AfterEach
	void disconnect() {
		quietClose(client);
	}

	@Test
	void largeOutputEmitsAtLeastOneLoggingNotification() {
		// Empty args — see BACKEND BUG in run report: the -parameters compile flag is
		// missing on the demo, so any JSON arg fails to bind. The largeOutput tool
		// tolerates a null `sizeKb` (defaults to 1024 KiB) and still emits the
		// `notifications/message` we are testing for. The 1 MiB default payload is
		// larger than necessary but does not affect this test's correctness.
		Map<String, Object> args = new LinkedHashMap<>();
		CallToolResult result = client.callTool(StressTestSupport.buildRequest("largeOutput", args));
		assertThat(Boolean.TRUE.equals(result.isError())).isFalse();

		Awaitility.await()
			.atMost(Duration.ofSeconds(60))
			.pollInterval(Duration.ofMillis(50))
			.untilAsserted(() -> assertThat(received).as("at least one notifications/message arrived for largeOutput")
				.isNotEmpty());

		// The demo emits with logger "demo.largeOutput" — assert that at least
		// one of the notifications is the one we expect.
		assertThat(received).anyMatch(n -> "demo.largeOutput".equals(n.logger()));
	}

}
