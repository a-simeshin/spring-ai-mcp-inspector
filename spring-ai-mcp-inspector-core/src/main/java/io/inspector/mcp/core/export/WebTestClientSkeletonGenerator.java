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
 * {@link JUnitTestSkeletonGenerator} for the WebFlux stack: emits a WebTestClient-based
 * {@code @SpringBootTest} that replays the captured JSON-RPC request against the MCP
 * endpoint. Besides the web starter and {@code spring-boot-starter-test}, the target
 * project needs {@code spring-boot-webtestclient} on its test classpath.
 *
 * @author Artem Simeshin
 */
public final class WebTestClientSkeletonGenerator implements JUnitTestSkeletonGenerator {

	/** Default MCP JSON-RPC endpoint used in the generated POST. */
	public static final String DEFAULT_MCP_ENDPOINT = "/mcp";

	private final String mcpEndpoint;

	/** Creates a generator targeting the default {@code /mcp} endpoint. */
	public WebTestClientSkeletonGenerator() {
		this(DEFAULT_MCP_ENDPOINT);
	}

	/**
	 * Creates a generator targeting a custom MCP endpoint.
	 * @param mcpEndpoint the MCP JSON-RPC endpoint path (for example {@code /mcp})
	 */
	public WebTestClientSkeletonGenerator(final String mcpEndpoint) {
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

			import org.junit.jupiter.api.Test;
			import org.skyscreamer.jsonassert.JSONAssert;
			import org.skyscreamer.jsonassert.JSONCompareMode;
			import org.springframework.beans.factory.annotation.Autowired;
			import org.springframework.boot.test.context.SpringBootTest;
			import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
			import org.springframework.http.MediaType;
			import org.springframework.test.web.reactive.server.WebTestClient;

			/**
			 * Regression test generated from an MCP Inspector call.
			 *
			 * <p>Replays the recorded JSON-RPC request for tool {@code {{TOOL_NAME}}}
			 * (method {@code {{TOOL_METHOD}}}) against {@code POST {{MCP_ENDPOINT}}} and
			 * compares the response body with the captured etalon using JSONAssert in
			 * LENIENT mode.
			 */
			@SpringBootTest
			@AutoConfigureWebTestClient
			class {{CLASS_NAME}} {

				private static final String MCP_ENDPOINT = "{{MCP_ENDPOINT}}";

				private static final String REQUEST_JSON = {{REQUEST_JSON}};

				private static final String ETALON_RESULT_JSON = {{ETALON_JSON}};

				@Autowired
				private WebTestClient webTestClient;

				@Test
				void invokeToolAndAssertKeyFields() throws Exception {
					final String responseBody = webTestClient.post()
							.uri(MCP_ENDPOINT)
							.contentType(MediaType.APPLICATION_JSON)
							.bodyValue(REQUEST_JSON)
							.exchange()
							.expectStatus()
							.isOk()
							.expectBody(String.class)
							.returnResult()
							.getResponseBody();

					JSONAssert.assertEquals(ETALON_RESULT_JSON, responseBody, JSONCompareMode.LENIENT);
				}

			}
			""";

}
