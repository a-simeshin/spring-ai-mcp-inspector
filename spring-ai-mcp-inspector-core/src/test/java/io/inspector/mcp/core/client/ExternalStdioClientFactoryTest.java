/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.client;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ExternalStdioClientFactory}. */
class ExternalStdioClientFactoryTest {

	private final ExternalStdioClientFactory factory = new ExternalStdioClientFactory();

	@Test
	void buildsStdioClientForGivenCommand() {
		McpSyncClient client = factory.forCommand(List.of("java", "-version"), Map.of());

		assertThat(client).isNotNull();
		closeQuietly(client);
	}

	@Test
	void buildsStdioClientWithEnvironment() {
		McpSyncClient client = factory.forCommand(List.of("java", "-version"), Map.of("FOO", "bar"));

		assertThat(client).isNotNull();
		closeQuietly(client);
	}

	@Test
	void rejectsEmptyCommand() {
		assertThatThrownBy(() -> factory.forCommand(List.of(), Map.of())).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("command");
	}

	@Test
	void rejectsNullCommand() {
		assertThatThrownBy(() -> factory.forCommand(null, Map.of())).isInstanceOf(IllegalArgumentException.class);
	}

	private static void closeQuietly(McpSyncClient client) {
		try {
			client.close();
		}
		catch (Exception ignored) {
			/* best-effort */
		}
	}

}
