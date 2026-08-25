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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.oauth.OAuthTokenResponse;
import io.inspector.mcp.core.proxy.ProxyUpstreamException;

/**
 * Backend half of the browser authorization-code (PKCE) flow (D9B).
 *
 * <p>
 * Phase 1 stores a PENDING profile and mints a server-issued one-time {@code state}
 * ({@link #mintState(String, String)}, TTL 10 minutes, bound to
 * {@code (ownerId, profileId)}). Phase 3 verifies and consumes that state
 * ({@link #verifyAndConsumeState(String, String, String)}) and exchanges the
 * browser-supplied {@code code} at the PENDING profile's {@code tokenUrl}
 * ({@link #exchange(OAuth2Profile, String, String)}), verifying the PKCE
 * {@code S256(codeVerifier) == codeChallenge} before the token request.
 *
 * <p>
 * Exchanged tokens are held BACKEND-ONLY, keyed by {@code profileId}
 * ({@link #storeTokens(String, TokenHandle)} / {@link #accessToken(String)}); the browser
 * only ever receives {@code {profileId}} back.
 *
 * @author Artem Simeshin
 */
public class OAuth2AuthCodeTokenExchanger implements TokenEvictor {

	private static final Logger LOG = LoggerFactory.getLogger(OAuth2AuthCodeTokenExchanger.class);

	/** Lifetime of a minted one-time state. */
	private static final Duration STATE_TTL = Duration.ofMinutes(10);

	/** Outbound token-request timeout. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	/** Assumed token lifetime when the token response omits {@code expires_in}. */
	private static final Duration DEFAULT_TOKEN_TTL = Duration.ofMinutes(5);

	/** PKCE challenge method verified before the exchange. */
	private static final String PKCE_METHOD_S256 = "S256";

	/** One-time states keyed by profile id. */
	private final ConcurrentMap<String, PendingState> states = new ConcurrentHashMap<>();

	/** Exchanged tokens keyed by profile id (never returned to the browser). */
	private final ConcurrentMap<String, TokenHandle> tokens = new ConcurrentHashMap<>();

	/** Outbound HTTP client (JDK). */
	private final java.net.http.HttpClient httpClient;

	/** JSON mapper for token responses. */
	private final JsonMapper objectMapper;

	public OAuth2AuthCodeTokenExchanger() {
		this(java.net.http.HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(), new JsonMapper());
	}

	public OAuth2AuthCodeTokenExchanger(final java.net.http.HttpClient httpClient, final JsonMapper objectMapper) {
		this.httpClient = (httpClient != null) ? httpClient : java.net.http.HttpClient.newHttpClient();
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
	}

	/**
	 * Mints a one-time CSRF state for {@code profileId}, bound to {@code ownerId}, TTL 10
	 * minutes. Any previous state for the profile is replaced.
	 * @param ownerId the owning browser session id
	 * @param profileId the PENDING profile id
	 * @return the server-issued state
	 */
	public String mintState(final String ownerId, final String profileId) {
		Assert.hasText(ownerId, "ownerId must not be blank");
		Assert.hasText(profileId, "profileId must not be blank");
		final String state = UUID.randomUUID().toString();
		this.states.put(profileId, new PendingState(ownerId, state, Instant.now().plus(STATE_TTL)));
		LOG.debug("oauth2-authcode[{}] state minted", profileId);
		return state;
	}

	/**
	 * Verifies the presented {@code state} against the server-issued value: owner
	 * binding, one-time (consume-on-success) and TTL. Any mismatch or a second use of an
	 * already-consumed state yields {@code false} (replay rejected).
	 * @param ownerId the owning browser session id
	 * @param profileId the PENDING profile id
	 * @param state the state presented by the browser callback
	 * @return {@code true} when the state was valid AND is now consumed
	 */
	public boolean verifyAndConsumeState(final String ownerId, final String profileId, final String state) {
		if (ownerId == null || profileId == null || state == null) {
			return false;
		}
		final PendingState pending = this.states.remove(profileId);
		if (pending == null) {
			return false;
		}
		if (!pending.ownerId().equals(ownerId) || !pending.state().equals(state)) {
			return false;
		}
		if (pending.expiresAt().isBefore(Instant.now())) {
			return false;
		}
		LOG.debug("oauth2-authcode[{}] state verified and consumed", profileId);
		return true;
	}

