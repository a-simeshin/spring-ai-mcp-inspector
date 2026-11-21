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

package io.inspector.mcp.core.export;

/**
 * {@link JUnitTestSkeletonGenerator} for the WebMVC stack: emits a MockMvc-based
 * {@code @SpringBootTest} that replays the captured JSON-RPC request against the MCP
 * endpoint. The generated test performs the MCP streamable-HTTP handshake (initialize,
 * then notifications/initialized on stateful servers), sends the {@code Accept:
 * application/json, text/event-stream} pair the MCP Java SDK server transports require,
 * carries the {@code Mcp-Session-Id} on every post-initialize request, extracts the
 * JSON-RPC response from a streamed SSE body when the server answers as an event stream,
 * and compares the {@code result} member only. Besides the web starter and
 * {@code spring-boot-starter-test}, the target project needs
 * {@code spring-boot-webmvc-test} on its test classpath (the
 * {@code spring-boot-starter-webmvc-test} starter brings it).
 *
 * @author Artem Simeshin
 */
public final class MockMvcSkeletonGenerator implements JUnitTestSkeletonGenerator {

	/** Default MCP JSON-RPC endpoint used in the generated POST. */
	public static final String DEFAULT_MCP_ENDPOINT = "/mcp";

	private final String mcpEndpoint;

	/** Creates a generator targeting the default {@code /mcp} endpoint. */
	public MockMvcSkeletonGenerator() {
		this(DEFAULT_MCP_ENDPOINT);
	}

	/**
	 * Creates a generator targeting a custom MCP endpoint.
	 * @param mcpEndpoint the MCP JSON-RPC endpoint path (for example {@code /mcp})
	 */
	public MockMvcSkeletonGenerator(final String mcpEndpoint) {
		if (mcpEndpoint == null || mcpEndpoint.isBlank()) {
			throw new IllegalArgumentException("mcpEndpoint must not be blank");
		}
		this.mcpEndpoint = mcpEndpoint;
	}

	@Override
	public String generate(final ToolCallRecord call, final String basePackage) {
		final String pkg = computeTestPackage(basePackage);
		final String className = computeClassName(call.toolName());
		return TEMPLATE.replace("{{TIMESTAMP}}", SkeletonText.commentSafe(call.timestamp()))
			.replace("{{PACKAGE}}", pkg)
			.replace("{{CLASS_NAME}}", className)
			.replace("{{TOOL_NAME}}", SkeletonText.commentSafe(call.toolName()))
			.replace("{{TOOL_METHOD}}", SkeletonText.commentSafe(call.method()))
			.replace("{{MCP_ENDPOINT}}", SkeletonText.commentSafe(this.mcpEndpoint))
			.replace("{{REQUEST_JSON}}", SkeletonText.stringLiteral(call.arguments()))
			.replace("{{ETALON_JSON}}", SkeletonText.stringLiteral(call.result()));
	}

