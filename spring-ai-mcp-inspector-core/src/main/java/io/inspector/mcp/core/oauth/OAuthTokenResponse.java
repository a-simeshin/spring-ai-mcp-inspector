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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lightweight OAuth 2.0 token-endpoint response.
 *
 * @param accessToken short-lived bearer token
 * @param tokenType typically {@code Bearer}
 * @param expiresIn seconds until {@code accessToken} expires; may be {@code null}
 * @param refreshToken optional refresh token
 * @param scope optional space-separated granted scopes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuthTokenResponse(@JsonProperty("access_token") String accessToken,
		@JsonProperty("token_type") String tokenType, @JsonProperty("expires_in") Long expiresIn,
		@JsonProperty("refresh_token") String refreshToken, @JsonProperty("scope") String scope) {
}
