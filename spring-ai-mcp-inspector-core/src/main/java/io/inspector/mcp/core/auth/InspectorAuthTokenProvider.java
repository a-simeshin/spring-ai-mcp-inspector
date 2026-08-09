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

package io.inspector.mcp.core.auth;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.inspector.mcp.core.config.McpInspectorProperties;

/**
 * Provides the bearer token the inspector UI and API require.
 *
 * <p>
 * Resolution order:
 *
 * <ol>
 * <li>If {@link McpInspectorProperties#getAuthToken()} is non-blank, that value is used
 * as-is.</li>
 * <li>Otherwise a 32-byte cryptographically random token is generated lazily on first
 * {@link #token()} call and cached for the lifetime of this provider. The result is a
 * 64-char lowercase hex string.</li>
 * </ol>
 *
 * @author Artem Simeshin
 */
public class InspectorAuthTokenProvider {

	private static final Logger LOG = LoggerFactory.getLogger(InspectorAuthTokenProvider.class);

	private static final int RANDOM_TOKEN_BYTES = 32;

	private final McpInspectorProperties properties;

	private final SecureRandom random;

	private volatile String cachedToken;

	public InspectorAuthTokenProvider(final McpInspectorProperties properties) {
		this(properties, new SecureRandom());
	}

	/**
	 * Package-visible constructor for tests that need a deterministic
	 * {@link SecureRandom}.
	 * @param properties the inspector configuration properties
	 * @param random the secure random source to use for token generation
	 */
	InspectorAuthTokenProvider(final McpInspectorProperties properties, final SecureRandom random) {
		this.properties = properties;
		this.random = random;
		if (properties != null && !properties.isAuthEnabled()) {
			// Eager singleton in both web stacks, so this fires once per application
			// start.
			LOG.warn(
					"MCP Inspector auth is disabled (spring.ai.mcp.inspector.auth-enabled=false). "
							+ "Network reachability is now the only control: anyone who can reach {} gets full "
							+ "use of the inspector proxy, which opens connections to arbitrary URLs from inside "
							+ "your network. Keep it on a trusted network or put it behind your own authentication.",
					properties.getPath());
		}
	}

	/**
	 * Returns the resolved auth token. Idempotent — repeated calls return the same value.
	 * @return the bearer token string (never {@code null} or blank)
	 */
	public String token() {
		final String configured = (this.properties != null) ? this.properties.getAuthToken() : null;
		if (configured != null && !configured.isBlank()) {
			return configured;
		}

		final String snapshot = this.cachedToken;
		if (snapshot != null) {
			return snapshot;
		}
		synchronized (this) {
			if (this.cachedToken == null) {
				final byte[] bytes = new byte[RANDOM_TOKEN_BYTES];
				this.random.nextBytes(bytes);
				this.cachedToken = HexFormat.of().formatHex(bytes);
			}
			return this.cachedToken;
		}
	}

}
