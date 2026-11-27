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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpElicitation;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link McpClientTrafficRecorder}: payload provenance, fresh correlation
 * per request and pairing of both server-initiated exchange directions.
 *
 * @author Artem Simeshin
 */
class McpClientTrafficRecorderTests {

	private static final McpClientTrafficRecorder.ClientEndpoint ENDPOINT = new McpClientTrafficRecorder.ClientEndpoint(
			"weather", "streamable-http", "https://***@api.example/mcp");

	private final RecordingTimelineService timeline = new RecordingTimelineService();

	private final McpClientTrafficRecorder recorder = new McpClientTrafficRecorder(this.timeline);

	@Test
	@DisplayName("outbound request event carries client provenance and pending correlation")
	void requestEventCarriesProvenance() {
		this.recorder.recordClientRequest(ENDPOINT, new JSONRPCRequest("2.0", "tools/call", 1, null));
		assertThat(this.timeline.events).hasSize(1);
		final TimelineEvent event = this.timeline.events.get(0);
		assertThat(event.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_REQUEST);
		assertThat(event.sessionId()).isNull();
		assertThat(event.correlationId()).isNotBlank();
		final JsonNode payload = event.payload();
		assertThat(payload.get("side").asString()).isEqualTo("client");
		assertThat(payload.get("clientName").asString()).isEqualTo("weather");
		assertThat(payload.get("direction").asString()).isEqualTo("out");
		assertThat(payload.get("transportType").asString()).isEqualTo("streamable-http");
		assertThat(payload.get("target").asString()).isEqualTo("https://***@api.example/mcp");
		assertThat(payload.get("method").asString()).isEqualTo("tools/call");
		assertThat(payload.get("id").asString()).isEqualTo("1");
		assertThat(this.recorder.pendingCorrelations()).isEqualTo(1);
	}

	@Test
	@DisplayName("sequential requests with the same id get fresh correlation ids (no collision)")
	void freshCorrelationPerRequestForSameId() {
		this.recorder.recordClientRequest(ENDPOINT, new JSONRPCRequest("2.0", "tools/call", 1, null));
		this.recorder.recordClientRequest(ENDPOINT, new JSONRPCRequest("2.0", "tools/call", 1, null));
		assertThat(this.timeline.events).hasSize(2);
		assertThat(this.timeline.events.get(0).correlationId())
			.isNotEqualTo(this.timeline.events.get(1).correlationId());
		// The second request replaces the pending entry for the same key.
		assertThat(this.recorder.pendingCorrelations()).isEqualTo(1);
	}

	@Test
	@DisplayName("inbound response pairs with its request: same correlation, latency, no orphan")
	void responsePairsWithRequest() {
		this.recorder.recordClientRequest(ENDPOINT, new JSONRPCRequest("2.0", "tools/call", 7, null));
		this.recorder.recordClientResponse(ENDPOINT, new JSONRPCResponse("2.0", 7, Map.of("ok", true), null));
		assertThat(this.timeline.events).hasSize(2);
		final TimelineEvent request = this.timeline.events.get(0);
		final TimelineEvent response = this.timeline.events.get(1);
		assertThat(response.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_RESPONSE);
		assertThat(response.correlationId()).isEqualTo(request.correlationId());
		assertThat(response.payload().get("latencyMs")).isNotNull();
		assertThat(response.payload().get("latencyMs").asLong()).isGreaterThanOrEqualTo(0L);
		assertThat(response.payload().get("orphan")).isNull();
		assertThat(response.payload().get("direction").asString()).isEqualTo("in");
		assertThat(this.recorder.pendingCorrelations()).isZero();
	}

