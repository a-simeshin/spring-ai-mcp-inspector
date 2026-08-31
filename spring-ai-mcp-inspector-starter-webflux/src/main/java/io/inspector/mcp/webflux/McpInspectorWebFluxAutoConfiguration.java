/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.inspector.mcp.webflux;

import java.util.List;

import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import tools.jackson.databind.json.JsonMapper;

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
import io.inspector.mcp.core.shutdown.McpServerTransportDrain;
import io.inspector.mcp.core.timeline.McpTrafficRecorder;
import io.inspector.mcp.core.timeline.TimelineService;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.webflux.filter.InspectorAuthWebFilter;
import io.inspector.mcp.webflux.proxy.ProxyAuthWebFilter;
import io.inspector.mcp.webflux.proxy.ProxyHandler;
import io.inspector.mcp.webflux.router.InspectorHandler;
import io.inspector.mcp.webflux.router.InspectorRouterConfig;
import io.inspector.mcp.webflux.router.TimelineHandler;

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
 *
 * @author Artem Simeshin
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "spring.ai.mcp.inspector", name = "enabled", havingValue = "true",
		matchIfMissing = true)
@EnableConfigurationProperties(McpInspectorProperties.class)
@EnableScheduling
@Import(InspectorRouterConfig.class)
public class McpInspectorWebFluxAutoConfiguration {

	/**
	 * Jackson 2 {@link JsonMapper} backing the inspector's proxy relay, bootstrap
	 * rendering and MCP {@code json-jackson2} bridge.
	 *
	 * <p>
	 * Spring Boot 4 auto-configures a Jackson 3 mapper by default and no longer exposes a
	 * {@code tools.jackson.databind.json.JsonMapper} bean, so provide one for the
	 * inspector internals unless the application already defines its own.
	 * @return a Jackson 2 object mapper
	 */
	@Bean
	@ConditionalOnMissingBean
	public JsonMapper mcpInspectorObjectMapper() {
		return new JsonMapper();
	}

	@Bean
	@ConditionalOnMissingBean
	public TransportDetector mcpInspectorTransportDetector(final Environment environment) {
		return new TransportDetector(environment);
	}

	@Bean
	@ConditionalOnMissingBean
	public LoopbackMcpClientFactory mcpInspectorLoopbackMcpClientFactory() {
		return new LoopbackMcpClientFactory();
	}

	@Bean
	@ConditionalOnMissingBean
	public ExternalStdioClientFactory mcpInspectorExternalStdioClientFactory(final JsonMapper objectMapper) {
		return new ExternalStdioClientFactory(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorAuthTokenProvider mcpInspectorAuthTokenProvider(final McpInspectorProperties properties) {
		return new InspectorAuthTokenProvider(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorBootstrapAssembler mcpInspectorBootstrapAssembler(final McpInspectorProperties properties,
			final InspectorAuthTokenProvider authTokenProvider, final TransportDetector transportDetector,
			final List<InspectorBootstrapCustomizer> customizers) {
		return new InspectorBootstrapAssembler(properties, authTokenProvider, transportDetector, customizers);
	}

	@Bean
	@ConditionalOnMissingBean
	public BootstrapHtmlRenderer mcpInspectorBootstrapHtmlRenderer(final JsonMapper objectMapper) {
		return new BootstrapHtmlRenderer(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorOAuthClient mcpInspectorOAuthClient(final JsonMapper objectMapper) {
		return new InspectorOAuthClient(java.net.http.HttpClient.newHttpClient(), objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorHandler mcpInspectorHandler(final TransportDetector transportDetector,
			final LoopbackMcpClientFactory loopbackFactory, final ExternalStdioClientFactory externalStdioFactory,
			final InspectorAuthTokenProvider tokenProvider, final JsonMapper objectMapper,
			final InspectorOAuthClient oauthClient, final McpInspectorProperties properties,
			final InspectorBootstrapAssembler bootstrapAssembler, final BootstrapHtmlRenderer bootstrapHtmlRenderer) {
		return new InspectorHandler(transportDetector, loopbackFactory, externalStdioFactory, tokenProvider,
				objectMapper, oauthClient, properties, bootstrapAssembler, bootstrapHtmlRenderer);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorAuthWebFilter mcpInspectorAuthWebFilter(final McpInspectorProperties properties,
			final InspectorAuthTokenProvider tokenProvider) {
		return new InspectorAuthWebFilter(properties, tokenProvider);
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxySessionRegistry mcpInspectorProxySessionRegistry(final McpInspectorProperties properties) {
		final ProxySessionRegistry registry = new ProxySessionRegistry();
		registry.setInactivityBudget(properties.getTimeouts().getSessionReaper());
		return registry;
	}

	/**
	 * Closes the host's MCP server transport provider before Boot's graceful-shutdown
	 * phase starts waiting — see {@link McpServerTransportDrain} for why spring-ai's own
	 * teardown is too late. A no-op when the application runs no MCP server of its own,
	 * or when {@code spring.ai.mcp.inspector.shutdown.close-mcp-server-transports=false}.
	 *
	 * <p>
	 * The switch is read from the bound properties rather than gating the bean with
	 * {@code @ConditionalOnProperty}, so the accessor is the single source of truth
	 * instead of a field that quietly does nothing.
	 * @param providers the MCP server transport providers in this context, if any
	 * @param properties the inspector properties carrying the shutdown switch
	 * @return the shutdown listener
	 */
	@Bean
	@ConditionalOnMissingBean
	public McpServerTransportDrain mcpInspectorServerTransportDrain(
			final ObjectProvider<McpServerTransportProviderBase> providers, final McpInspectorProperties properties) {
		return new McpServerTransportDrain(providers, properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxyTransportFactory mcpInspectorProxyTransportFactory(final JsonMapper objectMapper) {
		return new ProxyTransportFactory(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public McpProxy mcpInspectorMcpProxy(final JsonMapper objectMapper,
			final ObjectProvider<McpTrafficRecorder> trafficRecorder) {
		return new McpProxy(objectMapper, trafficRecorder.getIfAvailable());
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxyHandler mcpInspectorProxyHandler(final ProxySessionRegistry registry,
			final ProxyTransportFactory transportFactory, final McpProxy mcpProxy,
			final TransportDetector transportDetector, final JsonMapper objectMapper,
			final McpInspectorProperties properties) {
		return new ProxyHandler(registry, transportFactory, mcpProxy, transportDetector, objectMapper, properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxyAuthWebFilter mcpInspectorProxyAuthWebFilter(final McpInspectorProperties properties,
			final InspectorAuthTokenProvider tokenProvider) {
		return new ProxyAuthWebFilter(properties, tokenProvider);
	}

	/**
	 * Reactive handler for {@code GET ${path}/api/timeline}, mirroring the WebMVC
	 * {@code TimelineController}: active only when the timeline subsystem is enabled (the
	 * same {@code spring.ai.mcp.inspector.timeline.enabled} flag gates the
	 * {@link TimelineService} bean in the core auto-configuration).
	 * @param timelineService the shared timeline service
	 * @return the timeline handler
	 */
	@Bean
	@ConditionalOnBean(TimelineService.class)
	public TimelineHandler mcpInspectorTimelineHandler(final TimelineService timelineService) {
		return new TimelineHandler(timelineService);
	}

	@Bean
	public WebFluxConfigurer mcpInspectorCorsConfigurer(final McpInspectorProperties properties) {
		return new WebFluxConfigurer() {
			@Override
			public void addCorsMappings(final CorsRegistry registry) {
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
