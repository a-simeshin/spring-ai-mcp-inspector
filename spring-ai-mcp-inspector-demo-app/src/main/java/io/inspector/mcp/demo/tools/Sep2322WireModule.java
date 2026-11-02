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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ser.ValueSerializerModifier;

/**
 * Jackson module that teaches the demo server's {@code mcpServerJsonMapper} the SEP-2322
 * multi round-trip wire fields which the MCP Java SDK 2.0.x schema records do not carry
 * yet ({@code CallToolResult} and {@code CallToolRequest} are final records with no
 * {@code resultType}/{@code inputRequests}/{@code requestState}/{@code
 * inputResponses} components).
 *
 * <p>
 * Transport convention, both directions ride through the existing record fields so no SDK
 * type has to be replaced:
 *
 * <ul>
 * <li>Response side: a tool puts {@code resultType}, {@code inputRequests} and
 * {@code requestState} into {@link McpSchema.CallToolResult}'s {@code meta} map. The
 * wrapping {@link ValueSerializer} renders the record exactly as the stock serializer
 * would, then hoists those three keys from {@code _meta} to the top level of the result
 * object, which is the shape SEP-2322 defines. Results without the markers serialize
 * byte-for-byte like before.</li>
 * <li>Request side: a re-send carries {@code inputResponses} and {@code requestState} as
 * siblings of {@code name}/{@code arguments} in the {@code tools/call} params. The stock
 * deserializer silently drops them (verified against mcp-core 2.0.1), so this module
 * installs a deserializer that rebuilds the record from the params tree and stores both
 * params-level fields inside the {@code meta} map under their wire names. Tool handlers
 * read them through {@link #capturedRequestState} and
 * {@link #capturedInputResponses}.</li>
 * </ul>
 */
public final class Sep2322WireModule {

	/**
	 * Marker key the tool sets in {@code CallToolResult.meta()}; hoisted to the top level
	 * of the result object on the wire.
	 */
	public static final String RESULT_TYPE = "resultType";

	/**
	 * Marker key the tool sets in {@code CallToolResult.meta()}; hoisted to the top level
	 * of the result object on the wire.
	 */
	public static final String INPUT_REQUESTS = "inputRequests";

	/**
	 * Marker key the tool sets in {@code CallToolResult.meta()}; hoisted to the top level
	 * of the result object on the wire.
	 */
	public static final String REQUEST_STATE = "requestState";

	private static final String CAPTURED_INPUT_RESPONSES = "inputResponses";

	private static final String CAPTURED_REQUEST_STATE = "requestState";

	private Sep2322WireModule() {
	}

	/**
	 * Builds the module. Register it on the server mapper only: it rewrites how
	 * {@code tools/call} requests and results cross the wire, which is a server-side
	 * concern.
	 * @return the module, never {@code null}
	 */
	public static SimpleModule module() {
		SimpleModule module = new SimpleModule("inspector-demo-sep2322");
		module.setSerializerModifier(new ValueSerializerModifier() {
			@Override
			public ValueSerializer<?> modifySerializer(SerializationConfig config, BeanDescription.Supplier beanDesc,
					ValueSerializer<?> serializer) {
				if (beanDesc.get().getType().getRawClass() == McpSchema.CallToolResult.class) {
					return new InputRequiredHoistingSerializer();
				}
				return serializer;
			}
		});
		module.addDeserializer(McpSchema.CallToolRequest.class, new ReSendCapturingDeserializer());
		return module;
	}

	/**
	 * Reads the {@code requestState} the client echoed on a re-send, if any.
	 * @param request the deserialized {@code tools/call} request
	 * @return the echoed state token, or {@code null} when the call carried none
	 */
	public static String capturedRequestState(McpSchema.CallToolRequest request) {
		if (request.meta() == null) {
			return null;
		}
		Object value = request.meta().get(CAPTURED_REQUEST_STATE);
		return (value instanceof String state) ? state : null;
	}

	/**
	 * Reads the raw {@code inputResponses} object the client sent on a re-send, if any.
	 * @param request the deserialized {@code tools/call} request
	 * @return the params-level {@code inputResponses} tree, or {@code null}
	 */
	public static JsonNode capturedInputResponses(McpSchema.CallToolRequest request) {
		if (request.meta() == null) {
			return null;
		}
		Object value = request.meta().get(CAPTURED_INPUT_RESPONSES);
		return (value instanceof JsonNode responses) ? responses : null;
	}