	@Test
	@DisplayName("inbound response without a pending request is marked orphan")
	void responseOrphanWithoutRequest() {
		this.recorder.recordClientResponse(ENDPOINT, new JSONRPCResponse("2.0", 99, Map.of("ok", true), null));
		assertThat(this.timeline.events).hasSize(1);
		final TimelineEvent response = this.timeline.events.get(0);
		assertThat(response.payload().get("orphan").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("outbound JSONRPCResponse pairs with the server-initiated request: not orphan, with latency")
	void outboundResponsePairsWithServerRequest() {
		this.recorder.recordServerRequest(ENDPOINT, new JSONRPCRequest("2.0", "sampling/createMessage", 42, null));
		this.recorder.recordOutboundResponse(ENDPOINT, new JSONRPCResponse("2.0", 42, Map.of("ok", true), null));
		assertThat(this.timeline.events).hasSize(2);
		final TimelineEvent serverRequest = this.timeline.events.get(0);
		assertThat(serverRequest.type()).isEqualTo(TimelineEventType.MCP_JSONRPC_REQUEST);
		assertThat(serverRequest.payload().get("direction").asString()).isEqualTo("in");
		final TimelineEvent outboundResponse = this.timeline.events.get(1);
		assertThat(outboundResponse.correlationId()).isEqualTo(serverRequest.correlationId());
		assertThat(outboundResponse.payload().get("orphan")).isNull();
		assertThat(outboundResponse.payload().get("latencyMs")).isNotNull();
		assertThat(outboundResponse.payload().get("direction").asString()).isEqualTo("out");
		assertThat(this.recorder.pendingCorrelations()).isZero();
	}

	@Test
	@DisplayName("outbound JSONRPCResponse without a server request is marked orphan")
	void outboundResponseOrphanWithoutServerRequest() {
		this.recorder.recordOutboundResponse(ENDPOINT, new JSONRPCResponse("2.0", 5, Map.of("ok", true), null));
		assertThat(this.timeline.events).hasSize(1);
		assertThat(this.timeline.events.get(0).payload().get("orphan").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("identical integer ids on both sides pair within their own key space")
	void identicalIdsDoNotCrossPair() {
		this.recorder.recordClientRequest(ENDPOINT, new JSONRPCRequest("2.0", "tools/call", 1, null));
		this.recorder.recordServerRequest(ENDPOINT, new JSONRPCRequest("2.0", "sampling/createMessage", 1, null));
		final String clientRequestCorrelation = this.timeline.events.get(0).correlationId();
		final String serverRequestCorrelation = this.timeline.events.get(1).correlationId();
		assertThat(clientRequestCorrelation).isNotEqualTo(serverRequestCorrelation);
		// The outbound answer id=1 pairs with the server request, never with the client
		// request.
		this.recorder.recordOutboundResponse(ENDPOINT, new JSONRPCResponse("2.0", 1, Map.of("ok", true), null));
		assertThat(this.timeline.events.get(2).correlationId()).isEqualTo(serverRequestCorrelation);
		// The inbound answer id=1 pairs with the client request.
		this.recorder.recordClientResponse(ENDPOINT, new JSONRPCResponse("2.0", 1, Map.of("ok", true), null));
		assertThat(this.timeline.events.get(3).correlationId()).isEqualTo(clientRequestCorrelation);
		assertThat(this.recorder.pendingCorrelations()).isZero();
	}

	@Test
	@DisplayName("notifications are recorded for both directions without pending state")
	void notificationsBothDirections() {
		this.recorder.recordClientNotification(ENDPOINT,
				new JSONRPCNotification("2.0", "notifications/initialized", null));
		this.recorder.recordServerNotification(ENDPOINT, new JSONRPCNotification("2.0", "notifications/message", null));
		assertThat(this.timeline.events).hasSize(2);
		assertThat(this.timeline.events.get(0).type()).isEqualTo(TimelineEventType.MCP_JSONRPC_NOTIFICATION);
		assertThat(this.timeline.events.get(0).payload().get("direction").asString()).isEqualTo("out");
		assertThat(this.timeline.events.get(1).payload().get("direction").asString()).isEqualTo("in");
		assertThat(this.recorder.pendingCorrelations()).isZero();
	}

	@Test
	@DisplayName("an error response carries the error message in the payload")
	void errorResponseCarriesMessage() {
		this.recorder.recordClientRequest(ENDPOINT, new JSONRPCRequest("2.0", "tools/call", 3, null));
		this.recorder.recordClientResponse(ENDPOINT,
				new JSONRPCResponse("2.0", 3, null, new JSONRPCError(-32603, "internal error")));
		final TimelineEvent response = this.timeline.events.get(1);
		assertThat(response.payload().get("error").asString()).isEqualTo("internal error");
		assertThat(response.correlationId()).isEqualTo(this.timeline.events.get(0).correlationId());
	}

	@Test
	@DisplayName("null messages are ignored")
	void nullMessagesIgnored() {
		this.recorder.recordClientRequest(ENDPOINT, null);
		this.recorder.recordClientNotification(ENDPOINT, null);
		this.recorder.recordClientResponse(ENDPOINT, null);
		this.recorder.recordOutboundResponse(ENDPOINT, null);
		this.recorder.recordServerRequest(ENDPOINT, null);
		this.recorder.recordServerNotification(ENDPOINT, null);
		assertThat(this.timeline.events).isEmpty();
	}

	@Test
	@DisplayName("null target is omitted from the payload")
	void nullTargetOmitted() {
		final McpClientTrafficRecorder.ClientEndpoint noTarget = new McpClientTrafficRecorder.ClientEndpoint("c",
				"stdio", null);
		this.recorder.recordClientRequest(noTarget, new JSONRPCRequest("2.0", "tools/list", 1, null));
		assertThat(this.timeline.events.get(0).payload().get("target")).isNull();
	}

	@Test
	@DisplayName("a raw target with credentials is sanitized in the serialized payload")
	void rawTargetSanitizedInPayload() {
		final McpClientTrafficRecorder.ClientEndpoint raw = new McpClientTrafficRecorder.ClientEndpoint("weather",
				"streamable-http", "https://user:pass@host/path?token=x");
		this.recorder.recordClientRequest(raw, new JSONRPCRequest("2.0", "tools/list", 1, null));
		final TimelineEvent event = this.timeline.events.get(0);
		assertThat(event.payload().get("target").asString()).isEqualTo("https://***@host/path");
		final String serialized = event.payload().toString();
		assertThat(serialized).doesNotContain("user:pass").doesNotContain("token=x");
	}

	@Test
	@DisplayName("pending correlations are evicted beyond the shared budget")
	void pendingEvictedBeyondBudget() {
		for (int i = 0; i <= McpClientTrafficRecorder.MAX_PENDING_CORRELATIONS; i++) {
			this.recorder.recordClientRequest(ENDPOINT, new JSONRPCRequest("2.0", "tools/call", i, null));
		}
		assertThat(this.recorder.pendingCorrelations()).isEqualTo(McpClientTrafficRecorder.MAX_PENDING_CORRELATIONS);
	}

	/**
	 * Handler correlation: {@code consumedBy} enrichment of server-initiated exchanges
	 * through {@link ClientHandlerScanner}.
	 */
	@Nested
	@DisplayName("consumedBy enrichment")
	class HandlerEnrichment {

		private ClientHandlerScanner scanner() {
			final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
					SamplingHandlerConfig.class);
			final ClientHandlerScanner scanner = new ClientHandlerScanner();
			scanner.setApplicationContext(context);
			return scanner;
		}

		@Test
		@DisplayName("sampling pair carries consumedBy on request and outbound response")
		void samplingPairCarriesConsumedBy() {
			final McpClientTrafficRecorder enriched = new McpClientTrafficRecorder(
					McpClientTrafficRecorderTests.this.timeline, scanner());
			enriched.recordServerRequest(ENDPOINT, new JSONRPCRequest("2.0", "sampling/createMessage", 42, null));
			final TimelineEvent request = McpClientTrafficRecorderTests.this.timeline.events.get(0);
			assertThat(request.payload().get("consumedBy").size()).isEqualTo(1);
			assertThat(request.payload().get("consumedBy").get(0).asString()).isEqualTo("sampleHandler#sample");
			enriched.recordOutboundResponse(ENDPOINT, new JSONRPCResponse("2.0", 42, Map.of("ok", true), null));
			final TimelineEvent response = McpClientTrafficRecorderTests.this.timeline.events.get(1);
			assertThat(response.correlationId()).isEqualTo(request.correlationId());
			assertThat(response.payload().get("consumedBy").size()).isEqualTo(1);
			assertThat(response.payload().get("consumedBy").get(0).asString()).isEqualTo("sampleHandler#sample");
		}

		@Test
		@DisplayName("elicitation pair carries consumedBy on request and outbound response")
		void elicitationPairCarriesConsumedBy() {
			final McpClientTrafficRecorder enriched = new McpClientTrafficRecorder(
					McpClientTrafficRecorderTests.this.timeline, scanner());
			enriched.recordServerRequest(ENDPOINT, new JSONRPCRequest("2.0", "elicitation/create", 7, null));
			enriched.recordOutboundResponse(ENDPOINT, new JSONRPCResponse("2.0", 7, Map.of("ok", true), null));
			assertThat(McpClientTrafficRecorderTests.this.timeline.events.get(0).payload().get("consumedBy").size())
				.isEqualTo(1);
			assertThat(McpClientTrafficRecorderTests.this.timeline.events.get(0)
				.payload()
				.get("consumedBy")
				.get(0)
				.asString()).isEqualTo("elicitHandler#elicit");
			assertThat(McpClientTrafficRecorderTests.this.timeline.events.get(1).payload().get("consumedBy").size())
				.isEqualTo(1);
		}

		@Test
		@DisplayName("logging notification carries consumedBy on the single event")
		void loggingNotificationCarriesConsumedBy() {
			final McpClientTrafficRecorder enriched = new McpClientTrafficRecorder(
					McpClientTrafficRecorderTests.this.timeline, scanner());
			enriched.recordServerNotification(ENDPOINT, new JSONRPCNotification("2.0", "notifications/message", null));
			assertThat(McpClientTrafficRecorderTests.this.timeline.events).hasSize(1);
			assertThat(McpClientTrafficRecorderTests.this.timeline.events.get(0)
				.payload()
				.get("consumedBy")
				.get(0)
				.asString()).isEqualTo("logHandler#log");
			assertThat(McpClientTrafficRecorderTests.this.recorder.pendingCorrelations()).isZero();
		}

		@Test
		@DisplayName("progress notification carries consumedBy on the single event")
		void progressNotificationCarriesConsumedBy() {
			final McpClientTrafficRecorder enriched = new McpClientTrafficRecorder(
					McpClientTrafficRecorderTests.this.timeline, scanner());
			enriched.recordServerNotification(ENDPOINT, new JSONRPCNotification("2.0", "notifications/progress", null));
			assertThat(McpClientTrafficRecorderTests.this.timeline.events).hasSize(1);
			assertThat(McpClientTrafficRecorderTests.this.timeline.events.get(0)
				.payload()
				.get("consumedBy")
				.get(0)
				.asString()).isEqualTo("progressHandler#progress");
		}

		@Test
		@DisplayName("unrelated server-initiated methods carry no consumedBy")
		void unrelatedMethodsCarryNothing() {
			final McpClientTrafficRecorder enriched = new McpClientTrafficRecorder(
					McpClientTrafficRecorderTests.this.timeline, scanner());
			enriched.recordServerRequest(ENDPOINT, new JSONRPCRequest("2.0", "roots/list", 1, null));
			assertThat(McpClientTrafficRecorderTests.this.timeline.events.get(0).payload().get("consumedBy")).isNull();
		}

		@Test
		@DisplayName("a client without a matching binding carries no consumedBy")
		void noBindingNoConsumedBy() {
			final McpClientTrafficRecorder enriched = new McpClientTrafficRecorder(
					McpClientTrafficRecorderTests.this.timeline, scanner());
			final McpClientTrafficRecorder.ClientEndpoint otherClient = new McpClientTrafficRecorder.ClientEndpoint(
					"other", "streamable-http", "https://other.example/mcp");
			enriched.recordServerRequest(otherClient, new JSONRPCRequest("2.0", "sampling/createMessage", 1, null));
			assertThat(McpClientTrafficRecorderTests.this.timeline.events.get(0).payload().get("consumedBy")).isNull();
		}

		@Test
		@DisplayName("a recorder without a scanner never emits consumedBy")
		void noScannerNoConsumedBy() {
			final McpClientTrafficRecorder plain = McpClientTrafficRecorderTests.this.recorder;
			plain.recordServerRequest(ENDPOINT, new JSONRPCRequest("2.0", "sampling/createMessage", 1, null));
			assertThat(McpClientTrafficRecorderTests.this.timeline.events.get(0).payload().get("consumedBy")).isNull();
		}

		@Test
		@DisplayName("an unknown notification method carries no consumedBy")
		void unknownNotificationMethodCarriesNothing() {
			final McpClientTrafficRecorder enriched = new McpClientTrafficRecorder(
					McpClientTrafficRecorderTests.this.timeline, scanner());
			enriched.recordServerNotification(ENDPOINT, new JSONRPCNotification("2.0", "custom/notification", null));
			assertThat(McpClientTrafficRecorderTests.this.timeline.events.get(0).payload().get("consumedBy")).isNull();
		}

	}

	@Configuration
	static class SamplingHandlerConfig {

		@Bean
		SampleHandler sampleHandler() {
			return new SampleHandler();
		}

		@Bean
		ElicitHandler elicitHandler() {
			return new ElicitHandler();
		}

		@Bean
		LogHandler logHandler() {
			return new LogHandler();
		}

		@Bean
		ProgressHandler progressHandler() {
			return new ProgressHandler();
		}

	}

	static class SampleHandler {

		@McpSampling(clients = "weather")
		String sample(final String request) {
			return request;
		}

	}

	static class ElicitHandler {

		@McpElicitation(clients = "weather")
		String elicit(final String request) {
			return request;
		}

	}

	static class LogHandler {

		@McpLogging(clients = "weather")
		void log(final String notification) {
		}

	}

	static class ProgressHandler {

		@McpProgress(clients = "weather")
		void progress(final String notification) {
		}

	}

	/**
	 * Test double capturing appended events in order.
	 */
	private static final class RecordingTimelineService implements TimelineService {

		private final List<TimelineEvent> events = new ArrayList<>();

		@Override
		public void append(final TimelineEvent event) {
			this.events.add(event);
		}

		@Override
		public List<TimelineEvent> query(final TimelineQuery query) {
			return List.of();
		}

		@Override
		public void clear() {
			this.events.clear();
		}

	}

}
