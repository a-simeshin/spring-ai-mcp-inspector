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

package io.inspector.mcp.core.oauth;

/**
 * Body of {@code POST /mcp-inspector/api/oauth/initiate}.
 *
 * @param authorizationEndpoint absolute OAuth authorization URL (e.g.
 * {@code https://auth.example.com/authorize})
 * @param tokenEndpoint absolute OAuth token URL used later by {@code /oauth/callback};
 * may be {@code null} if the UI plans to exchange the code itself
 * @param clientId OAuth client id
 * @param redirectUri registered redirect URI; required
 * @param scope optional space-separated scope list
 * @param codeChallenge optional PKCE {@code S256} code challenge
 * @author Artem Simeshin
 */
public record OAuthInitiateRequest(String authorizationEndpoint, String tokenEndpoint, String clientId,
		String redirectUri, String scope, String codeChallenge) {
}
