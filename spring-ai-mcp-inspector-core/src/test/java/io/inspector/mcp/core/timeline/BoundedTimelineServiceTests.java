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
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BoundedTimelineService}.
 */
class BoundedTimelineServiceTests {

	private final BoundedTimelineService service = new BoundedTimelineService(100, Duration.ofMinutes(15));

	@Test
	void appendAndQuery() {
		final UUID correlationId = UUID.randomUUID();
		final TimelineEvent event = TimelineEvent.createLogEvent(correlationId, "INFO", "test.Logger", "main", "hello",
				null);
		this.service.append(event);
		final List<TimelineEvent> result = this.service
			.query(TimelineQuery.builder().correlationId(correlationId).build());
		assertThat(result).hasSize(1);
		assertThat(result.get(0).message()).isEqualTo("hello");
	}

	@Test
	void queryReturnsNewestFirst() {
		final UUID correlationId = UUID.randomUUID();
		this.service.append(TimelineEvent.createLogEvent(correlationId, "INFO", "test", "main", "first", null));
		this.service.append(TimelineEvent.createLogEvent(correlationId, "INFO", "test", "main", "second", null));
		final List<TimelineEvent> result = this.service
			.query(TimelineQuery.builder().correlationId(correlationId).build());
		assertThat(result).hasSize(2);
		assertThat(result.get(0).message()).isEqualTo("second");
		assertThat(result.get(1).message()).isEqualTo("first");
	}

	@Test
	void queryBySessionId() {
		final String sessionId = "sess-1";
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
				TimelineEventType.MCP_JSONRPC_REQUEST, sessionId, "req-1", "tools/list", null, null, null, null, null,
				null, null, null);
		this.service.append(event);
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().sessionId(sessionId).build());
		assertThat(result).hasSize(1);
	}

	@Test
	void queryByType() {
		this.service.append(TimelineEvent.createLogEvent(UUID.randomUUID(), "INFO", "test", "main", "log", null));
		final List<TimelineEvent> result = this.service
			.query(TimelineQuery.builder().eventTypes(List.of(TimelineEventType.APP_LOG)).build());
		assertThat(result).hasSize(1);
	}

	@Test
	void queryByTimeRange() {
		final Instant now = Instant.now();
		this.service.append(TimelineEvent.createLogEvent(UUID.randomUUID(), "INFO", "test", "main", "old", null));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().since(now).build());
		assertThat(result).hasSize(1);
	}

	@Test
	void queryRespectsLimit() {
		final UUID correlationId = UUID.randomUUID();
		for (int i = 0; i < 10; i++) {
			this.service.append(TimelineEvent.createLogEvent(correlationId, "INFO", "test", "main", "msg-" + i, null));
		}
		final List<TimelineEvent> result = this.service
			.query(TimelineQuery.builder().correlationId(correlationId).limit(3).build());
		assertThat(result).hasSize(3);
	}

	@Test
	void clearRemovesAll() {
		this.service.append(TimelineEvent.createLogEvent(UUID.randomUUID(), "INFO", "test", "main", "msg", null));
		this.service.clear();
		assertThat(this.service.size()).isZero();
	}

	@Test
	void nullQueryReturnsEmpty() {
		final List<TimelineEvent> result = this.service.query(null);
		assertThat(result).isEmpty();
	}

	@Test
	void nullAppendIsNoOp() {
		this.service.append(null);
		assertThat(this.service.size()).isZero();
	}

	@Test
	void capacityEviction() {
		final BoundedTimelineService small = new BoundedTimelineService(3, Duration.ofMinutes(15));
		for (int i = 0; i < 5; i++) {
			small.append(TimelineEvent.createLogEvent(UUID.randomUUID(), "INFO", "test", "main", "msg-" + i, null));
		}
		assertThat(small.size()).isEqualTo(3);
	}

	@Test
	void ttlEviction() throws InterruptedException {
		final BoundedTimelineService shortTtl = new BoundedTimelineService(100, Duration.ofMillis(10));
		shortTtl.append(TimelineEvent.createLogEvent(UUID.randomUUID(), "INFO", "test", "main", "old", null));
		Thread.sleep(50);
		shortTtl.append(TimelineEvent.createLogEvent(UUID.randomUUID(), "INFO", "test", "main", "new", null));
		assertThat(shortTtl.size()).isEqualTo(1);
	}

	@Test
	void constructorRejectsInvalidCapacity() {
		assertThatThrownBy(() -> new BoundedTimelineService(0, Duration.ofMinutes(15)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void constructorRejectsNullTtl() {
		assertThatThrownBy(() -> new BoundedTimelineService(100, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void constructorRejectsZeroTtl() {
		assertThatThrownBy(() -> new BoundedTimelineService(100, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void defaultConstructorUsesSaneDefaults() {
		final BoundedTimelineService defaultService = new BoundedTimelineService();
		assertThat(defaultService.size()).isZero();
		defaultService.append(TimelineEvent.createLogEvent(UUID.randomUUID(), "INFO", "test", "main", "msg", null));
		assertThat(defaultService.size()).isOne();
	}

}
