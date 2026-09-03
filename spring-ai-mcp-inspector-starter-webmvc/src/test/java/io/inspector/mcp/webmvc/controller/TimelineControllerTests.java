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

package io.inspector.mcp.webmvc.controller;

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
 * Unit tests for {@link TimelineController}. Covers all query filter combinations and
 * parameter parsing via the public API.
 */
@Epic("WebMvc Inspector")
@Feature("TimelineController")
class TimelineControllerTests {

	private TimelineService timelineService;

	private TimelineController controller;

	@BeforeEach
	void setUp() {
		this.timelineService = mock(TimelineService.class);
		this.controller = new TimelineController(this.timelineService);
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
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of());
			final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), null, null,
					TimelineEventType.MCP_JSONRPC_REQUEST, Instant.now(), null);
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of(event));

			// when
			final List<TimelineEvent> result = TimelineControllerTests.this.controller.query(null, null, null, null,
					null, null, null, 100);

			// then
			assertThat(result).hasSize(1).contains(event);
		}

		@Test
		@Story("All filters")
		@Severity(SeverityLevel.NORMAL)
		@Description("query() with all filters passes them through to TimelineQuery builder")
		void query_withAllFilters_passesFilters() {
			// given
			final Instant now = Instant.now();
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineControllerTests.this.controller.query(UUID.randomUUID().toString(), "sess1", now, now,
					"MCP_JSONRPC_REQUEST,APP_LOG", null, null, 10);

			// then
			verify(TimelineControllerTests.this.timelineService).query(argThat((q) -> q.limit() == 10));
		}

	}

	@Nested
	@DisplayName("parseTypes via query()")
	class ParseTypes {

		@Test
		@Story("Null types")
		@Severity(SeverityLevel.MINOR)
		@Description("query() with null types passes empty event type list to the service")
		void query_nullTypes_sendsEmptyTypes() {
			// given
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineControllerTests.this.controller.query(null, null, null, null, null, null, null, 500);

			// then
			verify(TimelineControllerTests.this.timelineService)
				.query(argThat((q) -> q.eventTypes() == null || q.eventTypes().isEmpty()));
		}

		@Test
		@Story("Valid types")
		@Severity(SeverityLevel.NORMAL)
		@Description("query() with valid comma-separated type names parses them into TimelineEventTypes")
		void query_validTypes_parsesTypes() {
			// given
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineControllerTests.this.controller.query(null, null, null, null, "MCP_JSONRPC_REQUEST,APP_LOG", null,
					null, 500);

			// then: the query contains both parsed types
			verify(TimelineControllerTests.this.timelineService).query(argThat((q) -> {
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
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineControllerTests.this.controller.query(null, null, null, null, "UNKNOWN,APP_LOG", null, null, 500);

			// then: only APP_LOG survives
			verify(TimelineControllerTests.this.timelineService).query(argThat((q) -> {
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
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineControllerTests.this.controller.query(null, null, null, null,
					"  MCP_JSONRPC_RESPONSE , , BAD , MCP_JSONRPC_NOTIFICATION  ", null, null, 500);

			// then
			verify(TimelineControllerTests.this.timelineService).query(argThat((q) -> {
				final List<TimelineEventType> types = q.eventTypes();
				return types != null && types.size() == 2 && types.contains(TimelineEventType.MCP_JSONRPC_RESPONSE)
						&& types.contains(TimelineEventType.MCP_JSONRPC_NOTIFICATION);
			}));
		}

	}

	@Nested
	@DisplayName("TimelineQuery limit clamping via query()")
	class QueryLimit {

		@Test
		@Severity(SeverityLevel.MINOR)
		@Description("query() with limit=0 clamps to DEFAULT_LIMIT (500)")
		void query_zeroLimit_clampsToDefault() {
			// given
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineControllerTests.this.controller.query(null, null, null, null, null, null, null, 0);

			// then
			verify(TimelineControllerTests.this.timelineService)
				.query(argThat((q) -> q.limit() == TimelineQuery.DEFAULT_LIMIT));
		}

		@Test
		@Severity(SeverityLevel.MINOR)
		@Description("query() with limit > MAX_LIMIT clamps to MAX_LIMIT (5000)")
		void query_excessiveLimit_clampsToMax() {
			// given
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of());

			// when
			TimelineControllerTests.this.controller.query(null, null, null, null, null, null, null, 999999);

			// then
			verify(TimelineControllerTests.this.timelineService)
				.query(argThat((q) -> q.limit() == TimelineQuery.MAX_LIMIT));
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
			given(TimelineControllerTests.this.timelineService.query(any()))
				.willReturn(List.of(diagEvent, clientEvent, nullPayloadEvent));

			// when
			final List<TimelineEvent> result = TimelineControllerTests.this.controller.diagnostics();

			// then
			assertThat(result).hasSize(1);
			assertThat(result.get(0).id()).isEqualTo("d1");
		}

		@Test
		@Story("Empty result when no diagnostics events")
		@Description("diagnostics() returns an empty list when no events have endpoint=client-diagnostics")
		void diagnostics_noDiagnostics_returnsEmpty() {
			// given
			final JsonNode clientPayload = JsonNodeFactory.instance.objectNode().put("endpoint", "client");
			final TimelineEvent clientEvent = new TimelineEvent("c1", null, null, TimelineEventType.MCP_JSONRPC_REQUEST,
					Instant.now(), clientPayload);
			given(TimelineControllerTests.this.timelineService.query(any())).willReturn(List.of(clientEvent));

			// when
			final List<TimelineEvent> result = TimelineControllerTests.this.controller.diagnostics();

			// then
			assertThat(result).isEmpty();
		}

	}

}
