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

package io.inspector.mcp.webmvc.controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.inspector.mcp.core.auth.AuthProfile;
import io.inspector.mcp.core.auth.AuthProfileExchangeRequest;
import io.inspector.mcp.core.auth.AuthProfilePrefillProvider;
import io.inspector.mcp.core.auth.AuthProfileRegistrationRequest;
import io.inspector.mcp.core.auth.AuthProfileRegistrationResponse;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.AuthProfileSummary;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;
import io.inspector.mcp.core.proxy.ProxyErrorDto;
import io.inspector.mcp.core.proxy.ProxyUpstreamException;
import io.inspector.mcp.webmvc.auth.InspectorSessionAttributes;
import io.inspector.mcp.webmvc.auth.ServletSessionOwnerResolver;

/**
 * Owner-scoped CRUD + exchange endpoints for named auth profiles (D2/D8/D9).
 *
 * <p>
 * Every endpoint is scoped to the request's session owner: the owner id comes from the
 * {@code OWNER_ID} request attribute stashed by {@code InspectorAuthFilter} after it
 * validates {@code X-MCP-Inspector-Auth} and resolves the signed session-owner cookie. A
 * profile is never visible, usable or mutable across owners; unknown/foreign ids yield
 * {@code 404} without leaking existence.
 *
 * <p>
 * Registration of a {@code CLIENT_CREDENTIALS} profile is transactional across
 * {@code (store.register, tokenManager.acquire)} (D9A): when the initial
 * {@code client_credentials} exchange fails, the store entry and any credentials are
 * rolled back and {@code 502 token_exchange_failed} is returned — no orphan profile, no
 * retained client secret.
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}/api/auth-profile")
public class AuthProfileController {

	/** Structured code returned when the initial client-credentials exchange fails. */
	private static final String CODE_TOKEN_EXCHANGE_FAILED = "token_exchange_failed";

	private final AuthProfileStore store;

	private final AuthProfilePrefillProvider prefillProvider;

	private final OAuth2ClientCredentialsTokenManager tokenManager;

	private final OAuth2AuthCodeTokenExchanger exchanger;

	private final ServletSessionOwnerResolver sessionOwnerResolver;

	public AuthProfileController(final AuthProfileStore store, final AuthProfilePrefillProvider prefillProvider,
			final OAuth2ClientCredentialsTokenManager tokenManager, final OAuth2AuthCodeTokenExchanger exchanger,
			final ServletSessionOwnerResolver sessionOwnerResolver) {
		this.store = store;
		this.prefillProvider = prefillProvider;
		this.tokenManager = tokenManager;
		this.exchanger = exchanger;
		this.sessionOwnerResolver = sessionOwnerResolver;
	}

	/**
	 * Registers a profile: an inline {@code {profile}}, a {@code {name,type}} prefill
	 * reference, or an auth-code PENDING profile. A {@code CLIENT_CREDENTIALS} profile
	 * additionally runs the initial token exchange (D9A).
	 * @param request the registration request
	 * @param httpRequest the current request (owner attribute / fallback resolution)
	 * @param httpResponse the current response (cookie minting when the inspector guard
	 * is disabled)
	 * @return {@code 200 {profileId}} (+ {@code state}/{@code authorizationUrl} for
	 * pending), {@code 400} on invalid input, {@code 502} on a failed initial exchange
	 */
	@PostMapping
	public ResponseEntity<Object> register(@RequestBody final AuthProfileRegistrationRequest request,
			final HttpServletRequest httpRequest, final HttpServletResponse httpResponse) {
		request.validate();
		final String ownerId = ownerId(httpRequest, httpResponse);
		final AuthProfile profile = resolveProfile(request);
		final String profileId = this.store.register(ownerId, profile);
		if (profile instanceof OAuth2Profile oauth2 && oauth2.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS) {
			try {
				this.tokenManager.acquire(profileId, oauth2);
			}
			catch (final RuntimeException ex) {
				// D9A rollback: no orphan profile, no retained client secret.
				this.store.delete(ownerId, profileId);
				this.tokenManager.evict(profileId);
				return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(new ProxyErrorDto(502, CODE_TOKEN_EXCHANGE_FAILED,
							"OAuth2 client-credentials exchange failed: " + ex.getMessage(),
							"Verify the token URL, client id and client secret, then re-register.", null));
			}
		}
		return ResponseEntity.ok(AuthProfileRegistrationResponse.of(profileId));
	}

	/**
	 * Lists the owner's profiles as secret-free summaries.
	 * @param httpRequest the current request
	 * @param httpResponse the current response
	 * @return {@code 200 [summaries]}
	 */
	@GetMapping
	public ResponseEntity<List<AuthProfileSummary>> list(final HttpServletRequest httpRequest,
			final HttpServletResponse httpResponse) {
		return ResponseEntity.ok(this.store.list(ownerId(httpRequest, httpResponse)));
	}

	/**
	 * Lists the Spring-config prefill profiles (no secrets).
	 * @return {@code 200 [summaries]}
	 */
	@GetMapping("/prefill")
	public ResponseEntity<List<AuthProfileSummary>> prefill() {
		return ResponseEntity.ok(this.prefillProvider.list());
	}

	/**
	 * Replaces the profile for {@code profileId} (owner-scoped; {@code 204} on success).
	 * A credential-bearing {@code CLIENT_CREDENTIALS} update stores the new credentials
	 * and evicts the old cached token (finding #7).
	 * @param profileId the profile id path variable
	 * @param request the update request carrying the inline replacement profile
	 * @param httpRequest the current request
	 * @param httpResponse the current response
	 * @return {@code 204} on success, {@code 404} for unknown/foreign ids, {@code 400}
	 * without an inline profile
	 */
	@PutMapping("/{profileId}")
	public ResponseEntity<Void> update(@PathVariable final String profileId,
			@RequestBody final AuthProfileRegistrationRequest request, final HttpServletRequest httpRequest,
			final HttpServletResponse httpResponse) {
		if (!request.hasInlineProfile()) {
			throw new IllegalArgumentException("PUT requires an inline {profile}");
		}
		request.validate();
		final String ownerId = ownerId(httpRequest, httpResponse);
		final AuthProfile profile = request.profile();
		if (!this.store.update(ownerId, profileId, profile)) {
			return ResponseEntity.notFound().build();
		}
		if (profile instanceof OAuth2Profile oauth2 && oauth2.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS) {
			this.tokenManager.update(profileId, oauth2);
		}
		return ResponseEntity.noContent().build();
	}

	/**
	 * Deletes the profile for {@code profileId} (owner-scoped) and evicts its tokens.
	 * @param profileId the profile id path variable
	 * @param httpRequest the current request
	 * @param httpResponse the current response
	 * @return {@code 204} on success, {@code 404} for unknown/foreign ids
	 */
	@DeleteMapping("/{profileId}")
	public ResponseEntity<Void> delete(@PathVariable final String profileId, final HttpServletRequest httpRequest,
			final HttpServletResponse httpResponse) {
		if (!this.store.delete(ownerId(httpRequest, httpResponse), profileId)) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.noContent().build();
	}

	/**
	 * Completes the browser authorization-code flow: verifies the server-issued one-time
	 * {@code state}, verifies PKCE {@code S256(codeVerifier)}, exchanges the {@code code}
	 * at the pending profile's {@code tokenUrl} and marks the profile ACTIVE (D9B).
	 * @param profileId the PENDING profile id path variable
	 * @param request the exchange request {@code {code, codeVerifier, state}}
	 * @param httpRequest the current request
	 * @param httpResponse the current response
	 * @return {@code 200 {profileId}} on success, {@code 400} on state/PKCE mismatch or
	 * replay, {@code 404} for unknown/foreign/non-pending ids
	 */
	@PostMapping("/{profileId}/exchange")
	public ResponseEntity<Object> exchange(@PathVariable final String profileId,
			@RequestBody final AuthProfileExchangeRequest request, final HttpServletRequest httpRequest,
			final HttpServletResponse httpResponse) {
		request.validate();
		final String ownerId = ownerId(httpRequest, httpResponse);
		final Optional<AuthProfile> pending = this.store.resolvePending(ownerId, profileId);
		if (pending.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		if (!this.exchanger.verifyAndConsumeState(ownerId, profileId, request.state())) {
			return ResponseEntity.badRequest().body(Map.of("error", "state mismatch, expired or already consumed"));
		}
		try {
			final OAuth2AuthCodeTokenExchanger.TokenHandle handle = this.exchanger
				.exchange((OAuth2Profile) pending.get(), request.code(), request.codeVerifier());
			this.exchanger.storeTokens(profileId, handle);
			this.store.markActive(ownerId, profileId);
		}
		catch (final IllegalArgumentException ex) {
			return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
		}
		catch (final ProxyUpstreamException ex) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(new ProxyErrorDto(502, "token_exchange_failed", ex.getMessage(),
						"Verify the token URL and client credentials, then retry.", null));
		}
		return ResponseEntity.ok(AuthProfileRegistrationResponse.of(profileId));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> onIllegalArgument(final IllegalArgumentException ex) {
		return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
	}

	private AuthProfile resolveProfile(final AuthProfileRegistrationRequest request) {
		if (request.hasInlineProfile()) {
			return request.profile();
		}
		if (request.isPrefillReference()) {
			return this.prefillProvider.resolve(request.name())
				.orElseThrow(() -> new IllegalArgumentException("unknown prefill profile: " + request.name()));
		}
		return request.pendingOAuth2Profile();
	}

	private String ownerId(final HttpServletRequest request, final HttpServletResponse response) {
		final Object attribute = request.getAttribute(InspectorSessionAttributes.OWNER_ID);
		if (attribute instanceof String ownerId) {
			return ownerId;
		}
		// Defensive fallback when the inspector guard is disabled: mint the owner via
		// the resolver exactly like the filter would.
		return this.sessionOwnerResolver.resolve(request, response);
	}

}
