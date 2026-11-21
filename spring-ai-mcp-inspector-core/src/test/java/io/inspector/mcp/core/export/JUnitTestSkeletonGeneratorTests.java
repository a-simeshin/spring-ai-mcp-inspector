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

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

	/**
	 * Compiles {@code source} with the platform compiler against this module's test
	 * classpath: exactly the jars a generated regression test may reference
	 * ({@code spring-boot-starter-test} and the stack test module).
	 * @param className simple name of the generated class
	 * @param source generated Java source
	 * @param outputDir directory the class files are written to
	 * @throws Exception when the compiler cannot be configured
	 */
	private static void assertCompiles(final String className, final String source, final Path outputDir)
			throws Exception {
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("system Java compiler (a JDK is required)").isNotNull();
		final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
				StandardCharsets.UTF_8)) {
			fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
			fileManager.setLocation(StandardLocation.CLASS_PATH,
					Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
						.map(File::new)
						.toList());
			final JavaFileObject unit = new SimpleJavaFileObject(
					URI.create("string:///" + className + JavaFileObject.Kind.SOURCE.extension),
					JavaFileObject.Kind.SOURCE) {

				@Override
				public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
					return source;
				}
			};
			final Boolean success = compiler.getTask(null, fileManager, diagnostics, null, null, List.of(unit)).call();
			final String errors = diagnostics.getDiagnostics()
				.stream()
				.filter((diagnostic) -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
				.map((diagnostic) -> "line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT))
				.collect(Collectors.joining("\n"));
			assertThat(success).as("generated source must compile, javac errors:\n%s", errors).isTrue();
		}
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
			assertThat(flat).contains("ETALON_RESPONSE_JSON = \"" + ESCAPED_SAMPLE_RESULT + "\";");
			assertThat(source).contains(CONTINUATION);
			assertThat(source)
				.contains("JSONAssert.assertEquals(extractResult(ETALON_RESPONSE_JSON), extractResult(response),");
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
			assertThat(flat).contains("ETALON_RESPONSE_JSON = \"" + ESCAPED_SAMPLE_RESULT + "\";");
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

	@Nested
	@DisplayName("Streamable HTTP handshake contract")
	class StreamableHttpHandshake {

		@Test
		@Story("WebMVC skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("WebMVC skeleton declares the Accept pair, initializes the session, extracts SSE answers and compares result only")
		void generate_whenSampleCall_webMvcCarriesStreamableHttpContract() {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			final String source = generator.generate(SAMPLE_CALL, "com.example.demo");

			assertThat(source).contains(".accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)");
			assertThat(source).contains("notifications/initialized");
			assertThat(source).contains("Mcp-Session-Id");
			assertThat(source).contains("extractJsonRpcResponse(replay.body(), requestId())");
			assertThat(source).contains("extractResult(ETALON_RESPONSE_JSON)");
			assertThat(source).contains("asyncDispatch(result)");
		}

		@Test
		@Story("WebFlux skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("WebFlux skeleton declares the Accept pair, initializes the session, extracts SSE answers and compares result only")
		void generate_whenSampleCall_webFluxCarriesStreamableHttpContract() {
			final WebTestClientSkeletonGenerator generator = new WebTestClientSkeletonGenerator();

			final String source = generator.generate(SAMPLE_CALL, "com.example.demo");

			assertThat(source).contains(".accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)");
			assertThat(source).contains("notifications/initialized");
			assertThat(source).contains("Mcp-Session-Id");
			assertThat(source).contains("extractJsonRpcResponse(replay.body(), requestId())");
			assertThat(source).contains("extractResult(ETALON_RESPONSE_JSON)");
		}

	}

	@Nested
	@DisplayName("Generated sources compile")
	class Compiles {

		@Test
		@Story("Compilable skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the generated WebMVC source compiles with javac against this module's test classpath")
		void compile_whenWebMvcVariant_compilesAgainstTestClasspath(final @TempDir Path outputDir) throws Exception {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			assertCompiles("EchoRegressionTest", generator.generate(SAMPLE_CALL, "com.example.demo"), outputDir);
		}

		@Test
		@Story("Compilable skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the generated WebFlux source compiles with javac against this module's test classpath")
		void compile_whenWebFluxVariant_compilesAgainstTestClasspath(final @TempDir Path outputDir) throws Exception {
			final WebTestClientSkeletonGenerator generator = new WebTestClientSkeletonGenerator();

			assertCompiles("EchoRegressionTest", generator.generate(SAMPLE_CALL, "com.example.demo"), outputDir);
		}

	}

	@Nested
	@DisplayName("SSE answer extraction targets the replayed request id")
	class SseAnswerExtraction {

		private static final String NOTIFICATION_DATA = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}";

		private static final String FOREIGN_ID_DATA = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":"
				+ "[{\"type\":\"text\",\"text\":\"foreign\"}]}}";

		private static final String REPLAYED_ID_DATA = "{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{\"content\":"
				+ "[{\"type\":\"text\",\"text\":\"replayed\"}]}}";

		private static final String MULTI_EVENT_STREAM = "event: message\ndata: " + NOTIFICATION_DATA
				+ "\n\nevent: message\ndata: " + FOREIGN_ID_DATA + "\n\nevent: message\ndata: " + REPLAYED_ID_DATA
				+ "\n\n";

		@Test
		@Story("WebMVC skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("compiled WebMVC extractJsonRpcResponse returns the data line answering the replayed id only, "
				+ "server notifications and foreign-id answers are ignored")
		void extract_whenStreamCarriesForeignIdAndNotification_returnsReplayedIdAnswer_webMvc(
				final @TempDir Path outputDir) throws Exception {
			final MockMvcSkeletonGenerator generator = new MockMvcSkeletonGenerator();

			assertCompiles("EchoRegressionTest", generator.generate(SAMPLE_CALL, "com.example.demo"), outputDir);

			assertThat(extractViaReflection(outputDir, MULTI_EVENT_STREAM, "42"))
				.as("the data line answering the replayed id must be returned")
				.isEqualTo(REPLAYED_ID_DATA);
			assertThat(extractViaReflection(outputDir, MULTI_EVENT_STREAM, "99"))
				.as("events not answering the replayed id must be ignored, the raw body returned")
				.isEqualTo(MULTI_EVENT_STREAM);
		}

		@Test
		@Story("WebFlux skeleton")
		@Severity(SeverityLevel.CRITICAL)
		@Description("compiled WebFlux extractJsonRpcResponse returns the data line answering the replayed id only, "
				+ "server notifications and foreign-id answers are ignored")
		void extract_whenStreamCarriesForeignIdAndNotification_returnsReplayedIdAnswer_webFlux(
				final @TempDir Path outputDir) throws Exception {
			final WebTestClientSkeletonGenerator generator = new WebTestClientSkeletonGenerator();

			assertCompiles("EchoRegressionTest", generator.generate(SAMPLE_CALL, "com.example.demo"), outputDir);

			assertThat(extractViaReflection(outputDir, MULTI_EVENT_STREAM, "42"))
				.as("the data line answering the replayed id must be returned")
				.isEqualTo(REPLAYED_ID_DATA);
			assertThat(extractViaReflection(outputDir, MULTI_EVENT_STREAM, "99"))
				.as("events not answering the replayed id must be ignored, the raw body returned")
				.isEqualTo(MULTI_EVENT_STREAM);
		}

		/**
		 * Invokes the generated static {@code extractJsonRpcResponse} of the class
		 * compiled into {@code outputDir} reflectively: the extraction logic is asserted
		 * as behavior of the compiled generated code, not as template text.
		 * @param outputDir directory holding the compiled generated test
		 * @param rawBody raw answer body the generated test would inspect
		 * @param requestId id of the replayed request
		 * @return the reduced JSON-RPC response the generated code computes
		 * @throws Exception when the compiled class cannot be loaded or invoked
		 */
		private static String extractViaReflection(final Path outputDir, final String rawBody, final String requestId)
				throws Exception {
			try (URLClassLoader loader = new URLClassLoader(new URL[] { outputDir.toUri().toURL() },
					JUnitTestSkeletonGeneratorTests.class.getClassLoader())) {
				final Class<?> generated = Class.forName("com.example.demo.tools.EchoRegressionTest", true, loader);
				final Method method = generated.getDeclaredMethod("extractJsonRpcResponse", String.class, String.class);
				method.setAccessible(true);
				return (String) method.invoke(null, rawBody, requestId);
			}
		}

	}

}
