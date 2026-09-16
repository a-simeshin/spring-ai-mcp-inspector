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

package io.inspector.mcp.core.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes an opaque fingerprint of the authentication profile carried by an inbound
 * request. Two requests with identical fingerprints are considered to share the same auth
 * context for stateless session binding.
 *
 * <p>
 * The fingerprint covers:
 * <ul>
 * <li>the {@code Authorization} header value (hashed, never logged in plain text)</li>
 * <li>any custom-auth header names and values named by the {@code x-custom-auth-headers}
 * request header</li>
 * </ul>
 *
 * <p>
 * An {@code null} or absent profile (no Authorization and no custom headers) maps to the
 * constant {@link #ANONYMOUS}, which is a valid fingerprint: all anonymous requests to
 * the same server URL share one stateless session.
 *
 * @author Artem Simeshin
 */
public final class AuthProfileHasher {

	/**
	 * Fingerprint for requests that carry no authentication material at all.
	 */
	public static final String ANONYMOUS = "anonymous";

	private AuthProfileHasher() {
	}

	/**
	 * Computes the auth-profile fingerprint.
	 * @param authorization the {@code Authorization} header value, or {@code null}
	 * @param customHeaders the custom-auth headers, or {@code null} / empty
	 * @return the fingerprint, never {@code null}
	 */
	public static String fingerprint(final String authorization, final Map<String, String> customHeaders) {
		final boolean hasAuth = authorization != null && !authorization.isBlank();
		final boolean hasCustom = customHeaders != null && !customHeaders.isEmpty();
		if (!hasAuth && !hasCustom) {
			return ANONYMOUS;
		}
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			if (hasAuth) {
				digest.update("authz\n".getBytes(StandardCharsets.UTF_8));
				digest.update(authorization.getBytes(StandardCharsets.UTF_8));
				digest.update("\n".getBytes(StandardCharsets.UTF_8));
			}
			if (hasCustom) {
				// Sort by header name so ordering does not affect the fingerprint.
				final TreeMap<String, String> sorted = new TreeMap<>(customHeaders);
				for (final Map.Entry<String, String> entry : sorted.entrySet()) {
					digest.update("custom\n".getBytes(StandardCharsets.UTF_8));
					digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
					digest.update("\n".getBytes(StandardCharsets.UTF_8));
					digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
					digest.update("\n".getBytes(StandardCharsets.UTF_8));
				}
			}
			return bytesToHex(digest.digest());
		}
		catch (final NoSuchAlgorithmException ex) {
			// SHA-256 is guaranteed by the JLS; this is unreachable on any conforming
			// JVM.
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

	private static String bytesToHex(final byte[] bytes) {
		final StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (final byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

}