	/**
	 * Serializes {@link McpSchema.CallToolResult} through a stock-configuration mapper
	 * into a tree (no SEP-2322 module registered there, so the record renders exactly
	 * like the default serializer would, and there is no recursion), then hoists the
	 * SEP-2322 markers from {@code _meta} to the top level of the result object. Results
	 * without markers keep their stock rendering.
	 */
	private static final class InputRequiredHoistingSerializer extends ValueSerializer<McpSchema.CallToolResult> {

		/**
		 * Mirrors the server mapper's NON_NULL inclusion so the intermediate tree matches
		 * what the stock server mapper would have written.
		 */
		private static final JsonMapper STOCK = JsonMapper.builder()
			.changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
					JsonInclude.Include.NON_NULL))
			.build();

		@Override
		public void serialize(McpSchema.CallToolResult value, JsonGenerator gen, SerializationContext ctxt) {
			JsonNode rendered = STOCK.valueToTree(value);
			if (!(rendered instanceof ObjectNode result)) {
				gen.writeTree(rendered);
				return;
			}
			gen.writeTree(hoist(result));
		}

		/**
		 * Moves the three marker keys out of {@code _meta} and in front of every other
		 * result field; drops {@code _meta} entirely when nothing remains in it.
		 */
		private static ObjectNode hoist(ObjectNode result) {
			JsonNode meta = result.get("_meta");
			if (!(meta instanceof ObjectNode metaObject) || !metaObject.has(REQUEST_STATE)) {
				return result;
			}
			ObjectNode hoisted = JsonNodeFactory.instance.objectNode();
			hoisted.put(RESULT_TYPE, metaObject.path(RESULT_TYPE).asString(""));
			hoisted.put(REQUEST_STATE, metaObject.path(REQUEST_STATE).asString(""));
			JsonNode inputRequests = metaObject.get(INPUT_REQUESTS);
			if (inputRequests != null) {
				hoisted.set(INPUT_REQUESTS, inputRequests);
			}
			metaObject.remove(RESULT_TYPE);
			metaObject.remove(INPUT_REQUESTS);
			metaObject.remove(REQUEST_STATE);
			if (metaObject.isEmpty()) {
				result.remove("_meta");
			}
			Iterator<Map.Entry<String, JsonNode>> remaining = result.properties().iterator();
			while (remaining.hasNext()) {
				Map.Entry<String, JsonNode> field = remaining.next();
				hoisted.set(field.getKey(), field.getValue());
			}
			return hoisted;
		}

	}

	/**
	 * Rebuilds {@link McpSchema.CallToolRequest} from the raw params tree, preserving the
	 * stock record semantics for {@code name}, {@code arguments} and {@code _meta}
	 * (missing stays {@code null}), and folding the SEP-2322 re-send fields
	 * {@code inputResponses} and {@code requestState} into the {@code meta} map under
	 * their wire names. Unknown params keep being ignored, exactly like the stock
	 * deserializer does.
	 */
	private static final class ReSendCapturingDeserializer extends ValueDeserializer<McpSchema.CallToolRequest> {

		@Override
		public McpSchema.CallToolRequest deserialize(JsonParser p, DeserializationContext ctxt) {
			JsonNode tree = p.readValueAsTree();
			// The SDK only ever produces object params for tools/call; degrade
			// defensively instead of failing on a shape that cannot occur.
			ObjectNode params = tree.isObject() ? (ObjectNode) tree : JsonNodeFactory.instance.objectNode();
			String name = params.hasNonNull("name") ? params.get("name").asString() : null;
			Map<String, Object> arguments = params.hasNonNull("arguments") ? readMap(params.get("arguments"), ctxt)
					: null;
			Map<String, Object> meta = params.hasNonNull("_meta") ? readMap(params.get("_meta"), ctxt) : null;
			boolean carriesReSend = params.hasNonNull(CAPTURED_INPUT_RESPONSES)
					|| params.hasNonNull(CAPTURED_REQUEST_STATE);
			if (carriesReSend) {
				Map<String, Object> merged = new LinkedHashMap<>((meta == null) ? Map.of() : meta);
				if (params.hasNonNull(CAPTURED_INPUT_RESPONSES)) {
					merged.put(CAPTURED_INPUT_RESPONSES, params.get(CAPTURED_INPUT_RESPONSES));
				}
				if (params.hasNonNull(CAPTURED_REQUEST_STATE)) {
					merged.put(CAPTURED_REQUEST_STATE, params.get(CAPTURED_REQUEST_STATE).asString());
				}
				meta = merged;
			}
			return new McpSchema.CallToolRequest(name, arguments, meta);
		}

		@SuppressWarnings("unchecked")
		private static Map<String, Object> readMap(JsonNode node, DeserializationContext ctxt) {
			return ctxt.readTreeAsValue(node, Map.class);
		}

	}

}
