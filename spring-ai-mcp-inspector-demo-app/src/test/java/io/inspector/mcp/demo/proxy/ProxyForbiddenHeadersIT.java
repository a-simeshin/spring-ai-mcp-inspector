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
package io.inspector.mcp.demo.proxy;

import java.io.OutputStream;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starter-level integration tests for the {@code x-custom-auth-headers} wire contract,
 * driven through the real proxy endpoints ({@code POST /mcp-inspector-api/mcp} and
 * {@code GET /mcp-inspector-api/sse}) against a real JDK {@code HttpServer} upstream, on
 * both webmvc and webflux stacks (this test-jar runs once per stack).
 *
 * <p>
 * The wire contract is a JSON array of header names, exactly as the inspector UI sends it
 * ({@code JSON.stringify(names)} in the vendored client). The proxy used to split the raw
 * value on commas, so every UI-sent name arrived wrapped in JSON brackets and quotes, the
 * value lookups found nothing, and custom headers were silently dropped.
 *
 * <p>
 * Proves, per stack:
 *
 * <ol>
 * <li>custom header values containing commas and spaces reach the upstream verbatim (they
 * were silently dropped or corrupted before);</li>
 * <li>a malformed payload (the legacy comma-separated shape, or non-array JSON) is an
 * explicit 400, not a silent drop;</li>
 * <li>a restricted header name (e.g. {@code host}) fails at transport build time with a
 * diagnostic response, and the upstream receives no request at all.</li>
 * </ol>
 */
