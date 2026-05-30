/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc;

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
import io.inspector.mcp.webmvc.controller.InspectorConfigController;
import io.inspector.mcp.webmvc.controller.InspectorIndexController;
import io.inspector.mcp.webmvc.controller.InspectorRestController;
import io.inspector.mcp.webmvc.filter.InspectorAuthFilter;
import io.inspector.mcp.webmvc.proxy.ProxyAuthFilter;
import io.inspector.mcp.webmvc.proxy.ProxyConfigController;
import io.inspector.mcp.webmvc.proxy.ProxyFetchController;
import io.inspector.mcp.webmvc.proxy.ProxyHealthController;
import io.inspector.mcp.webmvc.proxy.SseProxyController;
import io.inspector.mcp.webmvc.proxy.StreamableHttpProxyController;
import io.inspector.mcp.webmvc.sse.InspectorSseEmitterRegistry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Servlet-stack auto-configuration for the Spring AI MCP Inspector.
 *
 * <p>
 * Activates when:
 *
 * <ul>
 * <li>The application is a servlet web application, and</li>
 * <li>{@code spring.ai.mcp.inspector.enabled} is {@code true} (default).</li>
 * </ul>
 *
 * <p>
 * Registers all inspector beans, the {@code InspectorAuthFilter} (scoped to
 * {@code /mcp-inspector/api/*}), the static-resource handler for the SPA bundle, and CORS
 * rules driven by {@link McpInspectorProperties#getAllowedOrigins()}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "spring.ai.mcp.inspector", name = "enabled", havingValue = "true",
		matchIfMissing = true)
@EnableConfigurationProperties(McpInspectorProperties.class)
@Import({ InspectorRestController.class, InspectorIndexController.class, InspectorConfigController.class,
		SseProxyController.class, StreamableHttpProxyController.class, ProxyConfigController.class,
		ProxyHealthController.class, ProxyFetchController.class })
public class McpInspectorWebMvcAutoConfiguration implements WebMvcConfigurer {

	private final McpInspectorProperties properties;

	public McpInspectorWebMvcAutoConfiguration(McpInspectorProperties properties) {
		this.properties = properties;
	}

	@Bean
	@ConditionalOnMissingBean
	public TransportDetector mcpInspectorTransportDetector(Environment environment) {
		return new TransportDetector(environment);
	}

	@Bean
	@ConditionalOnMissingBean
	public LoopbackMcpClientFactory mcpInspectorLoopbackClientFactory() {
		return new LoopbackMcpClientFactory();
	}

	@Bean
	@ConditionalOnMissingBean
	public ExternalStdioClientFactory mcpInspectorExternalStdioClientFactory() {
		return new ExternalStdioClientFactory();
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorAuthTokenProvider mcpInspectorAuthTokenProvider() {
		return new InspectorAuthTokenProvider(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorBootstrapAssembler mcpInspectorBootstrapAssembler(InspectorAuthTokenProvider authTokenProvider,
			TransportDetector transportDetector, List<InspectorBootstrapCustomizer> customizers) {
		return new InspectorBootstrapAssembler(properties, authTokenProvider, transportDetector, customizers);
	}

	@Bean
	@ConditionalOnMissingBean
	public BootstrapHtmlRenderer mcpInspectorBootstrapHtmlRenderer(ObjectMapper objectMapper) {
		return new BootstrapHtmlRenderer(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorSseEmitterRegistry mcpInspectorSseEmitterRegistry() {
		return new InspectorSseEmitterRegistry();
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorServerPortHolder mcpInspectorServerPortHolder() {
		return new InspectorServerPortHolder();
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorOAuthClient mcpInspectorOAuthClient() {
		return new InspectorOAuthClient();
	}

	@Bean
	public FilterRegistrationBean<InspectorAuthFilter> mcpInspectorAuthFilterRegistration(
			InspectorAuthTokenProvider tokenProvider) {
		InspectorAuthFilter filter = new InspectorAuthFilter(properties, tokenProvider);
		FilterRegistrationBean<InspectorAuthFilter> registration = new FilterRegistrationBean<>(filter);
		registration.addUrlPatterns(properties.getPath() + "/api/*");
		registration.setName("mcpInspectorAuthFilter");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
		return registration;
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
	public FilterRegistrationBean<ProxyAuthFilter> mcpInspectorProxyAuthFilterRegistration(
			InspectorAuthTokenProvider tokenProvider) {
		ProxyAuthFilter filter = new ProxyAuthFilter(properties, tokenProvider);
		FilterRegistrationBean<ProxyAuthFilter> registration = new FilterRegistrationBean<>(filter);
		registration.addUrlPatterns(properties.getProxyPath() + "/*");
		registration.setName("mcpInspectorProxyAuthFilter");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 60);
		return registration;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler(properties.getPath() + "/**")
			.addResourceLocations("classpath:/mcp-inspector-bundle/")
			.setCachePeriod(0);
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		List<String> origins = properties.getAllowedOrigins();
		if (origins == null || origins.isEmpty()) {
			return;
		}
		registry.addMapping(properties.getPath() + "/api/**")
			.allowedOrigins(origins.toArray(String[]::new))
			.allowedMethods("GET", "POST", "DELETE", "OPTIONS")
			.allowedHeaders("*")
			.allowCredentials(true)
			.maxAge(3600);
		// The vendored upstream UI calls the proxy under a separate prefix —
		// expose CORS rules for it too so non-loopback hosts can connect.
		registry.addMapping(properties.getProxyPath() + "/**")
			.allowedOrigins(origins.toArray(String[]::new))
			.allowedMethods("GET", "POST", "DELETE", "OPTIONS")
			.allowedHeaders("*")
			.exposedHeaders("mcp-session-id", "WWW-Authenticate")
			.allowCredentials(true)
			.maxAge(3600);
	}

}
