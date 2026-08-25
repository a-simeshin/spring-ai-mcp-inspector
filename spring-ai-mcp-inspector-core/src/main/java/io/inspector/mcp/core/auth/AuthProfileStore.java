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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

/**
 * Owner-scoped, in-memory registry of named authentication profiles.
 *
 * <p>
 * Every profile is keyed by a server-issued opaque {@code profileId} and scoped to the
 * owning browser session's {@code ownerId} (derived from the signed
 * {@code MCP_INSPECTOR_SESSION} cookie, see {@link OwnerTokenCodec}). A profile is never
 * visible, usable or mutable across owners — all owner-scoped operations return empty /
 * {@code false} / throw for foreign ids without leaking existence.
 *
 * <p>
 * Profile {@code name}s are unique within an owner. Entries carry a bounded TTL
 * ({@link #setProfileTtl(Duration)}, default 24h) and are swept by
 * {@link #removeExpired(Instant)} — the {@code ProxySessionRegistry} reaper drives that
 * sweep. A profile may be bound to at most one proxy session (one-time
 * {@code REGISTERED/ACTIVE → BOUND} transition, rejected reuse); a bound profile is
 * immutable (updates rejected).
 *
 * <p>
 * The store is pure CRUD — it never performs OAuth exchanges itself. When a
 * {@link TokenEvictor} is set via {@link #setTokenEvictor(TokenEvictor)}, every removal
 * path (delete / clear / clearBySession / removeExpired / update) invokes
 * {@code evict(profileId)} for each removed profile so the token managers drop cached
 * tokens and stored credentials together with the profile.
 *
 * @author Artem Simeshin
 */
public class AuthProfileStore {

	private static final Logger LOG = LoggerFactory.getLogger(AuthProfileStore.class);

	/** Default profile TTL when none is configured. */
	public static final Duration DEFAULT_PROFILE_TTL = Duration.ofHours(24);

	/** All stored profiles keyed by profileId. */
	private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

	/** Optional eviction hook into the OAuth2 token machinery. */
	private volatile TokenEvictor tokenEvictor;

	/** Bounded lifetime of a stored profile entry. */
	private volatile Duration profileTtl = DEFAULT_PROFILE_TTL;

	/**
	 * Registers {@code profile} under {@code ownerId} and returns the new server-issued
	 * profile id. The profile name must be non-blank and unique within the owner. An
	 * authorization-code OAuth2 profile is stored in {@code PENDING} state, everything
	 * else in {@code REGISTERED} state.
	 * @param ownerId the owning browser session id (never {@code null})
	 * @param profile the profile to store (never {@code null})
	 * @return the new opaque profile id
	 * @throws IllegalArgumentException on a blank name or a duplicate name within the
	 * owner
	 */
	public String register(final String ownerId, final AuthProfile profile) {
		Assert.hasText(ownerId, "ownerId must not be blank");
		Assert.notNull(profile, "profile must not be null");
		Assert.hasText(profile.name(), "profile.name must not be blank");
		synchronized (this) {
			Assert.state(findByName(ownerId, profile.name()).isEmpty(),
					"a profile named '" + profile.name() + "' already exists for this session");
			final String profileId = UUID.randomUUID().toString();
			final ProfileState state = isPendingAuthCode(profile) ? ProfileState.PENDING : ProfileState.REGISTERED;
			this.entries.put(profileId,
					new Entry(ownerId, profileId, profile, state, Instant.now().plus(this.profileTtl), null));
			LOG.debug("auth-profile[{}] registered by owner {} (state {})", profileId, ownerId, state);
			return profileId;
		}
	}

	/**
	 * Resolves the profile for {@code profileId} if it belongs to {@code ownerId}.
	 * Foreign or unknown ids yield empty — existence is not leaked.
	 * @param ownerId the owning browser session id
	 * @param profileId the profile id
	 * @return the stored profile (with secrets), or empty
	 */
	public Optional<AuthProfile> resolve(final String ownerId, final String profileId) {
		if (ownerId == null || profileId == null) {
			return Optional.empty();
		}
		final Entry entry = this.entries.get(profileId);
		if (entry == null || !entry.ownerId().equals(ownerId)) {
			return Optional.empty();
		}
		return Optional.of(entry.profile());
	}

	/**
	 * Resolves a PENDING (authorization-code) profile for {@code profileId} if it belongs
	 * to {@code ownerId}.
	 * @param ownerId the owning browser session id
	 * @param profileId the profile id
	 * @return the pending profile, or empty when unknown / foreign / not pending
	 */
	public Optional<AuthProfile> resolvePending(final String ownerId, final String profileId) {
		if (ownerId == null || profileId == null) {
			return Optional.empty();
		}
		final Entry entry = this.entries.get(profileId);
		if (entry == null || !entry.ownerId().equals(ownerId) || entry.state() != ProfileState.PENDING) {
			return Optional.empty();
		}
		return Optional.of(entry.profile());
	}

