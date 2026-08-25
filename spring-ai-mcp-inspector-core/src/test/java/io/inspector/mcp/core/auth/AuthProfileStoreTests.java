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
import java.util.List;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AuthProfileStore} — owner-scoped CRUD, TTL, bind and token
 * eviction (D4/D8/D9A).
 */
@Epic("MCP Inspector Core")
@Feature("AuthProfileStore")
class AuthProfileStoreTests {

	private static final String OWNER_A = "owner-a";

	private static final String OWNER_B = "owner-b";

	private final AuthProfileStore store = new AuthProfileStore();

	private static BearerProfile bearer(final String name, final String token) {
		return new BearerProfile(name, token);
	}

	@Nested
	@DisplayName("register()")
	class Register {

		@Test
		@Story("Registration")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() stores the profile and returns a server-issued opaque profile id")
		void register_validProfile_returnsProfileId() {
			// when
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));

			// then
			assertThat(profileId).isNotBlank();
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_A, profileId)).contains(bearer("prod", "tok"));
			assertThat(AuthProfileStoreTests.this.store.size()).isEqualTo(1);
		}

		@Test
		@Story("Name uniqueness")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() rejects a duplicate name within the same owner")
		void register_duplicateNameSameOwner_throws() {
			// given
			AuthProfileStoreTests.this.store.register(OWNER_A, bearer("dup", "tok-1"));

			// when/then
			assertThatThrownBy(() -> AuthProfileStoreTests.this.store.register(OWNER_A, bearer("dup", "tok-2")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already exists");
		}

		@Test
		@Story("Name uniqueness")
		@Severity(SeverityLevel.CRITICAL)
		@Description("register() allows the same name under a DIFFERENT owner")
		void register_sameNameDifferentOwner_allows() {
			// given
			AuthProfileStoreTests.this.store.register(OWNER_A, bearer("dup", "tok-1"));

			// when
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_B, bearer("dup", "tok-2"));

			// then
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_B, profileId)).contains(bearer("dup", "tok-2"));
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("register() rejects blank names and null inputs")
		void register_invalidInputs_throws() {
			// when/then
			assertThatThrownBy(() -> AuthProfileStoreTests.this.store.register(OWNER_A, bearer(" ", "tok")))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> AuthProfileStoreTests.this.store.register(OWNER_A, null))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> AuthProfileStoreTests.this.store.register(" ", bearer("n", "tok")))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@Story("Auth-code pending")
		@Severity(SeverityLevel.NORMAL)
		@Description("register() stores an authorization-code profile as PENDING (not boundable, not resolvable as pending-resolve)")
		void register_authCodeProfile_isPending() {
			// given
			final OAuth2Profile pending = new OAuth2Profile("pc", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
					"cid", null, "scope", "https://t/auth", "https://app/cb", "ch", "S256");

			// when
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, pending);

			// then
			assertThat(AuthProfileStoreTests.this.store.resolvePending(OWNER_A, profileId)).contains(pending);
			assertThat(AuthProfileStoreTests.this.store.bind(OWNER_A, profileId, "s-1")).isFalse();
		}

	}

	@Nested
	@DisplayName("resolve() / resolvePending()")
	class Resolve {

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolve() returns empty for a foreign owner — existence is not leaked")
		void resolve_foreignOwner_returnsEmpty() {
			// given
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));

			// when/then
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_B, profileId)).isEmpty();
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_A, "unknown-id")).isEmpty();
			assertThat(AuthProfileStoreTests.this.store.resolve(null, profileId)).isEmpty();
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_A, null)).isEmpty();
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("resolvePending() returns empty for foreign owner and for non-pending profiles")
		void resolvePending_notPendingOrForeign_returnsEmpty() {
			// given
			final String registered = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));
			final OAuth2Profile pending = new OAuth2Profile("pc", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
					"cid", null, null, "https://t/auth", "https://app/cb", "ch", "S256");
			final String pendingId = AuthProfileStoreTests.this.store.register(OWNER_A, pending);

			// when/then
			assertThat(AuthProfileStoreTests.this.store.resolvePending(OWNER_A, registered)).isEmpty();
			assertThat(AuthProfileStoreTests.this.store.resolvePending(OWNER_B, pendingId)).isEmpty();
			assertThat(AuthProfileStoreTests.this.store.resolvePending(OWNER_A, pendingId)).contains(pending);
		}

	}

	@Nested
	@DisplayName("bind()")
	class Bind {

		@Test
		@Story("Binding")
		@Severity(SeverityLevel.CRITICAL)
		@Description("bind() binds a REGISTERED profile once and rejects a second bind (one-time)")
		void bind_registeredProfile_bindsOnceAndRejectsReuse() {
			// given
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));

			// when
			final boolean first = AuthProfileStoreTests.this.store.bind(OWNER_A, profileId, "s-1");
			final boolean second = AuthProfileStoreTests.this.store.bind(OWNER_A, profileId, "s-2");

			// then
			assertThat(first).isTrue();
			assertThat(second).isFalse();
			// bound profiles are immutable
			assertThat(AuthProfileStoreTests.this.store.update(OWNER_A, profileId, bearer("prod", "tok-2"))).isFalse();
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("bind() rejects a foreign owner, unknown id, pending profile and nulls")
		void bind_invalidTargets_returnsFalse() {
			// given
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));

			// when/then
			assertThat(AuthProfileStoreTests.this.store.bind(OWNER_B, profileId, "s-1")).isFalse();
			assertThat(AuthProfileStoreTests.this.store.bind(OWNER_A, "unknown", "s-1")).isFalse();
			assertThat(AuthProfileStoreTests.this.store.bind(null, profileId, "s-1")).isFalse();
			assertThat(AuthProfileStoreTests.this.store.bind(OWNER_A, null, "s-1")).isFalse();
			assertThat(AuthProfileStoreTests.this.store.bind(OWNER_A, profileId, null)).isFalse();
		}

		@Test
		@Story("Binding")
		@Severity(SeverityLevel.NORMAL)
		@Description("markActive() activates a PENDING profile so it becomes boundable")
		void bind_pendingProfileAfterMarkActive_binds() {
			// given
			final OAuth2Profile pending = new OAuth2Profile("pc", OAuth2GrantMode.AUTHORIZATION_CODE, "https://t/token",
					"cid", null, null, "https://t/auth", "https://app/cb", "ch", "S256");
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, pending);

			// when
			final boolean activated = AuthProfileStoreTests.this.store.markActive(OWNER_A, profileId);

			// then
			assertThat(activated).isTrue();
			assertThat(AuthProfileStoreTests.this.store.bind(OWNER_A, profileId, "s-1")).isTrue();
			assertThat(AuthProfileStoreTests.this.store.markActive(OWNER_A, profileId)).isFalse();
			assertThat(AuthProfileStoreTests.this.store.markActive(OWNER_B, profileId)).isFalse();
		}

	}

	@Nested
	@DisplayName("list()")
	class ListSummaries {

		@Test
		@Story("Listing")
		@Severity(SeverityLevel.CRITICAL)
		@Description("list() returns only the owner's profiles as secret-free summaries")
		void list_onlyOwnersProfiles_secretsExcluded() {
			// given
			AuthProfileStoreTests.this.store.register(OWNER_A, bearer("a-1", "secret-a"));
			final String a2 = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("a-2", "secret-b"));
			AuthProfileStoreTests.this.store.register(OWNER_B, bearer("b-1", "secret-c"));

			// when
			final List<AuthProfileSummary> summaries = AuthProfileStoreTests.this.store.list(OWNER_A);

			// then
			assertThat(summaries).hasSize(2);
			assertThat(summaries).extracting(AuthProfileSummary::name).containsExactlyInAnyOrder("a-1", "a-2");
			assertThat(summaries).extracting(AuthProfileSummary::type).containsOnly(AuthProfileType.BEARER);
			assertThat(summaries.stream().flatMap((summary) -> summary.toString().lines())).noneMatch(
					(line) -> line.contains("secret-a") || line.contains("secret-b") || line.contains("secret-c"));
			assertThat(AuthProfileStoreTests.this.store.list(OWNER_B)).hasSize(1);
			assertThat(AuthProfileStoreTests.this.store.list(null)).isEmpty();
		}

		@Test
		@Story("Listing")
		@Severity(SeverityLevel.NORMAL)
		@Description("list() renders per-type non-secret fields (keyName/placement for API-key, header names for custom)")
		void list_perTypeNonSecretFields_arePresent() {
			// given
			AuthProfileStoreTests.this.store.register(OWNER_A,
					new ApiKeyProfile("k", "X-API-Key", "secret-key", ApiKeyPlacement.QUERY));
			AuthProfileStoreTests.this.store.register(OWNER_A,
					new CustomHeadersProfile("c", List.of(new CustomHeader("X-Tenant", "secret-tenant"))));

			// when
			final List<AuthProfileSummary> summaries = AuthProfileStoreTests.this.store.list(OWNER_A);

			// then
			final AuthProfileSummary apiKey = summaries.stream()
				.filter((summary) -> summary.type() == AuthProfileType.API_KEY)
				.findFirst()
				.orElseThrow();
			assertThat(apiKey.keyName()).isEqualTo("X-API-Key");
			assertThat(apiKey.placement()).isEqualTo(ApiKeyPlacement.QUERY);
			assertThat(apiKey.toString()).doesNotContain("secret-key");

			final AuthProfileSummary custom = summaries.stream()
				.filter((summary) -> summary.type() == AuthProfileType.CUSTOM_HEADERS)
				.findFirst()
				.orElseThrow();
			assertThat(custom.headerNames()).containsExactly("X-Tenant");
			assertThat(custom.toString()).doesNotContain("secret-tenant");
		}

	}

	@Nested
	@DisplayName("update()")
	class Update {

		@Test
		@Story("Update")
		@Severity(SeverityLevel.CRITICAL)
		@Description("update() replaces the profile owner-scoped and evicts the old tokens")
		void update_ownedProfile_replacesAndEvicts() {
			// given
			final TokenEvictor evictor = mock(TokenEvictor.class);
			AuthProfileStoreTests.this.store.setTokenEvictor(evictor);
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok-1"));

			// when
			final boolean updated = AuthProfileStoreTests.this.store.update(OWNER_A, profileId,
					bearer("prod", "tok-2"));

			// then
			assertThat(updated).isTrue();
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_A, profileId)).contains(bearer("prod", "tok-2"));
			verify(evictor).evict(profileId);
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("update() returns false for foreign owner, unknown id and bound profiles")
		void update_foreignOrBound_returnsFalse() {
			// given
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));
			AuthProfileStoreTests.this.store.bind(OWNER_A, profileId, "s-1");

			// when/then
			assertThat(AuthProfileStoreTests.this.store.update(OWNER_B, profileId, bearer("prod", "tok-2"))).isFalse();
			assertThat(AuthProfileStoreTests.this.store.update(OWNER_A, "unknown", bearer("prod", "tok-2"))).isFalse();
			assertThat(AuthProfileStoreTests.this.store.update(OWNER_A, profileId, bearer("prod", "tok-2"))).isFalse();
		}

		@Test
		@Story("Update")
		@Severity(SeverityLevel.NORMAL)
		@Description("update() recomputes PENDING state for an authorization-code replacement and keeps the TTL")
		void update_toAuthCodeProfile_becomesPending() {
			// given
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));
			final OAuth2Profile pending = new OAuth2Profile("prod", OAuth2GrantMode.AUTHORIZATION_CODE,
					"https://t/token", "cid", null, null, "https://t/auth", "https://app/cb", "ch", "S256");

			// when
			final boolean updated = AuthProfileStoreTests.this.store.update(OWNER_A, profileId, pending);

			// then
			assertThat(updated).isTrue();
			assertThat(AuthProfileStoreTests.this.store.resolvePending(OWNER_A, profileId)).contains(pending);
			assertThat(AuthProfileStoreTests.this.store.bind(OWNER_A, profileId, "s-1")).isFalse();
		}

	}

	@Nested
	@DisplayName("delete() / clear()")
	class DeleteClear {

		@Test
		@Story("Deletion")
		@Severity(SeverityLevel.CRITICAL)
		@Description("delete() removes the profile and evicts its tokens")
		void delete_ownedProfile_removesAndEvicts() {
			// given
			final TokenEvictor evictor = mock(TokenEvictor.class);
			AuthProfileStoreTests.this.store.setTokenEvictor(evictor);
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));

			// when
			final boolean deleted = AuthProfileStoreTests.this.store.delete(OWNER_A, profileId);

			// then
			assertThat(deleted).isTrue();
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_A, profileId)).isEmpty();
			assertThat(AuthProfileStoreTests.this.store.size()).isZero();
			verify(evictor).evict(profileId);
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("delete() returns false for a foreign owner — a profile is never deletable across owners")
		void delete_foreignOwner_returnsFalseAndDoesNotEvict() {
			// given
			final TokenEvictor evictor = mock(TokenEvictor.class);
			AuthProfileStoreTests.this.store.setTokenEvictor(evictor);
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));

			// when
			final boolean deleted = AuthProfileStoreTests.this.store.delete(OWNER_B, profileId);

			// then
			assertThat(deleted).isFalse();
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_A, profileId)).isPresent();
			verify(evictor, never()).evict(profileId);
		}

		@Test
		@Story("Deletion")
		@Severity(SeverityLevel.NORMAL)
		@Description("clear() removes by profile id regardless of owner and evicts; unknown ids return false")
		void clear_anyOwner_removesAndEvicts() {
			// given
			final TokenEvictor evictor = mock(TokenEvictor.class);
			AuthProfileStoreTests.this.store.setTokenEvictor(evictor);
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));

			// when
			final boolean cleared = AuthProfileStoreTests.this.store.clear(profileId);

			// then
			assertThat(cleared).isTrue();
			assertThat(AuthProfileStoreTests.this.store.size()).isZero();
			verify(evictor).evict(profileId);
			assertThat(AuthProfileStoreTests.this.store.clear(profileId)).isFalse();
			assertThat(AuthProfileStoreTests.this.store.clear(null)).isFalse();
		}

	}

	@Nested
	@DisplayName("clearBySession()")
	class ClearBySession {

		@Test
		@Story("Cleanup")
		@Severity(SeverityLevel.CRITICAL)
		@Description("clearBySession() removes every profile bound to the session and evicts each")
		void clearBySession_boundProfiles_removedAndEvicted() {
			// given
			final TokenEvictor evictor = mock(TokenEvictor.class);
			AuthProfileStoreTests.this.store.setTokenEvictor(evictor);
			final String p1 = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("a", "t1"));
			final String p2 = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("b", "t2"));
			AuthProfileStoreTests.this.store.bind(OWNER_A, p1, "s-1");
			AuthProfileStoreTests.this.store.bind(OWNER_A, p2, "s-1");

			// when
			final int removed = AuthProfileStoreTests.this.store.clearBySession("s-1");

			// then
			assertThat(removed).isEqualTo(2);
			assertThat(AuthProfileStoreTests.this.store.size()).isZero();
			verify(evictor).evict(p1);
			verify(evictor).evict(p2);
			assertThat(AuthProfileStoreTests.this.store.clearBySession("s-1")).isZero();
			assertThat(AuthProfileStoreTests.this.store.clearBySession(null)).isZero();
		}

		@Test
		@Story("Owner scoping")
		@Severity(SeverityLevel.NORMAL)
		@Description("clearBySession() leaves unbound profiles and other sessions untouched")
		void clearBySession_onlyBoundProfiles_removed() {
			// given
			final String bound = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("bound", "t1"));
			final String unbound = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("unbound", "t2"));
			AuthProfileStoreTests.this.store.bind(OWNER_A, bound, "s-1");

			// when
			final int removed = AuthProfileStoreTests.this.store.clearBySession("s-1");

			// then
			assertThat(removed).isEqualTo(1);
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_A, unbound)).isPresent();
		}

	}

	@Nested
	@DisplayName("removeExpired()")
	class RemoveExpired {

		@Test
		@Story("TTL sweep")
		@Severity(SeverityLevel.CRITICAL)
		@Description("removeExpired() removes entries past their TTL and evicts their tokens; live entries survive")
		void removeExpired_expiredEntries_removedAndEvicted() {
			// given
			final TokenEvictor evictor = mock(TokenEvictor.class);
			AuthProfileStoreTests.this.store.setTokenEvictor(evictor);
			AuthProfileStoreTests.this.store.setProfileTtl(Duration.ofMinutes(1));
			final String p1 = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("a", "t1"));
			final String p2 = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("b", "t2"));
			final Instant now = Instant.now();

			// when
			final int removed = AuthProfileStoreTests.this.store.removeExpired(now.plusSeconds(120));

			// then
			assertThat(removed).isEqualTo(2);
			assertThat(AuthProfileStoreTests.this.store.size()).isZero();
			verify(evictor).evict(p1);
			verify(evictor).evict(p2);

			// and a sweep before expiry removes nothing
			AuthProfileStoreTests.this.store.register(OWNER_A, bearer("c", "t3"));
			assertThat(AuthProfileStoreTests.this.store.removeExpired(now.minusSeconds(10))).isZero();
			assertThat(AuthProfileStoreTests.this.store.removeExpired(null)).isZero();
		}

	}

	@Nested
	@DisplayName("TTL configuration")
	class TtlConfiguration {

		@Test
		@Story("TTL")
		@Severity(SeverityLevel.NORMAL)
		@Description("setProfileTtl() falls back to the default for null / zero / negative durations")
		void setProfileTtl_invalidDurations_fallBackToDefault() {
			// when/then
			AuthProfileStoreTests.this.store.setProfileTtl(null);
			assertThat(AuthProfileStoreTests.this.store.resolve("o",
					AuthProfileStoreTests.this.store.register("o", bearer("n", "t"))))
				.isPresent();
			AuthProfileStoreTests.this.store.setProfileTtl(Duration.ZERO);
			AuthProfileStoreTests.this.store.setProfileTtl(Duration.ofSeconds(-5));
		}

		@Test
		@Story("TTL")
		@Severity(SeverityLevel.NORMAL)
		@Description("removeExpired() honors a custom short TTL from registration time")
		void removeExpired_shortTtl_expiresSoon() {
			// given
			AuthProfileStoreTests.this.store.setProfileTtl(Duration.ofSeconds(5));
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("short", "t"));

			// when
			final int removed = AuthProfileStoreTests.this.store.removeExpired(Instant.now().plusSeconds(10));

			// then
			assertThat(removed).isEqualTo(1);
			assertThat(AuthProfileStoreTests.this.store.resolve(OWNER_A, profileId)).isEmpty();
		}

	}

	@Nested
	@DisplayName("TokenEvictor wiring")
	class TokenEvictorWiring {

		@Test
		@Story("Eviction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a throwing TokenEvictor never breaks the removal path (eviction failures are logged, not propagated)")
		void evictorThatThrows_removalStillSucceeds() {
			// given
			final TokenEvictor evictor = mock(TokenEvictor.class);
			willThrow(new RuntimeException("token store gone")).given(evictor).evict(anyString());
			AuthProfileStoreTests.this.store.setTokenEvictor(evictor);
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));

			// when
			final boolean deleted = AuthProfileStoreTests.this.store.delete(OWNER_A, profileId);

			// then
			assertThat(deleted).isTrue();
			assertThat(AuthProfileStoreTests.this.store.size()).isZero();
		}

		@Test
		@Story("Eviction")
		@Severity(SeverityLevel.NORMAL)
		@Description("no evictor wired means removal paths run without eviction")
		void withoutEvictor_removalSucceeds() {
			// given — no evictor set
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok"));

			// when
			final boolean deleted = AuthProfileStoreTests.this.store.delete(OWNER_A, profileId);

			// then
			assertThat(deleted).isTrue();
			assertThat(AuthProfileStoreTests.this.store.size()).isZero();
		}

		@Test
		@Story("Eviction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a credential-bearing update evicts; a non-credential update path still evicts per D9A")
		void update_alwaysEvictsPerRemovalContract() {
			// given
			final TokenEvictor evictor = mock(TokenEvictor.class);
			AuthProfileStoreTests.this.store.setTokenEvictor(evictor);
			final String profileId = AuthProfileStoreTests.this.store.register(OWNER_A, bearer("prod", "tok-1"));

			// when
			AuthProfileStoreTests.this.store.update(OWNER_A, profileId,
					new ApiKeyProfile("prod", "X-Key", "new-secret", ApiKeyPlacement.HEADER));

			// then
			final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
			verify(evictor).evict(captor.capture());
			assertThat(captor.getValue()).isEqualTo(profileId);
		}

	}

}
