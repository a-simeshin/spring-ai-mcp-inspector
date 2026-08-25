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

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.ResponseCookie;
import org.springframework.web.server.ServerWebExchange;

import io.inspector.mcp.core.auth.OwnerTokenCodec;

/**
 * Reactive-stack session-owner resolver (D8, finding #1).
 *
 * <p>
 * Reads the {@code MCP_INSPECTOR_SESSION} cookie from the {@link ServerWebExchange},
 * validates it via the STACK-NEUTRAL {@link OwnerTokenCodec} and returns the embedded
 * owner id. When the cookie is ABSENT, forged (bad HMAC) or expired, a FRESH signed token
 * is minted and added as a {@code Set-Cookie} response header — the caller always ends up
 * with a valid owner and never trusts an unverifiable value.
 *
 * <p>
 * The cookie is {@code HttpOnly; SameSite=Lax; Path=/} so it reaches both the inspector
 * API namespace and the proxy namespace ({@code ${path}-api}).
 *
 * @author Artem Simeshin
 */
public class ReactiveSessionOwnerResolver {

	/** Cookie lifetime — matches the codec's token TTL. */
	private static final Duration COOKIE_MAX_AGE = OwnerTokenCodec.TOKEN_TTL;

	private final OwnerTokenCodec codec;

	public ReactiveSessionOwnerResolver(final OwnerTokenCodec codec) {
		this.codec = codec;
	}

	/**
	 * Resolves the exchange's session owner, minting a fresh signed cookie when absent /
	 * forged / expired.
	 * @param exchange the current reactive exchange
	 * @return the validated owner id (never {@code null})
	 */
	public String resolve(final ServerWebExchange exchange) {
		final String presented = cookieValue(exchange);
		final java.util.Optional<String> validated = this.codec.validate(presented);
		if (validated.isPresent()) {
			return validated.get();
		}
		final String ownerId = UUID.randomUUID().toString();
		final String token = this.codec.mint(ownerId, java.time.Instant.now());
		final ResponseCookie cookie = ResponseCookie.from(OwnerTokenCodec.COOKIE_NAME, token)
			.httpOnly(true)
			.sameSite("Lax")
			.path("/")
			.maxAge(COOKIE_MAX_AGE)
			.build();
		exchange.getResponse().addCookie(cookie);
		return ownerId;
	}

	private static String cookieValue(final ServerWebExchange exchange) {
		if (exchange == null || exchange.getRequest().getCookies() == null) {
			return null;
		}
		final var cookie = exchange.getRequest().getCookies().getFirst(OwnerTokenCodec.COOKIE_NAME);
		return (cookie != null) ? cookie.getValue() : null;
	}

}
