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
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorResourceFactory;

/**
 * Boots a fresh {@link DemoApplication} on a random port for the proxy-level integration
 * tests under {@code io.inspector.mcp.demo.proxy}.
 *
 * <p>
 * Two responsibilities:
 *
 * <ul>
 * <li>Force exactly one web stack ({@code webmvc} or {@code webflux}) by excluding the
 * autoconfig classes of the opposite stack. With both starters on the test classpath (see
 * the demo {@code pom.xml}), without these exclusions the boot would otherwise see both
 * {@code spring-ai-starter-mcp-server-webmvc} and {@code -webflux} and fail to start a
 * coherent transport.</li>
 * <li>Pass the MCP protocol selection ({@code SSE} / {@code STREAMABLE} /
 * {@code STATELESS}) as a {@code --spring.ai.mcp.server.protocol} argument so it beats
 * {@code application.yml}.</li>
 * </ul>
 *
 * <p>
 * Pattern mirrors {@code InspectorE2ETest#startApp} verbatim — the e2e suite has been
 * hardened against random-port collisions and dual-stack classpath over six combos, so we
 * reuse the proven approach instead of inventing a new one.
 */
final class ProxyAppHarness {

	/** Web stack to bind. */
	enum Stack {

		WEBMVC, WEBFLUX

	}

	private ProxyAppHarness() {
	}

	/**
	 * A client pinned to HTTP/1.1, which every IT in this package should use.
	 *
	 * <p>
	 * {@link HttpClient} negotiates HTTP/2 by default and remembers the outcome per
	 * {@code host:port}. The harness boots on random ports and the OS recycles them, so a
	 * port that once answered as Tomcat (which speaks h2c) can come back as Netty (which
	 * does not) and the cached decision produces a bare {@code 400} — or, with the
	 * connection wedged mid-upgrade, a request that simply never returns. Both were
	 * observed only after {@link ForceNetty} started binding the {@code WEBFLUX} rows to
	 * reactor-netty for real: a {@code 400} on {@code POST /api/connect} locally, and a
	 * 62-second {@code HttpTimeoutException} in {@code ProxySessionLifecycleIT} on CI.
	 *
	 * <p>
	 * These tests exercise SSE over HTTP/1.1 and have no reason to negotiate anything.
	 * @param connectTimeout how long to wait for the TCP connect
	 * @return a fresh client; callers hold it in a {@code static final} field
	 */
	static HttpClient httpClient(final Duration connectTimeout) {
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
	}

	/**
	 * Boots the demo app on a random port.
	 * @param stack which servlet/reactive stack to bind
	 * @param protocol one of {@code SSE}, {@code STREAMABLE}, {@code STATELESS}
	 * @param authEnabled whether to leave the proxy auth filter active
	 * @param authToken fixed token to use (only honored when {@code authEnabled}) —
	 * {@code null} lets the framework generate a random one
	 * @param extraArgs additional command-line args, appended last so they beat both
	 * {@code application.yml} and the build's system properties
	 * @return live context; caller is responsible for
	 * {@link ConfigurableApplicationContext#close()}
	 */
	static ConfigurableApplicationContext start(Stack stack, String protocol, boolean authEnabled, String authToken,
			String... extraArgs) {
		boolean reactive = stack == Stack.WEBFLUX;
		WebApplicationType type = reactive ? WebApplicationType.REACTIVE : WebApplicationType.SERVLET;

		String exclude;
		if (reactive) {
			exclude = String.join(",",
					"org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebMvcAutoConfiguration",
					"org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration",
					"org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebMvcAutoConfiguration",
					"io.inspector.mcp.webmvc.McpInspectorWebMvcAutoConfiguration");
		}
		else {
			exclude = String.join(",",
					"org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebFluxAutoConfiguration",
					"org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration",
					"org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebFluxAutoConfiguration",
					"io.inspector.mcp.webflux.McpInspectorWebFluxAutoConfiguration");
		}

		java.util.List<String> args = new java.util.ArrayList<>();
		args.add("--server.port=0");
		args.add("--spring.main.web-application-type=" + (reactive ? "reactive" : "servlet"));
		args.add("--spring.ai.mcp.server.protocol=" + protocol.toUpperCase());
		args.add("--spring.ai.mcp.inspector.auth-enabled=" + authEnabled);
		if (authToken != null) {
			args.add("--spring.ai.mcp.inspector.auth-token=" + authToken);
		}
		args.add("--spring.autoconfigure.exclude=" + exclude);
		java.util.Collections.addAll(args, extraArgs);

		SpringApplicationBuilder builder = new SpringApplicationBuilder(DemoApplication.class).web(type);
		if (reactive) {
			builder.sources(ForceNetty.class);
		}
		return builder.run(args.toArray(new String[0]));
	}

	/**
	 * Pins the reactive stack to reactor-netty.
	 *
	 * <p>
	 * Boot's {@code ReactiveWebServerFactoryAutoConfiguration} imports
	 * {@code EmbeddedTomcat} before {@code EmbeddedNetty}, and the default Maven profile
	 * puts {@code spring-boot-starter-web} (hence {@code tomcat-embed-core}) on the test
	 * classpath at compile scope. Without this bean every {@code WEBFLUX} row of these
	 * ITs silently boots {@code TomcatReactiveWebServerFactory} — verified: the log said
	 * "TomcatWebServer - Tomcat started" for all four {@link Stack} rows — so the
	 * reactive assertions proved nothing about the stack users actually run. A
	 * user-defined
	 * {@link org.springframework.boot.web.reactive.server.ReactiveWebServerFactory} wins
	 * because the autoconfigured ones are {@code @ConditionalOnMissingBean}.
	 *
	 * <p>
	 * The {@link ReactorResourceFactory} is not optional here. Boot's own
	 * {@code reactorServerResourceFactory} / {@code reactorClientResourceFactory} both
	 * default to reactor-netty's <em>global</em> {@code HttpResources}, and several ITs
	 * in this package boot two reactive contexts in one JVM ({@code ProxyTargetLossIT}
	 * runs an inspector app plus a target app). Closing either one then disposes the
	 * shared loops and the survivor's server stops accepting — observed as a bare
	 * {@code java.net.ConnectException} against a port that had just answered.
	 * Per-context resources keep the two apps independent. This is a harness concern
	 * only: a real deployment runs one context per JVM.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ForceNetty {

		@Bean
		ReactorResourceFactory reactorResourceFactory() {
			ReactorResourceFactory factory = new ReactorResourceFactory();
			factory.setUseGlobalResources(false);
			return factory;
		}

		@Bean
		NettyReactiveWebServerFactory nettyReactiveWebServerFactory(ReactorResourceFactory resourceFactory) {
			NettyReactiveWebServerFactory factory = new NettyReactiveWebServerFactory();
			factory.setResourceFactory(resourceFactory);
			return factory;
		}

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

}
