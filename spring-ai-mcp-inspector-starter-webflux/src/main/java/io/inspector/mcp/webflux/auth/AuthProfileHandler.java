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

package io.inspector.mcp.webflux.auth;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import io.inspector.mcp.core.auth.AuthProfile;
import io.inspector.mcp.core.auth.AuthProfileExchangeRequest;
import io.inspector.mcp.core.auth.AuthProfilePrefillProvider;
import io.inspector.mcp.core.auth.AuthProfileRegistrationRequest;
import io.inspector.mcp.core.auth.AuthProfileRegistrationResponse;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;
import io.inspector.mcp.core.proxy.ProxyErrorDto;
import io.inspector.mcp.core.proxy.ProxyUpstreamException;

/**
 * Reactive handlers for the owner-scoped auth-profile CRUD + exchange endpoints
 * (D2/D8/D9) — the WebFlux twin of {@code AuthProfileController}.
 *
 * <p>
 * Every endpoint is scoped to the request's session owner: the owner id comes from the
 * {@code OWNER_ID} exchange attribute stashed by {@code InspectorAuthWebFilter} after it
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
public class AuthProfileHandler {

	/** Structured code returned when the initial client-credentials exchange fails. */
	private static final String CODE_TOKEN_EXCHANGE_FAILED = "token_exchange_failed";

	private final AuthProfileStore store;

	private final AuthProfilePrefillProvider prefillProvider;

	private final OAuth2ClientCredentialsTokenManager tokenManager;

	private final OAuth2AuthCodeTokenExchanger exchanger;

	private final ReactiveSessionOwnerResolver sessionOwnerResolver;

	private final tools.jackson.databind.json.JsonMapper objectMapper;

	public AuthProfileHandler(final AuthProfileStore store, final AuthProfilePrefillProvider prefillProvider,
			final OAuth2ClientCredentialsTokenManager tokenManager, final OAuth2AuthCodeTokenExchanger exchanger,
			final ReactiveSessionOwnerResolver sessionOwnerResolver) {
		this(store, prefillProvider, tokenManager, exchanger, sessionOwnerResolver, null);
	}

	public AuthProfileHandler(final AuthProfileStore store, final AuthProfilePrefillProvider prefillProvider,
			final OAuth2ClientCredentialsTokenManager tokenManager, final OAuth2AuthCodeTokenExchanger exchanger,
			final ReactiveSessionOwnerResolver sessionOwnerResolver,
			final tools.jackson.databind.json.JsonMapper objectMapper) {
		this.store = store;
		this.prefillProvider = prefillProvider;
		this.tokenManager = tokenManager;
		this.exchanger = exchanger;
		this.sessionOwnerResolver = sessionOwnerResolver;
		this.objectMapper = (objectMapper != null) ? objectMapper : new tools.jackson.databind.json.JsonMapper();
	}

	/**
	 * POST {@code /auth-profile} — registers an inline profile, a prefill reference, or
	 * an auth-code PENDING profile (D2/D9A/D9B).
	 * @param request the registration request
	 * @return a {@link Mono} emitting the registration response
	 */
	public Mono<ServerResponse> register(final ServerRequest request) {
		return request.bodyToMono(String.class).flatMap((raw) -> {
			try {
				final AuthProfileRegistrationRequest body = requestBody(raw);
				body.validate();
				final String ownerId = ownerId(request);
				final AuthProfile profile = resolveProfile(body);
				final String profileId = this.store.register(ownerId, profile);
				if (profile instanceof OAuth2Profile oauth2
						&& oauth2.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS) {
					try {
						this.tokenManager.acquire(profileId, oauth2);
					}
					catch (final RuntimeException ex) {
						// D9A rollback: no orphan profile, no retained client secret.
						this.store.delete(ownerId, profileId);
						this.tokenManager.evict(profileId);
						return ServerResponse.status(HttpStatus.BAD_GATEWAY)
							.bodyValue(new ProxyErrorDto(502, CODE_TOKEN_EXCHANGE_FAILED,
									"OAuth2 client-credentials exchange failed: " + ex.getMessage(),
									"Verify the token URL, client id and client secret, then re-register.", null));
					}
				}
				if (profile instanceof OAuth2Profile oauth2
						&& oauth2.grantMode() == OAuth2GrantMode.AUTHORIZATION_CODE) {
					// D9B phase 1: server-issued one-time state for the browser
					// authorization
					// flow; the same state is verified at the exchange step.
					final String state = this.exchanger.mintState(ownerId, profileId);
					return ServerResponse.ok()
						.bodyValue(
								AuthProfileRegistrationResponse.pending(profileId, state, oauth2.authorizationUrl()));
				}
				return ServerResponse.ok().bodyValue(AuthProfileRegistrationResponse.of(profileId));
			}
			catch (final IllegalArgumentException ex) {
				return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
			}
		}).switchIfEmpty(ServerResponse.badRequest().bodyValue(Map.of("error", "missing request body")));
	}

	/**
	 * GET {@code /auth-profile} — lists the owner's profiles as secret-free summaries.
	 * @param request the current request
	 * @return a {@link Mono} emitting the summaries
	 */
	public Mono<ServerResponse> list(final ServerRequest request) {
		return ServerResponse.ok().bodyValue(this.store.list(ownerId(request)));
	}

	/**
	 * GET {@code /auth-profile/prefill} — lists the Spring-config prefill profiles (no
	 * secrets).
	 * @param request the current request
	 * @return a {@link Mono} emitting the prefill summaries
	 */
	public Mono<ServerResponse> prefill(final ServerRequest request) {
		return ServerResponse.ok().bodyValue(this.prefillProvider.list());
	}

	/**
	 * PUT {@code /auth-profile/{profileId}} — replaces the profile (owner-scoped; 204 on
	 * success). A credential-bearing {@code CLIENT_CREDENTIALS} update stores the new
	 * credentials and evicts the old cached token (finding #7).
	 * @param request the update request
	 * @return a {@link Mono} emitting the update result
	 */
	public Mono<ServerResponse> update(final ServerRequest request) {
		final String profileId = request.pathVariable("profileId");
		return request.bodyToMono(String.class).flatMap((raw) -> {
			try {
				final AuthProfileRegistrationRequest body = requestBody(raw);
				if (!body.hasInlineProfile()) {
					return ServerResponse.badRequest().bodyValue(Map.of("error", "PUT requires an inline {profile}"));
				}
				body.validate();
				final String ownerId = ownerId(request);
				final AuthProfile profile = body.profile();
				if (!this.store.update(ownerId, profileId, profile)) {
					return ServerResponse.notFound().build();
				}
				if (profile instanceof OAuth2Profile oauth2
						&& oauth2.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS) {
					this.tokenManager.update(profileId, oauth2);
				}
				return ServerResponse.noContent().build();
			}
			catch (final IllegalArgumentException ex) {
				return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
			}
		}).switchIfEmpty(ServerResponse.badRequest().bodyValue(Map.of("error", "missing request body")));
	}

	/**
	 * DELETE {@code /auth-profile/{profileId}} — deletes the profile (owner-scoped) and
	 * evicts its tokens.
	 * @param request the current request
	 * @return a {@link Mono} emitting the delete result
	 */
	public Mono<ServerResponse> delete(final ServerRequest request) {
		if (!this.store.delete(ownerId(request), request.pathVariable("profileId"))) {
			return ServerResponse.notFound().build();
		}
		return ServerResponse.noContent().build();
	}

	/**
	 * POST {@code /auth-profile/{profileId}/exchange} — completes the browser
	 * authorization-code flow (D9B): state verify + consume, PKCE check, code exchange,
	 * ACTIVE transition.
	 * @param request the exchange request
	 * @return a {@link Mono} emitting the exchange result
	 */
	public Mono<ServerResponse> exchange(final ServerRequest request) {
		final String profileId = request.pathVariable("profileId");
		return request.bodyToMono(String.class).flatMap((raw) -> {
			try {
				final AuthProfileExchangeRequest body = parse(raw, AuthProfileExchangeRequest.class);
				body.validate();
				final String ownerId = ownerId(request);
				final Optional<AuthProfile> pending = this.store.resolvePending(ownerId, profileId);
				if (pending.isEmpty()) {
					return ServerResponse.notFound().build();
				}
				if (!this.exchanger.verifyAndConsumeState(ownerId, profileId, body.state())) {
					return ServerResponse.badRequest()
						.bodyValue(Map.of("error", "state mismatch, expired or already consumed"));
				}
				try {
					final OAuth2AuthCodeTokenExchanger.TokenHandle handle = this.exchanger
						.exchange((OAuth2Profile) pending.get(), body.code(), body.codeVerifier());
					this.exchanger.storeTokens(profileId, handle);
					this.store.markActive(ownerId, profileId);
				}
				catch (final IllegalArgumentException ex) {
					return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
				}
				catch (final ProxyUpstreamException ex) {
					return ServerResponse.status(HttpStatus.BAD_GATEWAY)
						.bodyValue(new ProxyErrorDto(502, CODE_TOKEN_EXCHANGE_FAILED, ex.getMessage(),
								"Verify the token URL and client credentials, then retry.", null));
				}
				return ServerResponse.ok().bodyValue(AuthProfileRegistrationResponse.of(profileId));
			}
			catch (final IllegalArgumentException ex) {
				return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
			}
		}).switchIfEmpty(ServerResponse.badRequest().bodyValue(Map.of("error", "missing request body")));
	}

	private AuthProfileRegistrationRequest requestBody(final String raw) {
		return parse(raw, AuthProfileRegistrationRequest.class);
	}

	private <T> T parse(final String raw, final Class<T> type) {
		try {
			return this.objectMapper.readValue(raw, type);
		}
		catch (final RuntimeException ex) {
			throw new IllegalArgumentException("malformed request body: " + ex.getMessage());
		}
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

	private String ownerId(final ServerRequest request) {
		final Object attribute = request.exchange().getAttribute(InspectorSessionAttributes.OWNER_ID);
		if (attribute instanceof String ownerId) {
			return ownerId;
		}
		// Defensive fallback when the inspector guard is disabled: mint the owner via
		// the resolver exactly like the filter would.
		return this.sessionOwnerResolver.resolve(request.exchange());
	}

}
