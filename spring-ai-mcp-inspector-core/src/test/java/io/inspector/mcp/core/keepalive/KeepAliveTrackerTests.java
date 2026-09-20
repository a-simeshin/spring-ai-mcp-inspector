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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.inspector.mcp.core.keepalive.KeepAliveTracker.State;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KeepAliveTracker}: detection bookkeeping, rolling interval
 * estimation, staleness transitions in both directions, and the edge cases the card
 * demands (no pings at all, single ping, listener notification, thread-safety under
 * concurrent recording).
 *
 * @author Artem Simeshin
 */
class KeepAliveTrackerTests {

	@Test
	void noPings_neverStaleAndNoInterval() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final State state = tracker.snapshot(Instant.now());
		assertThat(state.lastPingAt()).isNull();
		assertThat(state.recentPings()).isEmpty();
		assertThat(state.estimatedIntervalMillis()).isNull();
		assertThat(state.isStale()).isFalse();
	}

	@Test
	void singlePing_noIntervalEstimateAndNotStale() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
		tracker.recordPing(t0);

		final State state = tracker.snapshot(t0.plusSeconds(60));
		assertThat(state.lastPingAt()).isEqualTo(t0);
		assertThat(state.recentPings()).containsExactly(t0);
		assertThat(state.estimatedIntervalMillis()).isNull();
		assertThat(state.isStale()).isFalse();
	}

	@Test
	void twoPings_estimatesIntervalAsGap() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
		tracker.recordPing(t0);
		tracker.recordPing(t0.plusSeconds(10));

		final State state = tracker.snapshot(t0.plusSeconds(10));
		assertThat(state.estimatedIntervalMillis()).isEqualTo(10_000L);
		assertThat(state.isStale()).isFalse();
	}

	@Test
	void medianOfOddNumberOfGapsIsMiddleValue() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
		tracker.recordPing(t0);
		tracker.recordPing(t0.plusSeconds(5));
		tracker.recordPing(t0.plusSeconds(15));
		tracker.recordPing(t0.plusSeconds(50));
		// gaps: 5s, 10s, 35s -> median = 10s
		final State state = tracker.snapshot(t0.plusSeconds(50));
		assertThat(state.estimatedIntervalMillis()).isEqualTo(10_000L);
	}

	@Test
	void medianOfEvenNumberOfGapsIsMeanOfMiddleTwo() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
		tracker.recordPing(t0);
		tracker.recordPing(t0.plusSeconds(10));
		tracker.recordPing(t0.plusSeconds(20));
		// gaps: 10s, 10s -> median = 10s
		final State state = tracker.snapshot(t0.plusSeconds(20));
		assertThat(state.estimatedIntervalMillis()).isEqualTo(10_000L);
	}

	@Test
	void transitionsToStaleAfterTwoIntervalsOfSilence() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
		tracker.recordPing(t0);
		tracker.recordPing(t0.plusSeconds(10));
		tracker.recordPing(t0.plusSeconds(20));

		// interval = 10s, so stale when silence > 20s
		assertThat(tracker.snapshot(t0.plusSeconds(20)).isStale()).isFalse();
		assertThat(tracker.snapshot(t0.plusSeconds(40)).isStale()).isFalse(); // silence
																				// == 20s,
																				// not >
																				// 2*interval
		assertThat(tracker.snapshot(t0.plusSeconds(41)).isStale()).isTrue();
	}

	@Test
	void transitionsBackToLiveWhenPingArrives() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
		tracker.recordPing(t0);
		tracker.recordPing(t0.plusSeconds(10));
		// silence 25s -> stale
		assertThat(tracker.snapshot(t0.plusSeconds(35)).isStale()).isTrue();
		// ping resumes
		tracker.recordPing(t0.plusSeconds(35));
		assertThat(tracker.snapshot(t0.plusSeconds(35)).isStale()).isFalse();
	}

	@Test
	void keepsAtMostTenRecentPings() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
		for (int i = 0; i < 15; i++) {
			tracker.recordPing(t0.plusSeconds(i));
		}
		final State state = tracker.snapshot(t0.plusSeconds(15));
		assertThat(state.recentPings()).hasSize(KeepAliveTracker.MAX_PINGS);
		// oldest retained is t=5 (15 - 10)
		assertThat(state.recentPings().get(0)).isEqualTo(t0.plusSeconds(5));
		assertThat(state.lastPingAt()).isEqualTo(t0.plusSeconds(14));
	}

	@Test
	void listenerIsNotifiedOnEachPing() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final List<Instant> received = new ArrayList<>();
		tracker.setListener(received::add);

		final Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
		tracker.recordPing(t0);
		tracker.recordPing(t0.plusSeconds(5));
		assertThat(received).containsExactly(t0, t0.plusSeconds(5));
	}

	@Test
	void nullListenerThrowsNoException() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		tracker.recordPing(Instant.now());
		assertThat(tracker.recentPings()).hasSize(1);
	}

	@Test
	void throwingListenerDoesNotBreakRecording() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		tracker.setListener((at) -> {
			throw new RuntimeException("boom");
		});
		tracker.recordPing(Instant.now());
		assertThat(tracker.recentPings()).hasSize(1);
	}

	@Test
	void nullPingTimestampIsIgnored() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		tracker.recordPing(null);
		assertThat(tracker.recentPings()).isEmpty();
	}

	@Test
	void concurrentRecordingIsThreadSafe() throws InterruptedException {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final int threads = 8;
		final int pingsPerThread = 100;
		final ExecutorService pool = Executors.newFixedThreadPool(threads);
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicInteger counter = new AtomicInteger();
		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					latch.await();
				}
				catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				for (int j = 0; j < pingsPerThread; j++) {
					tracker.recordPing(Instant.now().plusMillis(counter.incrementAndGet()));
				}
			});
		}
		latch.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		// Tracker is bounded: only last N retained. Concurrent snapshots stay consistent.
		final State state = tracker.snapshot(Instant.now().plus(Duration.ofDays(1)));
		assertThat(state.recentPings()).hasSize(KeepAliveTracker.MAX_PINGS);
		// intervals all == 1ms
		assertThat(state.estimatedIntervalMillis()).isEqualTo(1L);
	}

	@Test
	void resetReturnsTrackerToNeverPingedState() {
		final KeepAliveTracker tracker = new KeepAliveTracker();
		final Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
		tracker.recordPing(t0);
		tracker.recordPing(t0.plusSeconds(10));
		tracker.reset();
		final State state = tracker.snapshot(t0.plusSeconds(60));
		assertThat(state.lastPingAt()).isNull();
		assertThat(state.recentPings()).isEmpty();
		assertThat(state.estimatedIntervalMillis()).isNull();
		assertThat(state.isStale()).isFalse();
	}

}
