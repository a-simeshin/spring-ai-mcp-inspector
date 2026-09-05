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

package io.inspector.mcp.webflux.router;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.EntityResponse;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.inspector.mcp.core.timeline.TimelineEvent;
import io.inspector.mcp.core.timeline.TimelineEventType;
import io.inspector.mcp.core.timeline.TimelineQuery;
import io.inspector.mcp.core.timeline.TimelineService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TimelineHandler}. Covers query parameter parsing, type parsing,
 * the diagnostics filter, and edge cases of the private helper methods, matching the
 * WebMVC {@code TimelineControllerTests} contract.
 */
@Epic("MCP Inspector WebFlux")
@Feature("TimelineHandler")
class TimelineHandlerTests {

	private static final HandlerStrategies STRATEGIES = HandlerStrategies.withDefaults();

	private TimelineService timelineService;

	private TimelineHandler handler;

	@BeforeEach
	void setUp() {
		this.timelineService = mock(TimelineService.class);
		this.handler = new TimelineHandler(this.timelineService);
	}

	private static ServerRequest request(final MockServerHttpRequest mock) {
		final MockServerWebExchange exchange = MockServerWebExchange.from(mock);
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	@SuppressWarnings("unchecked")
	private static <T> List<T> entityBody(final ServerResponse response) {
		return (List<T>) ((EntityResponse<Object>) response).entity();
	}

	@Nested
	@DisplayName("query()")
	class Query {

		@Test
		@Story("No filters")
		@Severity(SeverityLevel.NORMAL)
		@Description("query() with no optional filters delegates to timelineService.query(...)")
		void query_noFilters_callsService() {
			// given
			final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), null, null,
					TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), null);
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of(event));

			// when
			final Mono<ServerResponse> result = TimelineHandlerTests.this.handler
				.query(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline").build()));

			// then
			assertThat(result.block().statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(result.block().headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		}

		@Test
		@Story("All filters")
		@Severity(SeverityLevel.NORMAL)
		@Description("query() with all filters passes them through to TimelineQuery builder")
		void query_withAllFilters_passesFilters() {
			// given
			final Instant now = Instant.now();
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineHandlerTests.this.handler
				.query(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline")
					.queryParam("correlationId", "corr1")
					.queryParam("sessionId", "sess1")
					.queryParam("since", now.toString())
					.queryParam("until", now.toString())
					.queryParam("types", "MCP_JSONRPC_REQUEST,APP_LOG")
					.queryParam("clientName", "test-client")
					.queryParam("direction", "client->server")
					.queryParam("limit", "10")
					.build()))
				.block();

			// then
			verify(TimelineHandlerTests.this.timelineService).query(argThat((q) -> q.limit() == 10));
		}

		@Test
		@Story("Blank params")
		@Severity(SeverityLevel.MINOR)
		@Description("query() with blank query params treats them as absent")
		void query_blankParams_treatedAsAbsent() {
			// given
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineHandlerTests.this.handler
				.query(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline")
					.queryParam("correlationId", "")
					.queryParam("sessionId", "  ")
					.queryParam("limit", "0")
					.build()))
				.block();

			// then : limit 0 is clamped to DEFAULT_LIMIT by TimelineQuery
			verify(TimelineHandlerTests.this.timelineService)
				.query(argThat((q) -> q.limit() == TimelineQuery.DEFAULT_LIMIT));
		}

		@Test
		@Story("Unparsable instant")
		@Severity(SeverityLevel.MINOR)
		@Description("query() with an unparsable since/until silently returns null")
		void query_unparsableInstant_returnsNull() {
			// given
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineHandlerTests.this.handler
				.query(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline")
					.queryParam("since", "not-an-instant")
					.queryParam("until", "also-not-an-instant")
					.build()))
				.block();

			// then : null since/until are valid, just no filtering
			verify(TimelineHandlerTests.this.timelineService).query(any());
		}

		@Test
		@Story("Unparsable limit")
		@Severity(SeverityLevel.MINOR)
		@Description("query() with an unparsable limit falls back to DEFAULT_LIMIT")
		void query_unparsableLimit_fallsBackToDefault() {
			// given
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineHandlerTests.this.handler
				.query(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline")
					.queryParam("limit", "not-a-number")
					.build()))
				.block();

			// then
			verify(TimelineHandlerTests.this.timelineService)
				.query(argThat((q) -> q.limit() == TimelineQuery.DEFAULT_LIMIT));
		}

	}

	@Nested
	@DisplayName("parseTypes via query()")
	class ParseTypes {

		@Test
		@Story("Null types")
		@Severity(SeverityLevel.MINOR)
		@Description("query() with no types param passes empty event type list to the service")
		void query_noTypes_sendsEmptyTypes() {
			// given
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineHandlerTests.this.handler
				.query(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline").build()))
				.block();

			// then
			verify(TimelineHandlerTests.this.timelineService)
				.query(argThat((q) -> q.eventTypes() == null || q.eventTypes().isEmpty()));
		}

		@Test
		@Story("Valid types")
		@Severity(SeverityLevel.NORMAL)
		@Description("query() with valid comma-separated type names parses them into TimelineEventTypes")
		void query_validTypes_parsesTypes() {
			// given
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineHandlerTests.this.handler
				.query(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline")
					.queryParam("types", "MCP_JSONRPC_REQUEST,APP_LOG")
					.build()))
				.block();

			// then: the query contains both parsed types
			verify(TimelineHandlerTests.this.timelineService).query(argThat((q) -> {
				final List<TimelineEventType> types = q.eventTypes();
				return types != null && types.size() == 2 && types.contains(TimelineEventType.MCP_JSONRPC_REQUEST)
						&& types.contains(TimelineEventType.APP_LOG);
			}));
		}

		@Test
		@Story("Invalid types silently skipped")
		@Severity(SeverityLevel.NORMAL)
		@Description("query() with an unknown type name silently excludes it")
		void query_invalidType_skipsUnknown() {
			// given
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineHandlerTests.this.handler
				.query(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline")
					.queryParam("types", "UNKNOWN,APP_LOG")
					.build()))
				.block();

			// then: only APP_LOG survives
			verify(TimelineHandlerTests.this.timelineService).query(argThat((q) -> {
				final List<TimelineEventType> types = q.eventTypes();
				return types != null && types.size() == 1 && types.contains(TimelineEventType.APP_LOG);
			}));
		}

		@Test
		@Story("Whitespace trimming")
		@Severity(SeverityLevel.NORMAL)
		@Description("query() trims whitespace around type names and skips empty segments")
		void query_withWhitespace_trimsAndSkipsEmpty() {
			// given
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineHandlerTests.this.handler
				.query(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline")
					.queryParam("types", "  MCP_JSONRPC_RESPONSE , , BAD , MCP_JSONRPC_NOTIFICATION  ")
					.build()))
				.block();

			// then
			verify(TimelineHandlerTests.this.timelineService).query(argThat((q) -> {
				final List<TimelineEventType> types = q.eventTypes();
				return types != null && types.size() == 2 && types.contains(TimelineEventType.MCP_JSONRPC_RESPONSE)
						&& types.contains(TimelineEventType.MCP_JSONRPC_NOTIFICATION);
			}));
		}

	}

	@Nested
	@DisplayName("diagnostics()")
	@Severity(SeverityLevel.CRITICAL)
	class Diagnostics {

		@Test
		@Story("Filters by endpoint=client-diagnostics")
		@Description("diagnostics() returns only events whose payload.endpoint equals client-diagnostics")
		void diagnostics_filtersByEndpoint() {
			// given
			final JsonNode diagPayload = JsonNodeFactory.instance.objectNode().put("endpoint", "client-diagnostics");
			final JsonNode clientPayload = JsonNodeFactory.instance.objectNode().put("endpoint", "client");
			final JsonNode nullPayload = null;
			final TimelineEvent diagEvent = new TimelineEvent("d1", null, null, TimelineEventType.APP_LOG,
					Instant.now(), diagPayload);
			final TimelineEvent clientEvent = new TimelineEvent("c1", null, null, TimelineEventType.MCP_JSONRPC_REQUEST,
					Instant.now(), clientPayload);
			final TimelineEvent nullPayloadEvent = new TimelineEvent("n1", null, null, TimelineEventType.APP_LOG,
					Instant.now(), nullPayload);
			given(TimelineHandlerTests.this.timelineService.query(any()))
				.willReturn(List.of(diagEvent, clientEvent, nullPayloadEvent));

			// when
			final Mono<ServerResponse> result = TimelineHandlerTests.this.handler
				.diagnostics(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline/diagnostics").build()));

			// then
			final List<TimelineEvent> body = entityBody(result.block());
			assertThat(body).hasSize(1);
			assertThat(body.get(0).id()).isEqualTo("d1");
		}

		@Test
		@Story("Empty result when no diagnostics events")
		@Description("diagnostics() returns an empty list when no events have endpoint=client-diagnostics")
		void diagnostics_noDiagnostics_returnsEmpty() {
			// given
			final JsonNode clientPayload = JsonNodeFactory.instance.objectNode().put("endpoint", "client");
			final TimelineEvent clientEvent = new TimelineEvent("c1", null, null, TimelineEventType.MCP_JSONRPC_REQUEST,
					Instant.now(), clientPayload);
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of(clientEvent));

			// when
			final Mono<ServerResponse> result = TimelineHandlerTests.this.handler
				.diagnostics(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline/diagnostics").build()));

			// then
			final List<TimelineEvent> body = entityBody(result.block());
			assertThat(body).isEmpty();
		}

		@Test
		@Story("Empty events list")
		@Description("diagnostics() returns an empty list when no events exist at all")
		void diagnostics_noEvents_returnsEmpty() {
			// given
			given(TimelineHandlerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			final Mono<ServerResponse> result = TimelineHandlerTests.this.handler
				.diagnostics(request(MockServerHttpRequest.get("/mcp-inspector/api/timeline/diagnostics").build()));

			// then
			final List<TimelineEvent> body = entityBody(result.block());
			assertThat(body).isEmpty();
		}

	}

}
