/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.oauth;

/**
 * Response of {@code POST /mcp-inspector/api/oauth/initiate}: the constructed
 * authorization URL and the random {@code state} token that the UI must send to the IdP
 * and verify on callback.
 *
 * @param authUrl fully constructed authorization-endpoint URL including query string
 * @param state random anti-CSRF token; the UI compares this against {@code state}
 * returned in the callback
 */
public record OAuthInitiateResponse(String authUrl, String state) {
}
