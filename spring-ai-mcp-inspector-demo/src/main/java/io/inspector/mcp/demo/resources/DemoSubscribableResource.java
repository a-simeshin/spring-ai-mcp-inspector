/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.resources;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ResourcesUpdatedNotification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Subscribable resource that surfaces the inspector UI's {@code resources/subscribe} →
 * {@code notifications/resources/updated} path.
 *
 * <p>
 * Spring AI's MCP server starter registers {@code @McpResource}-annotated methods as
 * {@code SyncResourceSpecification}s and the SDK manages subscription bookkeeping
 * internally once
 * {@link io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.ResourceCapabilities#subscribe()}
 * is enabled — see
 * {@link io.inspector.mcp.demo.resources.DemoResourceSubscribeCapabilityCustomizer}.
 *
 * <p>
 * Every 5 seconds (after the {@link McpSyncServer} bean is ready) this class publishes a
 * {@link ResourcesUpdatedNotification} for {@code demo://clock}. The SDK fans the
 * notification out only to sessions that have an active subscription, so idle inspectors
 * do not receive noise.
 *
 * <p>
 * <strong>Why not annotation-only:</strong> SDK 0.18.2 does not expose a resource-level
 * {@code @McpResourceUpdated} annotation; the only programmatic surface to emit the
 * notification is
 * {@link McpSyncServer#notifyResourcesUpdated(ResourcesUpdatedNotification)}, which we
 * get by injecting the auto-configured server bean. The injection is lazy via
 * {@link ObjectProvider} so that bootstrap order with the autoconfig is non-fatal even if
 * the bean is not yet wired when this component is constructed.
 */
@Component
public class DemoSubscribableResource {

	/** URI of the subscribable resource — kept package-public for tests. */
	public static final String CLOCK_URI = "demo://clock";

	private static final Logger LOG = LoggerFactory.getLogger(DemoSubscribableResource.class);

	private final ObjectProvider<McpSyncServer> serverProvider;

	/**
	 * Cached resolution of the {@link McpSyncServer} bean (resolved lazily on first
	 * tick).
	 */
	private final AtomicReference<McpSyncServer> serverRef = new AtomicReference<>();

	public DemoSubscribableResource(ObjectProvider<McpSyncServer> serverProvider) {
		this.serverProvider = serverProvider;
	}

	/**
	 * Returns the current wall-clock instant as ISO-8601 text. Repeated reads yield
	 * different values, which is what makes {@code resources/subscribe} meaningful for
	 * this URI.
	 */
	@McpResource(uri = CLOCK_URI, name = "demo-clock",
			description = "Server wall-clock — emits notifications/resources/updated every 5s for subscribers",
			mimeType = "text/plain")
	public String clock() {
		return Instant.now().toString();
	}

	/**
	 * Periodic tick — fires every 5 seconds, starting 2 seconds after the scheduler is
	 * up. Emits a {@code resources/updated} notification keyed on {@link #CLOCK_URI}. If
	 * the server bean is not yet available (e.g. very early startup, or in test contexts
	 * that exclude the server starter), the tick is a silent no-op.
	 */
	@Scheduled(fixedDelayString = "${demo.clock-tick-millis:5000}", initialDelay = 2000)
	public void emitClockUpdate() {
		McpSyncServer server = serverRef.get();
		if (server == null) {
			server = serverProvider.getIfAvailable();
			if (server == null) {
				return;
			}
			serverRef.compareAndSet(null, server);
		}
		try {
			server.notifyResourcesUpdated(new ResourcesUpdatedNotification(CLOCK_URI));
		}
		catch (RuntimeException e) {
			LOG.debug("Skipping clock tick — notifyResourcesUpdated failed: {}", e.toString());
		}
	}

}
