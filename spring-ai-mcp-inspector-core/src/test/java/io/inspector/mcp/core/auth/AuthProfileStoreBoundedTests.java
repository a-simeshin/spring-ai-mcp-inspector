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

import java.time.Duration;
import java.time.Instant;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bounded behavior tests for {@link AuthProfileStore}: per-owner cap, TTL sweep, and
 * cap-overflow protection (D1).
 */
@Epic("MCP Inspector Core")
@Feature("AuthProfileStore bounded behavior")
class AuthProfileStoreBoundedTests {

	private static final String OWNER_A = "owner-a";

	private static final String OWNER_B = "owner-b";

	private final AuthProfileStore store = new AuthProfileStore();

	private static BearerProfile bearer(final String name, final String token) {
		return new BearerProfile(name, token);
	}

	@Nested
	@DisplayName("per-owner cap")
	class Cap {

		@Test
		@Story("Cap enforcement")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() rejects the 51st profile for the same owner")
		void register_atCap_rejectsWithLimitMessage() {
			// given
			AuthProfileStoreBoundedTests.this.store.setMaxProfilesPerOwner(3);
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p1", "tok"));
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p2", "tok"));
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p3", "tok"));

			// when/then
			assertThatThrownBy(() -> AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p4", "tok")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("profile limit reached for this session (3)");
		}

		@Test
		@Story("Cap boundary")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() allows exactly the cap number of profiles")
		void register_atCapBoundary_allowsExactCap() {
			// given
			AuthProfileStoreBoundedTests.this.store.setMaxProfilesPerOwner(3);

			// when
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p1", "tok"));
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p2", "tok"));
			final String id3 = AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p3", "tok"));

			// then
			assertThat(id3).isNotBlank();
			assertThat(AuthProfileStoreBoundedTests.this.store.sizeForOwner(OWNER_A)).isEqualTo(3);
			assertThat(AuthProfileStoreBoundedTests.this.store.size()).isEqualTo(3);
		}

		@Test
		@Story("Per-owner isolation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the cap counts only the caller's owner, not other owners")
		void register_atCap_otherOwnerUnaffected() {
			// given
			AuthProfileStoreBoundedTests.this.store.setMaxProfilesPerOwner(2);
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("a1", "tok"));
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("a2", "tok"));

			// when
			final String b1 = AuthProfileStoreBoundedTests.this.store.register(OWNER_B, bearer("b1", "tok"));
			final String b2 = AuthProfileStoreBoundedTests.this.store.register(OWNER_B, bearer("b2", "tok"));