	/**
	 * Binds {@code profileId} to {@code sessionId}, owner-scoped and one-time. Only
	 * {@code REGISTERED} and {@code ACTIVE} profiles can be bound; a {@code PENDING}
	 * profile must be exchanged first, and a {@code BOUND} profile rejects reuse.
	 * @param ownerId the owning browser session id
	 * @param profileId the profile id
	 * @param sessionId the proxy session id to bind to
	 * @return {@code true} when the binding succeeded, {@code false} otherwise (unknown /
	 * foreign / pending / already-bound)
	 */
	public boolean bind(final String ownerId, final String profileId, final String sessionId) {
		if (ownerId == null || profileId == null || sessionId == null) {
			return false;
		}
		synchronized (this) {
			final Entry entry = this.entries.get(profileId);
			if (entry == null || !entry.ownerId().equals(ownerId)) {
				return false;
			}
			if (entry.state() != ProfileState.REGISTERED && entry.state() != ProfileState.ACTIVE) {
				return false;
			}
			this.entries.put(profileId, new Entry(entry.ownerId(), profileId, entry.profile(), ProfileState.BOUND,
					entry.expiresAt(), sessionId));
			LOG.debug("auth-profile[{}] bound to session {}", profileId, sessionId);
			return true;
		}
	}

	/**
	 * Removes the profile {@code profileId} (any owner — internal cleanup path) and
	 * evicts its tokens.
	 * @param profileId the profile id
	 * @return {@code true} when a profile was removed
	 */
	public boolean clear(final String profileId) {
		if (profileId == null) {
			return false;
		}
		synchronized (this) {
			final Entry removed = this.entries.remove(profileId);
			if (removed == null) {
				return false;
			}
			evict(profileId);
			LOG.debug("auth-profile[{}] cleared", profileId);
			return true;
		}
	}

	/**
	 * Removes every profile bound to {@code sessionId} and evicts their tokens.
	 * @param sessionId the proxy session id
	 * @return the number of removed profiles
	 */
	public int clearBySession(final String sessionId) {
		if (sessionId == null) {
			return 0;
		}
		synchronized (this) {
			final List<String> removed = this.entries.values()
				.stream()
				.filter((entry) -> sessionId.equals(entry.boundSessionId()))
				.map(Entry::profileId)
				.toList();
			removed.forEach(this.entries::remove);
			removed.forEach(this::evict);
			if (!removed.isEmpty()) {
				LOG.debug("auth-profile: cleared {} profiles bound to session {}", removed.size(), sessionId);
			}
			return removed.size();
		}
	}

	/**
	 * Removes every entry whose TTL has passed and evicts their tokens.
	 * @param now the sweep timestamp
	 * @return the number of removed profiles
	 */
	public int removeExpired(final Instant now) {
		if (now == null) {
			return 0;
		}
		synchronized (this) {
			final List<String> expired = this.entries.values()
				.stream()
				.filter((entry) -> !entry.expiresAt().isAfter(now))
				.map(Entry::profileId)
				.toList();
			expired.forEach(this.entries::remove);
			expired.forEach(this::evict);
			if (!expired.isEmpty()) {
				LOG.debug("auth-profile: removed {} expired profiles", expired.size());
			}
			return expired.size();
		}
	}

	/**
	 * Lists the owner's profiles as secret-free summaries.
	 * @param ownerId the owning browser session id
	 * @return the owner's profile summaries (never {@code null})
	 */
	public List<AuthProfileSummary> list(final String ownerId) {
		if (ownerId == null) {
			return List.of();
		}
		return this.entries.values()
			.stream()
			.filter((entry) -> entry.ownerId().equals(ownerId))
			.map((entry) -> AuthProfileSummary.from(entry.profileId(), entry.profile()))
			.toList();
	}

	/**
	 * Replaces the profile for {@code profileId} (owner-scoped). Allowed only while the
	 * profile is not bound; the profile's lifecycle state is recomputed for the new
	 * shape. Evicts the profile's tokens so a stale credential cannot outlive the update.
	 * @param ownerId the owning browser session id
	 * @param profileId the profile id
	 * @param profile the replacement profile
	 * @return {@code true} when updated, {@code false} for unknown / foreign / bound ids
	 */
	public boolean update(final String ownerId, final String profileId, final AuthProfile profile) {
		if (ownerId == null || profileId == null || profile == null) {
			return false;
		}
		Assert.hasText(profile.name(), "profile.name must not be blank");
		synchronized (this) {
			final Entry entry = this.entries.get(profileId);
			if (entry == null || !entry.ownerId().equals(ownerId)) {
				return false;
			}
			if (entry.state() == ProfileState.BOUND) {
				return false;
			}
			final ProfileState nextState = isPendingAuthCode(profile) ? ProfileState.PENDING : ProfileState.REGISTERED;
			this.entries.put(profileId, entry.withProfile(profile).withState(nextState));
			evict(profileId);
			LOG.debug("auth-profile[{}] updated by owner {}", profileId, ownerId);
			return true;
		}
	}

