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

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-config prefill profiles for target MCP servers (D7) — a NEW dedicated namespace;
 * the inspector's OWN auth components ({@code InspectorAuthTokenProvider},
 * {@code InspectorOAuthClient}) are not reused for target-server prefill.
 *
 * <p>
 * Example application.yml (the binding path is
 * {@code spring.ai.mcp.inspector.auth-profiles.profiles[0].*}):
 *
 * <pre>
 * spring:
 *   ai:
 *     mcp:
 *       inspector:
 *         auth-profiles:
 *           profiles:
 *             - name: prod-bearer
 *               type: BEARER
 *               bearer:
 *                 token: ${PROD_BEARER_TOKEN}
 *             - name: prod-api
 *               type: API_KEY
 *               api-key:
 *                 name: X-API-Key
 *                 value: ${PROD_API_KEY}
 *                 placement: HEADER
 *             - name: prod-oauth
 *               type: OAUTH2
 *               oauth2:
 *                 grant-mode: CLIENT_CREDENTIALS
 *                 token-url: https://auth.example.com/token
 *                 client-id: inspector
 *                 client-secret: ${PROD_CLIENT_SECRET}
 *                 scopes: mcp.read
 *             - name: extra-headers
 *               type: CUSTOM_HEADERS
 *               custom-headers:
 *                 headers:
 *                   - name: X-Tenant
 *                     value: acme
 * </pre>
 *
 * @author Artem Simeshin
 */
@ConfigurationProperties(prefix = "spring.ai.mcp.inspector.auth-profiles")
public class AuthProfileProperties {

	/** Declared prefill profiles. */
	private List<AuthProfileConfig> profiles = new ArrayList<>();

	public List<AuthProfileConfig> getProfiles() {
		return this.profiles;
	}

	public void setProfiles(final List<AuthProfileConfig> profiles) {
		this.profiles = (profiles != null) ? profiles : new ArrayList<>();
	}

	/** One declared prefill profile. */
	public static class AuthProfileConfig {

		/** Profile name (must be unique across the declared list). */
		private String name;

		/** Profile kind. */
		private AuthProfileType type;

		/** Bearer fields. */
		private BearerConfig bearer = new BearerConfig();

		/** API-key fields. */
		private ApiKeyConfig apiKey = new ApiKeyConfig();

		/** OAuth2 fields. */
		private OAuth2Config oauth2 = new OAuth2Config();

		/** Custom-header fields. */
		private CustomHeadersConfig customHeaders = new CustomHeadersConfig();

		public String getName() {
			return this.name;
		}

		public void setName(final String name) {
			this.name = name;
		}

		public AuthProfileType getType() {
			return this.type;
		}

		public void setType(final AuthProfileType type) {
			this.type = type;
		}

		public BearerConfig getBearer() {
			return this.bearer;
		}

		public void setBearer(final BearerConfig bearer) {
			this.bearer = (bearer != null) ? bearer : new BearerConfig();
		}

		public ApiKeyConfig getApiKey() {
			return this.apiKey;
		}

		public void setApiKey(final ApiKeyConfig apiKey) {
			this.apiKey = (apiKey != null) ? apiKey : new ApiKeyConfig();
		}

		public OAuth2Config getOauth2() {
			return this.oauth2;
		}

		public void setOauth2(final OAuth2Config oauth2) {
			this.oauth2 = (oauth2 != null) ? oauth2 : new OAuth2Config();
		}

		public CustomHeadersConfig getCustomHeaders() {
			return this.customHeaders;
		}

		public void setCustomHeaders(final CustomHeadersConfig customHeaders) {
			this.customHeaders = (customHeaders != null) ? customHeaders : new CustomHeadersConfig();
		}

	}

	/** Bearer profile config. */
	public static class BearerConfig {

		/** The bearer token value. */
		private String token;

		public String getToken() {
			return this.token;
		}

		public void setToken(final String token) {
			this.token = token;
		}

	}

	/** API-key profile config. */
	public static class ApiKeyConfig {

		/** Header / query parameter name. */
		private String name;

		/** The key value. */
		private String value;

		/** Placement; defaults to {@code HEADER}. */
		private ApiKeyPlacement placement = ApiKeyPlacement.HEADER;

		public String getName() {
			return this.name;
		}

		public void setName(final String name) {
			this.name = name;
		}

		public String getValue() {
			return this.value;
		}

		public void setValue(final String value) {
			this.value = value;
		}

		public ApiKeyPlacement getPlacement() {
			return this.placement;
		}

		public void setPlacement(final ApiKeyPlacement placement) {
			this.placement = (placement != null) ? placement : ApiKeyPlacement.HEADER;
		}

	}

	/** OAuth2 profile config. */
	public static class OAuth2Config {

		/** Grant mode; defaults to {@code CLIENT_CREDENTIALS}. */
		private OAuth2GrantMode grantMode = OAuth2GrantMode.CLIENT_CREDENTIALS;

		/** Token endpoint URL. */
		private String tokenUrl;

		/** OAuth2 client id. */
		private String clientId;

		/** OAuth2 client secret (required for CLIENT_CREDENTIALS). */
		private String clientSecret;

		/** Optional space-separated scopes. */
		private String scopes;

		public OAuth2GrantMode getGrantMode() {
			return this.grantMode;
		}

		public void setGrantMode(final OAuth2GrantMode grantMode) {
			this.grantMode = (grantMode != null) ? grantMode : OAuth2GrantMode.CLIENT_CREDENTIALS;
		}

		public String getTokenUrl() {
			return this.tokenUrl;
		}

		public void setTokenUrl(final String tokenUrl) {
			this.tokenUrl = tokenUrl;
		}

		public String getClientId() {
			return this.clientId;
		}

		public void setClientId(final String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return this.clientSecret;
		}

		public void setClientSecret(final String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public String getScopes() {
			return this.scopes;
		}

		public void setScopes(final String scopes) {
			this.scopes = scopes;
		}

	}

	/** Custom-headers profile config. */
	public static class CustomHeadersConfig {

		/** Ordered header entries. */
		private List<HeaderConfig> headers = new ArrayList<>();

		public List<HeaderConfig> getHeaders() {
			return this.headers;
		}

		public void setHeaders(final List<HeaderConfig> headers) {
			this.headers = (headers != null) ? headers : new ArrayList<>();
		}

	}

	/** One custom header entry. */
	public static class HeaderConfig {

		/** Header name. */
		private String name;

		/** Header value. */
		private String value;

		public String getName() {
			return this.name;
		}

		public void setName(final String name) {
			this.name = name;
		}

		public String getValue() {
			return this.value;
		}

		public void setValue(final String value) {
			this.value = value;
		}

	}

}
