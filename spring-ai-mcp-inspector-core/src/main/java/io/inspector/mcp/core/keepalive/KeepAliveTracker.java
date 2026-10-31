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

package io.inspector.mcp.core.keepalive;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * Tracks inbound MCP keep-alive {@code ping} requests from a target server for one
 * inspector session.
 *
 * <p>
 * Production motivation: spring-projects/spring-ai#4862. Behind a load balancer with a
 * 60s idle timeout (ALB, nginx), a silent keep-alive drop leaves the client believing the
 * connection is alive. The inspector must surface the actual ping cadence so a developer
 * can see whether pings are flowing at all, and tell apart "server never sends pings"
 * from "pings stopped".
 *
 * <p>
 * The tracker is per-session and thread-safe. It stores the last {@value #MAX_PINGS} ping
 * timestamps, computes a rolling median interval over the gaps between them, and reports
 * {@link State#isStale() staleness} when {@code now - lastPingAt > 2 *
 * estimatedInterval}. With a single ping observed the interval cannot be estimated, so
 * {@code isStale} falls back to the fixed {@value #STALE_FALLBACK_MILLIS}ms threshold.
 * With zero pings observed the same fixed threshold applies, anchored at the moment
 * tracking started, so a session whose transport never reports a single ping is still
 * flagged after {@value #STALE_FALLBACK_MILLIS}ms of silence (required by the tracking
 * issue).
 *
 * @author Artem Simeshin
 */
public final class KeepAliveTracker {

	/**
	 * Maximum number of recent ping timestamps retained per session. Sized so the UI can
	 * render a useful "last N" without unbounded growth.
	 */
	public static final int MAX_PINGS = 10;

	/**
	 * Staleness multiplier: a session is stale when the silence exceeds
	 * {@value #STALE_FACTOR} times the estimated interval.
	 */
	public static final int STALE_FACTOR = 2;

	/**
	 * Fallback staleness threshold used when the interval cannot be estimated yet (fewer
	 * than two pings observed): the session is stale once the silence since the last ping
	 * (or, with zero pings, since tracking started) exceeds this value. Required by the
	 * tracking issue.
	 */
	public static final long STALE_FALLBACK_MILLIS = 90_000L;

	private final Deque<Instant> pings = new ArrayDeque<>(MAX_PINGS);

	private volatile Consumer<Instant> listener;

	private final Instant trackingSince;

	/**
	 * Creates a tracker whose zero-ping staleness fallback window starts now.
	 */
	public KeepAliveTracker() {
		this(Instant.now());
	}

	/**
	 * Creates a tracker whose zero-ping staleness fallback window starts at
	 * {@code trackingSince}, the moment the session began tracking.
	 * @param trackingSince the anchor for the {@link #STALE_FALLBACK_MILLIS} fallback
	 * when no ping has been observed yet (must not be {@code null})
	 */
	public KeepAliveTracker(final Instant trackingSince) {
		this.trackingSince = Objects.requireNonNull(trackingSince, "trackingSince");
	}

	/**
	 * Registers a listener invoked with the timestamp of each recorded ping. Used to push
	 * live keep-alive events to UI subscribers without polling. Replaces any previously
	 * registered listener.
	 * @param listener the listener; {@code null} clears the current one
	 */
	public void setListener(final Consumer<Instant> listener) {
		this.listener = listener;
	}

	/**
	 * Records an inbound ping at {@code at}. Oldest entries beyond {@link #MAX_PINGS} are
	 * evicted. The registered listener, if any, is invoked after the timestamp has been
	 * added.
	 * @param at the ping timestamp (must not be {@code null})
	 */
	public void recordPing(final Instant at) {
		if (at == null) {
			return;
		}
		synchronized (this) {
			this.pings.addLast(at);
			while (this.pings.size() > MAX_PINGS) {
				this.pings.removeFirst();
			}
		}
		final Consumer<Instant> current = this.listener;
		if (current != null) {
			try {
				current.accept(at);
			}
			catch (final RuntimeException ignored) {
				// Listener failures must not break the recording path.
			}
		}
	}

	/**
	 * Returns the most recent ping timestamp, or {@code null} when no ping has been
	 * observed yet.
	 * @return last ping timestamp, or {@code null}
	 */
	public synchronized Instant lastPingAt() {
		return this.pings.peekLast();
	}

	/**
	 * Returns the recent ping timestamps, oldest first, up to {@link #MAX_PINGS} entries.
	 * The returned list is an immutable snapshot.
	 * @return immutable snapshot of recent pings
	 */
	public synchronized List<Instant> recentPings() {
		return List.copyOf(this.pings);
	}

	/**
	 * Estimates the ping interval as a rolling median over the gaps between the retained
	 * pings. Needs at least two pings; with fewer, the result is empty so the caller can
	 * suppress the staleness warning instead of guessing.
	 * @return the estimated interval, or empty when fewer than two pings observed
	 */
	public synchronized OptionalLong estimatedIntervalMillis() {
		if (this.pings.size() < 2) {
			return OptionalLong.empty();
		}
		final List<Long> gaps = new ArrayList<>(this.pings.size() - 1);
		Instant previous = null;
		for (final Instant current : this.pings) {
			if (previous != null) {
				gaps.add(Duration.between(previous, current).toMillis());
			}
			previous = current;
		}
		Collections.sort(gaps);
		final int mid = gaps.size() / 2;
		final long median = (gaps.size() % 2 == 1) ? gaps.get(mid) : (gaps.get(mid - 1) + gaps.get(mid)) / 2L;
		return OptionalLong.of(median);
	}

	/**
	 * Returns a snapshot of the current tracker state at {@code now}.
	 * @param now the reference timestamp used to evaluate staleness (must not be
	 * {@code null})
	 * @return the immutable state snapshot
	 */
	public State snapshot(final Instant now) {
		final Instant last;
		final List<Instant> recent;
		final OptionalLong interval;
		synchronized (this) {
			last = this.pings.peekLast();
			recent = List.copyOf(this.pings);
			interval = estimatedIntervalMillis();
		}
		final boolean stale;
		if (last == null) {
			// No ping ever: the fixed fallback still applies, anchored at tracking start,
			// so a silent drop is surfaced even when the transport never reports a ping.
			stale = Duration.between(this.trackingSince, now).toMillis() > STALE_FALLBACK_MILLIS;
		}
		else if (interval.isEmpty()) {
			// A single ping, so the cadence is unknown: fall back to the fixed 90s
			// threshold so a silent drop is still surfaced.
			stale = Duration.between(last, now).toMillis() > STALE_FALLBACK_MILLIS;
		}
		else {
			final long silenceMillis = Duration.between(last, now).toMillis();
			stale = silenceMillis > STALE_FACTOR * interval.getAsLong();
		}
		return new State(last, recent, interval.isEmpty() ? null : interval.getAsLong(), stale);
	}

	/**
	 * Clears all retained pings, returning the tracker to the never-pinged state. The
	 * tracking anchor is kept, so the zero-ping fallback window still runs from the
	 * moment the session started tracking.
	 */
	public synchronized void reset() {
		this.pings.clear();
	}

	/**
	 * Immutable snapshot of the tracker state at a point in time.
	 *
	 * @param lastPingAt the most recent ping timestamp, or {@code null} when none
	 * observed
	 * @param recentPings recent ping timestamps, oldest first, up to {@link #MAX_PINGS}
	 * entries
	 * @param estimatedIntervalMillis median interval between pings in milliseconds, or
	 * {@code null} when fewer than two pings have been observed
	 * @param isStale whether the silence since {@code lastPingAt} (or since tracking
	 * started, when no ping has arrived) exceeds {@link #STALE_FACTOR} times the
	 * estimated interval, or {@link #STALE_FALLBACK_MILLIS} milliseconds when the
	 * interval is not yet estimable
	 */
	public record State(Instant lastPingAt, List<Instant> recentPings, Long estimatedIntervalMillis, boolean isStale) {

	}

}
