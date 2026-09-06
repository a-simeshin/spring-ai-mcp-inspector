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

package io.inspector.mcp.webmvc;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthProfilePrefillProvider;
import io.inspector.mcp.core.auth.AuthProfileProperties;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OwnerTokenCodec;
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
import io.inspector.mcp.webmvc.auth.ServletSessionOwnerResolver;
import io.inspector.mcp.webmvc.controller.AuthProfileController;
import io.inspector.mcp.webmvc.controller.InspectorConfigController;
import io.inspector.mcp.webmvc.controller.InspectorIndexController;
import io.inspector.mcp.webmvc.controller.InspectorRestController;
import io.inspector.mcp.webmvc.controller.TimelineController;
import io.inspector.mcp.webmvc.filter.InspectorAuthFilter;
import io.inspector.mcp.webmvc.proxy.ProxyAuthFilter;
import io.inspector.mcp.webmvc.proxy.ProxyConfigController;
import io.inspector.mcp.webmvc.proxy.ProxyFetchController;
import io.inspector.mcp.webmvc.proxy.ProxyHealthController;
import io.inspector.mcp.webmvc.proxy.SseProxyController;
import io.inspector.mcp.webmvc.proxy.StreamableHttpProxyController;
import io.inspector.mcp.webmvc.sse.InspectorSseEmitterRegistry;

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
 *
 * @author Artem Simeshin
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "spring.ai.mcp.inspector", name = "enabled", havingValue = "true",
		matchIfMissing = true)
@EnableConfigurationProperties({ McpInspectorProperties.class, AuthProfileProperties.class })
@EnableScheduling
@Import({ InspectorRestController.class, InspectorIndexController.class, InspectorConfigController.class,
		AuthProfileController.class, SseProxyController.class, StreamableHttpProxyController.class,
		ProxyConfigController.class, ProxyHealthController.class, ProxyFetchController.class })
public class McpInspectorWebMvcAutoConfiguration implements WebMvcConfigurer {

	private final McpInspectorProperties properties;

	public McpInspectorWebMvcAutoConfiguration(final McpInspectorProperties properties) {
		this.properties = properties;
	}

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
	public OwnerTokenCodec mcpInspectorOwnerTokenCodec() {
		return new OwnerTokenCodec();
	}

	@Bean
	@ConditionalOnMissingBean
	public ServletSessionOwnerResolver mcpInspectorServletSessionOwnerResolver(final OwnerTokenCodec ownerTokenCodec) {
		return new ServletSessionOwnerResolver(ownerTokenCodec);
	}

	@Bean
	@ConditionalOnMissingBean
	public AuthProfileStore mcpInspectorAuthProfileStore() {
		return new AuthProfileStore();
	}

	@Bean
	@ConditionalOnMissingBean
	public OAuth2ClientCredentialsTokenManager mcpInspectorTokenManager(final AuthProfileStore authProfileStore) {
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager();
		// D9A: the manager is wired as the store's TokenEvictor so every removal path
		// (delete/clear/clearBySession/removeExpired/update) drops the cached token AND
		// the stored credentials together with the profile.
		authProfileStore.setTokenEvictor(manager);
		return manager;
	}

	@Bean
	@ConditionalOnMissingBean
	public OAuth2AuthCodeTokenExchanger mcpInspectorAuthCodeTokenExchanger() {
		return new OAuth2AuthCodeTokenExchanger();
	}

