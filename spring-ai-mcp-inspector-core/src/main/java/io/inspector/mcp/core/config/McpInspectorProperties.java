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

package io.inspector.mcp.core.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration properties for the Spring AI MCP Inspector.
 *
 * <p>
 * Bound to the {@code spring.ai.mcp.inspector} prefix.
 *
 * <h2>Note on {@code path} resolution in annotations</h2>
 *
 * <p>
 * The {@code path} property feeds two kinds of consumers:
 *
 * <ul>
 * <li>Programmatic mount points (resource handlers, filter URL patterns, CORS mappings,
 * the reactive {@code RouterFunction}) read it directly via {@link #getPath()} /
 * {@link #getProxyPath()} at bean-construction time.</li>
 * <li>Spring-MVC {@code @RequestMapping} / {@code @GetMapping} annotations use SpEL
 * placeholders of the form {@code "${spring.ai.mcp.inspector.path}/api"}. Spring resolves
 * these placeholders against the {@code Environment} at handler-mapping registration
 * time, <strong>not</strong> against the bound {@code McpInspectorProperties} bean.</li>
 * </ul>
 *
 * <p>
 * Practical consequence for tests: setting the property via {@code @TestPropertySource}
 * or {@code @SpringBootTest(properties=...)} works, but {@code @DynamicPropertySource}
 * registers too late for {@code @RequestMapping} placeholders to pick up the override.
 *
 * @author Artem Simeshin
 */
@ConfigurationProperties(prefix = "spring.ai.mcp.inspector")
public class McpInspectorProperties {

	/** Whether the inspector is enabled. Defaults to {@code true}. */
	private boolean enabled = true;

	/**
	 * Base path where the inspector UI / API is mounted. Must start with {@code /} and
	 * must not end with a trailing slash. The companion proxy backend is mounted under
	 * {@link #getProxyPath()} (derived as {@code path + "-api"}).
	 */
	private String path = "/mcp-inspector";

	/**
	 * Whether bearer-token auth on the inspector API is enabled. Defaults to
	 * {@code true}.
	 */
	private boolean authEnabled = true;

	/**
	 * Static auth token. When {@code null} or empty, the inspector lazily generates a
	 * 32-byte hex token on first access (see InspectorAuthTokenProvider).
	 */
	private String authToken;

	/** Origins allowed by the inspector CORS / origin-check filter. Empty by default. */
	private List<String> allowedOrigins = new ArrayList<>();

	/** Tunable timeouts for the proxy backend and server→UI request bridge. */
	@NestedConfigurationProperty
	private Timeouts timeouts = new Timeouts();

	/** Context-shutdown behaviour. */
	@NestedConfigurationProperty
	private Shutdown shutdown = new Shutdown();

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(final boolean enabled) {
		this.enabled = enabled;
	}

	public String getPath() {
		return this.path;
	}

	public void setPath(final String path) {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("spring.ai.mcp.inspector.path must not be blank");
		}
		if (!path.startsWith("/")) {
			throw new IllegalArgumentException("spring.ai.mcp.inspector.path must start with '/': " + path);
		}
		if (path.length() > 1 && path.endsWith("/")) {
			throw new IllegalArgumentException("spring.ai.mcp.inspector.path must not end with '/': " + path);
		}
		this.path = path;
	}

	/**
	 * Returns the path prefix the proxy backend is mounted under. Derived as
	 * {@code path + "-api"} — e.g. when {@code path == "/mcp-inspector"} this returns
	 * {@code "/mcp-inspector-api"}.
	 * @return the proxy path (never {@code null})
	 */
	public String getProxyPath() {
		return this.path + "-api";
	}

	public boolean isAuthEnabled() {
		return this.authEnabled;
	}

	public void setAuthEnabled(final boolean authEnabled) {
		this.authEnabled = authEnabled;
	}

	public String getAuthToken() {
		return this.authToken;
	}

	public void setAuthToken(final String authToken) {
		this.authToken = authToken;
	}

	public List<String> getAllowedOrigins() {
		return this.allowedOrigins;
	}

	public void setAllowedOrigins(final List<String> allowedOrigins) {
		this.allowedOrigins = (allowedOrigins != null) ? allowedOrigins : new ArrayList<>();
	}

	/**
	 * Returns the Ant-style URL patterns covering every mount point the inspector claims
	 * — the UI/API base path, the proxy backend, and the two fixed OAuth callback routes.
	 *
	 * <p>
	 * Intended to be handed to a host application's Spring Security configuration so the
	 * inspector can be authorized (or exempted from CSRF) as a single unit, without the
	 * host having to restate paths that move with {@link #getPath()}.
	 *
	 * <p>
	 * This is <strong>not</strong> a configuration property: the name deliberately omits
	 * the {@code get} prefix so Boot's JavaBean binder does not expose it under
	 * {@code spring.ai.mcp.inspector.security-path-patterns}.
	 * @return an immutable list of Ant patterns (never {@code null} or empty)
	 */
	public List<String> securityPathPatterns() {
		return List.of(this.path + "/**", getProxyPath() + "/**", "/oauth/callback", "/oauth/callback/debug");
	}

	public Timeouts getTimeouts() {
		return this.timeouts;
	}

	public void setTimeouts(final Timeouts timeouts) {
		this.timeouts = (timeouts != null) ? timeouts : new Timeouts();
	}

	public Shutdown getShutdown() {
		return this.shutdown;
	}

	public void setShutdown(final Shutdown shutdown) {
		this.shutdown = (shutdown != null) ? shutdown : new Shutdown();
	}

	/**
	 * Context-shutdown behaviour. Bound under {@code spring.ai.mcp.inspector.shutdown}.
	 */
	public static class Shutdown {

		/**
		 * Whether to close the host application's MCP server transport provider on
		 * {@code ContextClosedEvent} instead of leaving it to bean destruction.
		 *
		 * <p>
		 * Defaults to {@code true}. A live MCP session pins an inbound request open, and
		 * spring-ai releases it only during bean destruction — after Boot's graceful
		 * shutdown phase has already waited out
		 * {@code spring.lifecycle.timeout-per-shutdown-phase} in full. Closing it first
		 * removes that wait.
		 *
		 * <p>
		 * The cost of {@code true}: an MCP tool call still streaming its response when
		 * shutdown starts is cut short, where today it would get the graceful window. Set
		 * this to {@code false} to keep the old behaviour.
		 */
		private boolean closeMcpServerTransports = true;

		public boolean isCloseMcpServerTransports() {
			return this.closeMcpServerTransports;
		}

		public void setCloseMcpServerTransports(final boolean closeMcpServerTransports) {
			this.closeMcpServerTransports = closeMcpServerTransports;
		}

	}

	/**
	 * Tunable timeouts for the proxy backend. All values accept Spring's relaxed
	 * {@link Duration} syntax — e.g. {@code 30s}, {@code 2m}, {@code 500ms} — and fall
	 * back to upstream-compatible defaults when unset.
	 *
	 * <p>
	 * Bound under {@code spring.ai.mcp.inspector.timeouts}.
	 */
	public static class Timeouts {

		/**
		 * Inactivity budget for a proxied SSE / streamable-HTTP browser session (servlet
		 * stack only; the reactive stack keeps the stream open for the lifetime of its
		 * publisher). Generous because MCP servers may idle for minutes. Default 30m.
		 */
		private Duration sseSession = Duration.ofMinutes(30);

		/**
		 * Per-request wall-clock budget for awaiting the matching JSON-RPC response from
		 * an upstream streamable-HTTP server before returning {@code 504}. Default 30s.
		 */
		private Duration streamableRequest = Duration.ofSeconds(30);

		/** Connect timeout for the outbound {@code /fetch} HTTP client. Default 10s. */
		private Duration fetchConnect = Duration.ofSeconds(10);

		/** Per-request timeout for outbound {@code /fetch} calls. Default 30s. */
		private Duration fetchRequest = Duration.ofSeconds(30);

		/**
		 * How long a server→UI request (sampling / elicitation / roots) waits for the
		 * browser to answer before the proxied tool call fails. Default 120s.
		 */
		private Duration serverRequest = Duration.ofSeconds(120);

		public Duration getSseSession() {
			return this.sseSession;
		}

		public void setSseSession(final Duration sseSession) {
			this.sseSession = sseSession;
		}

		public Duration getStreamableRequest() {
			return this.streamableRequest;
		}

		public void setStreamableRequest(final Duration streamableRequest) {
			this.streamableRequest = streamableRequest;
		}

		public Duration getFetchConnect() {
			return this.fetchConnect;
		}

		public void setFetchConnect(final Duration fetchConnect) {
			this.fetchConnect = fetchConnect;
		}

		public Duration getFetchRequest() {
			return this.fetchRequest;
		}

		public void setFetchRequest(final Duration fetchRequest) {
			this.fetchRequest = fetchRequest;
		}

		public Duration getServerRequest() {
			return this.serverRequest;
		}

		public void setServerRequest(final Duration serverRequest) {
			this.serverRequest = serverRequest;
		}

	}

}
