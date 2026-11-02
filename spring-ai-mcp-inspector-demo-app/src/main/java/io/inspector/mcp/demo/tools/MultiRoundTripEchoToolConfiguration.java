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
package io.inspector.mcp.demo.tools;

import io.inspector.mcp.demo.resources.DemoResourceSubscribeCapabilityCustomizer;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic server-side fixture for the SEP-2322 multi round-trip flow, exposed as
 * the {@code multiRoundTripEcho} demo tool. It takes three {@code tools/call} requests to
 * drive it end to end:
 *
 * <ol>
 * <li>a fresh call returns {@code resultType="input_required"} with three typed
 * {@code inputRequests} (string, enum, integer) plus an opaque {@code requestState}
 * token;</li>
 * <li>the re-send with {@code inputResponses} answering all three and the echoed
 * {@code requestState} validates the answers and returns ONE MORE {@code input_required}
 * round (a single boolean request) with a new token;</li>
 * <li>the last re-send answering the boolean returns a final text result echoing every
 * validated answer.</li>
 * </ol>
 *
 * <p>
 * Everything is stateless and free of entropy: identical answers always produce
 * byte-identical {@code requestState} tokens and identical final text, which is what
 * makes the tool usable as a stable E2E fixture. Validation failures (unknown or
 * malformed {@code requestState}, missing {@code inputResponses} entries, an action other
 * than {@code "accept"}, a wrong enum value, a non-integer or negative refill count) all
 * fail with JSON-RPC error {@code -32602}.
 *
 * <p>
 * The wire fields themselves are taught to the server by {@link Sep2322WireModule},
 * registered here through a mapper bean that mirrors
 * {@code McpServerJsonMapperAutoConfiguration} one-to-one and adds that single module.
 */
@Configuration
public class MultiRoundTripEchoToolConfiguration {

	private static final String TOOL_NAME = "multiRoundTripEcho";

	private static final String ROUND_ONE_STATE_PREFIX = "mrt-echo.r1.";

	private static final String ROUND_TWO_STATE_PREFIX = "mrt-echo.r2.";

	private static final List<String> COFFEE_SIZES = List.of("small", "medium", "large");

	/**
	 * Deterministic canonical JSON for the state tokens (fixed key order, no entropy).
	 */
	private static final JsonMapper STATE_CODEC = JsonMapper.builder().build();

	private static final Base64.Encoder STATE_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder STATE_DECODER = Base64.getUrlDecoder();

