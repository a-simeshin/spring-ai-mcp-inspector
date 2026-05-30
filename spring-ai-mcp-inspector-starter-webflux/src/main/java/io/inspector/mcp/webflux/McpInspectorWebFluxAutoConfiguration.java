/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.bootstrap.BootstrapHtmlRenderer;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapAssembler;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapCustomizer;
import io.inspector.mcp.core.client.ExternalStdioClientFactory;
import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.oauth.InspectorOAuthClient;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.webflux.filter.InspectorAuthWebFilter;
import io.inspector.mcp.webflux.proxy.ProxyAuthWebFilter;
import io.inspector.mcp.webflux.proxy.ProxyHandler;
import io.inspector.mcp.webflux.router.InspectorHandler;
import io.inspector.mcp.webflux.router.InspectorRouterConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Reactive (WebFlux) auto-configuration for the Spring AI MCP Inspector.
 *
 * <p>
 * Activated when the host application is a reactive Spring Boot web app and
 * {@code spring.ai.mcp.inspector.enabled} is unset or {@code true}. Registers:
 *
 * <ul>
 * <li>{@link TransportDetector}, {@link LoopbackMcpClientFactory},
 * {@link ExternalStdioClientFactory}, {@link InspectorAuthTokenProvider} — core
 * beans.</li>
 * <li>{@link InspectorHandler} — reactive HTTP handler facade for the inspector API.</li>
 * <li>{@link InspectorRouterConfig} — functional endpoint registrations.</li>
 * <li>{@link InspectorAuthWebFilter} — reactive bearer-token guard.</li>
 * <li>CORS mappings for inspector paths.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "spring.ai.mcp.inspector", name = "enabled", havingValue = "true",
		matchIfMissing = true)
@EnableConfigurationProperties(McpInspectorProperties.class)
@Import(InspectorRouterConfig.class)
public class McpInspectorWebFluxAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public TransportDetector mcpInspectorTransportDetector(Environment environment) {
		return new TransportDetector(environment);
	}

	@Bean
	@ConditionalOnMissingBean
	public LoopbackMcpClientFactory mcpInspectorLoopbackMcpClientFactory() {
		return new LoopbackMcpClientFactory();
	}

	@Bean
	@ConditionalOnMissingBean
	public ExternalStdioClientFactory mcpInspectorExternalStdioClientFactory(ObjectMapper objectMapper) {
		return new ExternalStdioClientFactory(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorAuthTokenProvider mcpInspectorAuthTokenProvider(McpInspectorProperties properties) {
		return new InspectorAuthTokenProvider(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorBootstrapAssembler mcpInspectorBootstrapAssembler(McpInspectorProperties properties,
			InspectorAuthTokenProvider authTokenProvider, TransportDetector transportDetector,
			List<InspectorBootstrapCustomizer> customizers) {
		return new InspectorBootstrapAssembler(properties, authTokenProvider, transportDetector, customizers);
	}

	@Bean
	@ConditionalOnMissingBean
	public BootstrapHtmlRenderer mcpInspectorBootstrapHtmlRenderer(ObjectMapper objectMapper) {
		return new BootstrapHtmlRenderer(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorOAuthClient mcpInspectorOAuthClient(ObjectMapper objectMapper) {
		return new InspectorOAuthClient(java.net.http.HttpClient.newHttpClient(), objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorHandler mcpInspectorHandler(TransportDetector transportDetector,
			LoopbackMcpClientFactory loopbackFactory, ExternalStdioClientFactory externalStdioFactory,
			InspectorAuthTokenProvider tokenProvider, ObjectMapper objectMapper, InspectorOAuthClient oauthClient,
			McpInspectorProperties properties, InspectorBootstrapAssembler bootstrapAssembler,
			BootstrapHtmlRenderer bootstrapHtmlRenderer) {
		return new InspectorHandler(transportDetector, loopbackFactory, externalStdioFactory, tokenProvider,
				objectMapper, oauthClient, properties, bootstrapAssembler, bootstrapHtmlRenderer);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorAuthWebFilter mcpInspectorAuthWebFilter(McpInspectorProperties properties,
			InspectorAuthTokenProvider tokenProvider) {
		return new InspectorAuthWebFilter(properties, tokenProvider);
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxySessionRegistry mcpInspectorProxySessionRegistry() {
		return new ProxySessionRegistry();
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxyTransportFactory mcpInspectorProxyTransportFactory(ObjectMapper objectMapper) {
		return new ProxyTransportFactory(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public McpProxy mcpInspectorMcpProxy(ObjectMapper objectMapper) {
		return new McpProxy(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxyHandler mcpInspectorProxyHandler(ProxySessionRegistry registry, ProxyTransportFactory transportFactory,
			McpProxy mcpProxy, TransportDetector transportDetector, ObjectMapper objectMapper,
			McpInspectorProperties properties) {
		return new ProxyHandler(registry, transportFactory, mcpProxy, transportDetector, objectMapper, properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxyAuthWebFilter mcpInspectorProxyAuthWebFilter(McpInspectorProperties properties,
			InspectorAuthTokenProvider tokenProvider) {
		return new ProxyAuthWebFilter(properties, tokenProvider);
	}

	@Bean
	public WebFluxConfigurer mcpInspectorCorsConfigurer(McpInspectorProperties properties) {
		return new WebFluxConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				if (properties.getAllowedOrigins() == null || properties.getAllowedOrigins().isEmpty()) {
					return;
				}
				registry.addMapping(properties.getPath() + "/**")
					.allowedOrigins(properties.getAllowedOrigins().toArray(new String[0]))
					.allowedMethods("GET", "POST", "DELETE")
					.allowedHeaders("*")
					.allowCredentials(false);
				registry.addMapping(properties.getProxyPath() + "/**")
					.allowedOrigins(properties.getAllowedOrigins().toArray(new String[0]))
					.allowedMethods("GET", "POST", "DELETE", "OPTIONS")
					.allowedHeaders("*")
					.exposedHeaders("mcp-session-id", "WWW-Authenticate")
					.allowCredentials(false);
			}
		};
	}

}
