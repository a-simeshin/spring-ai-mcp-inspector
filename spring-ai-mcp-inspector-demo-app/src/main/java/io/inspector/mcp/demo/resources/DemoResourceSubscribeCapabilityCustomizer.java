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

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.stereotype.Component;

/**
 * Enables {@code resources.subscribe = true} on the auto-configured MCP server
 * <em>and</em> preserves the Spring AI autoconfig's default servlet-stack tuning
 * ({@code immediateExecution(true)}).
 *
 * <p>
 * <strong>Why this is a plain {@link McpSyncServerCustomizer} bean and not
 * {@code @Primary}:</strong> the Spring AI autoconfig declares the customizer dependency
 * as {@code Optional<McpSyncServerCustomizer>}, which resolves at most one bean. The
 * {@code @Primary} slot is owned by the composed customizer in
 * {@code MultiRoundTripEchoToolConfiguration}, which delegates here so this class keeps
 * doing exactly what its name says. The {@code immediateExecution(true)} bit below still
 * re-applies the servlet-stack default of the autoconfig's hidden
 * {@code servletMcpSyncServerCustomizer}, because that hidden customizer is not applied
 * when a different bean wins the {@code Optional} resolution.
 *
 * <p>
 * The capabilities builder is overridden directly because the SDK's
 * {@code McpServer.SyncSpecification#capabilities(ServerCapabilities)} call is what
 * actually wires up the subscribe handlers internally.
 */
@Component
public class DemoResourceSubscribeCapabilityCustomizer implements McpSyncServerCustomizer {

	@Override
	public void customize(McpServer.SyncSpecification<?> spec) {
		spec.capabilities(ServerCapabilities.builder()
			.tools(true)
			// resources(subscribe, listChanged) — the subscribe flag is the whole point
			// of this customizer; listChanged stays on to match the autoconfig default.
			.resources(true, true)
			.prompts(true)
			.logging()
			.completions()
			.build());
		// Preserve the default servlet-stack tuning that the autoconfig's hidden
		// `servletMcpSyncServerCustomizer` would have applied if we hadn't been
		// selected as @Primary.
		spec.immediateExecution(true);
	}

}
