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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A bounded LRU store for pending request→response correlations, shared between
 * {@link McpClientTrafficRecorder} and {@link McpTrafficRecorder}.
 * <p>
 * Thread-safe: all mutations are guarded by an internal lock. The map evicts the eldest
 * entry when the capacity is exceeded, keeping memory bounded.
 *
 * @param <K> the key type used for correlation lookup
 * @author Artem Simeshin
 */
final class PendingCorrelationStore<K> {

	private final LinkedHashMap<K, PendingCorrelation> correlations;

	private final Object lock = new Object();

	/**
	 * Creates a new store with the given maximum capacity.
	 * @param maxCapacity the maximum number of pending entries before eldest eviction
	 */
	PendingCorrelationStore(final int maxCapacity) {
		this.correlations = new LinkedHashMap<>() {
			@Override
			protected boolean removeEldestEntry(final Map.Entry<K, PendingCorrelation> eldest) {
				return super.size() > maxCapacity;
			}
		};
	}

	/**
	 * Stores a pending correlation, replacing any existing entry for the same key.
	 * @param key the lookup key (must not be {@code null})
	 * @param value the pending correlation value (must not be {@code null})
	 */
	void store(final K key, final PendingCorrelation value) {
		synchronized (this.lock) {
			this.correlations.put(key, value);
		}
	}

	/**
	 * Removes and returns the pending correlation for the given key, or {@code null} if
	 * no entry exists.
	 * @param key the lookup key
	 * @return the removed correlation, or {@code null}
	 */
	PendingCorrelation remove(final K key) {
		synchronized (this.lock) {
			return this.correlations.remove(key);
		}
	}

	/**
	 * Returns the number of pending correlations currently stored.
	 * @return the pending count
	 */
	int size() {
		synchronized (this.lock) {
			return this.correlations.size();
		}
	}

	/**
	 * Removes all entries whose key matches the given predicate.
	 * @param predicate the condition to test keys against
	 */
	void removeIf(final Predicate<? super K> predicate) {
		synchronized (this.lock) {
			this.correlations.keySet().removeIf(predicate);
		}
	}

	/**
	 * A pending request correlation with bookkeeping for latency computation and optional
	 * progress tracking.
	 *
	 * @param correlationId the generated correlation id
	 * @param timestamp the instant when the request was recorded
	 * @param progressToken the request's {@code params._meta.progressToken} text, may be
	 * {@code null}
	 */
	record PendingCorrelation(String correlationId, Instant timestamp, String progressToken) {

		PendingCorrelation(final String correlationId, final Instant timestamp) {
			this(correlationId, timestamp, null);
		}

		/**
		 * Returns the elapsed time in milliseconds since this pending entry was created.
		 * @return elapsed milliseconds
		 */
		long elapsed() {
			return java.time.Duration.between(this.timestamp, Instant.now()).toMillis();
		}

	}

}
