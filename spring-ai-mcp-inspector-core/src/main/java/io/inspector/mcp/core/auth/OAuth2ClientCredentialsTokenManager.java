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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.oauth.OAuthTokenResponse;
import io.inspector.mcp.core.proxy.ProxyUpstreamException;

/**
 * Backend OAuth2 client-credentials token manager (D9A).
 *
 * <p>
 * Holds an in-memory token cache ({@code profileId → {accessToken, expiresAt}}) AND a
 * manager-owned credential registry ({@code profileId → {tokenUrl, clientId,
 * clientSecret, scopes}}). On {@link #acquire(String, OAuth2Profile)} the initial
 * {@code client_credentials} exchange runs at the profile's {@code tokenUrl} and the
 * cached token is never returned to the browser.
 *
 * <p>
 * Client-credentials has NO refresh token — {@code refresh} is ALWAYS a fresh
 * {@code client_credentials} re-exchange from the STORED credentials (never a
 * {@code refresh_token} grant), performed when {@code forceRefresh} is set or the cached
 * token is within 30s of expiry. Lookups are single-flight per profile id so concurrent
 * 401s cannot stampede the token endpoint.
 *
 * <p>
 * {@link #evict(String)} removes BOTH the cached token and the stored credentials (v14
 * D9A): after any eviction path a subsequent {@link #getAccessToken(String, boolean)}
 * finds no stored credentials and CANNOT re-exchange — it throws
 * {@link IllegalStateException}, mapped by the call site to the structured
 * {@code bad_request} DTO, never a silent fallback token.
 *
 * @author Artem Simeshin
 */
public class OAuth2ClientCredentialsTokenManager implements TokenEvictor {

	private static final Logger LOG = LoggerFactory.getLogger(OAuth2ClientCredentialsTokenManager.class);

	/** Outbound token-request timeout. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	/** Refresh is triggered when the cached token is within this skew of expiry. */
	private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

	/** Assumed token lifetime when the token response omits {@code expires_in}. */
	private static final Duration DEFAULT_TOKEN_TTL = Duration.ofMinutes(5);

	/** Outbound HTTP client (JDK). */
	private final HttpClient httpClient;

	/** JSON mapper for token responses. */
	private final JsonMapper objectMapper;

	/** Token cache keyed by profile id. */
	private final ConcurrentMap<String, TokenEntry> tokenCache = new ConcurrentHashMap<>();

	/** Credential registry keyed by profile id. */
	private final ConcurrentMap<String, StoredClientCredentials> credentials = new ConcurrentHashMap<>();

	/** Per-profile locks for the single-flight contract. */
	private final ConcurrentMap<String, Object> profileLocks = new ConcurrentHashMap<>();

