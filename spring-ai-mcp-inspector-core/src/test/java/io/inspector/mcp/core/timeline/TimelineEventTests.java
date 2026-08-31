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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TimelineEvent} convenience methods.
 */
class TimelineEventTests {

	@Test
	void nonAppLogEventReturnsNullForLogFields() {
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), JsonNodeFactory.instance.objectNode());
		assertThat(event.message()).isNull();
		assertThat(event.logLevel()).isNull();
		assertThat(event.loggerName()).isNull();
		assertThat(event.threadName()).isNull();
		assertThat(event.throwable()).isNull();
	}

	@Test
	void nullPayloadReturnsNullForLogFields() {
		final TimelineEvent event = TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main",
				"msg", null);
		// createLogEvent always sets a payload, so create one with explicit null payload
		final TimelineEvent nullPayloadEvent = new TimelineEvent(UUID.randomUUID().toString(),
				UUID.randomUUID().toString(), null, TimelineEventType.APP_LOG, Instant.now(), null);
		assertThat(nullPayloadEvent.message()).isNull();
		assertThat(nullPayloadEvent.logLevel()).isNull();
		assertThat(nullPayloadEvent.throwable()).isNull();
	}

	@Test
	void missingFieldInPayloadReturnsNull() {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("logLevel", "INFO");
		// message is missing
		payload.put("threadName", "main");
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null,
				TimelineEventType.APP_LOG, Instant.now(), payload);
		assertThat(event.message()).isNull();
		// But these should still work
		assertThat(event.logLevel()).isEqualTo("INFO");
		assertThat(event.threadName()).isEqualTo("main");
	}

	@Test
	void createLogEventWithNullCorrelationId() {
		final TimelineEvent event = TimelineEvent.createLogEvent(null, "INFO", "test", "main", "msg", null);
		assertThat(event.correlationId()).isNull();
		assertThat(event.type()).isEqualTo(TimelineEventType.APP_LOG);
		assertThat(event.id()).isNotNull();
		assertThat(event.message()).isEqualTo("msg");
	}

	@Test
	void createLogEventWithThrowable() {
		final TimelineEvent event = TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "ERROR", "test", "main",
				"error msg", "java.lang.RuntimeException: test");
		assertThat(event.throwable()).isEqualTo("java.lang.RuntimeException: test");
	}

}
