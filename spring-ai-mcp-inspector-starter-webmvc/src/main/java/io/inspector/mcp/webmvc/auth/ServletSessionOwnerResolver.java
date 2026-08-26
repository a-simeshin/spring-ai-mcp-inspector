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

package io.inspector.mcp.webmvc.auth;

import java.time.Duration;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import io.inspector.mcp.core.auth.OwnerTokenCodec;

/**
 * Servlet-stack session-owner resolver (D8, finding #1).
 *
 * <p>
 * Reads the {@code MCP_INSPECTOR_SESSION} cookie from the {@link HttpServletRequest},
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
public class ServletSessionOwnerResolver {

	/** Cookie lifetime — matches the codec's token TTL. */
	private static final Duration COOKIE_MAX_AGE = OwnerTokenCodec.TOKEN_TTL;

	private final OwnerTokenCodec codec;

	public ServletSessionOwnerResolver(final OwnerTokenCodec codec) {
		this.codec = codec;
	}

	/**
	 * Resolves the request's session owner, minting a fresh signed cookie when absent /
	 * forged / expired.
	 * @param request the current servlet request
	 * @param response the current servlet response (may receive a {@code Set-Cookie}
	 * header)
	 * @return the validated owner id (never {@code null})
	 */
	public String resolve(final HttpServletRequest request, final HttpServletResponse response) {
		final String presented = cookieValue(request);
		final java.util.Optional<String> validated = this.codec.validate(presented);
		if (validated.isPresent()) {
			return validated.get();
		}
		final String ownerId = UUID.randomUUID().toString();
		final String token = this.codec.mint(ownerId);
		if (response != null) {
			final ResponseCookie cookie = ResponseCookie.from(OwnerTokenCodec.COOKIE_NAME, token)
				.httpOnly(true)
				.sameSite("Lax")
				.path("/")
				.maxAge(COOKIE_MAX_AGE)
				.build();
			response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		}
		return ownerId;
	}

	private static String cookieValue(final HttpServletRequest request) {
		if (request == null || request.getCookies() == null) {
			return null;
		}
		for (final Cookie cookie : request.getCookies()) {
			if (OwnerTokenCodec.COOKIE_NAME.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
