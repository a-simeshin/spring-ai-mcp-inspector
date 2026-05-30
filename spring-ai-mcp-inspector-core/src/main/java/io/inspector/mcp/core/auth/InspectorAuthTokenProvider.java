/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.auth;

import java.security.SecureRandom;
import java.util.HexFormat;

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
 */
public class InspectorAuthTokenProvider {

	private static final int RANDOM_TOKEN_BYTES = 32;

	private final McpInspectorProperties properties;

	private final SecureRandom random;

	private volatile String cachedToken;

	public InspectorAuthTokenProvider(McpInspectorProperties properties) {
		this(properties, new SecureRandom());
	}

	/**
	 * Package-visible constructor for tests that need a deterministic
	 * {@link SecureRandom}.
	 */
	InspectorAuthTokenProvider(McpInspectorProperties properties, SecureRandom random) {
		this.properties = properties;
		this.random = random;
	}

	/**
	 * Returns the resolved auth token. Idempotent — repeated calls return the same value.
	 */
	public String token() {
		String configured = (properties != null) ? properties.getAuthToken() : null;
		if (configured != null && !configured.isBlank()) {
			return configured;
		}

		String snapshot = cachedToken;
		if (snapshot != null) {
			return snapshot;
		}
		synchronized (this) {
			if (cachedToken == null) {
				byte[] bytes = new byte[RANDOM_TOKEN_BYTES];
				random.nextBytes(bytes);
				cachedToken = HexFormat.of().formatHex(bytes);
			}
			return cachedToken;
		}
	}

}
