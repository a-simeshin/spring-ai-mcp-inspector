/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.netty;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.client.ReactorResourceFactory;

/**
 * Gives every Spring context booted in this module its own Reactor Netty event loops and
 * connection pool instead of the JVM-wide ones.
 *
 * <p>
 * Spring Boot's autoconfigured {@code reactorServerResourceFactory} defaults to
 * {@code useGlobalResources = true}, and its {@code destroy()} then calls
 * {@code HttpResources.disposeLoopsAndConnections()}. In production that is exactly right
 * — there is one context per JVM and the shutdown is the JVM's. In these tests it is not:
 * several ITs boot two apps at once (an inspector and the target it proxies to), and
 * closing either one tore down the loops the other was still serving on. The survivor
 * kept logging as if it were healthy while its listener no longer accepted anything, so
 * the next request failed with a bare {@code ConnectException} pointing at a server the
 * log said was up — see {@code ProxyTargetLossIT}, which kills its target mid-session on
 * purpose.
 *
 * <p>
 * This was invisible while the demo carried both web stacks: with
 * {@code spring-boot-starter-web} on the classpath Boot booted Tomcat-reactive, so no
 * Reactor Netty resources existed to share. Splitting the demo per stack is what surfaced
 * it.
 *
 * <p>
 * Registered for every {@code SpringApplication} in this module through
 * {@code META-INF/spring.factories}, so it covers both the harness-booted contexts and
 * the {@code @SpringBootTest} ones. It must apply to ALL of them: a context left on the
 * global resources would still dispose them for everybody.
 */
public class PerContextReactorResourcesInitializer implements ApplicationContextInitializer<GenericApplicationContext> {

	/**
	 * The bean name Spring Boot's own definition uses. Matching it keeps the
	 * {@code @ConditionalOnMissingBean} in {@code ReactorNettyConfigurations} backing off
	 * cleanly, and keeps the server and the WebClient connector sharing one factory the
	 * way they normally do.
	 */
	private static final String BEAN_NAME = "reactorServerResourceFactory";

	@Override
	public void initialize(GenericApplicationContext applicationContext) {
		applicationContext.registerBean(BEAN_NAME, ReactorResourceFactory.class, () -> {
			ReactorResourceFactory factory = new ReactorResourceFactory();
			factory.setUseGlobalResources(false);
			return factory;
		});
	}

}
