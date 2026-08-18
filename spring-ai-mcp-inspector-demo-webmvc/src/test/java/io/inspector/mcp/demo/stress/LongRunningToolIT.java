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
import java.util.Map;

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
 * Stress: long-running {@code slowEcho} tool (server sleeps 2s) must keep the proxy
 * connection alive — no idle-timeout truncation, and consecutive calls on the same
 * session must succeed.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("Stress & Scale")
@Feature("Long-running tool")
class LongRunningToolIT {

	/** {@code slowEcho} sleeps 2s server-side — anything below is a bug. */
	private static final Duration MIN_DURATION = Duration.ofMillis(1_800);

	/**
	 * Generous upper bound — protects against catastrophic slow-down or
	 * timeout-then-retry.
	 */
	private static final Duration MAX_DURATION = Duration.ofSeconds(15);

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
	@Story("Connection stays alive")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Verifies a 2s slowEcho keeps the proxy connection alive without idle-timeout truncation or retry")
	@DisplayName("slowEcho stays alive and returns a response")
	void slowEcho_whenServerSleeps_staysAliveAndReturns() {
		// given
		// `text` is a required @McpToolParam — MCP SDK 2.0 enforces input-schema
		// validation server-side, so it must be supplied. The tool sleeps 2s
		// server-side then echoes the text, which is the bit this test cares
		// about: connection-alive + no idle-timeout truncation.

		// when
		Instant t0 = Instant.now();
		String result = callToolText(client, "slowEcho", Map.of("text", "ping"));
		Duration elapsed = Duration.between(t0, Instant.now());

		// then
		assertThat(result).as("connection delivered a response").isNotNull();
		assertThat(elapsed).as("connection alive ≥ 2s").isGreaterThanOrEqualTo(MIN_DURATION);
		assertThat(elapsed).as("connection didn't time out / retry").isLessThanOrEqualTo(MAX_DURATION);
	}

	@Test
	@Story("Consecutive calls")
	@Severity(SeverityLevel.NORMAL)
	@Description("Verifies two consecutive slowEcho calls on the same session both return successfully")
	@DisplayName("two consecutive slow echoes both succeed")
	void twoConsecutiveSlowEchoes_onSameSession_bothSucceed() {
		// given & when
		String first = callToolText(client, "slowEcho", Map.of("text", "first"));
		String second = callToolText(client, "slowEcho", Map.of("text", "second"));

		// then
		// Proving both calls return on the same session is the load-bearing
		// assertion ("two consecutive slow-tool requests succeed").
		assertThat(first).as("first slowEcho returned").isNotNull();
		assertThat(second).as("second slowEcho returned").isNotNull();
	}

}
