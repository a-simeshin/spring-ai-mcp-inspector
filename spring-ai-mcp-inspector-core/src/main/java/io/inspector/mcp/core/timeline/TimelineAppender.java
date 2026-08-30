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

import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

/**
 * Logback appender that forwards {@link ILoggingEvent log events} to the
 * {@link TimelineService} as {@link TimelineEventType#APP_LOG} events.
 *
 * <p>
 * Reads the {@code mcp.correlationId} from the event's MDC and uses it to link the log
 * entry to the originating MCP request. When no correlation id is present the event is
 * still captured with a {@code null} correlationId.
 *
 * <p>
 * This appender is meant to be wrapped in a Logback {@code AsyncAppender} for
 * non-blocking behaviour (see
 * {@link #createAsyncAppender(ch.qos.logback.classic.AsyncAppender, int)}).
 *
 * @author Artem Simeshin
 */
public final class TimelineAppender extends AppenderBase<ILoggingEvent> {

	/** MDC key used to store the correlation id. */
	public static final String MDC_CORRELATION_ID = "mcp.correlationId";

	private final TimelineService timelineService;

	/**
	 * Creates a new appender that forwards events to the given service.
	 * @param timelineService the target timeline service (must not be {@code null})
	 */
	public TimelineAppender(final TimelineService timelineService) {
		this.timelineService = timelineService;
	}

	@Override
	protected void append(final ILoggingEvent event) {
		if (event == null) {
			return;
		}
		final Map<String, String> mdc = event.getMDCPropertyMap();
		final String correlationId = (mdc != null) ? mdc.get(MDC_CORRELATION_ID) : null;
		final String throwableStr = extractThrowable(event.getThrowableProxy());
		final String level = (event.getLevel() != null) ? event.getLevel().toString() : "";
		final String loggerName = (event.getLoggerName() != null) ? event.getLoggerName() : "";
		final String threadName = (event.getThreadName() != null) ? event.getThreadName() : "";
		final String message = (event.getFormattedMessage() != null) ? event.getFormattedMessage() : "";
		final TimelineEvent timelineEvent = TimelineEvent.createLogEvent(correlationId, level, loggerName, threadName,
				message, throwableStr);
		this.timelineService.append(timelineEvent);
	}

	private static String extractThrowable(final IThrowableProxy proxy) {
		if (proxy == null) {
			return null;
		}
		return ThrowableProxyUtil.asString(proxy);
	}

}
