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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

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

	private JSONRPCMessage deserialize(final JsonNode frame) throws Exception {
		return McpSchema.deserializeJsonRpcMessage(
				new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(this.mapper), frame.toString());
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
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
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
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
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
			assertThat(McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all())).isEmpty();
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
			final TimelineEvent event = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all()).get(0);
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
			final String requestCorrelationId = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all())
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
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
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
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
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
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
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
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
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
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
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
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
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

		@Test
		@DisplayName("same JSON-RPC id in different sessions are correlated independently")
		void sameIdAcrossSessionsIsIndependent() throws Exception {
			// given - two sessions each issue a request with id=1
			final ObjectNode frame1 = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "tools/list");
			final JSONRPCMessage typed1 = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					frame1.toString());

			final ObjectNode frame2 = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "resources/list");
			final JSONRPCMessage typed2 = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					frame2.toString());

			// when
			McpTrafficRecorderTests.this.recorder.recordOutbound("session-a", typed1, frame1);
			McpTrafficRecorderTests.this.recorder.recordOutbound("session-b", typed2, frame2);

			// then - both pending, no collision
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(2);

			// record a response for session-a
			final ObjectNode resA = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.set("result", McpTrafficRecorderTests.this.mapper.createObjectNode());
			final JSONRPCMessage resTypedA = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					resA.toString());
			McpTrafficRecorderTests.this.recorder.recordInbound("session-a", resTypedA, resA);
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(1);

			// record a response for session-b
			final ObjectNode resB = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.set("result", McpTrafficRecorderTests.this.mapper.createObjectNode());
			final JSONRPCMessage resTypedB = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					resB.toString());
			McpTrafficRecorderTests.this.recorder.recordInbound("session-b", resTypedB, resB);
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(0);

			// verify: session-a request/response share correlation
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
			final TimelineEvent reqA = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_REQUEST)
				.filter((e) -> "session-a".equals(e.sessionId()))
				.findFirst()
				.orElseThrow();
			final TimelineEvent resAevent = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_RESPONSE)
				.filter((e) -> "session-a".equals(e.sessionId()))
				.findFirst()
				.orElseThrow();
			assertThat(resAevent.correlationId()).as("session-a pair").isEqualTo(reqA.correlationId());

			// session-b request/response share correlation
			final TimelineEvent reqB = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_REQUEST)
				.filter((e) -> "session-b".equals(e.sessionId()))
				.findFirst()
				.orElseThrow();
			final TimelineEvent resBevent = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_RESPONSE)
				.filter((e) -> "session-b".equals(e.sessionId()))
				.findFirst()
				.orElseThrow();
			assertThat(resBevent.correlationId()).as("session-b pair").isEqualTo(reqB.correlationId());

			// the two sessions have different correlations
			assertThat(resAevent.correlationId()).as("correlations differ between sessions")
				.isNotEqualTo(resBevent.correlationId());
		}

	}

	@Nested
	@DisplayName("clearSession")
	class ClearSession {

		@Test
		@DisplayName("ignores null sessionId")
		void ignoresNullSessionId() {
			// when
			McpTrafficRecorderTests.this.recorder.clearSession(null);

			// then — no exception
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isZero();
		}

		@Test
		@DisplayName("removes pending correlations for the given session")
		void removesPendingForSession() throws Exception {
			// given — record a request in session-a
			final ObjectNode req = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "tools/list");
			final JSONRPCMessage typed = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					req.toString());
			McpTrafficRecorderTests.this.recorder.recordOutbound("session-a", typed, req);
			McpTrafficRecorderTests.this.recorder.recordOutbound("session-b", typed, req);
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(2);

			// when
			McpTrafficRecorderTests.this.recorder.clearSession("session-a");

			// then — only session-b remains
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(1);
		}

	}

	@Nested
	@DisplayName("recordStreamEvent with correlation")
	class RecordStreamEventWithCorrelation {

		@Test
		@DisplayName("preserves the originating correlation id")
		void preservesOriginatingCorrelation() throws Exception {
			// given
			final JsonNode payload = McpTrafficRecorderTests.this.mapper.createObjectNode().put("chunk", true);
			final String originatingCorrelationId = UUID.randomUUID().toString();

			// when
			McpTrafficRecorderTests.this.recorder.recordStreamEvent("s-1", originatingCorrelationId, payload);

			// then
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).correlationId()).isEqualTo(originatingCorrelationId);
		}

	}

	@Nested
	@DisplayName("MDC preservation")
	class MdcPreservation {

		@Test
		@DisplayName("restores prior MDC correlationId after outbound request")
		void restoresPriorMdcAfterOutbound() throws Exception {
			// given
			MDC.put(McpTrafficRecorder.MDC_CORRELATION_ID, "outer-context");
			final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "tools/list");
			final JSONRPCMessage typed = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					reqFrame.toString());

			try {
				// when
				McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", typed, reqFrame);

				// then — MDC is restored
				assertThat(MDC.get(McpTrafficRecorder.MDC_CORRELATION_ID)).isEqualTo("outer-context");
			}
			finally {
				MDC.remove(McpTrafficRecorder.MDC_CORRELATION_ID);
			}
		}

		@Test
		@DisplayName("restores prior MDC correlationId after inbound response")
		void restoresPriorMdcAfterInbound() throws Exception {
			// given — record a request first
			final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 42)
				.put("method", "tools/call");
			final JSONRPCMessage reqTyped = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					reqFrame.toString());
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", reqTyped, reqFrame);

			MDC.put(McpTrafficRecorder.MDC_CORRELATION_ID, "outer-context");
			final ObjectNode resFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 42)
				.set("result", McpTrafficRecorderTests.this.mapper.createObjectNode().put("ok", true));
			final JSONRPCMessage resTyped = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					resFrame.toString());

			try {
				// when
				McpTrafficRecorderTests.this.recorder.recordInbound("s-1", resTyped, resFrame);

				// then — MDC is restored
				assertThat(MDC.get(McpTrafficRecorder.MDC_CORRELATION_ID)).isEqualTo("outer-context");
			}
			finally {
				MDC.remove(McpTrafficRecorder.MDC_CORRELATION_ID);
			}
		}

		@Test
		@DisplayName("restores prior MDC when the timeline throws on append")
		void restoresPriorMdcWhenAppendThrows() {
			final TimelineService failing = mock(TimelineService.class);
			willThrow(new IllegalStateException("sink down")).given(failing)
				.append(ArgumentMatchers.any(TimelineEvent.class));
			final McpTrafficRecorder throwingRecorder = new McpTrafficRecorder(failing);

			// given: a prior MDC value and a request the recorder will try to append
			MDC.put(McpTrafficRecorder.MDC_CORRELATION_ID, "outer-context");
			try {
				// when: append throws; the recorder must still hand the thread back
				// with the prior correlation in place
				assertThatThrownBy(() -> throwingRecorder.recordStreamEvent("s-1",
						McpTrafficRecorderTests.this.mapper.createObjectNode().put("chunk", true)))
					.isInstanceOf(IllegalStateException.class)
					.hasMessage("sink down");

				// then
				assertThat(MDC.get(McpTrafficRecorder.MDC_CORRELATION_ID)).isEqualTo("outer-context");
			}
			finally {
				MDC.remove(McpTrafficRecorder.MDC_CORRELATION_ID);
			}
		}

		@Test
		@DisplayName("clears MDC on failure when there was no prior value")
		void clearsMdcWhenAppendThrowsWithoutPrior() {
			final TimelineService failing = mock(TimelineService.class);
			willThrow(new IllegalStateException("sink down")).given(failing)
				.append(ArgumentMatchers.any(TimelineEvent.class));
			final McpTrafficRecorder throwingRecorder = new McpTrafficRecorder(failing);

			MDC.remove(McpTrafficRecorder.MDC_CORRELATION_ID);
			// when
			assertThatThrownBy(() -> throwingRecorder.recordStreamEvent("s-1",
					McpTrafficRecorderTests.this.mapper.createObjectNode().put("chunk", true)))
				.isInstanceOf(IllegalStateException.class);

			// then: the recorder's own correlation id must not leak
			assertThat(MDC.get(McpTrafficRecorder.MDC_CORRELATION_ID)).isNull();
		}

	}

	@Nested
	@DisplayName("pending bound under concurrency")
	class PendingBoundConcurrency {

		@Test
		@DisplayName("never exceeds MAX_PENDING_CORRELATIONS under concurrent inserts")
		void concurrentStoresNeverExceedBound() throws Exception {
			// given: N writers each inserting unique requests past the bound,
			// released simultaneously by a barrier so they race in storePending
			final int threads = 8;
			final int perThread = 250;
			final ExecutorService pool = Executors.newFixedThreadPool(threads);
			final CountDownLatch ready = new CountDownLatch(threads);
			final CountDownLatch go = new CountDownLatch(1);
			final AtomicInteger maxSeen = new AtomicInteger();
			final List<Throwable> failures = new ArrayList<>();
			try {
				final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
				for (int t = 0; t < threads; t++) {
					final int tid = t;
					futures.add(pool.submit(() -> {
						try {
							ready.countDown();
							go.await();
							for (int i = 0; i < perThread; i++) {
								final ObjectNode frame = McpTrafficRecorderTests.this.mapper.createObjectNode()
									.put("jsonrpc", "2.0")
									.put("id", tid * 100_000 + i)
									.put("method", "tools/list");
								McpTrafficRecorderTests.this.recorder.recordOutbound("s-race",
										McpTrafficRecorderTests.this.deserialize(frame), frame);
								final int seen = McpTrafficRecorderTests.this.recorder.pendingCorrelations();
								maxSeen.accumulateAndGet(seen, Math::max);
							}
						}
						catch (final Throwable ex) {
							synchronized (failures) {
								failures.add(ex);
							}
						}
					}));
				}
				assertThat(ready.await(10, TimeUnit.SECONDS)).as("writers ready").isTrue();
				// when
				go.countDown();
				for (final java.util.concurrent.Future<?> future : futures) {
					future.get(30, TimeUnit.SECONDS);
				}
			}
			finally {
				pool.shutdownNow();
			}
			assertThat(failures).isEmpty();

			// then: the invariant is the observed maximum, not the final size
			assertThat(maxSeen.get()).as("pending map peak size must never exceed the bound")
				.isLessThanOrEqualTo(McpTrafficRecorder.MAX_PENDING_CORRELATIONS);
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations())
				.isLessThanOrEqualTo(McpTrafficRecorder.MAX_PENDING_CORRELATIONS);
		}

	}

	@Nested
	@DisplayName("inbound progress notification")
	class InboundProgressNotification {

		@Test
		@DisplayName("routes notifications/progress through recordStreamEvent")
		void routesProgressNotification() throws Exception {
			// given — a request with a progress token
			final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 10)
				.put("method", "tools/call");
			final JSONRPCMessage reqTyped = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					reqFrame.toString());
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", reqTyped, reqFrame);

			// when — an inbound progress notification
			final ObjectNode progressFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("method", "notifications/progress");
			final JSONRPCMessage progressTyped = McpSchema.deserializeJsonRpcMessage(
					new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(McpTrafficRecorderTests.this.mapper),
					progressFrame.toString());
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", progressTyped, progressFrame);

			// then — it's recorded as a stream event, not a notification
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
			final TimelineEvent progressEvent = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_STREAM_EVENT)
				.findFirst()
				.orElseThrow();
			assertThat(progressEvent).isNotNull();
		}

		@Test
		@DisplayName("progress notification reuses the originating request correlation")
		void progressNotificationReusesCorrelation() throws Exception {
			// given: an outbound request carrying params._meta.progressToken
			final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 11)
				.put("method", "tools/call");
			reqFrame.putObject("params").putObject("_meta").put("progressToken", "tok-1");
			final JSONRPCMessage reqTyped = deserialize(reqFrame);
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", reqTyped, reqFrame);
			final String requestCorrelation = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all())
				.get(0)
				.correlationId();

			// when: an inbound progress notification with the same token
			final ObjectNode progressFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("method", "notifications/progress");
			progressFrame.putObject("params").put("progressToken", "tok-1");
			final JSONRPCMessage progressTyped = deserialize(progressFrame);
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", progressTyped, progressFrame);

			// then: the stream event shares the request's correlation
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
			final TimelineEvent streamEvent = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_STREAM_EVENT)
				.findFirst()
				.orElseThrow();
			assertThat(streamEvent.correlationId()).isEqualTo(requestCorrelation);
		}

		@Test
		@DisplayName("unknown progress token falls back to a fresh correlation")
		void unknownProgressTokenFallsBack() throws Exception {
			// given: no matching request recorded
			final ObjectNode progressFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("method", "notifications/progress");
			progressFrame.putObject("params").put("progressToken", "tok-unknown");
			final JSONRPCMessage progressTyped = deserialize(progressFrame);

			// when
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", progressTyped, progressFrame);

			// then: still recorded as a stream event with a generated correlation
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).type()).isEqualTo(TimelineEventType.MCP_STREAM_EVENT);
			assertThat(events.get(0).correlationId()).isNotNull();
		}

		@Test
		@DisplayName("non-scalar progress token is ignored")
		void nonScalarProgressTokenIsIgnored() throws Exception {
			// given: progressToken is an object, not a value node
			final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 12)
				.put("method", "tools/call");
			reqFrame.putObject("params").putObject("_meta").putObject("progressToken").put("nested", true);
			final JSONRPCMessage reqTyped = deserialize(reqFrame);

			// when
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", reqTyped, reqFrame);

			// then: request still pending and correlated, token simply not registered
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(1);
		}

		@Test
		@DisplayName("outbound request without an id is still recorded")
		void outboundRequestWithoutIdIsRecorded() throws Exception {
			// given: a JSONRPCRequest whose id is null cannot exist via the typed
			// deserialiser, so exercise the null-session key normalisation instead
			final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 13)
				.put("method", "tools/list");
			final JSONRPCMessage reqTyped = deserialize(reqFrame);

			// when: null sessionId normalises to the empty key
			McpTrafficRecorderTests.this.recorder.recordOutbound(null, reqTyped, reqFrame);

			// then
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isEqualTo(1);

			// and the matching response with a null session finds it
			final ObjectNode resFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 13)
				.set("result", McpTrafficRecorderTests.this.mapper.createObjectNode());
			final JSONRPCMessage resTyped = deserialize(resFrame);
			McpTrafficRecorderTests.this.recorder.recordInbound(null, resTyped, resFrame);
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isZero();
		}

		@Test
		@DisplayName("response with null id gets a fresh correlation")
		void responseWithNullIdGetsFreshCorrelation() {
			// given: the typed JSONRPCResponse with a null id cannot be deserialised
			// (SDK validation rejects it), so mock the record interface
			final McpSchema.JSONRPCResponse nullIdResponse = mock(McpSchema.JSONRPCResponse.class);
			given(nullIdResponse.id()).willReturn(null);

			// when
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", nullIdResponse, null);

			// then: recorded as a response, no pending entry touched
			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_RESPONSE);
			assertThat(events.get(0).correlationId()).isNotNull();
		}

		@Test
		@DisplayName("unexpected outbound and inbound message types are ignored")
		void unexpectedMessageTypesAreIgnored() throws Exception {
			// given
			final ObjectNode resFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 14)
				.set("result", McpTrafficRecorderTests.this.mapper.createObjectNode());
			final JSONRPCMessage resTyped = deserialize(resFrame);
			final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 15)
				.put("method", "tools/list");
			final JSONRPCMessage reqTyped = deserialize(reqFrame);

			// when: a response outbound and a request inbound — neither is expected
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", resTyped, resFrame);
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", reqTyped, reqFrame);

			// then: both fall through to the unexpected-type branch; nothing recorded
			assertThat(McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all())).isEmpty();
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations()).isZero();
		}

		@Test
		@DisplayName("evicted pending entry removes its progress-token mapping")
		void evictedRequestClearsProgressTokenMapping() throws Exception {
			// given: a request with a progress token - this will be the eldest entry
			final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "tools/call");
			reqFrame.putObject("params").putObject("_meta").put("progressToken", "tok-evict");
			final JSONRPCMessage reqTyped = deserialize(reqFrame);
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", reqTyped, reqFrame);

			// when: fill past capacity to evict the eldest request
			for (int i = 0; i < McpTrafficRecorder.MAX_PENDING_CORRELATIONS + 5; i++) {
				final ObjectNode fillFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
					.put("jsonrpc", "2.0")
					.put("id", 100_000 + i)
					.put("method", "tools/list");
				McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", deserialize(fillFrame), fillFrame);
			}
			// the evicted entry is gone from pending
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations())
				.isLessThanOrEqualTo(McpTrafficRecorder.MAX_PENDING_CORRELATIONS);

			// when: a new request with the same progress token gets a fresh correlation
			final ObjectNode req2Frame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("id", 9999)
				.put("method", "tools/call");
			req2Frame.putObject("params").putObject("_meta").put("progressToken", "tok-evict");
			final JSONRPCMessage req2Typed = deserialize(req2Frame);
			McpTrafficRecorderTests.this.recorder.recordOutbound("s-1", req2Typed, req2Frame);

			// then: the progress notification for "tok-evict" uses the NEW request's
			// correlation, not the stale one
			final ObjectNode progressFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
				.put("jsonrpc", "2.0")
				.put("method", "notifications/progress");
			progressFrame.putObject("params").put("progressToken", "tok-evict");
			final JSONRPCMessage progressTyped = deserialize(progressFrame);
			McpTrafficRecorderTests.this.recorder.recordInbound("s-1", progressTyped, progressFrame);

			final List<TimelineEvent> events = McpTrafficRecorderTests.this.timelineService.query(TimelineQuery.all());
			// find the stream event for the progress notification
			final TimelineEvent progressEvent = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_STREAM_EVENT)
				.findFirst()
				.orElseThrow();
			// find the second request event (id=9999)
			final TimelineEvent req2Event = events.stream()
				.filter((e) -> e.type() == TimelineEventType.MCP_JSONRPC_REQUEST)
				.filter((e) -> {
					final JsonNode p = e.payload();
					return p != null && p.get("id") != null && p.get("id").asInt() == 9999;
				})
				.findFirst()
				.orElseThrow();
			// progress shares the new request's correlation, not the stale one
			assertThat(progressEvent.correlationId()).isEqualTo(req2Event.correlationId());
		}

		@Test
		@DisplayName("pending correlations are bounded at MAX_PENDING_CORRELATIONS")
		void pendingCorrelationsAreBounded() throws Exception {
			// given/when: send more requests than the bound, all unanswered
			for (int i = 0; i < McpTrafficRecorder.MAX_PENDING_CORRELATIONS + 5; i++) {
				final ObjectNode reqFrame = McpTrafficRecorderTests.this.mapper.createObjectNode()
					.put("jsonrpc", "2.0")
					.put("id", 100_000 + i)
					.put("method", "tools/list");
				McpTrafficRecorderTests.this.recorder.recordOutbound("s-bulk", deserialize(reqFrame), reqFrame);
			}

			// then: map never exceeds the bound
			assertThat(McpTrafficRecorderTests.this.recorder.pendingCorrelations())
				.isLessThanOrEqualTo(McpTrafficRecorder.MAX_PENDING_CORRELATIONS);
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

			final List<TimelineEvent> results = svc.query(TimelineQuery.byCorrelationId(corrId));
			assertThat(results).hasSize(1);
			assertThat(results.get(0).correlationId()).isEqualTo(corrId);
		}

		@Test
		@DisplayName("clear removes all events")
		void clearRemovesAll() {
			final TimelineService svc = new BoundedTimelineService();
			svc.append(new TimelineEvent("1", "c", null, TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), null));
			svc.clear();

			assertThat(svc.query(TimelineQuery.all())).isEmpty();
		}

	}

}
