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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link OwnerTokenCodec} — HMAC-signed session-owner token (D8). */
@Epic("MCP Inspector Core")
@Feature("OwnerTokenCodec")
class OwnerTokenCodecTests {

	private static final byte[] SECRET = new byte[32];

	static {
		// deterministic test secret: 32 bytes 0x01..0x20
		for (int i = 0; i < SECRET.length; i++) {
			SECRET[i] = (byte) (i + 1);
		}
	}

	private final OwnerTokenCodec codec = new OwnerTokenCodec(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));

	private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

	@Nested
	@DisplayName("mint()")
	class Mint {

		@Test
		@Story("Mint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("mint() produces a three-part ownerId.expiresAt.hmac token whose signature validates")
		void mint_validOwner_returnsSignedToken() {
			// when
			final String token = OwnerTokenCodecTests.this.codec.mint("owner-1");

			// then
			final String[] parts = token.split("\\.");
			assertThat(parts).hasSize(3);
			assertThat(parts[0]).isEqualTo("owner-1");
			assertThat(parts[1]).isEqualTo(Long.toString(NOW.plus(OwnerTokenCodec.TOKEN_TTL).getEpochSecond()));
			assertThat(parts[2]).hasSize(64); // hex SHA-256
			assertThat(OwnerTokenCodecTests.this.codec.validate(token)).contains("owner-1");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("mint() rejects a blank owner id")
		void mint_withBlankOwner_throws() {
			// when/then
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> OwnerTokenCodecTests.this.codec.mint("  "))
				.isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	@DisplayName("validate()")
	class Validate {

		@Test
		@Story("Valid token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("validate() returns the embedded owner id for a freshly minted token")
		void validate_withMintedToken_returnsOwnerId() {
			// given
			final String token = OwnerTokenCodecTests.this.codec.mint("owner-42");

			// when
			final java.util.Optional<String> ownerId = OwnerTokenCodecTests.this.codec.validate(token);

			// then
			assertThat(ownerId).contains("owner-42");
		}

		@Test
		@Story("Forged token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("validate() rejects a bare unsigned UUID — the fixation attack must not yield an owner")
		void validate_withBareUnsignedUuid_returnsEmpty() {
			// given — attacker presents the victim's owner id without any signature
			final String token = "victim-owner-uuid";

			// when
			final java.util.Optional<String> ownerId = OwnerTokenCodecTests.this.codec.validate(token);

			// then
			assertThat(ownerId).isEmpty();
		}

		@Test
		@Story("Forged token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("validate() rejects a structurally-valid but unsigned ownerId.timestamp.garbage token")
		void validate_withUnsignedTriple_returnsEmpty() {
			// given — structurally valid shape, garbage HMAC
			final String token = "owner-1." + NOW.getEpochSecond() + ".deadbeef";

			// when
			final java.util.Optional<String> ownerId = OwnerTokenCodecTests.this.codec.validate(token);

			// then
			assertThat(ownerId).isEmpty();
		}

		@Test
		@Story("Forged token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("validate() rejects a token signed with a different secret (wrong HMAC)")
		void validate_withWrongHmac_returnsEmpty() throws Exception {
			// given — same payload, signed with a different key
			final byte[] otherSecret = new byte[32];
			for (int i = 0; i < otherSecret.length; i++) {
				otherSecret[i] = (byte) (0x40 + i);
			}
			final long expiresAt = NOW.plus(OwnerTokenCodec.TOKEN_TTL).getEpochSecond();
			final Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(otherSecret, "HmacSHA256"));
			final String hmac = HexFormat.of().formatHex(mac.doFinal(("owner-1." + expiresAt).getBytes()));
			final String token = "owner-1." + expiresAt + "." + hmac;

			// when
			final java.util.Optional<String> ownerId = OwnerTokenCodecTests.this.codec.validate(token);

			// then
			assertThat(ownerId).isEmpty();
		}

		@Test
		@Story("Expired token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("validate() rejects a token once the codec clock passes its TTL, even when the signature is valid")
		void validate_withExpiredToken_returnsEmpty() {
			// given — minted at NOW, validated one second past the 24h TTL
			final OwnerTokenCodec later = new OwnerTokenCodec(SECRET,
					Clock.fixed(NOW.plus(OwnerTokenCodec.TOKEN_TTL).plusSeconds(1), ZoneOffset.UTC));
			final String token = OwnerTokenCodecTests.this.codec.mint("owner-1");

			// when
			final java.util.Optional<String> ownerId = later.validate(token);

			// then
			assertThat(ownerId).isEmpty();
		}

		@Test
		@Story("Valid token")
		@Severity(SeverityLevel.CRITICAL)
		@Description("validate() still accepts a token at the exact TTL boundary (expiresAt == now)")
		void validate_atExactTtl_returnsOwnerId() {
			// given — minted at NOW, validated exactly at the 24h TTL (expiry is
			// exclusive)
			final OwnerTokenCodec atTtl = new OwnerTokenCodec(SECRET,
					Clock.fixed(NOW.plus(OwnerTokenCodec.TOKEN_TTL), ZoneOffset.UTC));
			final String token = OwnerTokenCodecTests.this.codec.mint("owner-1");

			// when
			final java.util.Optional<String> ownerId = atTtl.validate(token);

			// then
			assertThat(ownerId).contains("owner-1");
		}

		@Test
		@Story("Malformed token")
		@Severity(SeverityLevel.NORMAL)
		@Description("validate() rejects null, blank and wrongly-shaped tokens")
		void validate_withMalformedTokens_returnsEmpty() {
			// when/then
			assertThat(OwnerTokenCodecTests.this.codec.validate(null)).isEmpty();
			assertThat(OwnerTokenCodecTests.this.codec.validate("")).isEmpty();
			assertThat(OwnerTokenCodecTests.this.codec.validate("  ")).isEmpty();
			assertThat(OwnerTokenCodecTests.this.codec.validate("only-one-part")).isEmpty();
			assertThat(OwnerTokenCodecTests.this.codec.validate("owner.1.two.three.four")).isEmpty();
			assertThat(OwnerTokenCodecTests.this.codec.validate("owner.not-a-number.abcdef")).isEmpty();
			assertThat(OwnerTokenCodecTests.this.codec.validate(".123.abcdef")).isEmpty();
		}

		@Test
		@Story("Valid token")
		@Severity(SeverityLevel.NORMAL)
		@Description("validate() accepts a token minted with a different codec instance sharing the same secret")
		void validate_withSameSecretDifferentInstance_returnsOwnerId() {
			// given
			final OwnerTokenCodec other = new OwnerTokenCodec(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
			final String token = other.mint("owner-shared");

			// when
			final java.util.Optional<String> ownerId = OwnerTokenCodecTests.this.codec.validate(token);

			// then
			assertThat(ownerId).contains("owner-shared");
		}

	}

}