@Epic("Inspector Proxy")
@Feature("Custom header forwarding: integration")
class ProxyForbiddenHeadersIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/** Per-request wall-clock budget. */
	private static final Duration BUDGET = Duration.ofSeconds(30);

	/** MCP protocol version. Must match the client's initialize frame. */
	private static final String PROTOCOL_VERSION = "2025-11-25";

	private ConfigurableApplicationContext app;

	/** Mock upstream: records every request's headers. */
	private HttpServer upstream;

	private int upstreamPort;

	/** Header maps received by the mock upstream, one entry per request. */
	private final List<Map<String, List<String>>> receivedHeaders = new CopyOnWriteArrayList<>();

	@AfterEach
	void tearDown() {
		if (app != null) {
			try {
				app.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			app = null;
		}
		if (upstream != null) {
			upstream.stop(0);
		}
	}

	/**
	 * Starts the app and a mock upstream MCP server that answers every POST with a valid
	 * JSON-RPC initialize response (enough for the proxy to relay a full round trip).
	 */
	private int startAppAndUpstream() throws Exception {
		upstream = HttpServer.create(new InetSocketAddress(0), 0);
		upstream.createContext("/mcp", (exchange) -> {
			final Map<String, List<String>> headers = new HashMap<>();
			for (final Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
				headers.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			}
			receivedHeaders.add(headers);
			if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				// The streamable transport's listening GET: keep it open and empty.
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
				exchange.sendResponseHeaders(200, 0);
				return;
			}
			final String response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{" + "\"protocolVersion\":\""
					+ PROTOCOL_VERSION + "\",\"capabilities\":{},\"serverInfo\":{"
					+ "\"name\":\"mock\",\"version\":\"1.0.0\"}}}";
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response.getBytes(StandardCharsets.UTF_8));
			}
		});
		upstream.setExecutor(Executors.newSingleThreadExecutor());
		upstream.start();
		upstreamPort = upstream.getAddress().getPort();

		app = ProxyAppHarness.start("STREAMABLE", false, null);
		return ProxyAppHarness.port(app);
	}

	private String upstreamUrl() {
		return "http://127.0.0.1:" + upstreamPort + "/mcp";
	}

	/** Builds a JSON-RPC initialize frame. */
	private static String buildInit() {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", PROTOCOL_VERSION);
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "forbidden-headers-it");
		info.put("version", "0.1.0");
		return MAPPER.writeValueAsString(init);
	}

	private HttpRequest.Builder postToProxy(final int port, final String... headers) {
		final HttpRequest.Builder builder = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/mcp?url="
					+ URLEncoder.encode(upstreamUrl(), StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(buildInit()));
		for (int i = 0; i + 1 < headers.length; i += 2) {
			builder.header(headers[i], headers[i + 1]);
		}
		return builder;
	}

	/**
	 * Reads at most 2048 bytes of the body and closes the stream, under a hard deadline:
	 * a mutant that answers 200 with an endless SSE stream must fail the run, not hang
	 * it. Called only on paths the status check has already approved.
	 */
	private static String drainBounded(final java.io.InputStream body) {
		if (body == null) {
			return "";
		}
		final java.util.concurrent.ExecutorService drainer = Executors.newSingleThreadExecutor();
		try {
			final java.util.concurrent.Future<String> read = drainer.submit(() -> {
				try (body) {
					return new String(body.readNBytes(2048), StandardCharsets.UTF_8);
				}
				catch (Exception ex) {
					return "<unreadable: " + ex + ">";
				}
			});
			return read.get(5, TimeUnit.SECONDS);
		}
		catch (Exception deadlineHit) {
			// release the connection even when the read never ends
			try {
				body.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			return "<drain hit its deadline: " + deadlineHit + ">";
		}
		finally {
			drainer.shutdownNow();
		}
	}

	private static String firstValue(final Map<String, List<String>> headers, final String name) {
		for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
				return entry.getValue().get(0);
			}
		}
		return null;
	}

	@Nested
	@DisplayName("Streamable-HTTP proxy endpoint (POST /mcp)")
	class StreamableEndpoint {

		@Test
		@DisplayName("JSON array payload: custom header values with commas and spaces reach the upstream verbatim")
		@Story("Allowed header forwarding")
		@Severity(SeverityLevel.CRITICAL)
		@Description("x-custom-auth-headers carries [\"X-Custom-A\",\"X-Custom-B\"] exactly as the UI sends it; "
				+ "values containing commas and spaces are forwarded to the upstream untouched on " + "both stacks")
		void jsonArrayCustomHeaders_reachUpstreamVerbatim() throws Exception {
			// given
			final int port = startAppAndUpstream();

			// when
			// the exact wire shape the UI produces (JSON.stringify of the names)
			final HttpResponse<String> response = HTTP.send(
					postToProxy(port, "x-custom-auth-headers", "[\"X-Custom-A\", \"X-Custom-B\"]", "X-Custom-A",
							"comma, separated , value", "X-Custom-B", "space separated value")
						.build(),
					HttpResponse.BodyHandlers.ofString());

			// then
			// the session opens and the initialize response comes back
			assertThat(response.statusCode())
				.as("JSON-array custom headers must open a session on %s, body=%s", ProxyAppHarness.stack(),
						response.body())
				.isEqualTo(200);
			assertThat(response.headers().firstValue("mcp-session-id").orElse(null))
				.as("session id must be issued on %s", ProxyAppHarness.stack())
				.isNotBlank();

			// and the upstream saw both custom headers verbatim
			Awaitility.await("upstream receives the custom headers on " + ProxyAppHarness.stack())
				.atMost(Duration.ofSeconds(20))
				.pollInterval(Duration.ofMillis(100))
				.until(() -> receivedHeaders.stream()
					.anyMatch((h) -> "comma, separated , value".equals(firstValue(h, "X-Custom-A"))));
			assertThat(receivedHeaders.stream()
				.anyMatch((h) -> "space separated value".equals(firstValue(h, "X-Custom-B"))))
				.as("X-Custom-B must carry its value verbatim on %s", ProxyAppHarness.stack())
				.isTrue();
		}

		@Test
		@DisplayName("legacy comma-separated payload is an explicit 400, upstream untouched")
		@Story("Malformed payload rejection")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The old split(',')" + "-era shape (bare comma-separated names) is rejected with 400 on both "
				+ "stacks: a client bug must be visible, not a session that silently forwards no custom headers")
		void legacyCommaSeparatedPayload_rejectedWith400() throws Exception {
			// given
			final int port = startAppAndUpstream();

			// when
			final HttpResponse<String> response = HTTP
				.send(postToProxy(port, "x-custom-auth-headers", "X-Custom-A, X-Custom-B", "X-Custom-A", "value-a")
					.build(), HttpResponse.BodyHandlers.ofString());

			// then
			// explicit 400, upstream untouched
			assertThat(response.statusCode())
				.as("legacy comma-separated payload must be 400 on %s, body=%s", ProxyAppHarness.stack(),
						response.body())
				.isEqualTo(400);

			// and the upstream received nothing at all
			assertThat(receivedHeaders).as("upstream must receive no request on %s", ProxyAppHarness.stack()).isEmpty();
		}

		@Test
		@DisplayName("non-array JSON payload is an explicit 400")
		@Story("Malformed payload rejection")
		@Severity(SeverityLevel.NORMAL)
		@Description("A JSON object instead of the array is rejected with 400 on both stacks")
		void nonArrayJsonPayload_rejectedWith400() throws Exception {
			// given
			final int port = startAppAndUpstream();

			// when
			final HttpResponse<String> response = HTTP.send(
					postToProxy(port, "x-custom-auth-headers", "{\"names\":[\"X-Custom-A\"]}").build(),
					HttpResponse.BodyHandlers.ofString());

			// then
			assertThat(response.statusCode()).as("non-array payload on %s", ProxyAppHarness.stack()).isEqualTo(400);
			assertThat(receivedHeaders).isEmpty();
		}

		@Test
		@DisplayName("restricted header name fails before any upstream I/O")
		@Story("Restricted header prevention")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Naming the restricted 'host' header (present on every request, so its inbound value always "
				+ "resolves) fails at transport build time with a diagnostic non-2xx response; the upstream "
				+ "receives no request")
		void restrictedHeaderName_failsBeforeAnyUpstreamIo() throws Exception {
			// given
			final int port = startAppAndUpstream();

			// when
			final HttpResponse<String> response = HTTP.send(
					postToProxy(port, "x-custom-auth-headers", "[\"host\"]").build(),
					HttpResponse.BodyHandlers.ofString());

			// then
			// a classified connect failure (502), never a silent drop
			assertThat(response.statusCode())
				.as("restricted header name must fail loudly on %s, body=%s", ProxyAppHarness.stack(), response.body())
				.isEqualTo(502);

			// and the upstream received nothing at all
			assertThat(receivedHeaders).as("upstream must receive no request on %s", ProxyAppHarness.stack()).isEmpty();
		}

	}

	@Nested
	@DisplayName("SSE proxy endpoint (GET /sse)")
	class SseEndpoint {

		@Test
		@DisplayName("JSON array payload: custom headers reach the upstream through the SSE session")
		@Story("Allowed header forwarding")
		@Severity(SeverityLevel.CRITICAL)
		@Description("GET /sse?transportType=streamable-http with a JSON-array x-custom-auth-headers opens the "
				+ "session and forwards the named custom headers to the upstream on both stacks")
		void jsonArrayCustomHeaders_reachUpstreamThroughSseSession() throws Exception {
			// given
			final int port = startAppAndUpstream();
			final String sseUrl = "http://127.0.0.1:" + port
					+ "/mcp-inspector-api/sse?transportType=streamable-http&url="
					+ URLEncoder.encode(upstreamUrl(), StandardCharsets.UTF_8);

			// when
			// open the SSE session with the UI's exact wire shape, reading frames
			// in the background; then push an initialize frame into the session
			final CopyOnWriteArrayList<String> frames = new CopyOnWriteArrayList<>();
			final Thread sseReader = new Thread(() -> {
				try {
					final HttpRequest request = HttpRequest.newBuilder(URI.create(sseUrl))
						.timeout(BUDGET)
						.header("Accept", "text/event-stream")
						.header("x-custom-auth-headers", "[\"X-Custom-A\"]")
						.header("X-Custom-A", "sse, comma , value")
						.GET()
						.build();
					final HttpResponse<java.io.InputStream> response = HTTP.send(request,
							HttpResponse.BodyHandlers.ofInputStream());
					try (var reader = new java.io.BufferedReader(
							new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
						String line;
						while ((line = reader.readLine()) != null) {
							if (line.startsWith("data:")) {
								frames.add(line.substring(5).trim());
							}
						}
					}
				}
				catch (Exception ignored) {
					/* stream teardown ends up here; assertions poll the buffers */
				}
			}, "sse-reader-" + ProxyAppHarness.stack());
			sseReader.setDaemon(true);
			sseReader.start();

			// the endpoint prologue carries the message endpoint with the session id
			Awaitility.await("endpoint prologue frame on " + ProxyAppHarness.stack())
				.atMost(Duration.ofSeconds(15))
				.pollInterval(Duration.ofMillis(50))
				.until(() -> frames.stream().anyMatch((d) -> d.startsWith("/mcp-inspector-api/message?sessionId=")));
			final String endpoint = frames.stream()
				.filter((d) -> d.startsWith("/mcp-inspector-api/message?sessionId="))
				.findFirst()
				.orElseThrow();
			final String sessionId = endpoint.substring(endpoint.indexOf("sessionId=") + "sessionId=".length());

			// push the initialize frame: this makes the proxy POST to the upstream with
			// the custom headers attached at transport build time
			final HttpRequest initialize = HttpRequest
				.newBuilder(
						URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/message?sessionId=" + sessionId))
				.timeout(BUDGET)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(buildInit()))
				.build();
			HTTP.send(initialize, HttpResponse.BodyHandlers.ofString());

			// then
			// the upstream received the custom header verbatim
			Awaitility.await("upstream receives the custom header on " + ProxyAppHarness.stack())
				.atMost(Duration.ofSeconds(20))
				.pollInterval(Duration.ofMillis(100))
				.until(() -> receivedHeaders.stream()
					.anyMatch((h) -> "sse, comma , value".equals(firstValue(h, "X-Custom-A"))));
		}

		@Test
		@DisplayName("legacy comma-separated payload is an explicit 400 before any stream opens")
		@Story("Malformed payload rejection")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The old split(',')-era shape on GET /sse is rejected with 400 on both stacks")
		void legacyCommaSeparatedPayload_rejectedWith400() throws Exception {
			// given
			final int port = startAppAndUpstream();
			final String sseUrl = "http://127.0.0.1:" + port + "/mcp-inspector-api/sse?transportType=sse&url="
					+ URLEncoder.encode(upstreamUrl(), StandardCharsets.UTF_8);

			// when
			// read the status first and touch the body only after it is approved: a
			// broken proxy answers 200 with an endless SSE stream here, and reading that
			// body before the status check would hang the run instead of failing it
			final HttpResponse<java.io.InputStream> response = HTTP.send(HttpRequest.newBuilder(URI.create(sseUrl))
				.timeout(BUDGET)
				.header("Accept", "text/event-stream")
				.header("x-custom-auth-headers", "X-Custom-A, X-Custom-B")
				.header("X-Custom-A", "value-a")
				.GET()
				.build(), HttpResponse.BodyHandlers.ofInputStream());

			// then
			// fail fast on the status before any body read
			assertThat(response.statusCode())
				.as("legacy comma-separated payload must be 400 on %s", ProxyAppHarness.stack())
				.isEqualTo(400);
			// the body is drained only on the approved success path, to release the
			// connection; its shape is pinned at unit level
			drainBounded(response.body());
			// and the upstream received nothing at all
			assertThat(receivedHeaders).as("upstream must receive no request on %s", ProxyAppHarness.stack()).isEmpty();
		}

	}

}
