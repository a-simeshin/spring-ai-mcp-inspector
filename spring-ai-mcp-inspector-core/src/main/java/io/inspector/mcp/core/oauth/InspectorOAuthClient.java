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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lightweight OAuth 2.0 authorization-code client backed by {@link HttpClient}.
 *
 * <p>
 * Used by the inspector "Auth Debugger" tab to walk through the OAuth flow against an
 * arbitrary IdP without pulling in Spring Security. Only the bits required for the
 * code-grant flow are implemented: building the authorization URL and exchanging an
 * authorization code for an access token.
 *
 * @author Artem Simeshin
 */
public class InspectorOAuthClient {

	private final HttpClient httpClient;

	private final JsonMapper objectMapper;

	public InspectorOAuthClient() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new JsonMapper());
	}

	public InspectorOAuthClient(final HttpClient httpClient, final JsonMapper objectMapper) {
		this.httpClient = (httpClient != null) ? httpClient : HttpClient.newHttpClient();
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
	}

	/**
	 * Builds the OAuth authorization-endpoint URL with the standard query parameters
	 * ({@code response_type=code}, {@code client_id}, {@code redirect_uri},
	 * {@code state}, optional {@code scope} and PKCE {@code code_challenge}).
	 * @param authEndpoint absolute authorization URL (e.g.
	 * {@code https://auth.example.com/authorize})
	 * @param clientId the OAuth client id
	 * @param redirectUri registered redirect URI
	 * @param scope optional space-separated scope list; may be {@code null} or blank
	 * @param state anti-CSRF state token
	 * @param codeChallenge optional PKCE S256 code challenge; may be {@code null} or
	 * blank
	 * @return fully constructed authorization URL including query string
	 */
	public String buildAuthUrl(final String authEndpoint, final String clientId, final String redirectUri,
			final String scope, final String state, final String codeChallenge) {
		Assert.hasText(authEndpoint, "authEndpoint must not be blank");
		Assert.hasText(clientId, "clientId must not be blank");
		Assert.hasText(redirectUri, "redirectUri must not be blank");
		Assert.hasText(state, "state must not be blank");

		final Map<String, String> params = new LinkedHashMap<>();
		params.put("response_type", "code");
		params.put("client_id", clientId);
		params.put("redirect_uri", redirectUri);
		params.put("state", state);
		if (scope != null && !scope.isBlank()) {
			params.put("scope", scope);
		}
		if (codeChallenge != null && !codeChallenge.isBlank()) {
			params.put("code_challenge", codeChallenge);
			params.put("code_challenge_method", "S256");
		}
		return authEndpoint + (authEndpoint.contains("?") ? "&" : "?") + encodeForm(params);
	}

	/**
	 * Exchanges an authorization {@code code} for an access token via a form-encoded
	 * {@code POST} to {@code tokenEndpoint}.
	 * @param tokenEndpoint absolute token URL
	 * @param clientId the OAuth client id
	 * @param code authorization code from the callback
	 * @param redirectUri same redirect URI that was used on the authorize call
	 * @param codeVerifier optional PKCE verifier; may be {@code null}
	 * @return parsed {@link OAuthTokenResponse} from the token endpoint
	 * @throws IOException if the HTTP request fails or the response is non-2xx
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	public OAuthTokenResponse exchangeCode(final String tokenEndpoint, final String clientId, final String code,
			final String redirectUri, final String codeVerifier) throws IOException, InterruptedException {
		Assert.hasText(tokenEndpoint, "tokenEndpoint must not be blank");
		Assert.hasText(clientId, "clientId must not be blank");
		Assert.hasText(code, "code must not be blank");
		Assert.hasText(redirectUri, "redirectUri must not be blank");

		final Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("client_id", clientId);
		form.put("redirect_uri", redirectUri);
		if (codeVerifier != null && !codeVerifier.isBlank()) {
			form.put("code_verifier", codeVerifier);
		}

		final HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(tokenEndpoint))
			.timeout(Duration.ofSeconds(15))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
			.build();

		final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("OAuth token endpoint returned " + response.statusCode() + ": " + response.body());
		}
		return this.objectMapper.readValue(response.body(), OAuthTokenResponse.class);
	}

	private static String encodeForm(final Map<String, String> params) {
		final StringBuilder sb = new StringBuilder();
		params.forEach((k, v) -> {
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8));
			sb.append('=');
			sb.append(URLEncoder.encode((v != null) ? v : "", StandardCharsets.UTF_8));
		});
		return sb.toString();
	}

}
