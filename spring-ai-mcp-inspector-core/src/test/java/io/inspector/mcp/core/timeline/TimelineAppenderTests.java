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
import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link TimelineAppender}.
 */
class TimelineAppenderTests {

	private final BoundedTimelineService timelineService = new BoundedTimelineService();

	private final TimelineAppender appender = new TimelineAppender(this.timelineService);

	@Test
	void appendConvertsLogEvent() {
		final ILoggingEvent event = createLogEvent("test.Logger", Level.INFO, "hello world", null,
				Map.of(TimelineAppender.MDC_CORRELATION_ID, UUID.randomUUID().toString()));
		this.appender.append(event);
		final List<TimelineEvent> events = this.timelineService.query(TimelineQuery.builder().build());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).eventType()).isEqualTo(TimelineEventType.APP_LOG);
		assertThat(events.get(0).message()).isEqualTo("hello world");
		assertThat(events.get(0).loggerName()).isEqualTo("test.Logger");
		assertThat(events.get(0).logLevel()).isEqualTo("INFO");
		assertThat(events.get(0).correlationId()).isNotNull();
	}

	@Test
	void appendWithoutCorrelationId() {
		final ILoggingEvent event = createLogEvent("test.Logger", Level.WARN, "no correlation", null, Map.of());
		this.appender.append(event);
		final List<TimelineEvent> events = this.timelineService.query(TimelineQuery.builder().build());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).correlationId()).isNull();
	}

	@Test
	void appendWithNullMdc() {
		final ILoggingEvent event = createLogEvent("test.Logger", Level.ERROR, "null mdc", null, null);
		this.appender.append(event);
		final List<TimelineEvent> events = this.timelineService.query(TimelineQuery.builder().build());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).correlationId()).isNull();
	}

	@Test
	void nullEventIsNoOp() {
		this.appender.append(null);
		assertThat(this.timelineService.size()).isZero();
	}

	@Test
	void appendWithNullMdcPropertyMap() {
		final ILoggingEvent event = mock(ILoggingEvent.class);
		given(event.getLoggerName()).willReturn("test.Logger");
		given(event.getLevel()).willReturn(Level.WARN);
		given(event.getFormattedMessage()).willReturn("null mdc map");
		given(event.getThreadName()).willReturn("test-thread");
		given(event.getThrowableProxy()).willReturn(null);
		given(event.getMDCPropertyMap()).willReturn(null);
		given(event.getTimeStamp()).willReturn(System.currentTimeMillis());
		this.appender.append(event);
		final List<TimelineEvent> events = this.timelineService.query(TimelineQuery.builder().build());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).correlationId()).isNull();
	}

	@Test
	void appendWithInvalidCorrelationId() {
		final ILoggingEvent event = mock(ILoggingEvent.class);
		given(event.getLoggerName()).willReturn("test.Logger");
		given(event.getLevel()).willReturn(Level.INFO);
		given(event.getFormattedMessage()).willReturn("invalid uuid");
		given(event.getThreadName()).willReturn("test-thread");
		given(event.getThrowableProxy()).willReturn(null);
		given(event.getMDCPropertyMap()).willReturn(Map.of(TimelineAppender.MDC_CORRELATION_ID, "not-a-uuid"));
		given(event.getTimeStamp()).willReturn(System.currentTimeMillis());
		this.appender.append(event);
		final List<TimelineEvent> events = this.timelineService.query(TimelineQuery.builder().build());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).correlationId()).isNull();
	}

	@Test
	void appendWithThrowable() {
		final IThrowableProxy throwableProxy = mock(IThrowableProxy.class);
		given(throwableProxy.getClassName()).willReturn("java.lang.RuntimeException");
		given(throwableProxy.getMessage()).willReturn("test error");
		given(throwableProxy.getStackTraceElementProxyArray()).willReturn(new StackTraceElementProxy[0]);
		given(throwableProxy.getCause()).willReturn(null);
		final ILoggingEvent event = createLogEvent("test.Logger", Level.ERROR, "with throwable", throwableProxy,
				Map.of());
		this.appender.append(event);
		final List<TimelineEvent> events = this.timelineService.query(TimelineQuery.builder().build());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).throwable()).isNotNull();
	}

	static ILoggingEvent createLogEvent(final String loggerName, final Level level, final String message,
			final IThrowableProxy throwableProxy, final Map<String, String> mdc) {
		final ILoggingEvent event = mock(ILoggingEvent.class);
		given(event.getLoggerName()).willReturn(loggerName);
		given(event.getLevel()).willReturn(level);
		given(event.getFormattedMessage()).willReturn(message);
		given(event.getThreadName()).willReturn("test-thread");
		given(event.getThrowableProxy()).willReturn(throwableProxy);
		given(event.getMDCPropertyMap()).willReturn((mdc != null) ? mdc : Map.of());
		given(event.getMdc()).willReturn((mdc != null) ? mdc : Map.of());
		given(event.getTimeStamp()).willReturn(System.currentTimeMillis());
		given(event.getLoggerContextVO()).willReturn(mock(LoggerContextVO.class));
		return event;
	}

}
