/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.bootstrap;

import java.util.List;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;

/**
 * Builds an {@link InspectorBootstrap} payload from the framework's autodetected state
 * ({@link McpInspectorProperties}, {@link InspectorAuthTokenProvider},
 * {@link TransportDetector}) and then applies any user-supplied
 * {@link InspectorBootstrapCustomizer} beans in {@code @Order} sequence.
 *
 * <p>
 * Stateless and thread-safe — a single instance is registered as a Spring bean by both
 * the servlet and reactive auto-configurations.
 *
 * <p>
 * Spring auto-sorts the injected {@code List<InspectorBootstrapCustomizer>} by
 * {@link org.springframework.core.annotation.Order @Order}; no manual sort is performed
 * here. If no customizer beans are registered, Spring injects an empty list.
 */
public class InspectorBootstrapAssembler {

	private final McpInspectorProperties properties;

	private final InspectorAuthTokenProvider authTokenProvider;

	private final TransportDetector transportDetector;

	private final List<InspectorBootstrapCustomizer> customizers;

	public InspectorBootstrapAssembler(McpInspectorProperties properties, InspectorAuthTokenProvider authTokenProvider,
			TransportDetector transportDetector, List<InspectorBootstrapCustomizer> customizers) {
		this.properties = properties;
		this.authTokenProvider = authTokenProvider;
		this.transportDetector = transportDetector;
		this.customizers = (customizers != null) ? customizers : List.of();
	}

	/**
	 * Assembles a fresh {@link InspectorBootstrap}. Always returns a new instance — safe
	 * to call once per request.
	 * @return populated bootstrap, never {@code null}
	 */
	public InspectorBootstrap assemble() {
		InspectorBootstrap bootstrap = new InspectorBootstrap();
		bootstrap.setAuthToken(authTokenProvider.token());
		bootstrap.setProxyAddress(properties.getProxyPath());

		DetectedTransport detected = transportDetector.detect();
		bootstrap.setDetectedTransport(mapTransportName(detected.type()));
		bootstrap.setDetectedUrl(detected.endpoint() != null ? detected.endpoint() : "");

		for (InspectorBootstrapCustomizer customizer : customizers) {
			customizer.customize(bootstrap);
		}
		return bootstrap;
	}

	/**
	 * Maps the detected transport type into the lowercase identifier the upstream UI
	 * bundle expects. Kept in lockstep with the equivalent helper in the index
	 * controllers (see {@code InspectorIndexController#mapTransportName}).
	 */
	private static String mapTransportName(TransportType type) {
		if (type == null) {
			return "";
		}
		return switch (type) {
			case SSE -> "sse";
			case STREAMABLE, STATELESS -> "streamable-http";
			case STDIO_NO_HTTP -> "stdio";
			case UNKNOWN -> "";
		};
	}

}
