/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.oauth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory bookkeeping for the OAuth 2.1 stub server: maps each issued authorization
 * code to the PKCE {@code code_challenge} (S256 / plain) and the
 * {@code code_challenge_method} that produced it.
 *
 * <p>
 * Scoped per JVM — restarts wipe state. Test-only; do not use outside of the
 * {@code oauth-stub} profile.
 */
@Component
@ConditionalOnProperty(prefix = "demo.oauth-stub", name = "enabled", havingValue = "true")
public class OAuthStubStore {

	/** One pending entry per outstanding authorization code. */
	public record PendingCode(String codeChallenge, String codeChallengeMethod, String redirectUri) {
	}

	private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();

	/** Records an outstanding code returned from the {@code /authorize} endpoint. */
	public void put(String code, PendingCode pending) {
		codes.put(code, pending);
	}

	/** Removes a code; returns its bookkeeping entry, or {@code null} if unknown. */
	public PendingCode consume(String code) {
		return codes.remove(code);
	}

}
