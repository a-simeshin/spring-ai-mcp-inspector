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

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.inspector.mcp.core.timeline.TimelineEvent;
import io.inspector.mcp.core.timeline.TimelineEventType;
import io.inspector.mcp.core.timeline.TimelineQuery;
import io.inspector.mcp.core.timeline.TimelineService;

/**
 * REST controller exposing the {@link TimelineService} to the inspector SPA.
 *
 * <p>
 * All routes are mounted under {@code ${spring.ai.mcp.inspector.path}/api/timeline} and
 * protected by {@code InspectorAuthFilter}.
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}/api/timeline")
public class TimelineController {

	private final TimelineService timelineService;

	public TimelineController(final TimelineService timelineService) {
		this.timelineService = timelineService;
	}

	/**
	 * Returns timeline events matching the optional filter criteria, sorted newest first.
	 * @param correlationId optional correlation id filter
	 * @param sessionId optional session id filter
	 * @param since optional start of time range (ISO-8601 instant)
	 * @param until optional end of time range (ISO-8601 instant)
	 * @param types optional comma-separated list of event type names (e.g.
	 * {@code MCP_JSONRPC_REQUEST,APP_LOG})
	 * @param clientName optional client name filter (matches payload.clientName)
	 * @param direction optional traffic direction filter, e.g. {@code client->server} or
	 * {@code server->client} (matches payload.direction)
	 * @param limit maximum number of events to return (default 500, max 5000)
	 * @return matching timeline events
	 */
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public List<TimelineEvent> query(@RequestParam(name = "correlationId", required = false) final String correlationId,
			@RequestParam(name = "sessionId", required = false) final String sessionId,
			@RequestParam(name = "since", required = false) final Instant since,
			@RequestParam(name = "until", required = false) final Instant until,
			@RequestParam(name = "types", required = false) final String types,
			@RequestParam(name = "clientName", required = false) final String clientName,
			@RequestParam(name = "direction", required = false) final String direction,
			@RequestParam(name = "limit", defaultValue = "500") final int limit) {

		final List<TimelineEventType> eventTypes = parseTypes(types);
		final TimelineQuery query = TimelineQuery.builder()
			.correlationId(correlationId)
			.sessionId(sessionId)
			.since(since)
			.until(until)
			.eventTypes(eventTypes)
			.clientName(clientName)
			.direction(direction)
			.limit(limit)
			.build();
		return this.timelineService.query(query);
	}

	/**
	 * Returns diagnostic events (client handler desync findings) from the timeline.
	 * @return diagnostic events matching payload endpoint=client-diagnostics
	 */
	@GetMapping(path = "/diagnostics", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<TimelineEvent> diagnostics() {
		return this.timelineService.query(TimelineQuery.all())
			.stream()
			.filter((e) -> e.payload() != null && e.payload().has("endpoint")
					&& "client-diagnostics".equals(e.payload().get("endpoint").asText()))
			.toList();
	}

	/**
	 * Parses a comma-separated list of {@link TimelineEventType} names. Unknown or blank
	 * names are silently skipped.
	 * @param types the comma-separated string, may be {@code null}
	 * @return the parsed list, never {@code null} but may be empty
	 */
	private static List<TimelineEventType> parseTypes(final String types) {
		if (types == null || types.isBlank()) {
			return List.of();
		}
		return java.util.Arrays.stream(types.split(",")).map(String::trim).filter((s) -> !s.isEmpty()).map((s) -> {
			try {
				return TimelineEventType.valueOf(s);
			}
			catch (final IllegalArgumentException ex) {
				return null;
			}
		}).filter((t) -> t != null).toList();
	}

}
