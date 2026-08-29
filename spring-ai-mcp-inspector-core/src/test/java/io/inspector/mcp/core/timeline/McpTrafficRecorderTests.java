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
import java.util.List;
import java.util.UUID;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link McpTrafficRecorder}. */
class McpTrafficRecorderTests {

	private final JsonMapper mapper = new JsonMapper();

	private BoundedTimelineService timelineService;

	private McpTrafficRecorder recorder;

	@BeforeEach
	void setUp() {
		this.timelineService = new BoundedTimelineService();
		this.recorder = new McpTrafficRecorder(this.timelineService);
	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@DisplayName("rejects null TimelineService")
		void rejectsNullTimelineService() {
			assertThatThrownBy(() -> new McpTrafficRecorder(null)).isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	@DisplayName("recordOutbound")
	class RecordOutbound {

		@Test
		@DisplayName("records a JSON-RPC request event")
		void recordsRequest() throws Exception {
			// given
			final ObjectNode rawFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 7)
				.put("method", "tools/list");
			final JSONRPCMessage typed = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					rawFrame.toString());

			// when
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", typed, rawFrame);

			// then
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all());
			assertThat(events).hasSize(1);
			final TimelineEvent event = events.get(0);
			assertThat(event.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_REQUEST);
			assertThat(event.sessionId()).isEqualTo("s-1");
			assertThat(event.correlationId()).isNotNull();
			assertThat(event.payload()).isEqualTo(rawFrame);
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(1);
		}

		@Test
		@DisplayName("records a notification event")
		void recordsNotification() throws Exception {
			// given
			final ObjectNode rawFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("method", "notifications/initialized");
			final JSONRPCMessage typed = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					rawFrame.toString());

			// when
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", typed, rawFrame);

