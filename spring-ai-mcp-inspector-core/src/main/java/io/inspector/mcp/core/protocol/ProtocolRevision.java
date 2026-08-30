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
 * reports whether the mismatch constitutes a downgrade, along with the methods affected
 * by the gap. Supported revisions and their method deltas are registered in a static
 * table ({@link #KNOWN_REVISIONS}) that is easy to extend with new spec revisions.
 * <p>
 * Revision 2026-07-28 removes {@code ping}, {@code logging/setLevel},
 * {@code resources/subscribe} and {@code resources/unsubscribe} and moves the core to
 * stateless semantics: a peer pinned to the older revision answers these calls with
 * {@code MethodNotFound}. A client that requested 2026-07-28 while the server negotiated
 * an older revision therefore silently loses those four methods, and the mismatch is
 * reported here as {@link Severity#DOWNGRADE} with the affected methods listed.
 *
 * @author Artem Simeshin
 */
public final class ProtocolRevision {

	/**
	 * Known spec revisions in chronological order, mapped to the set of methods that
	 * revision removed relative to its predecessor. Extend by adding a new entry; the
	 * comparison logic is table-driven, not an if-cascade.
	 */
	private static final NavigableMap<String, Set<String>> KNOWN_REVISIONS;

	static {
		final NavigableMap<String, Set<String>> revisions = new TreeMap<>();
		// 2025-11-25: baseline revision, nothing removed.
		revisions.put("2025-11-25", Set.of());
		// 2026-07-28: drops ping, logging/setLevel, resources/subscribe,
		// resources/unsubscribe; core becomes stateless.
		revisions.put("2026-07-28", Set.of("ping", "logging/setLevel", "resources/subscribe", "resources/unsubscribe"));
		KNOWN_REVISIONS = revisions;
	}

	private ProtocolRevision() {
	}

	/**
	 * Check compatibility between a client-requested and a server-negotiated protocol
	 * revision.
	 * @param requestedVersion the revision the client requested in its {@code initialize}
	 * call, e.g. {@code "2026-07-28"}
	 * @param negotiatedVersion the revision the server negotiated in its
	 * {@code initialize} response
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

		// Revision keys are ISO dates, so lexicographic order equals chronological order.
		final int comparison = requestedVersion.compareTo(negotiatedVersion);
		if (comparison < 0) {
			// Client asked for an older revision than the server negotiated: the server
			// superset satisfies it.
			return new CompatibilityResult(Severity.OK, List.of(), "Client requested revision " + requestedVersion
					+ ", server negotiated newer " + negotiatedVersion + ". Compatible.");
		}

		// Client requested a NEWER revision than the server negotiated: downgrade.
		// Every method removed by a revision strictly newer than the negotiated one, up
		// to and including the requested revision, is unavailable on this connection.
		final Set<String> affected = new LinkedHashSet<>();
		for (final Map.Entry<String, Set<String>> entry : KNOWN_REVISIONS.entrySet()) {
			if (entry.getKey().compareTo(negotiatedVersion) > 0 && entry.getKey().compareTo(requestedVersion) <= 0) {
				affected.addAll(entry.getValue());
			}
		}
		final List<String> affectedMethods = sortedCopy(affected);
		return new CompatibilityResult(Severity.DOWNGRADE, affectedMethods,
				"Client requested revision " + requestedVersion + " but server negotiated " + negotiatedVersion
						+ ". Calls to " + affectedMethods + " will fail with MethodNotFound.");
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

		/** Client and server are compatible (same revision, or server is newer). */
		OK,
		/** Client requested a newer revision than the server negotiated. */
		DOWNGRADE,
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
