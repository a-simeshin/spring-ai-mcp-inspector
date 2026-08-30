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
 * Service that stores and retrieves {@link TimelineEvent timeline events}.
 *
 * <p>
 * Events are appended by the {@link McpTrafficRecorder} (for JSON-RPC traffic) and by a
 * log appender (for application logs). A downstream consumer — the inspector UI — queries
 * the timeline via {@link #query(TimelineQuery)}.
 *
 * <p>
 * All implementations must be thread-safe.
 *
 * @author Artem Simeshin
 */
public interface TimelineService {

	/**
	 * Appends an event to the timeline.
	 * @param event the event to append (must not be {@code null})
	 */
	void append(TimelineEvent event);

	/**
	 * Queries the timeline with the given filter.
	 * @param query the query parameters (must not be {@code null})
	 * @return an immutable list of matching events, newest first (never {@code null})
	 */
	List<TimelineEvent> query(TimelineQuery query);

	/**
	 * Removes all events from the timeline.
	 */
	void clear();

}