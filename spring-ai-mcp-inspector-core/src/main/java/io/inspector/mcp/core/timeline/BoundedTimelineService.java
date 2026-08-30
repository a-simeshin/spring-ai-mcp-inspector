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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bounded in-memory ring-buffer implementation of {@link TimelineService}.
 *
 * <p>
 * Stores up to the configured maximum number of events. When the buffer is full, the
 * oldest event is evicted to make room for the new one. All operations are thread-safe
 * via a {@link ReentrantReadWriteLock}.
 *
 * @author Artem Simeshin
 */
public final class BoundedTimelineService implements TimelineService {

	/** Default maximum number of events retained in the ring buffer. */
	static final int DEFAULT_MAX_EVENTS = 1000;

	/** Public alias for the default capacity. */
	public static final int MAX_EVENTS = DEFAULT_MAX_EVENTS;

	private final int maxEvents;

	private final TimelineEvent[] buffer;

	private int nextIndex;

	private int size;

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	/**
	 * Creates a new bounded timeline service with the default capacity.
	 */
	public BoundedTimelineService() {
		this(DEFAULT_MAX_EVENTS);
	}

	/**
	 * Creates a new bounded timeline service with the given capacity.
	 * @param capacity maximum number of events to retain
	 */
	public BoundedTimelineService(final int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive: " + capacity);
		}
		this.maxEvents = capacity;
		this.buffer = new TimelineEvent[capacity];
		this.nextIndex = 0;
		this.size = 0;
	}

	@Override
	public void append(final TimelineEvent event) {
		if (event == null) {
			return;
		}
		this.lock.writeLock().lock();
		try {
			this.buffer[this.nextIndex] = event;
			this.nextIndex = (this.nextIndex + 1) % this.maxEvents;
			if (this.size < this.maxEvents) {
				this.size++;
			}
		}
		finally {
			this.lock.writeLock().unlock();
		}
	}

	@Override
	public List<TimelineEvent> query(final TimelineQuery query) {
		this.lock.readLock().lock();
		try {
			final List<TimelineEvent> result = new ArrayList<>(this.size);
			// Walk the buffer from newest to oldest
			for (int i = 0; i < this.size; i++) {
				final int idx = (this.nextIndex - 1 - i + this.maxEvents) % this.maxEvents;
				final TimelineEvent event = this.buffer[idx];
				if (event == null) {
					continue;
				}
				if (matches(query, event)) {
					result.add(event);
				}
			}
			final int limit = query.limit();
			result.sort(Comparator.comparing(TimelineEvent::timestamp));
			if (result.size() > limit) {
				return Collections.unmodifiableList(result.subList(0, limit));
			}
			return Collections.unmodifiableList(result);
		}
		finally {
			this.lock.readLock().unlock();
		}
	}

	@Override
	public void clear() {
		this.lock.writeLock().lock();
		try {
			for (int i = 0; i < this.maxEvents; i++) {
				this.buffer[i] = null;
			}
			this.nextIndex = 0;
			this.size = 0;
		}
		finally {
			this.lock.writeLock().unlock();
		}
	}

	/**
	 * Returns the current number of events in the buffer.
	 * @return event count
	 */
	public int size() {
		this.lock.readLock().lock();
		try {
			return this.size;
		}
		finally {
			this.lock.readLock().unlock();
		}
	}

	private static boolean matches(final TimelineQuery query, final TimelineEvent event) {
		if (query.correlationId() != null && !query.correlationId().equals(event.correlationId())) {
			return false;
		}
		if (query.sessionId() != null && !query.sessionId().equals(event.sessionId())) {
			return false;
		}
		if (query.eventTypes() != null && !query.eventTypes().isEmpty()
				&& !query.eventTypes().contains(event.type())) {
			return false;
		}
		if (query.since() != null && event.timestamp().isBefore(query.since())) {
			return false;
		}
		if (query.until() != null && !event.timestamp().isBefore(query.until())) {
			return false;
		}
		return true;
	}

}
