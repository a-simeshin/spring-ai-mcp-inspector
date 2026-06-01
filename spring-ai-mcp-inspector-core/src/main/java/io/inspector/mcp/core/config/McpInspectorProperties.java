/*
 * Copyright 2025-present the original author or authors.
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

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

}