	public OAuth2ClientCredentialsTokenManager() {
		this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(), new JsonMapper());
	}

	public OAuth2ClientCredentialsTokenManager(final HttpClient httpClient, final JsonMapper objectMapper) {
		this.httpClient = (httpClient != null) ? httpClient : HttpClient.newHttpClient();
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
	}

	/**
	 * Stores the profile's credentials and performs the initial
	 * {@code client_credentials} exchange at the profile's {@code tokenUrl}. The exchange
	 * runs BEFORE the credentials are registered, so a failed initial acquire leaves
	 * nothing behind (the caller still rolls the store entry back per D9A).
	 * @param profileId the store profile id
	 * @param profile the client-credentials profile (clientSecret required)
	 * @return the exchanged token handle
	 * @throws ProxyUpstreamException on a non-2xx / malformed / timed-out token response
	 */
	public TokenHandle acquire(final String profileId, final OAuth2Profile profile) {
		Assert.hasText(profileId, "profileId must not be blank");
		Assert.notNull(profile, "profile must not be null");
		Assert.isTrue(profile.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS,
				"acquire requires a CLIENT_CREDENTIALS profile");
		Assert.hasText(profile.clientSecret(), "clientSecret is required for CLIENT_CREDENTIALS profiles");
		final TokenHandle handle = exchange(profile.tokenUrl(), profile.clientId(), profile.clientSecret(),
				profile.scopes());
		this.credentials.put(profileId, new StoredClientCredentials(profile.tokenUrl(), profile.clientId(),
				profile.clientSecret(), profile.scopes()));
		this.tokenCache.put(profileId, new TokenEntry(handle.accessToken(), handle.expiresAt()));
		LOG.debug("oauth2-cc[{}] acquired token expiring {}", profileId, handle.expiresAt());
		return handle;
	}

	/**
	 * Returns a valid access token for {@code profileId}. When {@code forceRefresh} is
	 * set or the cached token is within 30s of expiry, performs a FRESH
	 * {@code client_credentials} re-exchange from the STORED credentials. Single-flight
	 * per profile id.
	 * @param profileId the store profile id
	 * @param forceRefresh {@code true} to force a re-exchange regardless of the cache
	 * @return the (possibly re-exchanged) token handle
	 * @throws IllegalStateException when no stored credentials exist (the profile was
	 * evicted / deleted) — fails closed, never a stale re-exchange
	 * @throws ProxyUpstreamException on a failed re-exchange
	 */
	public TokenHandle getAccessToken(final String profileId, final boolean forceRefresh) {
		Assert.hasText(profileId, "profileId must not be blank");
		final Object lock = this.profileLocks.computeIfAbsent(profileId, (key) -> new Object());
		synchronized (lock) {
			final StoredClientCredentials stored = this.credentials.get(profileId);
			if (stored == null) {
				throw new IllegalStateException(
						"no stored client credentials for profile " + profileId + " (profile deleted or evicted)");
			}
			final TokenEntry cached = this.tokenCache.get(profileId);
			if (!forceRefresh && cached != null && !expired(cached)) {
				return new TokenHandle(cached.accessToken(), cached.expiresAt());
			}
			final TokenHandle fresh = exchange(stored.tokenUrl(), stored.clientId(), stored.clientSecret(),
					stored.scopes());
			this.tokenCache.put(profileId, new TokenEntry(fresh.accessToken(), fresh.expiresAt()));
			LOG.debug("oauth2-cc[{}] refreshed token expiring {}", profileId, fresh.expiresAt());
			return fresh;
		}
	}

	/**
	 * Replaces the stored credentials for a credential-bearing profile update and evicts
	 * the cached token (finding #7). Callers invoke this AFTER a successful
	 * credential-bearing {@code store.update(...)}.
	 * @param profileId the store profile id
	 * @param profile the replacement client-credentials profile
	 */
	public void update(final String profileId, final OAuth2Profile profile) {
		Assert.hasText(profileId, "profileId must not be blank");
		Assert.notNull(profile, "profile must not be null");
		Assert.isTrue(profile.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS,
				"update requires a CLIENT_CREDENTIALS profile");
		Assert.hasText(profile.clientSecret(), "clientSecret is required for CLIENT_CREDENTIALS profiles");
		this.credentials.put(profileId, new StoredClientCredentials(profile.tokenUrl(), profile.clientId(),
				profile.clientSecret(), profile.scopes()));
		this.tokenCache.remove(profileId);
		LOG.debug("oauth2-cc[{}] credentials updated, cached token evicted", profileId);
	}

	/**
	 * Total eviction: removes BOTH the cached token and the stored credentials (v14 D9A).
	 * After this call the profile cannot re-exchange.
	 * @param profileId the store profile id
	 */
	@Override
	public void evict(final String profileId) {
		if (profileId == null) {
			return;
		}
		this.tokenCache.remove(profileId);
		this.credentials.remove(profileId);
		LOG.debug("oauth2-cc[{}] evicted token and stored credentials", profileId);
	}

	/**
	 * Visible store size for tests.
	 * @return the number of stored credential entries
	 */
	public int credentialCount() {
		return this.credentials.size();
	}

	/**
	 * Visible cache size for tests.
	 * @return the number of cached token entries
	 */
	public int cacheSize() {
		return this.tokenCache.size();
	}

	private boolean expired(final TokenEntry entry) {
		return entry.expiresAt() == null || !entry.expiresAt().isAfter(Instant.now().plus(EXPIRY_SKEW));
	}

	/**
	 * Runs a {@code client_credentials} exchange against {@code tokenUrl} and parses the
	 * token response.
	 * @param tokenUrl the token endpoint URL
	 * @param clientId the OAuth2 client id
	 * @param clientSecret the OAuth2 client secret
	 * @param scopes optional space-separated scopes
	 * @return the parsed token handle
	 * @throws ProxyUpstreamException on non-2xx / malformed / timed-out responses
	 */
	private TokenHandle exchange(final String tokenUrl, final String clientId, final String clientSecret,
			final String scopes) {
		final Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "client_credentials");
		form.put("client_id", clientId);
		form.put("client_secret", clientSecret);
		if (scopes != null && !scopes.isBlank()) {
			form.put("scope", scopes);
		}
		try {
			final HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
				.build();
			final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new ProxyUpstreamException(response.statusCode(),
						"OAuth2 client_credentials exchange failed: HTTP " + response.statusCode());
			}
			final OAuthTokenResponse token = this.objectMapper.readValue(response.body(), OAuthTokenResponse.class);
			if (token.accessToken() == null || token.accessToken().isBlank()) {
				throw new ProxyUpstreamException(502, "OAuth2 token response is missing access_token");
			}
			final Instant expiresAt = (token.expiresIn() != null && token.expiresIn() > 0)
					? Instant.now().plusSeconds(token.expiresIn()) : Instant.now().plus(DEFAULT_TOKEN_TTL);
			return new TokenHandle(token.accessToken(), expiresAt);
		}
		catch (final ProxyUpstreamException ex) {
			throw ex;
		}
		catch (final IOException | InterruptedException | RuntimeException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new ProxyUpstreamException(502, "OAuth2 client_credentials exchange failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Form-encodes the parameter map (UTF-8, standard {@code x-www-form-urlencoded} space
	 * semantics).
	 * @param params the parameters
	 * @return the encoded body
	 */
	private static String encodeForm(final Map<String, String> params) {
		final StringBuilder sb = new StringBuilder();
		params.forEach((key, value) -> {
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
				.append('=')
				.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
		});
		return sb.toString();
	}

	/**
	 * Credentials stored per profile id (never returned to the browser).
	 *
	 * @param tokenUrl the token endpoint URL
	 * @param clientId the OAuth2 client id
	 * @param clientSecret the OAuth2 client secret
	 * @param scopes the optional space-separated scopes
	 */
	public record StoredClientCredentials(String tokenUrl, String clientId, String clientSecret, String scopes) {
	}

	/**
	 * Cached access token with its expiry.
	 *
	 * @param accessToken the access token value
	 * @param expiresAt the token expiry instant
	 */
	public record TokenHandle(String accessToken, Instant expiresAt) {
	}

	/**
	 * Internal cache entry.
	 *
	 * @param accessToken the access token value
	 * @param expiresAt the token expiry instant
	 */
	private record TokenEntry(String accessToken, Instant expiresAt) {
	}

}
