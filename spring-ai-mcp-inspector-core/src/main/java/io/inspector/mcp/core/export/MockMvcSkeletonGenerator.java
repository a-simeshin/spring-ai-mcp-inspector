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
	 * @param mcpEndpoint the MCP JSON-RPC endpoint path (for example
	 * {@code /mcp/message})
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
		final String escapedArgs = escapeJavaString(call.arguments());
		final String escapedResult = escapeJavaString(call.result());

		return TEMPLATE.replace("{{TIMESTAMP}}", call.timestamp())
			.replace("{{PACKAGE}}", pkg)
			.replace("{{CLASS_NAME}}", className)
			.replace("{{MCP_ENDPOINT}}", this.mcpEndpoint)
			.replace("{{TOOL_METHOD}}", call.method())
			.replace("{{ARGS_JSON}}", escapedArgs)
			.replace("{{ETALON_JSON}}", escapedResult);
	}

	private static final String TEMPLATE = """
			// Generated from inspector call {{TIMESTAMP}}

			package {{PACKAGE}};

			import org.junit.jupiter.api.Test;
			import org.skyscreamer.jsonassert.JSONAssert;
			import org.skyscreamer.jsonassert.JSONCompareMode;
			import org.springframework.beans.factory.annotation.Autowired;
			import org.springframework.boot.test.context.SpringBootTest;
			import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
			import org.springframework.http.MediaType;
			import org.springframework.test.web.servlet.MockMvc;
			import org.springframework.test.web.servlet.MvcResult;

			import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
			import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

			/**
			 * Regression test generated from an MCP Inspector call.
			 * <p>
			 * Calls the MCP server endpoint via MockMvc with the captured arguments
			 * and asserts the key response fields against the etalon.
			 */
			@SpringBootTest
			@AutoConfigureMockMvc
			class {{CLASS_NAME}} {

				private static final String MCP_ENDPOINT = "{{MCP_ENDPOINT}}";

				private static final String TOOL_METHOD = "{{TOOL_METHOD}}";

				private static final String EXPECTED_ARGS_JSON = {{ARGS_JSON}};

				private static final String ETALON_RESULT_JSON = {{ETALON_JSON}};

				@Autowired
				private MockMvc mockMvc;

				@Test
				void invokeToolAndAssertKeyFields() throws Exception {
					final MvcResult result = mockMvc
							.perform(post(MCP_ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(EXPECTED_ARGS_JSON))
							.andExpect(status().isOk())
							.andReturn();

					final String responseBody = result.getResponse().getContentAsString();
					JSONAssert.assertEquals(ETALON_RESULT_JSON, responseBody, JSONCompareMode.LENIENT);
				}

			}
			""";

	private static String escapeJavaString(final String raw) {
		final String escaped = raw.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"");
		return "\"\"\"" + System.lineSeparator() + escaped + "\"\"\"";
	}

}
