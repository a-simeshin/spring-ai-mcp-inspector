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
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Enables {@code resources.subscribe = true} on the auto-configured MCP server
 * <em>and</em> preserves the Spring AI autoconfig's servlet-stack-only
 * {@code immediateExecution(true)} tuning.
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
 * <strong>Why servlet-only (issue #377):</strong> on the reactive stack
 * {@code immediateExecution(true)} suppresses the SDK's offload of sync tool bodies to
 * {@code boundedElastic}, so tools run on the Netty event loop that also drives the
 * transport. The blocking {@code createElicitation} call inside {@code askUser} is then
 * rejected by Reactor and {@code tools/call} settles with an error before the user can
 * answer, while {@code slowEcho}'s sleep caps concurrent overlap at the loop count. The
 * autoconfig itself gates this tuning on the servlet web application type. The customizer
 * bean exists on both stacks (it carries the subscribe capability), so the gate lives
 * inside {@code customize} rather than on a bean condition: this module compiles against
 * plain {@code spring-boot-starter} (no {@code spring-web}, where the stateless
 * autoconfig's {@code StandardServletEnvironment} lives), so the check mirrors the
 * servlet application type via Boot's {@code ServletWebServerApplicationContext} instead,
 * which resolves {@code true} only on the servlet stack and is always on the classpath.
 *
 * <p>
 * The capabilities builder is overridden directly because the SDK's
 * {@code McpServer.SyncSpecification#capabilities(ServerCapabilities)} call is what
 * actually wires up the subscribe handlers internally.
 */
@Component
public class DemoResourceSubscribeCapabilityCustomizer implements McpSyncServerCustomizer {

	/**
	 * Servlet web-server application context of the running Boot version. Referenced by
	 * name because this module compiles against plain {@code spring-boot-starter} (its
	 * class lives in {@code spring-boot-web-server}, present at runtime on both demo
	 * stacks but never on this module's compile classpath).
	 */
	private static final String SERVLET_APP_CONTEXT_CLASS_NAME = "org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext";

	private final ApplicationContext applicationContext;

	public DemoResourceSubscribeCapabilityCustomizer(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

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
		// selected as @Primary. Servlet-only, exactly like the autoconfig itself:
		// on the reactive stack immediate execution would run tool bodies on the
		// Netty event loops (issue #377).
		if (runningOnServletStack(this.applicationContext)) {
			spec.immediateExecution(true);
		}
	}

	/**
	 * Returns {@code true} when the application runs a servlet web server. Mirrors the
	 * servlet-only gate the autoconfig applies to {@code immediateExecution} (issue
	 * #377): on the reactive stack immediate execution would run sync tool bodies on the
	 * Netty event loops instead of the SDK's boundedElastic offload.
	 * @param applicationContext the running application context
	 * @return whether the application context is a servlet web-server one
	 */
	private static boolean runningOnServletStack(ApplicationContext applicationContext) {
		try {
			return Class
				.forName(SERVLET_APP_CONTEXT_CLASS_NAME, false,
						DemoResourceSubscribeCapabilityCustomizer.class.getClassLoader())
				.isInstance(applicationContext);
		}
		catch (ClassNotFoundException ex) {
			// No servlet web-server module on the classpath: not a servlet stack.
			return false;
		}
	}

}
