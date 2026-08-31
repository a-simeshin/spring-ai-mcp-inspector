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

package io.inspector.mcp.core.protocol;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure (I/O-free) compatibility checker for MCP protocol revisions.
 * <p>
 * Compares a client's requested version against the server's negotiated version and
 * reports whether the mismatch makes methods unavailable on the wire. The wire runs at
 * the <em>negotiated</em> revision, so methods that the negotiated revision (or any
 * revision newer than it) removed from the spec are not available. If the client's
 * requested revision still expects those methods, they are reported as affected.
 * <p>
 * Two distinct mismatch cases are reported with different {@link Severity} values:
 * <ul>
 * <li>{@link Severity#DOWNGRADE DOWNGRADE} &mdash; client requested a newer revision than
 * the server negotiated. The server is older, so new features the client may rely on are
 * absent.</li>
 * <li>{@link Severity#INCOMPATIBLE INCOMPATIBLE} &mdash; client requested an older
 * revision than the server negotiated. The server is newer and has removed methods the
 * client still expects; calls to those methods will fail with
 * {@code MethodNotFound}.</li>
 * </ul>
 * <p>
 * Supported revisions and their method deltas are registered in a static table
 * ({@link #KNOWN_REVISIONS}) that is easy to extend with new spec revisions.
 *
 * @author Artem Simeshin
 */
public final class ProtocolRevision {

	/**
	 * Known spec revisions in chronological order, mapped to the set of methods that
	 * revision <em>removed</em> relative to its predecessor. The baseline revision
	 * (2025-11-25) removes nothing. Extend by adding a new entry; the comparison logic is
	 * table-driven, not an if-cascade.
	 */
	private static final NavigableMap<String, Set<String>> KNOWN_REVISIONS;

	static {
		final NavigableMap<String, Set<String>> revisions = new TreeMap<>();
		// 2025-11-25: baseline revision, nothing removed.
		revisions.put("2025-11-25", Set.of());
		// 2026-07-28: stateless protocol; removes handshake, ping, logging,
		// subscriptions, roots, tasks, and elicitation.
		revisions.put("2026-07-28", Set.of(
				// Major 2: initialize and notifications/initialized removed (stateless).
				"initialize", "notifications/initialized",
				// Major 4: resources/subscribe, resources/unsubscribe replaced by
				// subscriptions/listen.
				"resources/subscribe", "resources/unsubscribe",
				// Major 5: ping, logging/setLevel, notifications/roots/list_changed
				// removed.
				"ping", "logging/setLevel", "notifications/roots/list_changed",
				// Major 6: tasks/list and tasks/result moved to extension.
				"tasks/list", "tasks/result",
				// Minor 11: notifications/elicitation/complete removed (MRTR pattern).
				"notifications/elicitation/complete"));
		KNOWN_REVISIONS = revisions;
	}

	private ProtocolRevision() {
	}

	/**
	 * Check compatibility between a client-requested and a server-negotiated protocol
	 * revision.
	 * <p>
	 * The wire runs at the <em>negotiated</em> revision. Methods that the negotiated
	 * revision (or any revision newer than it) removed are not available. The result
	 * lists the methods that the client still expects (based on its requested revision)
	 * but are unavailable on the wire.
	 * @param requestedVersion the revision the client requested, e.g.
	 * {@code "2026-07-28"}
	 * @param negotiatedVersion the revision the server negotiated in its response
	 * @return a {@link CompatibilityResult} describing the mismatch
	 */
	public static CompatibilityResult check(final String requestedVersion, final String negotiatedVersion) {
		if (requestedVersion == null || negotiatedVersion == null) {
			return unknown("Revision is missing: requested=" + requestedVersion + ", negotiated=" + negotiatedVersion);
		}
		final Set<String> removedInRequested = KNOWN_REVISIONS.get(requestedVersion);
		final Set<String> removedInNegotiated = KNOWN_REVISIONS.get(negotiatedVersion);

		if (removedInRequested == null && removedInNegotiated == null) {
			return unknown(
					"Both revisions are unknown: requested=" + requestedVersion + ", negotiated=" + negotiatedVersion);
		}
		if (removedInRequested == null) {
			return unknown("Requested revision is unknown: " + requestedVersion);
		}
		if (removedInNegotiated == null) {
			return unknown("Negotiated revision is unknown: " + negotiatedVersion);
		}

		if (requestedVersion.equals(negotiatedVersion)) {
			return new CompatibilityResult(Severity.OK, List.of(),
					"Client and server agreed on revision " + requestedVersion + ".");
		}

		// Revision keys are ISO dates, so lexicographic order equals chronological
		// order.
		final int comparison = requestedVersion.compareTo(negotiatedVersion);
		if (comparison < 0) {
			// Client requested an OLDER revision than the server negotiated.
			// The server is running a newer protocol that may have removed methods
			// the client still expects. Compute affected methods: those removed by
			// revisions strictly between requested and negotiated (inclusive of
			// negotiated).
			final Set<String> affected = new LinkedHashSet<>();
			for (final Map.Entry<String, Set<String>> entry : KNOWN_REVISIONS.entrySet()) {
				if (entry.getKey().compareTo(requestedVersion) > 0
						&& entry.getKey().compareTo(negotiatedVersion) <= 0) {
					affected.addAll(entry.getValue());
				}
			}
			if (affected.isEmpty()) {
				return new CompatibilityResult(Severity.OK, List.of(), "Client requested revision " + requestedVersion
						+ ", server negotiated newer " + negotiatedVersion + ". Compatible.");
			}
			final List<String> affectedMethods = sortedCopy(affected);
			return new CompatibilityResult(Severity.INCOMPATIBLE, affectedMethods,
					"Client requested revision " + requestedVersion + " but server negotiated newer "
							+ negotiatedVersion + ". The server removed methods: " + affectedMethods
							+ ". Calls to these methods will fail with MethodNotFound.");
		}

		// Client requested a NEWER revision than the server negotiated: the server
		// is older. The table only tracks removals, not additions, so we cannot
		// enumerate the specific new features the client is missing. The summary
		// flags this as a downgrade.
		return new CompatibilityResult(Severity.DOWNGRADE, List.of(),
				"Client requested revision " + requestedVersion + " but server negotiated older " + negotiatedVersion
						+ ". The server is running an older protocol; new features" + " from " + requestedVersion
						+ " are unavailable.");
	}

	private static CompatibilityResult unknown(final String summary) {
		return new CompatibilityResult(Severity.UNKNOWN, List.of(), summary);
	}

	private static List<String> sortedCopy(final Set<String> values) {
		return values.stream().sorted().toList();
	}

	/**
	 * Severity of the version mismatch.
	 */
	public enum Severity {

		/** Client and server are compatible (same revision). */
		OK,
		/** Client requested a newer revision than the server negotiated. */
		DOWNGRADE,
		/** Client requested an older revision than the server negotiated. */
		INCOMPATIBLE,
		/** One or both revision strings are not recognised. */
		UNKNOWN

	}

	/**
	 * Result of a compatibility check.
	 *
	 * @param severity the severity of the mismatch
	 * @param affectedMethods methods unavailable on the negotiated revision, sorted and
	 * never {@code null}
	 * @param summary human-readable description of the outcome
	 */
	public record CompatibilityResult(Severity severity, List<String> affectedMethods, String summary) {

		public CompatibilityResult {
			affectedMethods = List.copyOf(affectedMethods);
		}

	}

}
