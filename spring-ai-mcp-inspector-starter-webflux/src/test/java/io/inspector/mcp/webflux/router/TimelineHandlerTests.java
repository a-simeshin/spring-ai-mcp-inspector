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
import java.util.Optional;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.timeline.ExceptionLeakDetector;
import io.inspector.mcp.core.timeline.TimelineEvent;
import io.inspector.mcp.core.timeline.TimelineEventType;
import io.inspector.mcp.core.timeline.TimelineService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link TimelineHandler}: the query-time {@code _leak} enrichment
 * contract mirroring the WebMVC {@code TimelineController}.
 *
 * @author Artem Simeshin
 */
@Epic("WebFlux Inspector")
@Feature("TimelineHandler")
class TimelineHandlerTests {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final String E11000 = "E11000 duplicate key error collection: db.app index: tenant_name_idx dup key: { name: \"acme\" }";

	/**
	 * W1/W2: the canonical serialized {@code _leak} node for the fixed E11000 sample;
	 * must stay byte-identical to the WebMVC TimelineController test constant.
	 */
	private static final String CANONICAL_LEAK_JSON = "{\"matchType\":\"EXACT\",\"verbatim\":true,"
			+ "\"logEventId\":\"log-1\",\"logTimestamp\":\"2026-01-01T09:59:59Z\","
			+ "\"logMessage\":\"insert failed\","
			+ "\"logThrowableClass\":\"org.springframework.dao.DuplicateKeyException\","
			+ "\"multipleCandidates\":false,\"droppedCandidates\":0}";

	private TimelineService timelineService;

	private ServerRequest request;

	@BeforeEach
	void setUp() {
		this.timelineService = mock(TimelineService.class);
		this.request = mock(ServerRequest.class);
		given(this.request.queryParam(anyString())).willReturn(Optional.empty());
	}

	@Test
	@Story("Detector present")
	@Severity(SeverityLevel.NORMAL)
	@Description("query() serves error tool results with the _leak marker when the detector is present")
	void query_withDetector_annotatesLeak() {
		// given: an error tool result and its matching APP_LOG inside the window
		// (fixed instant: the canonical _leak JSON must match the WebMVC W1 bytes)
		final Instant now = Instant.parse("2026-01-01T10:00:00Z");
		final TimelineEvent response = new TimelineEvent("resp-1", null, null, TimelineEventType.MCP_JSONRPC_RESPONSE,
				now, responsePayload());
		final TimelineEvent log = new TimelineEvent("log-1", null, null, TimelineEventType.APP_LOG, now.minusSeconds(1),
				logPayload());
		given(this.timelineService.query(any())).willReturn(List.of(response, log));
		final TimelineHandler handler = new TimelineHandler(this.timelineService, new ExceptionLeakDetector());

		// when
		final String body = bodyOf(handler);

		// then: the served body carries the _leak annotation, the stored payload does not
		assertThat(body).contains("\"_leak\"");
		assertThat(body).contains("\"matchType\":\"EXACT\"");
		assertThat(body).contains("\"verbatim\":true");
		assertThat(body).contains("\"logEventId\":\"log-1\"");
		// W2: byte-identical _leak JSON to the WebMVC TimelineController (W1)
		final JsonNode served = MAPPER.readTree(body);
		assertThat(MAPPER.writeValueAsString(served.get(0).path("payload").path("_leak")))
			.isEqualTo(CANONICAL_LEAK_JSON);
		assertThat(response.payload().has("_leak")).isFalse();
	}

	@Test
	@Story("Detector absent")
	@Severity(SeverityLevel.MINOR)
	@Description("query() serves events as-is when the detector is absent (feature disabled)")
	void query_withoutDetector_servesEventsAsIs() {
		// given
		final TimelineEvent response = new TimelineEvent("resp-2", null, null, TimelineEventType.MCP_JSONRPC_RESPONSE,
				Instant.now(), responsePayload());
		given(this.timelineService.query(any())).willReturn(List.of(response));
		final TimelineHandler handler = new TimelineHandler(this.timelineService, null);

		// when
		final String body = bodyOf(handler);

		// then
		assertThat(body).doesNotContain("_leak");
		assertThat(response.payload().has("_leak")).isFalse();
	}

	private String bodyOf(final TimelineHandler handler) {
		final ServerResponse serverResponse = handler.query(this.request).block();
		final MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/mcp-inspector/api/timeline").build());
		final ServerResponse.Context context = mock(ServerResponse.Context.class);
		given(context.messageWriters()).willReturn(HandlerStrategies.withDefaults().messageWriters());
		serverResponse.writeTo(exchange, context).block();
		return exchange.getResponse().getBodyAsString().block();
	}

	private static ObjectNode responsePayload() {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		final ObjectNode result = payload.putObject("result");
		result.put("isError", true);
		result.putArray("content").addObject().put("type", "text").put("text", E11000 + "\n" + E11000);
		return payload;
	}

	private static ObjectNode logPayload() {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("logLevel", "ERROR");
		payload.put("message", "insert failed");
		payload.put("throwable",
				"org.springframework.dao.DuplicateKeyException: " + E11000 + "\n\tat org.example.Foo.bar(Foo.java:10)");
		return payload;
	}

}
