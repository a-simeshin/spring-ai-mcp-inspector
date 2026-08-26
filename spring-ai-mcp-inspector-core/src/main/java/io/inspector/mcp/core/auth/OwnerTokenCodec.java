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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.util.Assert;

/**
 * Stack-neutral codec for the HMAC-SHA256 signed browser-session owner token.
 *
 * <p>
 * The token is the value of the {@code MCP_INSPECTOR_SESSION} cookie:
 * {@code ownerId.expiresAtEpochSeconds.hmac} where
 * {@code hmac = HEX(HMAC-SHA256(serverSecret, ownerId + "." + expiresAtEpochSeconds))}
 * and {@code expiresAtEpochSeconds = now + 24h}. The {@code serverSecret} is a 32-byte
 * {@link SecureRandom} key generated once per boot and held only by this codec — a client
 * cannot mint a valid token for an arbitrary owner id without it, so a stolen bare UUID
 * cannot be forged into a valid cookie.
 *
 * <p>
 * This class deliberately imports NO servlet / webflux types (finding #1): it only mints
 * and validates tokens over {@link String} / {@link Instant}. The web-specific cookie
 * plumbing lives in the starter resolvers ({@code ServletSessionOwnerResolver} /
 * {@code ReactiveSessionOwnerResolver}).
 *
 * @author Artem Simeshin
 */
public class OwnerTokenCodec {

	/** Name of the signed session-owner cookie. */
	public static final String COOKIE_NAME = "MCP_INSPECTOR_SESSION";

	/** Lifetime of a minted owner token. */
	public static final Duration TOKEN_TTL = Duration.ofHours(24);

	/** HMAC algorithm backing the signature. */
	static final String HMAC_ALGORITHM = "HmacSHA256";

	/** Length of the per-boot signing secret in bytes. */
	static final int SECRET_LENGTH_BYTES = 32;

	/** Number of token parts: {@code ownerId.expiresAtEpochSeconds.hmac}. */
	private static final int TOKEN_PARTS = 3;

	/** Index of the owner id part. */
	private static final int PART_OWNER_ID = 0;

	/** Index of the expiry-epoch-seconds part. */
	private static final int PART_EXPIRES_AT = 1;

	/** Index of the hex HMAC part. */
	private static final int PART_HMAC = 2;

	/** Per-boot signing secret. */
	private final byte[] serverSecret;

	/** Source of the current time for minting and expiry checks. */
	private final Clock clock;

	/**
	 * Creates the codec drawing a fresh 32-byte {@link SecureRandom} secret for this boot
	 * and reading the current time from {@link Clock#systemUTC()}.
	 */
	public OwnerTokenCodec() {
		this(Clock.systemUTC());
	}

	/**
	 * Creates the codec with a caller-supplied clock — test seam.
	 * @param clock the clock supplying the current time (must not be null)
	 */
	public OwnerTokenCodec(final Clock clock) {
		Assert.notNull(clock, "clock must not be null");
		this.serverSecret = new byte[SECRET_LENGTH_BYTES];
		new SecureRandom().nextBytes(this.serverSecret);
		this.clock = clock;
	}

	/**
	 * Creates the codec with a caller-supplied secret — test seam.
	 * @param serverSecret the signing secret (must be non-empty)
	 */
	OwnerTokenCodec(final byte[] serverSecret) {
		this(serverSecret, Clock.systemUTC());
	}

	/**
	 * Creates the codec with a caller-supplied secret and clock — test seam.
	 * @param serverSecret the signing secret (must be non-empty)
	 * @param clock the clock supplying the current time (must not be null)
	 */
	OwnerTokenCodec(final byte[] serverSecret, final Clock clock) {
		Assert.notNull(serverSecret, "serverSecret must not be null");
		Assert.isTrue(serverSecret.length > 0, "serverSecret must not be empty");
		Assert.notNull(clock, "clock must not be null");
		this.serverSecret = serverSecret.clone();
		this.clock = clock;
	}

	/**
	 * Mints a signed owner token valid for {@value #TOKEN_TTL} from the codec's clock.
	 * @param ownerId the server-issued owner id (typically a random UUID)
	 * @return the cookie value {@code ownerId.expiresAtEpochSeconds.hmac}
	 */
	public String mint(final String ownerId) {
		Assert.hasText(ownerId, "ownerId must not be blank");
		final long expiresAt = this.clock.instant().plus(TOKEN_TTL).getEpochSecond();
		return ownerId + "." + expiresAt + "." + hmac(ownerId, expiresAt);
	}

	/**
	 * Validates the signature and expiry of {@code token} and returns the embedded owner
	 * id. Absent / forged (bad HMAC) / expired tokens yield empty — the caller re-mints a
	 * fresh token instead of trusting the input.
	 * @param token the cookie value to validate
	 * @return the verified owner id, or empty when invalid
	 */
	public Optional<String> validate(final String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		final String[] parts = token.split("\\.", -1);
		if (parts.length != TOKEN_PARTS) {
			return Optional.empty();
		}
		final String ownerId = parts[PART_OWNER_ID];
		final long expiresAt;
		try {
			expiresAt = Long.parseLong(parts[PART_EXPIRES_AT]);
		}
		catch (final NumberFormatException ex) {
			return Optional.empty();
		}
		if (ownerId.isBlank() || !MessageDigest.isEqual(parts[PART_HMAC].getBytes(StandardCharsets.US_ASCII),
				hmac(ownerId, expiresAt).getBytes(StandardCharsets.US_ASCII))) {
			return Optional.empty();
		}
		if (Instant.ofEpochSecond(expiresAt).isBefore(this.clock.instant())) {
			return Optional.empty();
		}
		return Optional.of(ownerId);
	}

	/**
	 * Computes {@code HEX(HMAC-SHA256(serverSecret, ownerId + "." + expiresAt))}.
	 * @param ownerId the owner id part
	 * @param expiresAt the expiry-epoch-seconds part
	 * @return lowercase hex digest
	 */
	private String hmac(final String ownerId, final long expiresAt) {
		try {
			final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(this.serverSecret, HMAC_ALGORITHM));
			final byte[] digest = mac.doFinal((ownerId + "." + expiresAt).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (final NoSuchAlgorithmException ex) {
			// HmacSHA256 is guaranteed by the JDK; defensive only
			throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
		}
		catch (final java.security.InvalidKeyException ex) {
			throw new IllegalStateException("invalid HMAC key", ex);
		}
	}

}