	/**
	 * Deletes the profile for {@code profileId} (owner-scoped) and evicts its tokens.
	 * @param ownerId the owning browser session id
	 * @param profileId the profile id
	 * @return {@code true} when deleted, {@code false} for unknown / foreign ids
	 */
	public boolean delete(final String ownerId, final String profileId) {
		if (ownerId == null || profileId == null) {
			return false;
		}
		synchronized (this) {
			final Entry entry = this.entries.get(profileId);
			if (entry == null || !entry.ownerId().equals(ownerId)) {
				return false;
			}
			this.entries.remove(profileId);
			evict(profileId);
			LOG.debug("auth-profile[{}] deleted by owner {}", profileId, ownerId);
			return true;
		}
	}

	/**
	 * Marks a PENDING authorization-code profile ACTIVE after its code was exchanged
	 * successfully.
	 * @param ownerId the owning browser session id
	 * @param profileId the profile id
	 * @return {@code true} when the transition happened, {@code false} for unknown /
	 * foreign / non-pending ids
	 */
	public boolean markActive(final String ownerId, final String profileId) {
		if (ownerId == null || profileId == null) {
			return false;
		}
		synchronized (this) {
			final Entry entry = this.entries.get(profileId);
			if (entry == null || !entry.ownerId().equals(ownerId) || entry.state() != ProfileState.PENDING) {
				return false;
			}
			this.entries.put(profileId, entry.withState(ProfileState.ACTIVE));
			LOG.debug("auth-profile[{}] activated by owner {}", profileId, ownerId);
			return true;
		}
	}

	/**
	 * Sets the eviction hook invoked on every removal path. May be replaced at runtime
	 * (bean wiring happens after construction).
	 * @param tokenEvictor the evictor, or {@code null} to disable eviction
	 */
	public void setTokenEvictor(final TokenEvictor tokenEvictor) {
		this.tokenEvictor = tokenEvictor;
	}

	/**
	 * Sets the bounded lifetime of stored profile entries.
	 * @param profileTtl the TTL; non-positive values fall back to the default
	 */
	public void setProfileTtl(final Duration profileTtl) {
		this.profileTtl = (profileTtl != null && !profileTtl.isNegative() && !profileTtl.isZero()) ? profileTtl
				: DEFAULT_PROFILE_TTL;
	}

	/**
	 * Current store size — intended for tests / metrics.
	 * @return the number of stored profile entries
	 */
	public int size() {
		return this.entries.size();
	}

	/**
	 * Finds an owner's entry by profile name.
	 * @param ownerId the owner id
	 * @param name the profile name
	 * @return the matching entry, or empty
	 */
	private Optional<Entry> findByName(final String ownerId, final String name) {
		return this.entries.values()
			.stream()
			.filter((entry) -> entry.ownerId().equals(ownerId) && entry.profile().name().equals(name))
			.findFirst();
	}

	private static boolean isPendingAuthCode(final AuthProfile profile) {
		return profile instanceof OAuth2Profile oauth2 && oauth2.grantMode() == OAuth2GrantMode.AUTHORIZATION_CODE;
	}

	private void evict(final String profileId) {
		final TokenEvictor evictor = this.tokenEvictor;
		if (evictor != null) {
			try {
				evictor.evict(profileId);
			}
			catch (final RuntimeException ex) {
				LOG.warn("auth-profile[{}] token eviction failed: {}", profileId, ex.toString());
			}
		}
	}

	/**
	 * Lifecycle state of a stored profile.
	 */
	public enum ProfileState {

		/** Registered, not yet bound to a proxy session. */
		REGISTERED,

		/** Bound to a proxy session (one-time; immutable while bound). */
		BOUND,

		/** Authorization-code profile awaiting the browser {@code /exchange}. */
		PENDING,

		/**
		 * Authorization-code profile whose code was exchanged; token held backend-side.
		 */
		ACTIVE,

	}

	/**
	 * A stored profile entry.
	 *
	 * @param ownerId the owning browser-session id
	 * @param profileId the server-issued profile id
	 * @param profile the stored profile (with secrets)
	 * @param state the lifecycle state
	 * @param expiresAt the TTL expiry instant
	 * @param boundSessionId the bound proxy session id, or {@code null}
	 */
	private record Entry(String ownerId, String profileId, AuthProfile profile, ProfileState state, Instant expiresAt,
			String boundSessionId) {

		Entry withProfile(final AuthProfile newProfile) {
			return new Entry(this.ownerId, this.profileId, newProfile, this.state, this.expiresAt, this.boundSessionId);
		}

		Entry withState(final ProfileState newState) {
			return new Entry(this.ownerId, this.profileId, this.profile, newState, this.expiresAt, this.boundSessionId);
		}

	}

}
