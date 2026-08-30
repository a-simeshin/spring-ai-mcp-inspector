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
import java.util.List;

/**
 * Filter criteria for querying the timeline.
 *
 * <p>
 * All fields are optional; omitted (null/empty) criteria are not applied.
 *
 * @param correlationId filter by correlation id (may be {@code null})
 * @param sessionId filter by session id (may be {@code null})
 * @param since include events on or after this instant (may be {@code null})
 * @param until include events on or before this instant (may be {@code null})
 * @param eventTypes include only events of these types (empty = all types, may be
 * {@code null})
 * @param limit maximum number of events to return (default 500)
 * @author Artem Simeshin
 */
public record TimelineQuery(String correlationId, String sessionId, Instant since, Instant until,
		List<TimelineEventType> eventTypes, int limit) {

	/** Default limit when none is specified. */
	public static final int DEFAULT_LIMIT = 500;

	/** Maximum allowed limit. */
	public static final int MAX_LIMIT = 5000;

	/**
	 * Creates a query with the given parameters, applying defaults for limit.
	 */
	public TimelineQuery {
		if (limit <= 0) {
			limit = DEFAULT_LIMIT;
		}
		else if (limit > MAX_LIMIT) {
			limit = MAX_LIMIT;
		}
	}

	/**
	 * Convenience: returns the first event type if only one is specified, or {@code null}
	 * if none or multiple.
	 * @return the single type filter, may be {@code null}
	 */
	public TimelineEventType type() {
		if (this.eventTypes == null || this.eventTypes.isEmpty()) {
			return null;
		}
		return this.eventTypes.get(0);
	}

	/**
	 * Creates a query that filters by correlation ID.
	 * @param correlationId the correlation ID to match
	 * @return a new query with just the correlation filter
	 */
	public static TimelineQuery byCorrelationId(final String correlationId) {
		return new TimelineQuery(correlationId, null, null, null, null, DEFAULT_LIMIT);
	}

	/**
	 * Creates a query with no filters, returning up to the default limit.
	 * @return a new query (never {@code null})
	 */
	public static TimelineQuery all() {
		return new TimelineQuery(null, null, null, null, null, DEFAULT_LIMIT);
	}

	/**
	 * Returns a builder for {@code TimelineQuery}.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link TimelineQuery}.
	 */
	public static final class Builder {

		private String correlationId;

		private String sessionId;

		private Instant since;

		private Instant until;

		private List<TimelineEventType> eventTypes;

		private int limit;

		private Builder() {
		}

		public Builder correlationId(final String correlationId) {
			this.correlationId = correlationId;
			return this;
		}

		public Builder sessionId(final String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		public Builder since(final Instant since) {
			this.since = since;
			return this;
		}

		public Builder until(final Instant until) {
			this.until = until;
			return this;
		}

		public Builder eventTypes(final List<TimelineEventType> eventTypes) {
			this.eventTypes = eventTypes;
			return this;
		}

		public Builder limit(final int limit) {
			this.limit = limit;
			return this;
		}

		/**
		 * Convenience: set a time window of {@code duration} ending now.
		 * @param duration how far back to look
		 * @return this builder
		 */
		public Builder last(final Duration duration) {
			final Instant now = Instant.now();
			this.until = now;
			this.since = now.minus(duration);
			return this;
		}

		public TimelineQuery build() {
			return new TimelineQuery(this.correlationId, this.sessionId, this.since, this.until, this.eventTypes,
					this.limit);
		}

	}

}
