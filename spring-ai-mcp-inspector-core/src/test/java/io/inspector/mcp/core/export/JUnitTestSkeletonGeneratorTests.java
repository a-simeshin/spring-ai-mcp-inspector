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

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the JUnit 5 skeleton generators ({@link MockMvcSkeletonGenerator} and
 * {@link WebTestClientSkeletonGenerator}) and the {@link ToolCallRecord} contract they
 * are fed with.
 */
@Epic("MCP Inspector Core")
@Feature("Tool call export")
class JUnitTestSkeletonGeneratorTests {

	private static final String CONTINUATION = "\"\n		+ \"";

	private static final String ESCAPED_SAMPLE_ARGS = "{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":1,\\\"method\\\":\\\"tools/call\\\",\\\"params\\\":{\\\"name\\\":\\\"echo\\\",\\\"arguments\\\":{\\\"message\\\":\\\"Hello\\\"}}}";

	private static final String ESCAPED_SAMPLE_RESULT = "{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":1,\\\"result\\\":{\\\"content\\\":[{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"Hello\\\"}]}}";

	private static final String SAMPLE_ARGS = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"message\":\"Hello\"}}}";

	private static final String SAMPLE_RESULT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}}";

	private static final ToolCallRecord SAMPLE_CALL = new ToolCallRecord("echo", "tools/call", SAMPLE_ARGS,
			SAMPLE_RESULT, "2026-08-30T08:30:00Z");

	/**
	 * Joins the {@code +} continuation lines produced by the generators back into a
	 * single line, so embedded JSON content can be asserted as a whole regardless of
	 * where the line wrap fell.
	 */
	private static String unwrapped(final String source) {
		return source.replace(CONTINUATION, "");
	}

	@Nested
	@DisplayName("ToolCallRecord")
	class ToolCallRecordContract {

		@Test
		@Story("Record validation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("record rejects blank toolName and method, normalises null arguments/result/timestamp")
		void record_whenBlankToolName_throws() {
			assertThatThrownBy(() -> new ToolCallRecord(" ", "tools/call", "{}", "{}", "2026-01-01T00:00:00Z"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("toolName");
		}

		@Test
		@Story("Record validation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("record rejects null toolName")
		void record_whenNullToolName_throws() {
			assertThatThrownBy(() -> new ToolCallRecord(null, "tools/call", "{}", "{}", "2026-01-01T00:00:00Z"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("toolName");
		}

		@Test
		@Story("Record validation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("record rejects blank method")
		void record_whenBlankMethod_throws() {
			assertThatThrownBy(() -> new ToolCallRecord("echo", "", "{}", "{}", "2026-01-01T00:00:00Z"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("method");
		}

		@Test
		@Story("Record defaults")
		@Severity(SeverityLevel.NORMAL)
		@Description("null arguments/result become empty JSON objects, null timestamp falls back to now")
		void record_whenNullOptionalFields_appliesDefaults() {
			final ToolCallRecord call = new ToolCallRecord("echo", "tools/call", null, null, null);

			assertThat(call.arguments()).isEqualTo("{}");
			assertThat(call.result()).isEqualTo("{}");
			assertThat(call.timestamp()).isNotBlank();
		}

		@Test
		@Story("Record defaults")
		@Severity(SeverityLevel.NORMAL)
		@Description("blank timestamp falls back to a generated instant")
		void record_whenBlankTimestamp_generatesInstant() {
			final ToolCallRecord call = new ToolCallRecord("echo", "tools/call", "{}", "{}", "  ");

			assertThat(call.timestamp()).isNotBlank();
		}

	}

	@Nested
	@DisplayName("Naming and package computation")
	class NamingContract {

		@Test
		@Story("Package computation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("computeTestPackage appends .tools to the base package")
		void computeTestPackage_whenBasePresent_appendsTools() {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			assertThat(generator.computeTestPackage("com.example.demo")).isEqualTo("com.example.demo.tools");
		}

		@Test
		@Story("Package computation")
		@Severity(SeverityLevel.NORMAL)
		@Description("computeTestPackage falls back to 'tools' for blank base package")
		void computeTestPackage_whenBlankBase_fallsBackToTools() {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			assertThat(generator.computeTestPackage(null)).isEqualTo("tools");
			assertThat(generator.computeTestPackage("")).isEqualTo("tools");
			assertThat(generator.computeTestPackage("   ")).isEqualTo("tools");
		}

		@Test
		@Story("Class name computation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("computeClassName produces <ToolName>RegressionTest with capitalised tool name")
		void computeClassName_whenToolName_capitalisesAndSuffixes() {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			assertThat(generator.computeClassName("echo")).isEqualTo("EchoRegressionTest");
			assertThat(generator.computeClassName("listMyRoots")).isEqualTo("ListMyRootsRegressionTest");
		}

		@Test
		@Story("Class name computation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("dashed, spaced and digit-leading tool names are sanitised into valid identifiers")
		void computeClassName_whenToolNameNotIdentifier_sanitises() {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			assertThat(generator.computeClassName("my-tool")).isEqualTo("My_toolRegressionTest");
			assertThat(generator.computeClassName("echo tool")).isEqualTo("Echo_toolRegressionTest");
			assertThat(generator.computeClassName("2fast")).isEqualTo("_2fastRegressionTest");
		}

		@Test
		@Story("Class name computation")
		@Severity(SeverityLevel.NORMAL)
		@Description("computeClassName rejects blank tool name")
		void computeClassName_whenBlank_throws() {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			assertThatThrownBy(() -> generator.computeClassName("")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("toolName");
		}

	}

	@Nested
	@DisplayName("MockMvcSkeletonGenerator (WebMVC variant)")
	class MockMvcGeneration {

		@Test
		@Story("WebMVC skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("generated source carries package, class name, Boot 4 AutoConfigureMockMvc import, replay docs and header comment")
		void generate_whenSampleCall_producesCompleteSkeleton() {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			final String source = generator.generate(SAMPLE_CALL, "com.example.demo");

			assertThat(source).contains("// Generated from inspector call 2026-08-30T08:30:00Z");
			assertThat(source).contains("package com.example.demo.tools;");
			assertThat(source).contains("class EchoRegressionTest");
			assertThat(source).contains("@SpringBootTest");
			assertThat(source)
				.contains("import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;");
			assertThat(source).contains("@AutoConfigureMockMvc");
			assertThat(source).contains("private MockMvc mockMvc;");
			assertThat(source).contains("for tool {@code echo}");
			assertThat(source).contains("(method {@code tools/call})");
			assertThat(source).doesNotContain("private static final String TOOL_METHOD");
			assertThat(source).doesNotContain("\"\"\"");
			final int longest = source.lines().mapToInt(String::length).max().orElse(0);
			assertThat(longest).as("longest generated line").isLessThanOrEqualTo(120);
		}

		@Test
		@Story("WebMVC skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("captured request and etalon are embedded as wrapped string constants compared with JSONAssert")
		void generate_whenSampleCall_embedsRequestAndEtalon() {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			final String source = generator.generate(SAMPLE_CALL, "com.example.demo");

			final String flat = unwrapped(source);
			assertThat(flat).contains("REQUEST_JSON = \"" + ESCAPED_SAMPLE_ARGS + "\";");
			assertThat(flat).contains("ETALON_RESULT_JSON = \"" + ESCAPED_SAMPLE_RESULT + "\";");
			assertThat(source).contains(CONTINUATION);
			assertThat(source)
				.contains("JSONAssert.assertEquals(ETALON_RESULT_JSON, responseBody, JSONCompareMode.LENIENT);");
		}

		@Test
		@Story("WebMVC skeleton")
		@Severity(SeverityLevel.NORMAL)
		@Description("custom endpoint is honoured and blank endpoint rejected")
		void generate_whenCustomEndpoint_usesIt() {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator("/custom/mcp");

			final String source = generator.generate(SAMPLE_CALL, "com.example.demo");

			assertThat(source).contains("private static final String MCP_ENDPOINT = \"/custom/mcp\";");
			assertThatThrownBy(() -> new MockMvcSkeletonGenerator(" ")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("mcpEndpoint");
		}

	}

	@Nested
	@DisplayName("WebTestClientSkeletonGenerator (WebFlux variant)")
	class WebTestClientGeneration {

		@Test
		@Story("WebFlux skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("generated source carries package, class name, Boot 4 AutoConfigureWebTestClient import, replay docs and header comment")
		void generate_whenSampleCall_producesCompleteSkeleton() {
			final WebTestClientSkeletonGenerator generator = new WebTestClientSkeletonGenerator();

			final String source = generator.generate(SAMPLE_CALL, "com.example.demo");

			assertThat(source).contains("// Generated from inspector call 2026-08-30T08:30:00Z");
			assertThat(source).contains("package com.example.demo.tools;");
			assertThat(source).contains("class EchoRegressionTest");
			assertThat(source).contains("@SpringBootTest");
			assertThat(source)
				.contains("import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;");
			assertThat(source).contains("@AutoConfigureWebTestClient");
			assertThat(source).contains("private WebTestClient webTestClient;");
			assertThat(source).contains("for tool {@code echo}");
			assertThat(source).contains("(method {@code tools/call})");
			assertThat(source).doesNotContain("\"\"\"");
			final int longest = source.lines().mapToInt(String::length).max().orElse(0);
			assertThat(longest).as("longest generated line").isLessThanOrEqualTo(120);
		}

		@Test
		@Story("WebFlux skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("captured request and etalon are embedded as wrapped string constants compared with JSONAssert")
		void generate_whenSampleCall_embedsRequestAndEtalon() {
			final WebTestClientSkeletonGenerator generator = new WebTestClientSkeletonGenerator();

			final String source = generator.generate(SAMPLE_CALL, "com.example.demo");

			final String flat = unwrapped(source);
			assertThat(flat).contains("REQUEST_JSON = \"" + ESCAPED_SAMPLE_ARGS + "\";");
			assertThat(flat).contains("ETALON_RESULT_JSON = \"" + ESCAPED_SAMPLE_RESULT + "\";");
			assertThat(source).contains(CONTINUATION);
		}

		@Test
		@Story("WebFlux skeleton")
		@Severity(SeverityLevel.NORMAL)
		@Description("custom endpoint is honoured and blank endpoint rejected")
		void generate_whenCustomEndpoint_usesIt() {
			final WebTestClientSkeletonGenerator generator = new WebTestClientSkeletonGenerator("/custom/mcp");

			final String source = generator.generate(SAMPLE_CALL, "com.example.demo");

			assertThat(source).contains("private static final String MCP_ENDPOINT = \"/custom/mcp\";");
			assertThatThrownBy(() -> new WebTestClientSkeletonGenerator(""))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("mcpEndpoint");
		}

	}

	@Nested
	@DisplayName("String escaping")
	class Escaping {

		@Test
		@Story("JSON embedding")
		@Severity(SeverityLevel.CRITICAL)
		@Description("backslashes and quotes in captured payloads stay valid inside generated string literals")
		void generate_whenPayloadContainsBackslashAndQuotes_escapesSafely() {
			final String rawArgs = "{\"path\":\"C:\\tmp\\new\"}";

			final String rawResult = "{\"text\":\"a\\\"b\"}";

			final ToolCallRecord call = new ToolCallRecord("echo", "tools/call", rawArgs, rawResult,
					"2026-08-30T08:30:00Z");

			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			final String source = generator.generate(call, "com.example.demo");

			assertThat(source).contains("{\\\"path\\\":\\\"C:\\\\tmp\\\\new\\\"}");
			assertThat(source).contains("{\\\"text\\\":\\\"a\\\\\\\"b\\\"}");
		}

		@Test
		@Story("JSON embedding")
		@Severity(SeverityLevel.NORMAL)
		@Description("control characters are escaped so the generated literal stays on one physical line")
		void generate_whenPayloadContainsNewline_escapesControlCharacters() {
			final String rawResult = "{\"text\":\"ok\"}\n";

			final ToolCallRecord call = new ToolCallRecord("echo", "tools/call", "{}", rawResult,
					"2026-08-30T08:30:00Z");

			final WebTestClientSkeletonGenerator generator = new WebTestClientSkeletonGenerator();

			final String source = generator.generate(call, "com.example.demo");

			assertThat(source).contains("{\\\"text\\\":\\\"ok\\\"}\\n");
		}

	}

}
