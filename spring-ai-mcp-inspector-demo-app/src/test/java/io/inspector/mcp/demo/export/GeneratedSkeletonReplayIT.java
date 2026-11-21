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
package io.inspector.mcp.demo.export;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
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

import io.inspector.mcp.core.export.JUnitTestSkeletonGenerator;
import io.inspector.mcp.core.export.MockMvcSkeletonGenerator;
import io.inspector.mcp.core.export.ToolCallRecord;
import io.inspector.mcp.core.export.WebTestClientSkeletonGenerator;
import io.inspector.mcp.demo.proxy.ProxyAppHarness;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round trip for the generated regression skeleton (PR #415 rework, issue #80): a call is
 * recorded against the live demo MCP server, the skeleton for this stack is generated and
 * compiled, and the generated {@code @SpringBootTest} is executed through the JUnit
 * Platform Launcher. The generated test performs the streamable-HTTP handshake itself
 * (initialize, {@code notifications/initialized}, {@code Mcp-Session-Id}), sends the
 * {@code Accept: application/json, text/event-stream} pair the MCP SDK transports
 * require, extracts the JSON-RPC answer from a streamed SSE body, and must reproduce the
 * recorded etalon on the demo application.
 *
 * <p>
 * This IT runs on both stacks (the module supplies the stack): WebMVC boots the generated
 * MockMvc variant, WebFlux the WebTestClient variant. A mutant that drops the Accept
 * header turns the generated replay into a 400 and fails here.
 * </p>
 */
@Epic("Demo MCP Server")
@Feature("Generated regression skeleton")
class GeneratedSkeletonReplayIT {

	private static final String INITIALIZE_JSON = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"generated-skeleton-replay-it\",\"version\":\"1.0\"}}}";

	private static final String INITIALIZED_NOTIFICATION_JSON = "{\"jsonrpc\":\"2.0\","
			+ "\"method\":\"notifications/initialized\"}";

	private static final String REQUEST_JSON = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hello regression\"}}}";

	private static final String TEST_CLASS_NAME = "io.inspector.mcp.demo.tools.EchoRegressionTest";

	@Test
	@Story("Generated skeleton replay")
	@Severity(SeverityLevel.CRITICAL)
	@DisplayName("generatedSkeletonReplaysCapturedEchoCall - recorded from the live server, replayed green")
	@Description("Records a real echo tools/call from a running demo app (STREAMABLE), generates the skeleton for "
			+ "this stack, compiles it with javac, and runs it through the JUnit Platform Launcher: the generated "
			+ "@SpringBootTest replays the call against the demo MCP endpoint and must reproduce the etalon.")
	void generatedSkeleton_replaysCapturedEchoCall_againstDemoServer(@TempDir Path generatedClasses) throws Exception {
		// given: a captured (recorded) echo call against the live demo server
		final String recordedResponse = recordEchoCall();
		final ToolCallRecord call = new ToolCallRecord("echo", "tools/call", REQUEST_JSON, recordedResponse,
				Instant.now().toString());

		// when: the stack-specific skeleton is generated and compiled
		final JUnitTestSkeletonGenerator generator = "webflux".equals(ProxyAppHarness.stack())
				? new WebTestClientSkeletonGenerator() : new MockMvcSkeletonGenerator();
		final String source = generator.generate(call, "io.inspector.mcp.demo");
		compileGeneratedSource("EchoRegressionTest", source, generatedClasses);

		// then: the generated test boots the demo app and replays the captured call
		runGeneratedTest(generatedClasses);
	}

	/**
	 * Response body recorded from the live demo server: the JSON-RPC answer of the echo
	 * call, reduced out of an SSE stream when the server streamed it.
	 */
	private static String recordEchoCall() throws Exception {
		final ConfigurableApplicationContext app = ProxyAppHarness.start("STREAMABLE", false, null);
		try {
			final String base = "http://127.0.0.1:" + ProxyAppHarness.port(app) + "/mcp";
			final HttpClient client = HttpClient.newHttpClient();

			// initialize: on a stateful server the response carries Mcp-Session-Id
			HttpResponse<String> response = client.send(post(base, INITIALIZE_JSON, null),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertThat(response.statusCode()).as("initialize against the demo server must succeed").isEqualTo(200);
			final String session = response.headers().firstValue("mcp-session-id").orElse(null);
			if (session != null) {
				response = client.send(post(base, INITIALIZED_NOTIFICATION_JSON, session),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				assertThat(response.statusCode()).as("notifications/initialized must be accepted").isEqualTo(202);
			}

			// the captured call itself; its raw answer is the etalon for the generated
			// test
			response = client.send(post(base, REQUEST_JSON, session),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertThat(response.statusCode()).as("echo tools/call against the demo server must succeed").isEqualTo(200);
			return jsonRpcAnswer(response.body(), "42");
		}
		finally {
			app.close();
		}
	}

	/**
	 * Builds the POST a recording client sends: JSON body, the Accept pair the MCP SDK
	 * transports require, and the session id once the handshake returned one.
	 */
	private static HttpRequest post(String url, String body, String session) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (session != null) {
			builder = builder.header("Mcp-Session-Id", session);
		}
		return builder.build();
	}

	/**
	 * Recording-side reduction of a raw answer to the JSON-RPC response: JSON bodies pass
	 * through, SSE streams are reduced to the {@code data:} line answering the given
	 * request id. Minimal on purpose: the full replay logic lives in the generated test,
	 * this only turns the captured wire answer into an etalon.
	 */
	private static String jsonRpcAnswer(String rawBody, String requestId) {
		if (!rawBody.contains("data:")) {
			return rawBody;
		}
		for (String line : rawBody.split("\\r?\\n")) {
			final String trimmed = line.trim();
			if (!trimmed.startsWith("data:")) {
				continue;
			}
			final String data = trimmed.substring(5).trim();
			if (data.startsWith("{") && data.contains("\"result\"") && data.contains("\"id\":" + requestId)) {
				return data;
			}
		}
		return rawBody;
	}

	/**
	 * Compiles the generated source with the platform compiler against this module's test
	 * classpath: exactly the jars a generated regression test may reference.
	 * @param className simple name of the generated class
	 * @param source generated Java source
	 * @param outputDir directory the class files are written to
	 * @throws Exception when the compiler cannot be configured
	 */
	private static void compileGeneratedSource(String className, String source, Path outputDir) throws Exception {
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
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
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

	/**
	 * Runs the compiled generated test in this JVM through the JUnit Platform Launcher:
	 * the generated {@code @SpringBootTest} boots the demo application (mock web
	 * environment of this stack) and replays the captured call against it.
	 * @param generatedClasses directory holding the compiled generated test
	 * @throws Exception when the loader cannot be opened
	 */
	private static void runGeneratedTest(Path generatedClasses) throws Exception {
		try (URLClassLoader loader = new URLClassLoader(new URL[] { generatedClasses.toUri().toURL() },
				GeneratedSkeletonReplayIT.class.getClassLoader())) {
			final Thread currentThread = Thread.currentThread();
			final ClassLoader previous = currentThread.getContextClassLoader();
			currentThread.setContextClassLoader(loader);
			try {
				final LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
					.selectors(DiscoverySelectors.selectClass(TEST_CLASS_NAME))
					.build();
				final Launcher launcher = LauncherFactory.create();
				final SummaryGeneratingListener listener = new SummaryGeneratingListener();
				launcher.execute(request, listener);
				final TestExecutionSummary summary = listener.getSummary();
				final String failures = summary.getFailures()
					.stream()
					.map((failure) -> failure.getTestIdentifier().getDisplayName() + ": "
							+ String.valueOf(failure.getException()))
					.collect(Collectors.joining("\n"));
				assertThat(summary.getTotalFailureCount())
					.as("generated regression test must pass on %s, failures:\n%s", ProxyAppHarness.stack(), failures)
					.isZero();
			}
			finally {
				currentThread.setContextClassLoader(previous);
			}
		}
	}

}