	/**
	 * Exchanges the authorization {@code code} for tokens at the PENDING profile's
	 * {@code tokenUrl}. The PKCE verifier is checked FIRST: {@code S256(codeVerifier)}
	 * must equal the profile's {@code codeChallenge} or the exchange is rejected with
	 * {@link IllegalArgumentException} (mapped to {@code 400}).
	 * @param pendingProfile the PENDING OAuth2 profile (must be
	 * {@code AUTHORIZATION_CODE} with a code challenge)
	 * @param code the authorization code from the IdP callback
	 * @param codeVerifier the PKCE verifier
	 * @return the exchanged tokens (backend-held)
	 * @throws IllegalArgumentException on PKCE mismatch / malformed profile
	 * @throws ProxyUpstreamException on a non-2xx / malformed / timed-out token response
	 */
	public TokenHandle exchange(final OAuth2Profile pendingProfile, final String code, final String codeVerifier) {
		Assert.notNull(pendingProfile, "pendingProfile must not be null");
		Assert.isTrue(pendingProfile.grantMode() == OAuth2GrantMode.AUTHORIZATION_CODE,
				"exchange requires an AUTHORIZATION_CODE profile");
		Assert.hasText(code, "code must not be blank");
		Assert.hasText(codeVerifier, "codeVerifier must not be blank");
		Assert.hasText(pendingProfile.codeChallenge(), "pending profile must carry a codeChallenge");
		final String computed = s256(codeVerifier);
		if (!MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
				pendingProfile.codeChallenge().getBytes(StandardCharsets.US_ASCII))) {
			throw new IllegalArgumentException(
					"PKCE verification failed: S256(codeVerifier) does not match codeChallenge");
		}
		final Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("client_id", pendingProfile.clientId());
		form.put("code", code);
		form.put("redirect_uri", pendingProfile.redirectUri());
		form.put("code_verifier", codeVerifier);
		try {
			final java.net.http.HttpRequest request = java.net.http.HttpRequest
				.newBuilder(URI.create(pendingProfile.tokenUrl()))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(encodeForm(form)))
				.build();
			final java.net.http.HttpResponse<String> response = this.httpClient.send(request,
					java.net.http.HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new ProxyUpstreamException(response.statusCode(),
						"OAuth2 authorization_code exchange failed: HTTP " + response.statusCode());
			}
			final OAuthTokenResponse token = this.objectMapper.readValue(response.body(), OAuthTokenResponse.class);
			if (token.accessToken() == null || token.accessToken().isBlank()) {
				throw new ProxyUpstreamException(502, "OAuth2 token response is missing access_token");
			}
			final Instant expiresAt = (token.expiresIn() != null && token.expiresIn() > 0)
					? Instant.now().plusSeconds(token.expiresIn()) : Instant.now().plus(DEFAULT_TOKEN_TTL);
			return new TokenHandle(token.accessToken(), token.refreshToken(), expiresAt);
		}
		catch (final ProxyUpstreamException ex) {
			throw ex;
		}
		catch (final IOException | InterruptedException | RuntimeException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new ProxyUpstreamException(502, "OAuth2 authorization_code exchange failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Stores the exchanged tokens backend-side, keyed by {@code profileId}.
	 * @param profileId the profile id
	 * @param handle the exchanged tokens
	 */
	public void storeTokens(final String profileId, final TokenHandle handle) {
		Assert.hasText(profileId, "profileId must not be blank");
		Assert.notNull(handle, "handle must not be null");
		this.tokens.put(profileId, handle);
		LOG.debug("oauth2-authcode[{}] tokens stored", profileId);
	}

	/**
	 * Returns the backend-held access token for {@code profileId}.
	 * @param profileId the profile id
	 * @return the access token, or empty when not exchanged / evicted
	 */
	public Optional<String> accessToken(final String profileId) {
		if (profileId == null) {
			return Optional.empty();
		}
		final TokenHandle handle = this.tokens.get(profileId);
		return (handle != null && handle.accessToken() != null) ? Optional.of(handle.accessToken()) : Optional.empty();
	}

	/**
	 * Drops the profile's state AND exchanged tokens (eviction completeness).
	 * @param profileId the profile id
	 */
	@Override
	public void evict(final String profileId) {
		if (profileId == null) {
			return;
		}
		this.states.remove(profileId);
		this.tokens.remove(profileId);
		LOG.debug("oauth2-authcode[{}] evicted state and tokens", profileId);
	}

	/**
	 * Visible token-store size for tests.
	 * @return the number of stored token entries
	 */
	public int tokenCount() {
		return this.tokens.size();
	}

	/**
	 * Visible state-store size for tests.
	 * @return the number of pending state entries
	 */
	public int stateCount() {
		return this.states.size();
	}

	/**
	 * Computes the PKCE S256 challenge of {@code codeVerifier}.
	 * @param codeVerifier the verifier
	 * @return the base64url-encoded SHA-256 digest
	 */
	private static String s256(final String codeVerifier) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (final java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	/**
	 * Form-encodes the parameter map (UTF-8).
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
	 * Exchanged tokens held backend-side, keyed by profile id.
	 *
	 * @param accessToken the access token
	 * @param refreshToken the optional refresh token
	 * @param expiresAt the token expiry instant
	 */
	public record TokenHandle(String accessToken, String refreshToken, Instant expiresAt) {
	}

	/**
	 * Server-issued one-time state entry.
	 *
	 * @param ownerId the owning browser-session id
	 * @param state the server-issued state value
	 * @param expiresAt the state expiry instant
	 */
	private record PendingState(String ownerId, String state, Instant expiresAt) {
	}

}