	@Bean
	@ConditionalOnMissingBean
	public AuthProfilePrefillProvider mcpInspectorAuthProfilePrefillProvider(final AuthProfileProperties properties) {
		return new AuthProfilePrefillProvider(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorAuthTokenProvider mcpInspectorAuthTokenProvider() {
		return new InspectorAuthTokenProvider(this.properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public InspectorBootstrapAssembler mcpInspectorBootstrapAssembler(
			final InspectorAuthTokenProvider authTokenProvider, final TransportDetector transportDetector,
			final List<InspectorBootstrapCustomizer> customizers) {
		return new InspectorBootstrapAssembler(this.properties, authTokenProvider, transportDetector, customizers);
	}

	@Bean
	@ConditionalOnMissingBean
	public BootstrapHtmlRenderer mcpInspectorBootstrapHtmlRenderer(final JsonMapper objectMapper) {
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
			final InspectorAuthTokenProvider tokenProvider, final ServletSessionOwnerResolver sessionOwnerResolver) {
		final InspectorAuthFilter filter = new InspectorAuthFilter(this.properties, tokenProvider,
				sessionOwnerResolver);
		final FilterRegistrationBean<InspectorAuthFilter> registration = new FilterRegistrationBean<>(filter);
		registration.addUrlPatterns(this.properties.getPath() + "/api/*");
		registration.setName("mcpInspectorAuthFilter");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
		return registration;
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxySessionRegistry mcpInspectorProxySessionRegistry(final AuthProfileStore authProfileStore) {
		final ProxySessionRegistry registry = new ProxySessionRegistry();
		registry.setInactivityBudget(this.properties.getTimeouts().getSessionReaper());
		// D4: session teardown clears the bound profile; the reaper sweeps expired
		// profiles.
		registry.setAuthProfileStore(authProfileStore);
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

	/**
	 * REST endpoint for querying timeline events, active only when the timeline subsystem
	 * is enabled via {@code spring.ai.mcp.inspector.timeline.enabled=true}.
	 * @param timelineService the shared timeline service
	 * @return the timeline controller
	 */
	@Bean
	@ConditionalOnBean(TimelineService.class)
	public TimelineController mcpInspectorTimelineController(final TimelineService timelineService) {
		return new TimelineController(timelineService);
	}

	@Bean
	@ConditionalOnMissingBean
	public ProxyTransportFactory mcpInspectorProxyTransportFactory(final JsonMapper objectMapper,
			final McpInspectorProperties properties) {
		return new ProxyTransportFactory(objectMapper, properties.getTimeouts().getSseRequest());
	}

	@Bean
	@ConditionalOnMissingBean
	public McpProxy mcpInspectorMcpProxy(final JsonMapper objectMapper, final AuthProfileStore authProfileStore,
			final OAuth2ClientCredentialsTokenManager tokenManager,
			final OAuth2AuthCodeTokenExchanger authCodeExchanger,
			final ObjectProvider<McpTrafficRecorder> trafficRecorder) {
		return new McpProxy(objectMapper, authProfileStore, tokenManager, authCodeExchanger,
				trafficRecorder.getIfAvailable());
	}

	@Bean
	public FilterRegistrationBean<ProxyAuthFilter> mcpInspectorProxyAuthFilterRegistration(
			final InspectorAuthTokenProvider tokenProvider) {
		final ProxyAuthFilter filter = new ProxyAuthFilter(this.properties, tokenProvider);
		final FilterRegistrationBean<ProxyAuthFilter> registration = new FilterRegistrationBean<>(filter);
		registration.addUrlPatterns(this.properties.getProxyPath() + "/*");
		registration.setName("mcpInspectorProxyAuthFilter");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 60);
		return registration;
	}

	@Override
	public void addResourceHandlers(final ResourceHandlerRegistry registry) {
		// Only content-hashed bundle assets (plus mcp.svg) reach this handler —
		// index.html is claimed by InspectorIndexController and served no-store,
		// so a long max-age cannot pin a stale entry point.
		registry.addResourceHandler(this.properties.getPath() + "/**")
			.addResourceLocations("classpath:/mcp-inspector-bundle/")
			.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS));
	}

	@Override
	public void addCorsMappings(final CorsRegistry registry) {
		final List<String> origins = this.properties.getAllowedOrigins();
		if (origins == null || origins.isEmpty()) {
			return;
		}
		registry.addMapping(this.properties.getPath() + "/api/**")
			.allowedOrigins(origins.toArray(String[]::new))
			.allowedMethods("GET", "POST", "DELETE", "OPTIONS")
			.allowedHeaders("*")
			.allowCredentials(true)
			.maxAge(3600);
		// The vendored upstream UI calls the proxy under a separate prefix —
		// expose CORS rules for it too so non-loopback hosts can connect.
		registry.addMapping(this.properties.getProxyPath() + "/**")
			.allowedOrigins(origins.toArray(String[]::new))
			.allowedMethods("GET", "POST", "DELETE", "OPTIONS")
			.allowedHeaders("*")
			.exposedHeaders("mcp-session-id", "WWW-Authenticate")
			.allowCredentials(true)
			.maxAge(3600);
	}

}