			// then
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all());
			assertThat(events).hasSize(1);
			final TimelineEvent event = events.get(0);
			assertThat(event.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_NOTIFICATION);
			assertThat(event.correlationId()).isNotNull();
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(0);
		}

		@Test
		@DisplayName("ignores null message")
		void ignoresNullMessage() throws Exception {
			// when
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", null, null);

			// then
			assertThat(McpTrafficRecorderTests.this.timelineService.query(TimelineService.TimelineQuery.all()))
				.isEmpty();
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(0);
		}

		@Test
		@DisplayName("sets MDC correlationId")
		void setsMdcCorrelationId() throws Exception {
			// given
			final ObjectNode rawFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "tools/list");
			final JSONRPCMessage typed = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					rawFrame.toString());

			// when
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", typed, rawFrame);

			// then — MDC should NOT be set after the method returns because
			// MDCCloseable auto-closes
			assertThat(MDC.get(McpTrafficRecorder.MDC_CORRELATION_ID)).isNull();
			// but the event has the correlationId
			final TimelineEvent event = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all())
				.get(0);
			assertThat(event.correlationId()).isNotEmpty();
		}

	}

	@Nested
	@DisplayName("recordInbound")
	class RecordInbound {

		@Test
		@DisplayName("records a response with matching correlationId")
		void recordsResponseWithMatchingCorrelation() throws Exception {
			// given — first record a request
			final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 42)
				.put("method", "tools/call");
			final JSONRPCMessage reqTyped = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					reqFrame.toString());
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", reqTyped, reqFrame);
			final String requestCorrelationId = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all())
				.get(0)
				.correlationId();

			// when — record the matching response
			final ObjectNode resFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 42)
				.set("result", McpTrafficRecorderTests.this.mapper.createObjectNode().put("ok", true));
			final JSONRPCMessage resTyped = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					resFrame.toString());
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", resTyped, resFrame);

			// then
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all());
			assertThat(events).hasSize(2);
			// events are newest-first, so response is first
			final TimelineEvent responseEvent = events.get(0);
			assertThat(responseEvent.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_RESPONSE);
			assertThat(responseEvent.correlationId()).isEqualTo(requestCorrelationId);
			// pending correlation cleaned up
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(0);
		}

		@Test
		@DisplayName("records response with fresh correlationId when no request recorded")
		void recordsResponseWithoutMatchingRequest() throws Exception {
			// given — a response without a prior request
			final ObjectNode resFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 99)
				.set("result", McpTrafficRecorderTests.this.mapper.createObjectNode().put("ok", true));
			final JSONRPCMessage resTyped = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					resFrame.toString());

			// when
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", resTyped, resFrame);

			// then
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_RESPONSE);
			assertThat(events.get(0).correlationId()).isNotNull();
		}

		@Test
		@DisplayName("records inbound notification")
		void recordsInboundNotification() throws Exception {
			// given
			final ObjectNode rawFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("method", "notifications/tools/list_changed");
			final JSONRPCMessage typed = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					rawFrame.toString());

			// when
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", typed, rawFrame);

			// then
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_NOTIFICATION);
		}

	}

	@Nested
	@DisplayName("recordStreamEvent")
	class RecordStreamEvent {

		@Test
		@DisplayName("records a stream event")
		void recordsStreamEvent() throws Exception {
			// given
			final JsonNode payload = McpTrafficRecorderTests.this.mapper.createObjectNode().put("chunk", true);

			// when
			McpTrafficRecorderTests.this.recorder.recordStreamEvent("s-1", payload);

			// then
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all());
			assertThat(events).hasSize(1);
			final TimelineEvent event = events.get(0);
			assertThat(event.type()).isEqualTo(TimelineEventType.MCP_STREAM_EVENT);
			assertThat(event.payload()).isEqualTo(payload);
		}

		@Test
		@DisplayName("records stream event with null payload")
		void recordsStreamEventWithNullPayload() throws Exception {
			// when
			McpTrafficRecorderTests.this.recorder.recordStreamEvent("s-1", null);

			// then
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).payload()).isNull();
		}

	}

	@Nested
	@DisplayName("request/response correlation")
	class RequestResponseCorrelation {

		@Test
		@DisplayName("multiple requests keep separate correlations")
		void multipleRequestsKeepSeparateCorrelations() throws Exception {
			// given
			final ObjectNode req1 = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "tools/list");
			final ObjectNode req2 = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 2)
				.put("method", "resources/list");
			final JSONRPCMessage t1 = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					req1.toString());
			final JSONRPCMessage t2 = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					req2.toString());

			// when
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", t1, req1);
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", t2, req2);

			// then — 2 pending correlations
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(2);

			// record both responses (in reverse order)
			final ObjectNode res2 = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 2)
				.set("result", McpTrafficRecorderTests.this.mapper.createObjectNode());
			final JSONRPCMessage rt2 = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					res2.toString());
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", rt2, res2);
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(1);

			final ObjectNode res1 = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.set("result", McpTrafficRecorderTests.this.mapper.createObjectNode());
			final JSONRPCMessage rt1 = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					res1.toString());
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", rt1, res1);
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(0);

			// request 1 and response 1 share correlation
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService
				.query(TimelineService.TimelineQuery.all());
			final TimelineEvent req1Event = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_REQUEST)
				.filter((e) -> {
					final JsonNode p = e.payload();
					return p != null && p.get("id") != null && p.get("id").asInt() == 1;
				})
				.findFirst()
				.orElseThrow();
			final TimelineEvent res1Event = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_RESPONSE)
				.filter((e) -> {
					final JsonNode p = e.payload();
					return p != null && p.get("id") != null && p.get("id").asInt() == 1;
				})
				.findFirst()
				.orElseThrow();
			assertThat(res1Event.correlationId()).isEqualTo(req1Event.correlationId());

			final TimelineEvent req2Event = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_REQUEST)
				.filter((e) -> {
					final JsonNode p = e.payload();
					return p != null && p.get("id") != null && p.get("id").asInt() == 2;
				})
				.findFirst()
				.orElseThrow();
			final TimelineEvent res2Event = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_RESPONSE)
				.filter((e) -> {
					final JsonNode p = e.payload();
					return p != null && p.get("id") != null && p.get("id").asInt() == 2;
				})
				.findFirst()
				.orElseThrow();
			assertThat(res2Event.correlationId()).isEqualTo(req2Event.correlationId());

			// different requests have different correlations
			assertThat(req2Event.correlationId()).isNotEqualTo(req1Event.correlationId());
		}

	}

	@Nested
	@DisplayName("BoundedTimelineService")
	class BoundedTimelineServiceTests {

		@Test
		@DisplayName("drops oldest events when ring buffer is full")
		void dropsOldestWhenFull() {
			// given — fill the buffer with events
			final BoundedTimelineService bts = new BoundedTimelineService();
			final String sessionId = "s-1";
			for (int i = 0; i < BoundedTimelineService.MAX_EVENTS + 10; i++) {
				bts.append(new TimelineEvent(UUID.randomUUID().toString(), "corr-" + i, sessionId,
						TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), null));
			}

			// then — only max events remain
			assertThat(bts.size()).isEqualTo(BoundedTimelineService.MAX_EVENTS);
		}

		@Test
		@DisplayName("query filters by correlationId")
		void queryByCorrelationId() {
			final TimelineService svc = new BoundedTimelineService();
			final String corrId = "my-correlation";
			svc.append(new TimelineEvent("1", "other-correlation", null, TimelineEventType.MCP_JSONRPC_REQUEST,
					Instant.now(), null));
			svc.append(
					new TimelineEvent("2", corrId, null, TimelineEventType.MCP_JSONRPC_RESPONSE, Instant.now(), null));

			final List<TimelineEvent> results = svc.query(TimelineService.TimelineQuery.byCorrelationId(corrId));
			assertThat(results).hasSize(1);
			assertThat(results.get(0).correlationId()).isEqualTo(corrId);
		}

		@Test
		@DisplayName("clear removes all events")
		void clearRemovesAll() {
			final TimelineService svc = new BoundedTimelineService();
			svc.append(new TimelineEvent("1", "c", null, TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), null));
			svc.clear();

			assertThat(svc.query(TimelineService.TimelineQuery.all())).isEmpty();
		}

	}

}