	private static final McpSchema.Tool TOOL = McpSchema.Tool.builder()
		.name(TOOL_NAME)
		.title("Multi round-trip echo (SEP-2322 fixture)")
		.description(
				"""
						Deterministic SEP-2322 multi round-trip fixture; driving it takes three tools/call requests.

						Round 1: call with the arguments only. The result carries resultType="input_required",
						three typed inputRequests (favoriteCity: string, coffeeSize: enum small|medium|large,
						refillCount: integer) and an opaque requestState token starting with "mrt-echo.r1.".

						Round 2: re-send the call (with a NEW JSON-RPC id) with params.inputResponses answering
						every request (action="accept") and the echoed params.requestState. The result is one
						more input_required round (a single boolean request "confirmation") with a new token
						starting with "mrt-echo.r2.".

						Round 3: re-send again answering the boolean. The final result is a text echo of every
						validated answer.

						Example round 1 response result:
						{"resultType":"input_required","inputRequests":{"favoriteCity":{"method":"elicitation/create","params":{"mode":"form","message":"Which city should the echo use?","requestedSchema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}},"coffeeSize":{...},"refillCount":{...}},"requestState":"mrt-echo.r1.<token>"}

						Example round 2 request params:
						{"name":"multiRoundTripEcho","arguments":{},"inputResponses":{"favoriteCity":{"action":"accept","content":{"city":"Berlin"}},"coffeeSize":{"action":"accept","content":{"size":"large"}},"refillCount":{"action":"accept","content":{"refills":2}}},"requestState":"mrt-echo.r1.<echoed token>"}

						Example round 3 response result:
						{"content":[{"type":"text","text":"multiRoundTripEcho: round 1 - city=Berlin, size=large, refills=2; round 2 - confirmed=true"}]}

						Validation is strict by design (a stable fixture): unknown or malformed requestState,
						inputResponses without requestState (and vice versa), a missing inputResponses entry,
						action other than "accept", a wrong enum value, or a non-integer or negative refills
						count all return JSON-RPC error -32602 with a message naming the violation. Extra
						inputResponses keys are ignored, as SEP-2322 requires. requestState tokens embed no
						random data: identical answers always produce identical tokens, so the whole flow is
						replayable and byte-stable across restarts.
						""")
		.annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).build())
		.inputSchema(Map.of("type", "object"))
		.build();

	/**
	 * Replacement for the {@code McpServerJsonMapperAutoConfiguration} bean: the exact
	 * same construction plus {@link Sep2322WireModule}. The bean name must match the
	 * qualifier the server auto-configurations inject, and mirroring
	 * {@code defaultCandidate = false} keeps it invisible to unqualified by-type
	 * injections, exactly like the bean it replaces.
	 * @return the server {@link JsonMapper} carrying the SEP-2322 wire module
	 */
	@Bean(name = "mcpServerJsonMapper", defaultCandidate = false)
	JsonMapper mcpServerJsonMapper() {
		return JsonMapper.builder()
			.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
			.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
			.addModules(JacksonUtils.instantiateAvailableModules())
			.changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
					JsonInclude.Include.NON_NULL))
			.addModule(Sep2322WireModule.module())
			.build();
	}

	/**
	 * Wires the SEP-2322 wire module into the server itself, not only the transport. The
	 * qualifier-injected {@code mcpServerJsonMapper} bean above customizes wire
	 * (de)serialization, but {@code McpServerAutoConfiguration} never calls
	 * {@code jsonMapper(...)} on the server builder, so params conversions would fall
	 * back to the global {@code McpJsonDefaults} mapper and silently drop the re-send
	 * fields. The customizer closes that gap with the same mapper instance.
	 *
	 * <p>
	 * <strong>Why {@link Primary} and not a plain {@link McpSyncServerCustomizer}
	 * bean:</strong> the Spring AI autoconfig declares the customizer dependency as
	 * {@code Optional<McpSyncServerCustomizer>}, which resolves at most one bean. This
	 * composed bean is the {@code @Primary} one, and it delegates to
	 * {@link DemoResourceSubscribeCapabilityCustomizer} first so the resources-subscribe
	 * capability and the servlet-stack {@code immediateExecution(true)} tuning keep being
	 * applied.
	 * @param resourceSubscribeCustomizer the demo resources-subscribe customizer to
	 * delegate to before applying the mapper
	 * @return the customizer applied by the stateful server auto-configuration
	 */
	@Bean
	@Primary
	McpSyncServerCustomizer sep2322ServerJsonMapperCustomizer(
			DemoResourceSubscribeCapabilityCustomizer resourceSubscribeCustomizer) {
		return spec -> {
			resourceSubscribeCustomizer.customize(spec);
			spec.jsonMapper(new JacksonMcpJsonMapper(mcpServerJsonMapper()));
		};
	}

	/**
	 * Registers the tool for the stateful server flavours (SSE, streamable HTTP), which
	 * is what the demo runs. The stateless runtime is deliberately NOT registered for:
	 * its auto-configuration offers no customizer hook, so its server would deserialize
	 * re-sends with the global default mapper and drop {@code inputResponses} and
	 * {@code requestState}, leaving a silently broken fixture.
	 * @return the specification list consumed by {@code McpServerAutoConfiguration}
	 */
	@Bean
	List<McpServerFeatures.SyncToolSpecification> multiRoundTripEchoTool() {
		return List.of(McpServerFeatures.SyncToolSpecification.builder()
			.tool(TOOL)
			.callHandler((exchange, request) -> handle(request))
			.build());
	}

	private static McpSchema.CallToolResult handle(McpSchema.CallToolRequest request) {
		String requestState = Sep2322WireModule.capturedRequestState(request);
		JsonNode inputResponses = Sep2322WireModule.capturedInputResponses(request);
		if (requestState == null && inputResponses == null) {
			return roundOneResult();
		}
		if (requestState == null) {
			throw invalid("inputResponses are only valid on a re-send that echoes requestState");
		}
		JsonNode state = decodeState(requestState);
		if (state == null) {
			throw invalid("unrecognized requestState, expected a token issued by an earlier round");
		}
		int round = state.path("round").asInt(-1);
		if (round == 1) {
			return roundTwoResult(inputResponses);
		}
		if (round == 2) {
			return finalResult(state, inputResponses);
		}
		throw invalid("requestState names round " + round + ", this tool only has rounds 1 and 2");
	}

	private static McpSchema.CallToolResult roundOneResult() {
		Map<String, Object> inputRequests = new LinkedHashMap<>();
		inputRequests.put("favoriteCity",
				elicitation("Which city should the echo use?", schema(ordered("type", "string"), "city")));
		inputRequests.put("coffeeSize",
				elicitation("Pick a coffee size.", schema(ordered("type", "string", "enum", COFFEE_SIZES), "size")));
		inputRequests.put("refillCount",
				elicitation("How many refills?", schema(ordered("type", "integer", "minimum", 0), "refills")));
		return inputRequired(inputRequests, ROUND_ONE_STATE_PREFIX
				+ STATE_ENCODER.encodeToString(STATE_CODEC.writeValueAsBytes(ordered("round", 1))));
	}

	private static McpSchema.CallToolResult roundTwoResult(JsonNode inputResponses) {
		JsonNode responses = requireResponses(inputResponses);
		String city = stringAnswer(responses, "favoriteCity", "city");
		String size = enumAnswer(responses, "coffeeSize", "size");
		int refills = intAnswer(responses, "refillCount", "refills");
		Map<String, Object> inputRequests = new LinkedHashMap<>();
		inputRequests.put("confirmation",
				elicitation("Confirm the collected answers?", schema(ordered("type", "boolean"), "confirmed")));
		Map<String, Object> payload = ordered("round", 2, "city", city, "size", size, "refills", refills);
		return inputRequired(inputRequests,
				ROUND_TWO_STATE_PREFIX + STATE_ENCODER.encodeToString(STATE_CODEC.writeValueAsBytes(payload)));
	}

	private static McpSchema.CallToolResult finalResult(JsonNode state, JsonNode inputResponses) {
		JsonNode responses = requireResponses(inputResponses);
		boolean confirmed = booleanAnswer(responses, "confirmation", "confirmed");
		String city = state.path("city").asString();
		String size = state.path("size").asString();
		int refills = state.path("refills").asInt();
		return McpSchema.CallToolResult.builder()
			.addTextContent("multiRoundTripEcho: round 1 - city=" + city + ", size=" + size + ", refills=" + refills
					+ "; round 2 - confirmed=" + confirmed)
			.build();
	}

	private static McpSchema.CallToolResult inputRequired(Map<String, Object> inputRequests, String requestState) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put(Sep2322WireModule.RESULT_TYPE, "input_required");
		meta.put(Sep2322WireModule.INPUT_REQUESTS, inputRequests);
		meta.put(Sep2322WireModule.REQUEST_STATE, requestState);
		return McpSchema.CallToolResult.builder().meta(meta).build();
	}

	private static JsonNode decodeState(String requestState) {
		String prefix;
		if (requestState.startsWith(ROUND_ONE_STATE_PREFIX)) {
			prefix = ROUND_ONE_STATE_PREFIX;
		}
		else if (requestState.startsWith(ROUND_TWO_STATE_PREFIX)) {
			prefix = ROUND_TWO_STATE_PREFIX;
		}
		else {
			return null;
		}
		try {
			JsonNode state = STATE_CODEC.readTree(STATE_DECODER.decode(requestState.substring(prefix.length())));
			int expectedRound = ROUND_ONE_STATE_PREFIX.equals(prefix) ? 1 : 2;
			return (state.path("round").asInt(-1) == expectedRound) ? state : null;
		}
		catch (IllegalArgumentException | JacksonException ex) {
			return null;
		}
	}

	private static JsonNode requireResponses(JsonNode inputResponses) {
		if (inputResponses == null || !inputResponses.isObject()) {
			throw invalid("a re-send must carry an inputResponses object");
		}
		return inputResponses;
	}

	private static JsonNode acceptedContent(JsonNode responses, String key) {
		JsonNode entry = responses.get(key);
		if (entry == null || !entry.isObject()) {
			throw invalid("missing inputResponses entry \"" + key + "\"");
		}
		if (!"accept".equals(entry.path("action").asString())) {
			throw invalid("inputResponses." + key + ".action must be \"accept\"");
		}
		JsonNode content = entry.get("content");
		if (content == null || !content.isObject()) {
			throw invalid("inputResponses." + key + ".content must be an object");
		}
		return content;
	}

	private static String stringAnswer(JsonNode responses, String key, String field) {
		JsonNode value = acceptedContent(responses, key).get(field);
		if (value == null || !value.isString()) {
			throw invalid("inputResponses." + key + ".content." + field + " must be a string");
		}
		String answer = value.asString();
		if (answer.isBlank()) {
			throw invalid("inputResponses." + key + ".content." + field + " must not be blank");
		}
		return answer;
	}

	private static String enumAnswer(JsonNode responses, String key, String field) {
		String answer = stringAnswer(responses, key, field);
		if (!COFFEE_SIZES.contains(answer)) {
			throw invalid("inputResponses." + key + ".content." + field + " must be one of " + COFFEE_SIZES);
		}
		return answer;
	}

	private static int intAnswer(JsonNode responses, String key, String field) {
		JsonNode value = acceptedContent(responses, key).get(field);
		if (value == null || !value.isIntegralNumber()) {
			throw invalid("inputResponses." + key + ".content." + field + " must be an integer");
		}
		int answer = value.asInt();
		if (answer < 0) {
			throw invalid("inputResponses." + key + ".content." + field + " must be >= 0");
		}
		return answer;
	}

	private static boolean booleanAnswer(JsonNode responses, String key, String field) {
		JsonNode value = acceptedContent(responses, key).get(field);
		if (value == null || !value.isBoolean()) {
			throw invalid("inputResponses." + key + ".content." + field + " must be a boolean");
		}
		return value.asBoolean();
	}

	private static McpError invalid(String detail) {
		return McpError.builder(-32602).message(TOOL_NAME + ": " + detail).build();
	}

	private static Map<String, Object> elicitation(String message, Map<String, Object> requestedSchema) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("mode", "form");
		params.put("message", message);
		params.put("requestedSchema", requestedSchema);
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("method", "elicitation/create");
		request.put("params", params);
		return request;
	}

	private static Map<String, Object> schema(Map<String, Object> property, String field) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(field, property);
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", List.of(field));
		return schema;
	}

	private static Map<String, Object> ordered(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((String) pairs[i], pairs[i + 1]);
		}
		return map;
	}

}
