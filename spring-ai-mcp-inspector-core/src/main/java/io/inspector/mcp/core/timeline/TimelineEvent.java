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
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * An immutable event recorded on the MCP inspector timeline.
 *
 * <p>
 * Every event carries a unique {@code id} and a {@code correlationId} that links it to
 * the top-level JSON-RPC request that triggered it. Additional fields are populated
 * according to {@link #eventType()}.
 *
 * @param id globally unique event identifier
 * @param correlationId correlation id linking this event to the originating JSON-RPC
 * request (set via MDC key {@code mcp.correlationId})
 * @param timestamp instant when the event occurred
 * @param eventType discriminator for the kind of event
 * @param sessionId optional MCP session identifier
 * @param requestId optional JSON-RPC request id
 * @param method optional JSON-RPC method name
 * @param params optional JSON-RPC params payload ({@code null} for {@code APP_LOG})
 * @param result optional JSON-RPC result payload ({@code null} for {@code APP_LOG})
 * @param error optional JSON-RPC error payload ({@code null} for {@code APP_LOG})
 * @param logLevel log level name (only for {@link TimelineEventType#APP_LOG};
 * {@code null} otherwise)
 * @param loggerName logger name (only for {@code APP_LOG}; {@code null} otherwise)
 * @param threadName thread name that produced the event ({@code null} for MCP events)
 * @param message formatted log message (only for {@code APP_LOG}; {@code null} otherwise)
 * @param throwable stack trace of the throwable, if any (only for {@code APP_LOG};
 * {@code null} otherwise)
 * @author Artem Simeshin
 */
public record TimelineEvent(

		UUID id,

		UUID correlationId,

		Instant timestamp,

		TimelineEventType eventType,

		String sessionId,

		String requestId,

		String method,

		JsonNode params,

		JsonNode result,

		JsonNode error,

		String logLevel,

		String loggerName,

		String threadName,

		String message,

		String throwable

) {

	/**
	 * Creates a minimal {@code APP_LOG} event with the given correlation, log metadata
	 * and message.
	 * @param correlationId the correlation id from the MDC
	 * @param logLevel the log level string
	 * @param loggerName the logger name
	 * @param threadName the current thread name
	 * @param message the formatted log message
	 * @param throwable optional stack trace, may be {@code null}
	 * @return a new {@code APP_LOG} event
	 */
	public static TimelineEvent createLogEvent(final UUID correlationId, final String logLevel, final String loggerName,
			final String threadName, final String message, final String throwable) {
		return new TimelineEvent(UUID.randomUUID(), correlationId, Instant.now(), TimelineEventType.APP_LOG, null, null,
				null, null, null, null, logLevel, loggerName, threadName, message, throwable);
	}

}
