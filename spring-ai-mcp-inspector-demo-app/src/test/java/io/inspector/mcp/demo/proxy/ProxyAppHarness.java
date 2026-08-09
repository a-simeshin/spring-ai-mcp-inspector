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

import java.net.http.HttpClient;
import java.time.Duration;

import io.inspector.mcp.demo.DemoApplication;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

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
 * so the reactive rows silently ran on Tomcat-reactive; pinning reactor-netty instead
 * disposed loop resources that later contexts in the same JVM still needed, and hung the
 * suite on CI (issue 35). Splitting the modules removes the choice rather than fighting
 * it.
 */
final class ProxyAppHarness {

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
	static String stack() {
		return REACTIVE ? "webflux" : "webmvc";
	}

	/**
	 * A client pinned to HTTP/1.1, which every IT here should use.
	 *
	 * <p>
	 * {@link HttpClient} negotiates HTTP/2 by default and remembers the outcome per
	 * {@code host:port}. The harness boots on random ports and the OS recycles them, so a
	 * port that once answered as Tomcat (which speaks h2c) can come back as Netty (which
	 * does not) and the cached decision produces a bare {@code 400} — observed once in
	 * seven runs, on {@code POST /api/connect}, before the request reached any handler.
	 * These tests exercise SSE over HTTP/1.1 and have no reason to negotiate anything.
	 * @param connectTimeout how long to wait for the TCP connect
	 * @return a fresh client; callers hold it in a {@code static final} field
	 */
	static HttpClient httpClient(final Duration connectTimeout) {
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
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
	static ConfigurableApplicationContext start(String protocol, boolean authEnabled, String authToken,
			String... extraArgs) {
		WebApplicationType type = REACTIVE ? WebApplicationType.REACTIVE : WebApplicationType.SERVLET;

		java.util.List<String> args = new java.util.ArrayList<>();
		args.add("--server.port=0");
		args.add("--spring.main.web-application-type=" + (REACTIVE ? "reactive" : "servlet"));
		args.add("--spring.ai.mcp.server.protocol=" + protocol.toUpperCase());
		args.add("--spring.ai.mcp.inspector.auth-enabled=" + authEnabled);
		if (authToken != null) {
			args.add("--spring.ai.mcp.inspector.auth-token=" + authToken);
		}
		java.util.Collections.addAll(args, extraArgs);

		return new SpringApplicationBuilder(DemoApplication.class).web(type).run(args.toArray(new String[0]));
	}

	/** Extracts the dynamically allocated server port. */
	static int port(ConfigurableApplicationContext ctx) {
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
