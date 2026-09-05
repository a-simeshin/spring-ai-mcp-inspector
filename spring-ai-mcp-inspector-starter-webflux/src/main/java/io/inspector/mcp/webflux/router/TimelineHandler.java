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
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import io.inspector.mcp.core.timeline.TimelineEvent;
import io.inspector.mcp.core.timeline.TimelineEventType;
import io.inspector.mcp.core.timeline.TimelineQuery;
import io.inspector.mcp.core.timeline.TimelineService;

/**
 * Reactive HTTP handler for {@code GET ${path}/api/timeline}. Mirrors the WebMVC
 * {@code TimelineController} contract exactly: the same query parameters, the same
 * silent-skip semantics for unknown type names and unparsable instants, the same
 * newest-first ordering, and the same 500/5000 limit defaults enforced by
 * {@link TimelineQuery}.
 *
 * <p>
 * Sits behind {@code InspectorAuthWebFilter} because the route lives under
 * {@code ${path}/api/}, which is the filter's guarded prefix - the same protection the
 * servlet stack gives the controller via {@code InspectorAuthFilter}.
 *
 * @author Artem Simeshin
 */
public class TimelineHandler {

	private final TimelineService timelineService;

	public TimelineHandler(final TimelineService timelineService) {
		this.timelineService = timelineService;
	}

	/**
	 * Returns timeline events matching the optional filter criteria, newest first.
	 * @param request the incoming request; query params {@code correlationId},
	 * {@code sessionId}, {@code since}, {@code until}, {@code types}, {@code clientName},
	 * {@code direction}, {@code limit}
	 * @return the matching events as JSON
	 */
	public Mono<ServerResponse> query(final ServerRequest request) {
		final TimelineQuery query = TimelineQuery.builder()
			.correlationId(param(request, "correlationId"))
			.sessionId(param(request, "sessionId"))
			.since(instantParam(request, "since"))
			.until(instantParam(request, "until"))
			.eventTypes(parseTypes(request.queryParam("types").orElse(null)))
			.clientName(param(request, "clientName"))
			.direction(param(request, "direction"))
			.limit(intParam(request, "limit"))
			.build();
		final List<TimelineEvent> events = this.timelineService.query(query);
		return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(events);
	}

	/**
	 * Returns diagnostic events (client handler desync findings) from the timeline.
	 * @param request the incoming request
	 * @return the diagnostic events as JSON
	 */
	public Mono<ServerResponse> diagnostics(final ServerRequest request) {
		final List<TimelineEvent> events = this.timelineService.query(TimelineQuery.all())
			.stream()
			.filter((e) -> e.payload() != null && e.payload().has("endpoint")
					&& "client-diagnostics".equals(e.payload().get("endpoint").asText()))
			.toList();
		return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(events);
	}

	/**
	 * Reads a non-blank query parameter.
	 * @param request the incoming request
	 * @param name the parameter name
	 * @return the value, or {@code null} when absent or blank
	 */
	private static String param(final ServerRequest request, final String name) {
		return request.queryParam(name).filter((v) -> !v.isBlank()).orElse(null);
	}

	/**
	 * Parses an ISO-8601 instant parameter; unparsable values are silently skipped,
	 * matching the servlet stack's lenient binding behaviour in the inspector surface.
	 * @param request the incoming request
	 * @param name the parameter name
	 * @return the parsed instant, or {@code null} when absent or unparsable
	 */
	private static Instant instantParam(final ServerRequest request, final String name) {
		final String raw = param(request, name);
		if (raw == null) {
			return null;
		}
		try {
			return Instant.parse(raw);
		}
		catch (final RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Parses an integer query parameter.
	 * @param request the incoming request
	 * @param name the parameter name
	 * @return the parsed limit, or {@link TimelineQuery#DEFAULT_LIMIT} when absent or
	 * unparsable
	 */
	private static int intParam(final ServerRequest request, final String name) {
		final String raw = param(request, name);
		if (raw == null) {
			return TimelineQuery.DEFAULT_LIMIT;
		}
		try {
			return Integer.parseInt(raw);
		}
		catch (final NumberFormatException ex) {
			return TimelineQuery.DEFAULT_LIMIT;
		}
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
		final List<TimelineEventType> parsed = new ArrayList<>();
		for (final String token : types.split(",")) {
			final String name = token.trim();
			if (name.isEmpty()) {
				continue;
			}
			try {
				parsed.add(TimelineEventType.valueOf(name));
			}
			catch (final IllegalArgumentException ex) {
				// unknown type name - skip, as the WebMVC controller does
			}
		}
		return parsed;
	}

}
