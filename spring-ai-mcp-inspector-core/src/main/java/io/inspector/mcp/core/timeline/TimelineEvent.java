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
import java.util.Objects;

import tools.jackson.databind.JsonNode;

/**
 * A single event on the inspector timeline.
 *
 * <p>
 * Recorded by the {@link McpTrafficRecorder} (or a log appender) and stored in the
 * {@link TimelineService} for retrieval by the UI.
 *
 * @param id unique event identifier (never {@code null})
 * @param correlationId correlation identifier linking paired request/response or a group
 * of related events (never {@code null})
 * @param sessionId proxy session identifier (may be {@code null})
 * @param type event type discriminator (never {@code null})
 * @param timestamp instant when the event occurred (never {@code null})
 * @param payload JSON payload of the message (may be {@code null})
 * @author Artem Simeshin
 */
public record TimelineEvent(String id, String correlationId, String sessionId, TimelineEventType type,
		Instant timestamp, JsonNode payload) {

	/**
	 * Compact constructor with null-safety.
	 */
	public TimelineEvent {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(correlationId, "correlationId must not be null");
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(timestamp, "timestamp must not be null");
	}

}
