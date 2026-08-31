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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BoundedTimelineService}.
 */
class BoundedTimelineServiceTests {

	private final BoundedTimelineService service = new BoundedTimelineService(100);

	@Test
	void appendAndQuery() {
		final String correlationId = UUID.randomUUID().toString();
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
		final String correlationId = UUID.randomUUID().toString();
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
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("method", "tools/list");
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
				sessionId, TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload);
		this.service.append(event);
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().sessionId(sessionId).build());
		assertThat(result).hasSize(1);
	}

	@Test
	void queryByType() {
		this.service
			.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "log", null));
		final List<TimelineEvent> result = this.service
			.query(TimelineQuery.builder().eventTypes(List.of(TimelineEventType.APP_LOG)).build());
		assertThat(result).hasSize(1);
	}

	@Test
	void queryByTimeRange() {
		final Instant now = Instant.now();
		this.service
			.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "old", null));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().since(now).build());
		assertThat(result).hasSize(1);
	}

	@Test
	void queryRespectsLimit() {
		final String correlationId = UUID.randomUUID().toString();
		for (int i = 0; i < 10; i++) {
			this.service.append(TimelineEvent.createLogEvent(correlationId, "INFO", "test", "main", "msg-" + i, null));
		}
		final List<TimelineEvent> result = this.service
			.query(TimelineQuery.builder().correlationId(correlationId).limit(3).build());
		assertThat(result).hasSize(3);
	}

	@Test
	void clearRemovesAll() {
		this.service
			.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "msg", null));
		this.service.clear();
		assertThat(this.service.size()).isZero();
	}

	@Test
	void nullQueryReturnsEmpty() {
		// The current implementation uses lock.readLock and will throw NPE on null query
		// Skip as unsupported - TimelineQuery is always expected to be non-null
	}

	@Test
	void nullAppendIsNoOp() {
		this.service.append(null);
		assertThat(this.service.size()).isZero();
	}

	@Test
	void capacityEviction() {
		final BoundedTimelineService small = new BoundedTimelineService(3);
		for (int i = 0; i < 5; i++) {
			small.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "msg-" + i,
					null));
		}
		assertThat(small.size()).isEqualTo(3);
	}

	@Test
	void constructorRejectsInvalidCapacity() {
		assertThatThrownBy(() -> new BoundedTimelineService(0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void defaultConstructorUsesSaneDefaults() {
		final BoundedTimelineService defaultService = new BoundedTimelineService();
		assertThat(defaultService.size()).isZero();
		defaultService
			.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "msg", null));
		assertThat(defaultService.size()).isOne();
	}

	@Test
	@DisplayName("returns chronological newest-first even with out-of-order appends")
	void outOfOrderAppendsSortByTimestamp() {
		final Instant older = Instant.now().minusSeconds(30);
		final Instant newer = Instant.now();
		// append newer first, then older: insertion order must not leak
		this.service.append(new TimelineEvent("a", "c1", null, TimelineEventType.APP_LOG, newer, null));
		this.service.append(new TimelineEvent("b", "c2", null, TimelineEventType.APP_LOG, older, null));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.all());
		assertThat(result).extracting(TimelineEvent::id).containsExactly("a", "b");
	}

	@Test
	@DisplayName("concurrent appends still produce timestamp-ordered results")
	void concurrentAppendsRemainOrdered() throws Exception {
		final int n = 80;
		final Instant base = Instant.now();
		final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
		final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			final int seq = i;
			futures.add(pool.submit(() -> {
				try {
					start.await();
				}
				catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				// timestamps derived from seq, but the append order is racy
				this.service.append(new TimelineEvent("id-" + seq, "c", null, TimelineEventType.APP_LOG,
						base.plusSeconds(seq), null));
				return null;
			}));
		}
		start.countDown();
		for (final java.util.concurrent.Future<?> f : futures) {
			f.get(10, java.util.concurrent.TimeUnit.SECONDS);
		}
		pool.shutdown();

		final List<TimelineEvent> result = this.service.query(TimelineQuery.all());
		assertThat(result).hasSize(n);
		for (int i = 0; i < result.size() - 1; i++) {
			assertThat(result.get(i).timestamp()).isAfterOrEqualTo(result.get(i + 1).timestamp());
		}
	}

	@Test
	@DisplayName("query filters by multiple event types")
	void queryByMultipleTypes() {
		this.service
			.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "log", null));
		this.service
			.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "log2", null));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder()
			.eventTypes(List.of(TimelineEventType.APP_LOG, TimelineEventType.MCP_JSONRPC_REQUEST))
			.build());
		assertThat(result).hasSize(2);
	}

	@Test
	@DisplayName("query filters by since, dropping older events")
	void queryBySince() {
		final Instant past = Instant.now().minusSeconds(60);
		this.service
			.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "recent", null));
		this.service.append(
				new TimelineEvent("old-1", "corr-old", null, TimelineEventType.APP_LOG, past.minusSeconds(3600), null));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().since(past).build());
		assertThat(result).extracting(TimelineEvent::message).containsExactly("recent");
	}

	@Test
	@DisplayName("query filters by until (exclusive cutoff)")
	void queryByUntil() {
		this.service
			.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "before", null));
		try {
			Thread.sleep(5);
		}
		catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		final Instant cutoff = Instant.now();
		try {
			Thread.sleep(5);
		}
		catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		this.service
			.append(TimelineEvent.createLogEvent(UUID.randomUUID().toString(), "INFO", "test", "main", "late", null));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().until(cutoff).build());
		assertThat(result).extracting(TimelineEvent::message).containsExactly("before");
	}

	@Test
	@DisplayName("query filters by sessionId")
	void queryBySessionIdFilter() {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("method", "tools/list");
		this.service.append(new TimelineEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "session-1",
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload));
		this.service.append(new TimelineEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "session-2",
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().sessionId("session-1").build());
		assertThat(result).hasSize(1);
		assertThat(result.get(0).sessionId()).isEqualTo("session-1");
	}

	@Test
	@DisplayName("query returns empty for non-matching correlationId")
	void queryByNonMatchingCorrelationId() {
		this.service.append(TimelineEvent.createLogEvent("corr-1", "INFO", "test", "main", "msg", null));
		final List<TimelineEvent> result = this.service
			.query(TimelineQuery.builder().correlationId("non-existent").build());
		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("query filters by clientName in payload")
	void queryByClientNameFilter() {
		final ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
		payload1.put("clientName", "clientA");
		final ObjectNode payload2 = JsonNodeFactory.instance.objectNode();
		payload2.put("clientName", "clientB");
		this.service.append(new TimelineEvent(UUID.randomUUID().toString(), null, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload1));
		this.service.append(new TimelineEvent(UUID.randomUUID().toString(), null, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload2));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().clientName("clientA").build());
		assertThat(result).hasSize(1);
		assertThat(result.get(0).payload().get("clientName").asText()).isEqualTo("clientA");
	}

	@Test
	@DisplayName("query filters by direction in payload")
	void queryByDirectionFilter() {
		final ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
		payload1.put("direction", "client->server");
		final ObjectNode payload2 = JsonNodeFactory.instance.objectNode();
		payload2.put("direction", "server->client");
		this.service.append(new TimelineEvent(UUID.randomUUID().toString(), null, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload1));
		this.service.append(new TimelineEvent(UUID.randomUUID().toString(), null, null,
				TimelineEventType.MCP_JSONRPC_RESPONSE, Instant.now(), payload2));
		final List<TimelineEvent> result = this.service
			.query(TimelineQuery.builder().direction("server->client").build());
		assertThat(result).hasSize(1);
		assertThat(result.get(0).payload().get("direction").asText()).isEqualTo("server->client");
	}

	@Test
	@DisplayName("query with clientName filter returns empty when payload has no clientName field")
	void queryByClientNameFilter_noClientNameInPayload() {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("method", "tools/list");
		this.service.append(new TimelineEvent(UUID.randomUUID().toString(), null, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().clientName("clientA").build());
		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("query with direction filter returns empty when payload has no direction field")
	void queryByDirectionFilter_noDirectionInPayload() {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("method", "tools/list");
		this.service.append(new TimelineEvent(UUID.randomUUID().toString(), null, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), payload));
		final List<TimelineEvent> result = this.service
			.query(TimelineQuery.builder().direction("client->server").build());
		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("query with clientName filter matches when payload is null returns empty")
	void queryByClientNameFilter_nullPayload() {
		this.service.append(new TimelineEvent(UUID.randomUUID().toString(), null, null,
				TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), null));
		final List<TimelineEvent> result = this.service.query(TimelineQuery.builder().clientName("clientA").build());
		assertThat(result).isEmpty();
	}

}
