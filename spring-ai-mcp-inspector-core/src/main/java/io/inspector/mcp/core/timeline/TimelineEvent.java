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
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A single event on the inspector timeline.
 *
 * <p>
 * Recorded by the {@link McpTrafficRecorder} (or a log appender) and stored in the
 * {@link TimelineService} for retrieval by the UI.
 *
 * @param id unique event identifier (never {@code null})
 * @param correlationId correlation identifier linking paired request/response or a group
 * of related events (may be {@code null} for log events without MDC context)
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
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(timestamp, "timestamp must not be null");
	}

	/**
	 * Creates a minimal {@code APP_LOG} event with the given log metadata and message.
	 * The log-specific fields are encoded as a JSON payload.
	 * @param correlationId the correlation id from the MDC (may be {@code null})
	 * @param logLevel the log level string (must not be {@code null})
	 * @param loggerName the logger name (must not be {@code null})
	 * @param threadName the current thread name (must not be {@code null})
	 * @param message the formatted log message (must not be {@code null})
	 * @param throwable optional stack trace, may be {@code null}
	 * @return a new {@code APP_LOG} event (never {@code null})
	 */
	public static TimelineEvent createLogEvent(final String correlationId, final String logLevel,
			final String loggerName, final String threadName, final String message, final String throwable) {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("logLevel", Objects.requireNonNullElse(logLevel, ""));
		payload.put("loggerName", Objects.requireNonNullElse(loggerName, ""));
		payload.put("threadName", Objects.requireNonNullElse(threadName, ""));
		payload.put("message", Objects.requireNonNullElse(message, ""));
		if (throwable != null) {
			payload.put("throwable", throwable);
		}
		return new TimelineEvent(UUID.randomUUID().toString(), correlationId, null, TimelineEventType.APP_LOG,
				Instant.now(), payload);
	}

	/**
	 * Returns the log level from the payload, or {@code null} if not an APP_LOG event or
	 * payload is absent.
	 * @return the log level, may be {@code null}
	 */
	public String logLevel() {
		if (this.payload == null || this.type != TimelineEventType.APP_LOG) {
			return null;
		}
		final JsonNode node = this.payload.get("logLevel");
		return (node != null) ? node.asText() : null;
	}

	/**
	 * Returns the logger name from the payload, or {@code null} if not an APP_LOG event
	 * or payload is absent.
	 * @return the logger name, may be {@code null}
	 */
	public String loggerName() {
		if (this.payload == null || this.type != TimelineEventType.APP_LOG) {
			return null;
		}
		final JsonNode node = this.payload.get("loggerName");
		return (node != null) ? node.asText() : null;
	}

	/**
	 * Returns the thread name from the payload, or {@code null} if not an APP_LOG event
	 * or payload is absent.
	 * @return the thread name, may be {@code null}
	 */
	public String threadName() {
		if (this.payload == null || this.type != TimelineEventType.APP_LOG) {
			return null;
		}
		final JsonNode node = this.payload.get("threadName");
		return (node != null) ? node.asText() : null;
	}

	/**
	 * Returns the log message from the payload, or {@code null} if not an APP_LOG event
	 * or payload is absent.
	 * @return the message, may be {@code null}
	 */
	public String message() {
		if (this.payload == null || this.type != TimelineEventType.APP_LOG) {
			return null;
		}
		final JsonNode node = this.payload.get("message");
		return (node != null) ? node.asText() : null;
	}

	/**
	 * Returns the stack trace from the payload, or {@code null} if not an APP_LOG event
	 * or payload is absent, or no throwable was recorded.
	 * @return the throwable text, may be {@code null}
	 */
	public String throwable() {
		if (this.payload == null || this.type != TimelineEventType.APP_LOG) {
			return null;
		}
		final JsonNode node = this.payload.get("throwable");
		return (node != null) ? node.asText() : null;
	}

}