			// then
			assertThat(b1).isNotBlank();
			assertThat(b2).isNotBlank();
			assertThat(AuthProfileStoreBoundedTests.this.store.sizeForOwner(OWNER_A)).isEqualTo(2);
			assertThat(AuthProfileStoreBoundedTests.this.store.sizeForOwner(OWNER_B)).isEqualTo(2);
			assertThatThrownBy(() -> AuthProfileStoreBoundedTests.this.store.register(OWNER_B, bearer("b3", "tok")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("profile limit reached");
		}

		@Test
		@Story("Delete frees capacity")
		@Severity(SeverityLevel.CRITICAL)
		@Description("deleting a profile frees capacity for the owner to register again")
		void register_atCap_afterDelete_allowsAgain() {
			// given
			AuthProfileStoreBoundedTests.this.store.setMaxProfilesPerOwner(2);
			final String id1 = AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p1", "tok"));
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p2", "tok"));

			// when
			final boolean deleted = AuthProfileStoreBoundedTests.this.store.delete(OWNER_A, id1);
			final String id3 = AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p3", "tok"));

			// then
			assertThat(deleted).isTrue();
			assertThat(id3).isNotBlank();
			assertThat(AuthProfileStoreBoundedTests.this.store.sizeForOwner(OWNER_A)).isEqualTo(2);
		}

		@Test
		@Story("Rejection counter")
		@Severity(SeverityLevel.NORMAL)
		@Description("profileLimitRejections increments on each rejected register")
		void register_atCap_rejectionCounterIncrements() {
			// given
			AuthProfileStoreBoundedTests.this.store.setMaxProfilesPerOwner(1);
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p1", "tok"));

			// when
			assertThatThrownBy(() -> AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p2", "tok")));
			assertThatThrownBy(() -> AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p3", "tok")));

			// then
			assertThat(AuthProfileStoreBoundedTests.this.store.profileLimitRejections()).isEqualTo(2);
		}

	}

	@Nested
	@DisplayName("ACTIVE / BOUND profiles survive cap pressure")
	class ActiveBoundSurvival {

		@Test
		@Story("ACTIVE survives cap")
		@Severity(SeverityLevel.CRITICAL)
		@Description("PENDING -> ACTIVE at full cap: the ACTIVE profile survives cap overflow")
		void register_atCap_activeProfileSurvives() {
			// given: a PENDING auth-code profile and a full cap
			AuthProfileStoreBoundedTests.this.store.setMaxProfilesPerOwner(2);
			final String activeId = AuthProfileStoreBoundedTests.this.store.register(OWNER_A,
					new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "https://idp/token", "client-1", null,
							"mcp.read", "https://idp/auth", "https://app/callback", "challenge", "S256"));
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("bearer-1", "tok"));

			// when: mark the first ACTIVE and fill to overflow
			final boolean activated = AuthProfileStoreBoundedTests.this.store.markActive(OWNER_A, activeId);
			assertThat(activated).isTrue();
			assertThatThrownBy(() -> AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p3", "tok")));

			// then: the ACTIVE profile is still resolvable and bindable
			assertThat(AuthProfileStoreBoundedTests.this.store.resolve(OWNER_A, activeId)).isPresent();
			final boolean bound = AuthProfileStoreBoundedTests.this.store.bind(OWNER_A, activeId, "sess-1");
			assertThat(bound).isTrue();
		}

		@Test
		@Story("ACTIVE survives early sweep")
		@Severity(SeverityLevel.CRITICAL)
		@Description("removeExpired with a timestamp before the ACTIVE profile's TTL does not remove it")
		void activeProfile_survivesSweepBeforeExpiry() {
			// given: an ACTIVE profile with 24h TTL
			final String activeId = AuthProfileStoreBoundedTests.this.store.register(OWNER_A,
					new OAuth2Profile("ac", OAuth2GrantMode.AUTHORIZATION_CODE, "https://idp/token", "client-1", null,
							"mcp.read", "https://idp/auth", "https://app/callback", "challenge", "S256"));
			AuthProfileStoreBoundedTests.this.store.markActive(OWNER_A, activeId);

			// when: sweep with a timestamp well before the 24h TTL
			final Instant beforeExpiry = Instant.now().plus(Duration.ofHours(1));
			final int removed = AuthProfileStoreBoundedTests.this.store.removeExpired(beforeExpiry);

			// then: nothing removed; the ACTIVE profile is still resolvable
			assertThat(removed).isZero();
			assertThat(AuthProfileStoreBoundedTests.this.store.resolve(OWNER_A, activeId)).isPresent();
			assertThat(AuthProfileStoreBoundedTests.this.store.size()).isEqualTo(1);
		}

		@Test
		@Story("BOUND survives cap")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a BOUND profile at full cap is never removed by cap overflow")
		void boundProfile_survivesCapOverflow() {
			// given: a BOUND profile and a full cap
			AuthProfileStoreBoundedTests.this.store.setMaxProfilesPerOwner(2);
			final String boundId = AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("b1", "tok"));
			AuthProfileStoreBoundedTests.this.store.bind(OWNER_A, boundId, "sess-1");
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("b2", "tok"));

			// when: try to register beyond the cap
			assertThatThrownBy(() -> AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("b3", "tok")));

			// then: the BOUND profile is still resolvable
			assertThat(AuthProfileStoreBoundedTests.this.store.resolve(OWNER_A, boundId)).isPresent();
			assertThat(AuthProfileStoreBoundedTests.this.store.sizeForOwner(OWNER_A)).isEqualTo(2);
		}

	}

	@Nested
	@DisplayName("TTL sweep")
	class TtlSweep {

		@Test
		@Story("TTL sweep frees slots")
		@Severity(SeverityLevel.CRITICAL)
		@Description("removeExpired removes only entries past their TTL, freeing cap slots")
		void removeExpired_afterTtl_freesSlots() {
			// given: a store with 5-minute TTL and two entries
			AuthProfileStoreBoundedTests.this.store.setProfileTtl(Duration.ofMinutes(5));
			AuthProfileStoreBoundedTests.this.store.setMaxProfilesPerOwner(3);
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p1", "tok"));
			AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p2", "tok"));

			// when: sweep after the 5-minute TTL
			final Instant afterTtl = Instant.now().plus(Duration.ofMinutes(6));
			final int removed = AuthProfileStoreBoundedTests.this.store.removeExpired(afterTtl);

			// then: both removed; capacity freed for a new registration
			assertThat(removed).isEqualTo(2);
			assertThat(AuthProfileStoreBoundedTests.this.store.size()).isZero();
			assertThat(AuthProfileStoreBoundedTests.this.store.profilesExpiredTotal()).isEqualTo(2);
			final String id = AuthProfileStoreBoundedTests.this.store.register(OWNER_A, bearer("p3", "tok"));
			assertThat(id).isNotBlank();
		}

	}

}
