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

import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link McpClientTrafficRecorder}.
 *
 * @author Artem Simeshin
 */
class McpClientTrafficRecorderTests {

	private BoundedTimelineService timelineService;

	private McpClientTrafficRecorder recorder;

	@BeforeEach
	void setUp() {
		// given
		this.timelineService = new BoundedTimelineService();
		this.recorder = new McpClientTrafficRecorder(this.timelineService);
	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@DisplayName("rejects null TimelineService")
		void rejectsNullTimelineService() {
			assertThatThrownBy(() -> new McpClientTrafficRecorder(null)).isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	@DisplayName("recordClientRequest")
	class RecordClientRequest {

		@Test
		@DisplayName("appends MCP_JSONRPC_REQUEST event with client metadata")
		void appendsRequestEventWithMetadata() {
			// given
			final JSONRPCRequest request = new JSONRPCRequest("2.0", "tools/call", 1, null);

			// when
			McpClientTrafficRecorderTests.this.recorder.recordClientRequest("my-client", "stdio", request);

			// then
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			final TimelineEvent event = events.get(0);
			assertThat(event.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_REQUEST);
			assertThat(event.correlationId()).matches("[a-f0-9-]{36}");
			assertThat(event.payload().path("endpoint").asText()).isEqualTo("client");
			assertThat(event.payload().path("clientName").asText()).isEqualTo("my-client");
			assertThat(event.payload().path("transport").asText()).isEqualTo("stdio");
			assertThat(event.payload().path("direction").asText()).isEqualTo("client->server");
			assertThat(event.payload().path("method").asText()).isEqualTo("tools/call");
			assertThat(event.payload().path("id").asText()).isEqualTo("1");
		}

		@Test
		@DisplayName("stores pending correlation for response matching")
		void storesPendingCorrelation() {
			// given
			final JSONRPCRequest request = new JSONRPCRequest("2.0", "tools/list", 42, null);

			// when
			McpClientTrafficRecorderTests.this.recorder.recordClientRequest("client-a", "sse", request);

			// then
			assertThat(McpClientTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(1);
		}

		@Test
		@DisplayName("ignores null request")
		void ignoresNullRequest() {
			// when
			McpClientTrafficRecorderTests.this.recorder.recordClientRequest("c", "stdio", null);

			// then
			assertThat(McpClientTrafficRecorderTests.this.timelineService.query(TimelineQuery.all())).isEmpty();
		}

	}

	@Nested
	@DisplayName("recordClientResponse")
	class RecordClientResponse {

		@Test
		@DisplayName("pairs response with request by correlation id")
		void pairsResponseWithRequest() {
			// given
			final JSONRPCRequest request = new JSONRPCRequest("2.0", "tools/call", 7, null);
			McpClientTrafficRecorderTests.this.recorder.recordClientRequest("my-client", "stdio", request);

			// when
			final JSONRPCResponse response = JSONRPCResponse.result(7, java.util.Map.of());
			McpClientTrafficRecorderTests.this.recorder.recordClientResponse("my-client", "stdio", response);

			// then
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(2);
			final TimelineEvent responseEvent = events.get(0);
			assertThat(responseEvent.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_RESPONSE);
			assertThat(responseEvent.correlationId()).matches("[a-f0-9-]{36}");
			assertThat(responseEvent.payload().path("direction").asText()).isEqualTo("server->client");
			assertThat(McpClientTrafficRecorderTests.this.recorder.pendingCorrelations()).isZero();
		}

		@Test
		@DisplayName("marks orphan response when no matching request")
		void marksOrphanResponse() {
			// given
			final JSONRPCResponse response = JSONRPCResponse.result(99, java.util.Map.of());

			// when
			McpClientTrafficRecorderTests.this.recorder.recordClientResponse("orphan-client", "sse", response);

			// then
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			final TimelineEvent event = events.get(0);
			assertThat(event.payload().path("orphan").asText()).isEqualTo("true");
		}

		@Test
		@DisplayName("records latency for paired response")
		void recordsLatencyForPairedResponse() {
			// given
			final JSONRPCRequest request = new JSONRPCRequest("2.0", "ping", 1, null);
			McpClientTrafficRecorderTests.this.recorder.recordClientRequest("c", "stdio", request);

			// when
			final JSONRPCResponse response = JSONRPCResponse.result(1, java.util.Map.of());
			McpClientTrafficRecorderTests.this.recorder.recordClientResponse("c", "stdio", response);

			// then
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			final TimelineEvent responseEvent = events.get(0);
			assertThat(responseEvent.payload().has("latencyMs")).isTrue();
			assertThat(responseEvent.payload().path("latencyMs").asLong()).isGreaterThanOrEqualTo(0L);
		}

		@Test
		@DisplayName("pairs callback response with server request via srv:-prefixed key")
		void pairsCallbackResponseWithServerRequest() {
			// given: a server-initiated request (e.g. sampling/createMessage)
			final JSONRPCRequest serverRequest = new JSONRPCRequest("2.0", "sampling/createMessage", "srv-1", null);
			McpClientTrafficRecorderTests.this.recorder.recordServerRequest("my-client", "stdio", serverRequest);

			// when: the client answers with a JSONRPCResponse via recordClientResponse
			final JSONRPCResponse response = JSONRPCResponse.result("srv-1", java.util.Map.of());
			McpClientTrafficRecorderTests.this.recorder.recordClientResponse("my-client", "stdio", response);

			// then: response is not orphan, has latency, and pending is released
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(2);
			final TimelineEvent responseEvent = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_RESPONSE)
				.findFirst()
				.orElseThrow();
			assertThat(responseEvent.payload().has("orphan")).isFalse();
			assertThat(responseEvent.payload().has("latencyMs")).isTrue();
			assertThat(responseEvent.payload().path("latencyMs").asLong()).isGreaterThanOrEqualTo(0L);
			assertThat(McpClientTrafficRecorderTests.this.recorder.pendingCorrelations()).isZero();
		}

	}

	@Nested
	@DisplayName("recordClientNotification")
	class RecordClientNotification {

		@Test
		@DisplayName("appends MCP_JSONRPC_NOTIFICATION event with client metadata")
		void appendsNotificationEvent() {
			// given
			final JSONRPCNotification notification = new JSONRPCNotification("notifications/cancelled", null);

			// when
			McpClientTrafficRecorderTests.this.recorder.recordClientNotification("c", "stdio", notification);

			// then
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			final TimelineEvent event = events.get(0);
			assertThat(event.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_NOTIFICATION);
			assertThat(event.payload().path("direction").asText()).isEqualTo("client->server");
			assertThat(event.payload().path("method").asText()).isEqualTo("notifications/cancelled");
		}

	}

	@Nested
	@DisplayName("recordServerRequest")
	class RecordServerRequest {

		@Test
		@DisplayName("appends request with server->client direction")
		void appendsServerRequest() {
			// given
			final JSONRPCRequest request = new JSONRPCRequest("2.0", "sampling/createMessage", "srv-1", null);

			// when
			McpClientTrafficRecorderTests.this.recorder.recordServerRequest("c", "streamable-http", request);

			// then
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			final TimelineEvent event = events.get(0);
			assertThat(event.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_REQUEST);
			assertThat(event.payload().path("direction").asText()).isEqualTo("server->client");
			assertThat(event.correlationId()).matches("[a-f0-9-]{36}");
		}

	}

	@Nested
	@DisplayName("recordServerNotification")
	class RecordServerNotification {

		@Test
		@DisplayName("appends notification with server->client direction")
		void appendsServerNotification() {
			// given
			final JSONRPCNotification notification = new JSONRPCNotification("notifications/progress", null);

			// when
			McpClientTrafficRecorderTests.this.recorder.recordServerNotification("c", "sse", notification);

			// then
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			final TimelineEvent event = events.get(0);
			assertThat(event.payload().path("direction").asText()).isEqualTo("server->client");
			assertThat(event.payload().path("method").asText()).isEqualTo("notifications/progress");
		}

	}

	@Nested
	@DisplayName("request response correlation")
	class RequestResponseCorrelation {

		@Test
		@DisplayName("two clients with same id do not collide")
		void twoClientsSameIdNoCollision() {
			// given
			final JSONRPCRequest req1 = new JSONRPCRequest("2.0", "tools/call", 1, null);
			final JSONRPCRequest req2 = new JSONRPCRequest("2.0", "tools/call", 1, null);

			// when
			McpClientTrafficRecorderTests.this.recorder.recordClientRequest("client-a", "stdio", req1);
			McpClientTrafficRecorderTests.this.recorder.recordClientRequest("client-b", "sse", req2);
			McpClientTrafficRecorderTests.this.recorder.recordClientResponse("client-a", "stdio",
					JSONRPCResponse.result(1, java.util.Map.of()));
			McpClientTrafficRecorderTests.this.recorder.recordClientResponse("client-b", "sse",
					JSONRPCResponse.result(1, java.util.Map.of()));

			// then
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(4);
			final TimelineEvent responseA = events.stream()
				.filter((e) -> "client-a".equals(e.payload().path("clientName").asText())
						&& e.type() == TimelineEventType.MCP_JSONRPC_RESPONSE)
				.findFirst()
				.orElseThrow();
			final TimelineEvent responseB = events.stream()
				.filter((e) -> "client-b".equals(e.payload().path("clientName").asText())
						&& e.type() == TimelineEventType.MCP_JSONRPC_RESPONSE)
				.findFirst()
				.orElseThrow();
			assertThat(responseA.payload().path("clientName").asText()).isEqualTo("client-a");
			assertThat(responseB.payload().path("clientName").asText()).isEqualTo("client-b");
			assertThat(McpClientTrafficRecorderTests.this.recorder.pendingCorrelations()).isZero();
		}

		@Test
		@DisplayName("sequential same-id requests from one client get unique correlation ids")
		void sequentialSameIdRequestsGetUniqueCorrelationIds() {
			// given
			final JSONRPCRequest req1 = new JSONRPCRequest("2.0", "tools/call", 1, null);
			final JSONRPCRequest req2 = new JSONRPCRequest("2.0", "tools/call", 1, null);

			// when
			McpClientTrafficRecorderTests.this.recorder.recordClientRequest("c", "stdio", req1);
			McpClientTrafficRecorderTests.this.recorder.recordClientRequest("c", "stdio", req2);

			// then: each event has a unique correlation id
			final List<TimelineEvent> events = McpClientTrafficRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).hasSize(2);
			assertThat(events.get(0).correlationId()).isNotEqualTo(events.get(1).correlationId());
			// Only one pending entry survives (the second overwrites the first at the
			// same key)
			assertThat(McpClientTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(1);
		}

	}

}
