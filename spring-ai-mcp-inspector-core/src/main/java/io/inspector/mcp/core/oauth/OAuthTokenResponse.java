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
 * @author Artem Simeshin
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuthTokenResponse(@JsonProperty("access_token") String accessToken,
		@JsonProperty("token_type") String tokenType, @JsonProperty("expires_in") Long expiresIn,
		@JsonProperty("refresh_token") String refreshToken, @JsonProperty("scope") String scope) {
}