	private static final String TEMPLATE = """
			// Generated from inspector call {{TIMESTAMP}}

			package {{PACKAGE}};

			import java.nio.charset.StandardCharsets;

			import org.json.JSONException;
			import org.json.JSONObject;
			import org.junit.jupiter.api.Test;
			import org.skyscreamer.jsonassert.JSONAssert;
			import org.skyscreamer.jsonassert.JSONCompareMode;
			import org.springframework.beans.factory.annotation.Autowired;
			import org.springframework.boot.test.context.SpringBootTest;
			import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
			import org.springframework.http.MediaType;
			import org.springframework.mock.web.MockHttpServletResponse;
			import org.springframework.test.web.servlet.MockMvc;
			import org.springframework.test.web.servlet.MvcResult;
			import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

			import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
			import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

			/**
			 * Regression test generated from an MCP Inspector call.
			 *
			 * <p>Replays the recorded JSON-RPC request for tool {@code {{TOOL_NAME}}}
			 * (method {@code {{TOOL_METHOD}}}) against {@code POST {{MCP_ENDPOINT}}}.</p>
			 *
			 * <p>The replay performs the MCP streamable-HTTP handshake first: an
			 * {@code initialize} exchange, then {@code notifications/initialized} when the
			 * server is stateful and returned a {@code Mcp-Session-Id}, and only then the
			 * captured call, carrying the session id on every post-initialize request. Every
			 * POST declares {@code Accept: application/json, text/event-stream} as required
			 * by the MCP Java SDK server transports, and the JSON-RPC response is extracted
			 * from the {@code data:} lines when the server streams the answer as SSE. Only
			 * the {@code result} member is compared (JSON-RPC ids are not), with JSONAssert
			 * in LENIENT mode.</p>
			 */
			@SpringBootTest
			@AutoConfigureMockMvc
			class {{CLASS_NAME}} {

				private static final String MCP_ENDPOINT = "{{MCP_ENDPOINT}}";

				private static final String SESSION_HEADER = "Mcp-Session-Id";

				private static final String INITIALIZE_JSON = "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":1,\\"method\\":\\"initialize\\","
						+ "\\"params\\":{\\"protocolVersion\\":\\"2025-06-18\\",\\"capabilities\\":{},"
						+ "\\"clientInfo\\":{\\"name\\":\\"inspector-generated-regression\\",\\"version\\":\\"1.0\\"}}}";

				private static final String INITIALIZED_NOTIFICATION_JSON =
						"{\\"jsonrpc\\":\\"2.0\\",\\"method\\":\\"notifications/initialized\\"}";

				private static final String REQUEST_JSON = {{REQUEST_JSON}};

				private static final String ETALON_RESPONSE_JSON = {{ETALON_JSON}};

				@Autowired
				private MockMvc mockMvc;

				@Test
				void invokeToolAndAssertKeyFields() throws Exception {
					final Replayed initialize = exchange(INITIALIZE_JSON, null);
					if (initialize.status() != 200) {
						throw new AssertionError(
								"initialize returned HTTP " + initialize.status() + ": " + initialize.body());
					}
					final String session = initialize.sessionHeader();
					if (session != null && !session.isBlank()) {
						final Replayed ack = exchange(INITIALIZED_NOTIFICATION_JSON, session);
						if (ack.status() != 202) {
							throw new AssertionError("notifications/initialized returned HTTP " + ack.status()
									+ ": " + ack.body());
						}
					}
					final Replayed replay = exchange(REQUEST_JSON, session);
					if (replay.status() != 200) {
						throw new AssertionError(
								"captured call returned HTTP " + replay.status() + ": " + replay.body());
					}
					final String response = extractJsonRpcResponse(replay.body(), requestId());
					JSONAssert.assertEquals(extractResult(ETALON_RESPONSE_JSON), extractResult(response),
							JSONCompareMode.LENIENT);
				}

				private static String requestId() {
					try {
						final Object id = new JSONObject(REQUEST_JSON).opt("id");
						return (id == null) ? null : String.valueOf(id);
					}
					catch (JSONException ex) {
						return null;
					}
				}

				/**
				 * Sends one JSON-RPC message as a POST carrying the Accept pair the MCP SDK
				 * server transports require. Streamed (async) answers are dispatched to their
				 * completed response before the result is read.
				 */
				private Replayed exchange(String jsonBody, String session) throws Exception {
					final MockHttpServletRequestBuilder request = post(MCP_ENDPOINT)
							.contentType(MediaType.APPLICATION_JSON)
							.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
							.content(jsonBody);
					if (session != null) {
						request.header(SESSION_HEADER, session);
					}
					MvcResult result = this.mockMvc.perform(request).andReturn();
					if (result.getRequest().isAsyncStarted()) {
						result = this.mockMvc.perform(asyncDispatch(result)).andReturn();
					}
					final MockHttpServletResponse response = (MockHttpServletResponse) result.getResponse();
					return new Replayed(response.getStatus(), header(response, SESSION_HEADER),
							response.getContentAsString(StandardCharsets.UTF_8));
				}

				private static String header(MockHttpServletResponse response, String name) {
					for (String candidate : response.getHeaderNames()) {
						if (candidate.equalsIgnoreCase(name)) {
							return response.getHeader(candidate);
						}
					}
					return null;
				}

				/**
				 * Returns the JSON-RPC response payload: JSON bodies pass through unchanged,
				 * SSE streams are reduced to the {@code data:} line that answers the replayed
				 * request (server notifications in the same stream are ignored).
				 */
				static String extractJsonRpcResponse(String rawBody, String requestId) {
					if (rawBody == null || !rawBody.contains("data:")) {
						return rawBody;
					}
					for (String line : rawBody.split("\\\\r?\\\\n")) {
						final String trimmed = line.trim();
						if (!trimmed.startsWith("data:")) {
							continue;
						}
						final String data = trimmed.substring(5).trim();
						if (!data.startsWith("{")) {
							continue;
						}
						try {
							final JSONObject candidate = new JSONObject(data);
							final boolean answersRequest = candidate.has("result") || candidate.has("error");
							final boolean idMatches = (requestId == null) || (candidate.has("id")
									&& String.valueOf(candidate.get("id")).equals(requestId));
							if (answersRequest && idMatches) {
								return data;
							}
						}
						catch (JSONException ex) {
							// not JSON: try the next data line
						}
					}
					return rawBody;
				}

				/**
				 * Reduces a captured JSON-RPC envelope to its {@code result} member, so the
				 * etalon comparison is independent of the JSON-RPC id under which the call was
				 * recorded. Non-envelope bodies pass through unchanged.
				 */
				static String extractResult(String responseBody) {
					try {
						final JSONObject envelope = new JSONObject(responseBody);
						if (envelope.has("result")) {
							return String.valueOf(envelope.get("result"));
						}
					}
					catch (JSONException ex) {
						// fall through: compare the body as-is
					}
					return responseBody;
				}

				private record Replayed(int status, String sessionHeader, String body) {
				}

			}
			""";

}
