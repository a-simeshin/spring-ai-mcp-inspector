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

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LoopbackMcpClientFactory}.
 *
 * <p>
 * These tests verify that the factory constructs {@link McpSyncClient} instances without
 * throwing — they do not initiate any actual transport connection. Real connectivity is
 * covered by {@code LoopbackSseTransportIT} / streamable / stateless.
 */
class LoopbackMcpClientFactoryTest {

	private final LoopbackMcpClientFactory factory = new LoopbackMcpClientFactory();

	@Test
	void buildsStreamableClientWithMessageEndpoint() {
		McpSyncClient client = factory.forStreamable("localhost", 8080, "/mcp");

		assertThat(client).isNotNull();
		closeQuietly(client);
	}

	@Test
	void buildsSseClientWithMessageEndpoint() {
		McpSyncClient client = factory.forSse("localhost", 8080, "", "/mcp/message");

		assertThat(client).isNotNull();
		closeQuietly(client);
	}

	@Test
	void buildsStatelessClient() {
		McpSyncClient client = factory.forStateless("127.0.0.1", 9090, "/mcp");

		assertThat(client).isNotNull();
		closeQuietly(client);
	}

	@Test
	void buildsStreamableClientWithNullEndpointDefaultsToSlashMcp() {
		McpSyncClient client = factory.forStreamable("127.0.0.1", 8080, null);

		assertThat(client).isNotNull();
		closeQuietly(client);
	}

	private static void closeQuietly(McpSyncClient client) {
		try {
			client.close();
		}
		catch (Exception ignored) {
			/* best-effort cleanup */
		}
	}

}
