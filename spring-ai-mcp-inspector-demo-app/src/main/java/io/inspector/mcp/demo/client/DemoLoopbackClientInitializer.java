/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.client;

import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Initializes the demo app's own outbound MCP client once the application is fully
 * started (issue #120).
 *
 * <p>
 * The demo client connects back to the app's own MCP server endpoint (loopback). Client
 * beans are created during context refresh, before the server port exists, so the demo
 * runs with {@code spring.ai.mcp.client.initialized=false} and this listener performs the
 * explicit {@link McpSyncClient#initialize()} on {@link ApplicationReadyEvent}: at that
 * point the endpoint is accepting requests.
 *
 * <p>
 * An initialization failure is logged and left to the inspector's connection registry to
 * surface (the registry keeps the {@code FAILED} state), it never fails the application
 * startup.
 */
@Component
public class DemoLoopbackClientInitializer implements ApplicationListener<ApplicationReadyEvent> {

	private static final Logger LOG = LoggerFactory.getLogger(DemoLoopbackClientInitializer.class);

	private final ObjectProvider<List<McpSyncClient>> syncClients;

	DemoLoopbackClientInitializer(ObjectProvider<List<McpSyncClient>> syncClients) {
		this.syncClients = syncClients;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		List<McpSyncClient> clients = this.syncClients.getIfAvailable();
		if (clients == null) {
			return;
		}
		for (McpSyncClient client : clients) {
			if (client.isInitialized()) {
				continue;
			}
			String name = displayName(client);
			try {
				InitializeResult result = client.initialize();
				LOG.info("Demo outbound MCP client '{}' initialized against its own server '{}'", name,
						result.serverInfo().name());
			}
			catch (RuntimeException ex) {
				LOG.warn("Demo outbound MCP client '{}' failed to initialize: {}", name, ex.getMessage());
			}
		}
	}

	private static String displayName(McpSyncClient client) {
		var info = client.getClientInfo();
		if (info != null && info.title() != null && !info.title().isBlank()) {
			return info.title();
		}
		return (info != null) ? info.name() : "mcp-client";
	}

}
