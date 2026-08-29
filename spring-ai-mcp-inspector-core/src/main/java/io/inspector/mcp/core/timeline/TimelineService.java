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

import java.util.List;

/**
 * Service that captures and retrieves {@link TimelineEvent timeline events}.
 *
 * <p>
 * Implementations are expected to be thread-safe and bounded (in-memory ring buffer with
 * configurable capacity and TTL).
 *
 * @author Artem Simeshin
 */
public interface TimelineService {

	/**
	 * Append an event to the timeline.
	 * @param event the event to append (must not be {@code null})
	 */
	void append(TimelineEvent event);

	/**
	 * Query events matching the given filter criteria.
	 * @param query the filter criteria (must not be {@code null})
	 * @return matching events, newest first, never {@code null}
	 */
	List<TimelineEvent> query(TimelineQuery query);

	/**
	 * Remove all events from the timeline.
	 */
	void clear();

}
