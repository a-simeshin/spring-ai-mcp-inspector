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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.util.Assert;

/**
 * Lightweight OAuth 2.0 authorization-code client backed by {@link HttpClient}.
 *
 * <p>
 * Used by the inspector "Auth Debugger" tab to walk through the OAuth flow against an
 * arbitrary IdP without pulling in Spring Security. Only the bits required for the
 * code-grant flow are implemented: building the authorization URL and exchanging an
 * authorization code for an access token.
 */
public class InspectorOAuthClient {

	private final HttpClient httpClient;

	private final ObjectMapper objectMapper;

	public InspectorOAuthClient() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
	}

	public InspectorOAuthClient(HttpClient httpClient, ObjectMapper objectMapper) {
		this.httpClient = (httpClient != null) ? httpClient : HttpClient.newHttpClient();
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
	}

	/**
	 * Builds the OAuth authorization-endpoint URL with the standard query parameters
	 * ({@code response_type=code}, {@code client_id}, {@code redirect_uri},
	 * {@code state}, optional {@code scope} and PKCE {@code code_challenge}).
	 */
	public String buildAuthUrl(String authEndpoint, String clientId, String redirectUri, String scope, String state,
			String codeChallenge) {
		Assert.hasText(authEndpoint, "authEndpoint must not be blank");
		Assert.hasText(clientId, "clientId must not be blank");
		Assert.hasText(redirectUri, "redirectUri must not be blank");
		Assert.hasText(state, "state must not be blank");

		Map<String, String> params = new LinkedHashMap<>();
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
	 * {@code POST} to {@code tokenEndpoint}. Returns the parsed
	 * {@link OAuthTokenResponse}.
	 * @param tokenEndpoint absolute token URL
	 * @param clientId OAuth client id
	 * @param code authorization code from the callback
	 * @param redirectUri same redirect URI that was used on the authorize call
	 * @param codeVerifier optional PKCE verifier; may be {@code null}
	 */
	public OAuthTokenResponse exchangeCode(String tokenEndpoint, String clientId, String code, String redirectUri,
			String codeVerifier) throws IOException, InterruptedException {
		Assert.hasText(tokenEndpoint, "tokenEndpoint must not be blank");
		Assert.hasText(clientId, "clientId must not be blank");
		Assert.hasText(code, "code must not be blank");
		Assert.hasText(redirectUri, "redirectUri must not be blank");

		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("client_id", clientId);
		form.put("redirect_uri", redirectUri);
		if (codeVerifier != null && !codeVerifier.isBlank()) {
			form.put("code_verifier", codeVerifier);
		}

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(tokenEndpoint))
			.timeout(Duration.ofSeconds(15))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
			.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("OAuth token endpoint returned " + response.statusCode() + ": " + response.body());
		}
		return objectMapper.readValue(response.body(), OAuthTokenResponse.class);
	}

	private static String encodeForm(Map<String, String> params) {
		StringBuilder sb = new StringBuilder();
		params.forEach((k, v) -> {
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8));
			sb.append('=');
			sb.append(URLEncoder.encode(v != null ? v : "", StandardCharsets.UTF_8));
		});
		return sb.toString();
	}

}
