/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.proxy;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.inspector.mcp.demo.DemoApplication;

/**
 * Boots a fresh {@link DemoApplication} on a random port for the proxy-level integration
 * tests.
 *
 * <p>
 * These tests live in {@code demo-app} and are published in its test-jar, but they never
 * run here — this module has no web stack at all. {@code demo-webmvc} and
 * {@code demo-webflux} each pick them up through Failsafe's {@code dependenciesToScan},
 * so one copy of every test runs twice, once per stack, against a classpath that contains
 * exactly one server.
 *
 * <p>
 * That is why there is no {@code Stack} parameter and no
 * {@code spring.autoconfigure.exclude} list any more. Both existed to keep two stacks
 * apart inside a single module, and neither ever fully worked: with
 * {@code spring-boot-starter-web} on the classpath Boot's
 * {@code ReactiveWebServerFactoryAutoConfiguration} imports {@code EmbeddedTomcat} first,
 * so the reactive rows silently ran on Tomcat-reactive. Splitting the modules removes the
 * choice rather than fighting it.
 */
public final class ProxyAppHarness {

	/**
	 * Marker for the reactive inspector starter — present only in {@code demo-webflux}.
	 */
	private static final String WEBFLUX_MARKER = "io.inspector.mcp.webflux.McpInspectorWebFluxAutoConfiguration";

	private static final boolean REACTIVE = isPresent(WEBFLUX_MARKER);

	private ProxyAppHarness() {
	}

	/**
	 * Which stack this JVM is running, for assertion messages. Derived from the
	 * classpath, because that is now the only thing that decides it.
	 * @return {@code "webflux"} or {@code "webmvc"}
	 */
	public static String stack() {
		return REACTIVE ? "webflux" : "webmvc";
	}

	/**
	 * Boots the demo app on a random port, on whichever stack this module supplies.
	 * @param protocol one of {@code SSE}, {@code STREAMABLE}, {@code STATELESS}
	 * @param authEnabled whether to leave the proxy auth filter active
	 * @param authToken fixed token to use (only honored when {@code authEnabled}) —
	 * {@code null} lets the framework generate a random one
	 * @param extraArgs additional command-line args, appended last so they beat both
	 * {@code application.yml} and the build's system properties
	 * @return live context; caller is responsible for
	 * {@link ConfigurableApplicationContext#close()}
	 */
	public static ConfigurableApplicationContext start(String protocol, boolean authEnabled, String authToken,
			String... extraArgs) {
		// No web(...) call and no spring.main.web-application-type: Boot deduces the
		// application type from the classpath, and after the split the classpath holds
		// exactly one server. Forcing the type here would only restate what the module
		// already decided — and would go quietly stale if it ever disagreed.
		java.util.List<String> args = new java.util.ArrayList<>();
		args.add("--server.port=0");
		args.add("--spring.ai.mcp.server.protocol=" + protocol.toUpperCase());
		args.add("--spring.ai.mcp.inspector.auth-enabled=" + authEnabled);
		if (authToken != null) {
			args.add("--spring.ai.mcp.inspector.auth-token=" + authToken);
		}
		// Tests tear apps down abruptly (notably ProxyTargetLossIT kills the upstream
		// mid-session) and the proxy SSE streams are not completed on context close, so
		// a graceful shutdown waits out its whole phase. The root pom caps that phase at
		// 1s for every IT JVM; this drops in-flight connections outright, which is what
		// a disposable test context wants.
		args.add("--server.shutdown=immediate");
		java.util.Collections.addAll(args, extraArgs);

		return new SpringApplicationBuilder(DemoApplication.class).run(args.toArray(new String[0]));
	}

	/** Extracts the dynamically allocated server port. */
	public static int port(ConfigurableApplicationContext ctx) {
		return ((WebServerApplicationContext) ctx).getWebServer().getPort();
	}

	/** Composes the upstream-compatible MCP endpoint path for the given protocol. */
	static String mcpTargetPath(String protocol) {
		return switch (protocol.toUpperCase()) {
			case "SSE" -> "/sse"; // not used as a streamable target — kept for
									// completeness
			case "STREAMABLE", "STATELESS" -> "/mcp";
			default -> "/mcp";
		};
	}

	private static boolean isPresent(String className) {
		try {
			Class.forName(className, false, ProxyAppHarness.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException ex) {
			return false;
		}
	}

}
