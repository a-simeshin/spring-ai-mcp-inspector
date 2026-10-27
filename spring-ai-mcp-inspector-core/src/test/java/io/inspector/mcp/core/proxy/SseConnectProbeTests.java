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

package io.inspector.mcp.core.proxy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link SseConnectProbe} on a JDK {@link HttpServer} stub: the decision
 * matrix (M1-M6), the trigger gate, probe safety (GET only, no auth or custom headers,
 * body closed immediately) and failure isolation (the probe never throws).
 */
@DisplayName("SseConnectProbe")
class SseConnectProbeTests {

	private HttpServer server;

	private volatile String pathBehavior = "dead";

	private volatile long sleepMillis = 0;

	private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

	private SseConnectProbe probe;

	private URI base;

	@AfterEach
	void stopServer() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	private void startServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", (exchange) -> {
			this.requests.add(new RecordedRequest(exchange));
			final long sleep = this.sleepMillis;
			if (sleep > 0) {
				try {
					Thread.sleep(sleep);
				}
				catch (final InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			}
			final String behavior = this.pathBehavior;
			final String path = exchange.getRequestURI().getRawPath();
			try {
				respond(exchange, behavior, path);
			}
			catch (final IOException clientGone) {
				// The probe closes alive responses immediately; the JDK server throws
				// when the client disconnects mid-response. Swallow it.
			}
			finally {
				exchange.close();
			}
		});
		this.server.start();
		this.base = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/mcp");
		this.probe = new SseConnectProbe(Duration.ofMillis(500), Duration.ofMillis(900));
	}

	private static void respond(final HttpExchange exchange, final String behavior, final String path)
			throws IOException {
		if ("alive-sse".equals(behavior)) {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, -1);
		}
		else if ("alive-405-allow".equals(behavior)) {
			exchange.getResponseHeaders().add("Allow", "GET, text/event-stream");
			exchange.sendResponseHeaders(405, -1);
		}
		else if ("alive-first-only".equals(behavior) && "/sse".equals(path)) {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, -1);
		}
		else if ("500".equals(behavior)) {
			exchange.sendResponseHeaders(500, -1);
		}
		else if ("200-json".equals(behavior)) {
			final byte[] body = "{}".getBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		}
		else {
			exchange.sendResponseHeaders(404, -1);
		}
	}

	private static ProxyConnectFailure notFound() {
		return ProxyConnectFailure.classify(new McpTransportException("Server Not Found. Status code: 404"));
	}

	private static ProxyConnectFailure failedToSendMessage() {
		return new ProxyConnectFailure(ProxyConnectFailure.Reason.UNKNOWN, "failed to connect to the MCP server");
	}

	private static Throwable failedToSendMessageCause() {
		return new RuntimeException("Failed to send message: some.ResponseInfo@deadbeef");
	}

	@Nested
	@DisplayName("trigger gate")
	class Gate {

		@Test
		@DisplayName("not_found fires the probe")
		void notFoundFires() {
			assertThat(SseConnectProbe.shouldProbe(notFound(), new RuntimeException("boom"))).isTrue();
		}

		@Test
		@DisplayName("unknown with the SDK Failed to send message signature fires the probe (D3)")
		void unknownWithSignatureFires() {
			assertThat(SseConnectProbe.shouldProbe(failedToSendMessage(), failedToSendMessageCause())).isTrue();
		}

		@Test
		@DisplayName("unknown without the signature does not fire")
		void unknownWithoutSignatureDoesNotFire() {
			assertThat(SseConnectProbe.shouldProbe(failedToSendMessage(), new RuntimeException("boom"))).isFalse();
		}

		@Test
		@DisplayName("timeout, connection_refused, dns never fire (M6)")
		void otherReasonsNeverFire() {
			assertThat(SseConnectProbe
				.shouldProbe(ProxyConnectFailure.classify(new java.net.SocketTimeoutException("timed out")), null))
				.isFalse();
			assertThat(SseConnectProbe
				.shouldProbe(ProxyConnectFailure.classify(new java.net.ConnectException("refused")), null)).isFalse();
			assertThat(SseConnectProbe
				.shouldProbe(ProxyConnectFailure.classify(new java.net.UnknownHostException("nope")), null)).isFalse();
		}

	}

	@Nested
	@DisplayName("decision matrix")
	class Matrix {

		@Test
		@DisplayName("M1: 404 + SSE alive on the sibling path => sse_only with probePath /sse")
		void m1_aliveSibling() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "alive-first-only";
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe.evaluate(notFound(),
					new RuntimeException("boom"), SseConnectProbeTests.this.base);
			assertThat(verdict.outcome()).isEqualTo(SseConnectProbe.SseProbeVerdict.OUTCOME_ALIVE);
			assertThat(verdict.hint()).isNotNull();
			assertThat(verdict.hint().kind()).isEqualTo(ConnectHint.KIND_SSE_ONLY);
			assertThat(verdict.hint().probePath()).isEqualTo("/sse");
			assertThat(verdict.alivePath()).isEqualTo("/sse");
			assertThat(verdict.probedPaths()).containsExactly("/mcp", "/sse");
		}

		@Test
		@DisplayName("M1 variant: SSE alive on the same path stops the probe there")
		void m1_aliveSamePath() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "alive-sse";
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe.evaluate(notFound(),
					new RuntimeException("boom"), SseConnectProbeTests.this.base);
			assertThat(verdict.outcome()).isEqualTo(SseConnectProbe.SseProbeVerdict.OUTCOME_ALIVE);
			assertThat(verdict.hint().probePath()).isEqualTo("/mcp");
			assertThat(verdict.probedPaths()).containsExactly("/mcp");
		}

		@Test
		@DisplayName("M2: unknown + Failed-to-send-message + alive => same strong hint")
		void m2_unknownSignatureAlive() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "alive-first-only";
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe
				.evaluate(failedToSendMessage(), failedToSendMessageCause(), SseConnectProbeTests.this.base);
			assertThat(verdict.hint()).isNotNull();
			assertThat(verdict.hint().kind()).isEqualTo(ConnectHint.KIND_SSE_ONLY);
		}

		@Test
		@DisplayName("D2: 405 with Allow: text/event-stream counts as SSE-alive")
		void d2_alive405WithAllow() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "alive-405-allow";
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe.evaluate(notFound(),
					new RuntimeException("boom"), SseConnectProbeTests.this.base);
			assertThat(verdict.outcome()).isEqualTo(SseConnectProbe.SseProbeVerdict.OUTCOME_ALIVE);
			assertThat(verdict.hint().probePath()).isEqualTo("/mcp");
		}

		@Test
		@DisplayName("M3: 404 + definitively dead on both paths => maybe_sse, no alive path")
		void m3_deadBothPaths() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "404";
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe.evaluate(notFound(),
					new RuntimeException("boom"), SseConnectProbeTests.this.base);
			assertThat(verdict.outcome()).isEqualTo(SseConnectProbe.SseProbeVerdict.OUTCOME_DEAD);
			assertThat(verdict.hint()).isNotNull();
			assertThat(verdict.hint().kind()).isEqualTo(ConnectHint.KIND_MAYBE_SSE);
			assertThat(verdict.hint().probePath()).isEqualTo("/sse");
			assertThat(verdict.alivePath()).isNull();
		}

		@Test
		@DisplayName("M3 variant: 200 non-event-stream on both paths is definitively dead")
		void m3_200JsonIsDead() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "200-json";
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe.evaluate(notFound(),
					new RuntimeException("boom"), SseConnectProbeTests.this.base);
			assertThat(verdict.hint()).isNotNull();
			assertThat(verdict.hint().kind()).isEqualTo(ConnectHint.KIND_MAYBE_SSE);
		}

		@Test
		@DisplayName("M4: 405/5xx bucket + dead => no hint")
		void m4_unknownDeadNoHint() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "500";
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe
				.evaluate(failedToSendMessage(), failedToSendMessageCause(), SseConnectProbeTests.this.base);
			assertThat(verdict.outcome()).isEqualTo(SseConnectProbe.SseProbeVerdict.OUTCOME_DEAD);
			assertThat(verdict.hint()).isNull();
		}

		@Test
		@DisplayName("M5: probe timeout => timeout outcome, no hint (wall time bounded)")
		void m5_timeoutBoundedAndHintless() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "500";
			SseConnectProbeTests.this.sleepMillis = 3000;
			final long start = System.nanoTime();
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe.evaluate(notFound(),
					new RuntimeException("boom"), SseConnectProbeTests.this.base);
			final long wallMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
			assertThat(verdict.outcome()).isEqualTo(SseConnectProbe.SseProbeVerdict.OUTCOME_TIMEOUT);
			assertThat(verdict.hint()).isNull();
			// Per-path 500ms cap + scheduling slack, well under the 5s spec budget.
			assertThat(wallMillis).isLessThan(3000);
		}

		@Test
		@DisplayName("M6: gate miss => not_run, no paths touched, no hint")
		void m6_notRun() throws IOException {
			startServer();
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe.evaluate(
					ProxyConnectFailure.classify(new java.net.SocketTimeoutException("timed out")), null,
					SseConnectProbeTests.this.base);
			assertThat(verdict.outcome()).isEqualTo(SseConnectProbe.SseProbeVerdict.OUTCOME_NOT_RUN);
			assertThat(verdict.hint()).isNull();
			assertThat(verdict.probedPaths()).isEmpty();
			assertThat(SseConnectProbeTests.this.requests).isEmpty();
		}

		@Test
		@DisplayName("unresolvable target => not_run, probe never throws")
		void nullTargetNotRun() throws IOException {
			startServer();
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe.evaluate(notFound(),
					new RuntimeException("boom"), null);
			assertThat(verdict.outcome()).isEqualTo(SseConnectProbe.SseProbeVerdict.OUTCOME_NOT_RUN);
		}

	}

	@Nested
	@DisplayName("probe safety")
	class Safety {

		@Test
		@DisplayName("probe sends GET only, with no Authorization and no custom headers")
		void getOnlyNoAuthHeaders() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "alive-first-only";
			SseConnectProbeTests.this.probe.evaluate(notFound(), new RuntimeException("boom"),
					SseConnectProbeTests.this.base);
			// The probe is synchronous: both GETs (or the early stop) are on record.
			assertThat(SseConnectProbeTests.this.requests).isNotEmpty();
			for (final RecordedRequest request : SseConnectProbeTests.this.requests) {
				assertThat(request.method()).isEqualTo("GET");
				assertThat(request.hasHeader("Authorization")).isFalse();
				assertThat(request.hasHeader("X-Custom-Auth-Headers")).isFalse();
				assertThat(request.hasHeader("Cookie")).isFalse();
				assertThat(request.header("Accept")).contains("text/event-stream");
				assertThat(request.header("User-Agent")).isEqualTo("mcp-inspector-probe/1.0");
			}
		}

		@Test
		@DisplayName("a live event-stream answer does not stall the probe (body closed, early stop works)")
		void liveResponseClosedImmediately() throws IOException {
			startServer();
			SseConnectProbeTests.this.pathBehavior = "alive-first-only";
			// The probe must complete without waiting on the open stream: it stops
			// after the sibling answers and the JDK server handler closes the exchange.
			final long start = System.nanoTime();
			final SseConnectProbe.SseProbeVerdict verdict = SseConnectProbeTests.this.probe.evaluate(notFound(),
					new RuntimeException("boom"), SseConnectProbeTests.this.base);
			assertThat(verdict.outcome()).isEqualTo(SseConnectProbe.SseProbeVerdict.OUTCOME_ALIVE);
			assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(2000);
		}

		@Test
		@DisplayName("connection refused during the probe never throws to the caller")
		void neverThrows() {
			// Point the probe at a port with no listener: every path errors.
			final SseConnectProbe isolated = new SseConnectProbe(Duration.ofMillis(200), Duration.ofMillis(400));
			assertThatCode(() -> isolated.evaluate(notFound(), new RuntimeException("boom"),
					URI.create("http://127.0.0.1:1/mcp")))
				.doesNotThrowAnyException();
		}

	}

	@Nested
	@DisplayName("sibling path resolution")
	class Sibling {

		@Test
		@DisplayName("/mcp -> /sse")
		void siblingOfMcp() {
			assertThat(SseConnectProbe.siblingSseUri(URI.create("http://h:1/mcp")))
				.isEqualTo(URI.create("http://h:1/sse"));
		}

		@Test
		@DisplayName("/api/mcp -> /api/sse (last segment replaced)")
		void siblingOfNestedPath() {
			assertThat(SseConnectProbe.siblingSseUri(URI.create("http://h:1/api/mcp")))
				.isEqualTo(URI.create("http://h:1/api/sse"));
		}

		@Test
		@DisplayName("/ -> /sse")
		void siblingOfRoot() {
			assertThat(SseConnectProbe.siblingSseUri(URI.create("http://h:1/")))
				.isEqualTo(URI.create("http://h:1/sse"));
		}

		@Test
		@DisplayName("query and fragment are dropped")
		void queryDropped() {
			assertThat(SseConnectProbe.siblingSseUri(URI.create("http://h:1/mcp?x=1#frag")))
				.isEqualTo(URI.create("http://h:1/sse"));
		}

		@Test
		@DisplayName("/sse -> /sse (no further probing loop)")
		void siblingOfSse() {
			assertThat(SseConnectProbe.siblingSseUri(URI.create("http://h:1/sse")))
				.isEqualTo(URI.create("http://h:1/sse"));
		}

	}

	@Nested
	@DisplayName("classify signatures")
	class ClassifySignatures {

		@Test
		@DisplayName("the SDK 404 message classifies as NOT_FOUND (F3)")
		void sdk404ClassifiesNotFound() {
			assertThat(notFound().reason()).isEqualTo(ProxyConnectFailure.Reason.NOT_FOUND);
		}

		@Test
		@DisplayName("the SDK 405 bucket message carries the Failed to send message signature (F4/D3)")
		void sdk405SignatureDetected() {
			assertThat(SseConnectProbe.shouldProbe(failedToSendMessage(), failedToSendMessageCause())).isTrue();
		}

	}

	/**
	 * One probed request captured by the stub server.
	 */
	private static final class RecordedRequest {

		private final String method;

		private final List<String> headerNames = new CopyOnWriteArrayList<>();

		private final List<String> headerValues = new CopyOnWriteArrayList<>();

		RecordedRequest(final HttpExchange exchange) {
			this.method = exchange.getRequestMethod();
			exchange.getRequestHeaders().forEach((name, values) -> {
				this.headerNames.add(name);
				this.headerValues.addAll(values);
			});
		}

		private String method() {
			return this.method;
		}

		private boolean hasHeader(final String name) {
			return this.headerNames.stream().anyMatch((existing) -> existing.equalsIgnoreCase(name));
		}

		private String header(final String name) {
			final List<String> values = new CopyOnWriteArrayList<>();
			for (int i = 0; i < this.headerNames.size(); i++) {
				if (this.headerNames.get(i).equalsIgnoreCase(name)) {
					values.add(this.headerValues.get(i));
				}
			}
			return values.isEmpty() ? null : String.join(",", values);
		}

	}

}
