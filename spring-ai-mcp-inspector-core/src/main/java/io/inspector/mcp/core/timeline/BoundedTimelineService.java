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

package io.inspector.mcp.core.timeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe, bounded, in-memory timeline backed by a deque with TTL-based eviction.
 *
 * <p>
 * Events are retained up to {@code capacity} items and for at most {@code ttl} duration.
 * Eviction runs on every {@code append} and {@code query} call.
 *
 * @author Artem Simeshin
 */
public final class BoundedTimelineService implements TimelineService {

	/** Default maximum number of events to retain. */
	public static final int DEFAULT_CAPACITY = 1000;

	/** Default TTL for events. */
	public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

	private final int capacity;

	private final Duration ttl;

	private final ConcurrentLinkedDeque<TimelineEvent> deque;

	/**
	 * Creates a service with the default capacity (1000) and TTL (15 minutes).
	 */
	public BoundedTimelineService() {
		this(DEFAULT_CAPACITY, DEFAULT_TTL);
	}

	/**
	 * Creates a service with the given capacity and TTL.
	 * @param capacity maximum number of events to retain
	 * @param ttl maximum age of retained events
	 */
	public BoundedTimelineService(final int capacity, final Duration ttl) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive: " + capacity);
		}
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new IllegalArgumentException("ttl must be positive: " + ttl);
		}
		this.capacity = capacity;
		this.ttl = ttl;
		this.deque = new ConcurrentLinkedDeque<>();
	}

	@Override
	public void append(final TimelineEvent event) {
		if (event == null) {
			return;
		}
		this.deque.addLast(event);
		evict();
	}

	@Override
	public List<TimelineEvent> query(final TimelineQuery query) {
		evict();
		if (query == null) {
			return List.of();
		}
		final List<TimelineEvent> result = new ArrayList<>();
		for (final TimelineEvent event : this.deque) {
			if (matches(event, query)) {
				result.add(event);
			}
		}
		result.sort(Comparator.comparing(TimelineEvent::timestamp).reversed());
		final int limit = Math.min(query.limit(), result.size());
		return Collections.unmodifiableList(result.subList(0, limit));
	}

	@Override
	public void clear() {
		this.deque.clear();
	}

	/**
	 * Returns the current number of events in the buffer (after eviction).
	 * @return event count
	 */
	public int size() {
		evict();
		return this.deque.size();
	}

	private void evict() {
		final Instant cutoff = Instant.now().minus(this.ttl);
		while (!this.deque.isEmpty()) {
			final TimelineEvent oldest = this.deque.peekFirst();
			if (oldest != null && oldest.timestamp().isBefore(cutoff)) {
				this.deque.pollFirst();
			}
			else {
				break;
			}
		}
		while (this.deque.size() > this.capacity) {
			this.deque.pollFirst();
		}
	}

	private static boolean matches(final TimelineEvent event, final TimelineQuery query) {
		if (query.correlationId() != null && !query.correlationId().equals(event.correlationId())) {
			return false;
		}
		if (query.sessionId() != null && !query.sessionId().equals(event.sessionId())) {
			return false;
		}
		if (query.since() != null && event.timestamp().isBefore(query.since())) {
			return false;
		}
		if (query.until() != null && event.timestamp().isAfter(query.until())) {
			return false;
		}
		if (query.eventTypes() != null && !query.eventTypes().isEmpty()
				&& !query.eventTypes().contains(event.eventType())) {
			return false;
		}
		return true;
	}

}
