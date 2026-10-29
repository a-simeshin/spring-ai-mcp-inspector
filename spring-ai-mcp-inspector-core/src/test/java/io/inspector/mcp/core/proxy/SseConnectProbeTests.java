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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link SseConnectProbe}: the trigger gate, the two-path probe sequence
 * and the decision matrix M1-M6 from spec card t_42b235d6 (issue #250), exercised against
 * a plain JDK {@link HttpServer} stub (no new test dependencies).
 */
@Epic("MCP Inspector Core")
@Feature("SSE connect probe")
class SseConnectProbeTests {

	private HttpServer server;

	private final Map<String, StubAnswer> answers = new ConcurrentHashMap<>();

	private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();

	@BeforeEach
	void setUp() throws Exception {
		this.requests.clear();
		this.answers.clear();
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::dispatch);
		this.server.start();
	}

	@AfterEach
	void tearDown() {
		this.server.stop(0);
	}

	private void dispatch(final HttpExchange exchange) {
		final String path = exchange.getRequestURI().getPath();
		this.requests.add(new CapturedRequest(exchange.getRequestMethod(),
				exchange.getRequestHeaders().getFirst("Accept"), exchange.getRequestHeaders().getFirst("User-Agent"),
				exchange.getRequestHeaders().getFirst("Authorization")));
		final StubAnswer answer = this.answers.getOrDefault(path, new StubAnswer(404, Map.of(), 0));
		try (exchange) {
			if (answer.delayMs() > 0) {
				Thread.sleep(answer.delayMs());
			}
			answer.headers().forEach(exchange.getResponseHeaders()::add);
			if (answer.status() == 404 || answer.status() == 405) {
				exchange.sendResponseHeaders(answer.status(), -1);
			}
			else {
				final byte[] body = "stub".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(answer.status(), body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			}
		}
		catch (final Exception ex) {
			// stub: the probe must tolerate half-open responses
		}
	}

	private URI url(final String path) {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + path);
	}

	private static ProxyConnectFailure notFound() {
		return new ProxyConnectFailure(ProxyConnectFailure.Reason.NOT_FOUND,
				"server responded with 404: check the URL");
	}

	private static ProxyConnectFailure unknownFailedToSend() {
		return new ProxyConnectFailure(ProxyConnectFailure.Reason.UNKNOWN, "failed to connect to the MCP server");
	}

	private void stub(final String path, final int status, final String headerName, final String headerValue) {
		this.answers.put(path,
				new StubAnswer(status, (headerName != null) ? Map.of(headerName, headerValue) : Map.of(), 0));
	}

	private void stub(final String path, final int status, final String headerName, final String headerValue,
			final long delayMs) {
		this.answers.put(path,
				new StubAnswer(status, (headerName != null) ? Map.of(headerName, headerValue) : Map.of(), delayMs));
	}

	@Nested
	@DisplayName("Trigger gate (spec section 3)")
	class Gate {

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("shouldProbe() opens for NOT_FOUND and for UNKNOWN with the Failed-to-send-message signature")
		void shouldProbe_openForNotFoundAndUnknownSignature() {
			assertThat(SseConnectProbe.shouldProbe(notFound(), null)).isTrue();
			assertThat(SseConnectProbe.shouldProbe(unknownFailedToSend(),
					new RuntimeException("Failed to send message: 405")))
				.isTrue();
			assertThat(SseConnectProbe.shouldProbe(unknownFailedToSend(),
					new RuntimeException("wrapped", new IllegalStateException("Failed to send message: 500"))))
				.isTrue();
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("shouldProbe() stays closed for every non-trigger reason and for UNKNOWN without the signature (M6)")
		void shouldProbe_closedForNonTriggers() {
			assertThat(SseConnectProbe.shouldProbe(unknownFailedToSend(), new RuntimeException("connect failed")))
				.isFalse();
			for (final ProxyConnectFailure.Reason reason : List.of(ProxyConnectFailure.Reason.TIMEOUT,
					ProxyConnectFailure.Reason.CONNECTION_REFUSED, ProxyConnectFailure.Reason.DNS)) {
				assertThat(SseConnectProbe.shouldProbe(new ProxyConnectFailure(reason, "x"), null)).isFalse();
			}
		}

		@Test
		@Severity(SeverityLevel.NORMAL)
		@Description("diagnose() with a null target never probes (probe not_run)")
		void diagnose_nullTarget_noProbe() {
			final SseConnectProbe.Diagnosis diagnosis = SseConnectProbe.diagnose(notFound(), null, null);
			assertThat(diagnosis.probe()).isNull();
			assertThat(diagnosis.hint()).isNull();
			assertThat(diagnosis.probeOutcome()).isEqualTo("not_run");
		}

		@Test
		@Severity(SeverityLevel.NORMAL)
		@Description("diagnose() with a closed gate never touches the network (zero requests)")
		void diagnose_closedGate_zeroRequests() {
			final SseConnectProbe.Diagnosis diagnosis = SseConnectProbe.diagnose(unknownFailedToSend(),
					new RuntimeException("connect failed"), url("/mcp"));
			assertThat(diagnosis.probe()).isNull();
			assertThat(SseConnectProbeTests.this.requests).isEmpty();
		}

	}

	@Nested
	@DisplayName("Decision matrix (spec section 5)")
	class Matrix {

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M1: 404 with SSE alive on the sibling /sse path yields sse_only hint with probePath=/sse")
		void m1_notFound_siblingSseAlive_sseOnly() {
			stub("/mcp", 404, null, null);
			stub("/sse", 200, "Content-Type", "text/event-stream");
			final SseConnectProbe.Diagnosis diagnosis = SseConnectProbe.diagnose(notFound(), null, url("/mcp"));
			assertThat(diagnosis.probe().outcome()).isEqualTo(SseConnectProbe.Outcome.ALIVE);
			assertThat(diagnosis.probe().alivePath()).isEqualTo("/sse");
			assertThat(diagnosis.hint()).isNotNull();
			assertThat(diagnosis.hint().kind().wire()).isEqualTo("sse_only");
			assertThat(diagnosis.hint().probePath()).isEqualTo("/sse");
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M1 variant: SSE alive on the SAME path (non-Spring server) yields sse_only with the same path and stops there")
		void m1_notFound_samePathAlive_sseOnlySamePath() {
			stub("/mcp", 200, "Content-Type", "text/event-stream");
			final SseConnectProbe.Diagnosis diagnosis = SseConnectProbe.diagnose(notFound(), null, url("/mcp"));
			assertThat(diagnosis.probe().outcome()).isEqualTo(SseConnectProbe.Outcome.ALIVE);
			assertThat(diagnosis.probe().alivePath()).isEqualTo("/mcp");
			assertThat(diagnosis.hint().kind().wire()).isEqualTo("sse_only");
			assertThat(diagnosis.probe().probedPaths()).containsExactly("/mcp");
			assertThat(SseConnectProbeTests.this.requests).hasSize(1);
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M2/D2: 405 with Allow: text/event-stream on the same path counts as alive without further requests")
		void m2_unknownSignature_allowHeaderAlive_sseOnly() {
			stub("/mcp", 405, "Allow", "text/event-stream");
			final SseConnectProbe.Diagnosis diagnosis = SseConnectProbe.diagnose(unknownFailedToSend(),
					new RuntimeException("Failed to send message: 405"), url("/mcp"));
			assertThat(diagnosis.probe().outcome()).isEqualTo(SseConnectProbe.Outcome.ALIVE);
			assertThat(diagnosis.hint()).isNotNull();
			assertThat(diagnosis.hint().kind().wire()).isEqualTo("sse_only");
			assertThat(diagnosis.hint().probePath()).isEqualTo("/mcp");
			assertThat(SseConnectProbeTests.this.requests).hasSize(1);
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M3: 404 with definitively dead SSE on both paths yields maybe_sse (weak hint)")
		void m3_notFound_deadBothPaths_maybeSse() {
			stub("/mcp", 404, null, null);
			stub("/sse", 404, null, null);
			final SseConnectProbe.Diagnosis diagnosis = SseConnectProbe.diagnose(notFound(), null, url("/mcp"));
			assertThat(diagnosis.probe().outcome()).isEqualTo(SseConnectProbe.Outcome.DEAD);
			assertThat(diagnosis.hint()).isNotNull();
			assertThat(diagnosis.hint().kind().wire()).isEqualTo("maybe_sse");
			assertThat(diagnosis.hint().probePath()).isEqualTo("/sse");
			assertThat(SseConnectProbeTests.this.requests).hasSize(2);
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M4: 500-bucket (UNKNOWN signature) with dead SSE yields NO hint")
		void m4_unknownSignature_dead_noHint() {
			stub("/mcp", 404, null, null);
			stub("/sse", 404, null, null);
			final SseConnectProbe.Diagnosis diagnosis = SseConnectProbe.diagnose(unknownFailedToSend(),
					new RuntimeException("Failed to send message: 500"), url("/mcp"));
			assertThat(diagnosis.probe()).isNotNull();
			assertThat(diagnosis.hint()).isNull();
		}

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("M5: probe timeout yields no hint, never throws, and stays inside the sequence budget")
		void m5_probeTimeout_originalErrorPreserved() {
			stub("/mcp", 200, "Content-Type", "text/event-stream", 4000);
			final long start = System.nanoTime();
			final SseConnectProbe.Diagnosis diagnosis = SseConnectProbe.diagnose(notFound(), null, url("/mcp"));
			final long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
			assertThat(diagnosis.hint()).isNull();
			assertThat(diagnosis.probe().outcome()).isEqualTo(SseConnectProbe.Outcome.TIMEOUT);
			// hard cap 5 s plus scheduler slack; a regression to an unbounded wait shows
			// here
			assertThat(elapsedMs).isLessThan(9000);
		}

		@Test
		@Severity(SeverityLevel.NORMAL)
		@Description("2xx without text/event-stream is NOT alive (definitively dead)")
		void probe_2xxWithoutEventStream_dead() {
			stub("/mcp", 200, "Content-Type", "application/json");
			stub("/sse", 200, "Content-Type", "application/json");
			final SseConnectProbe.ProbeResult result = SseConnectProbe.probe(url("/mcp"));
			assertThat(result.outcome()).isEqualTo(SseConnectProbe.Outcome.DEAD);
		}

		@Test
		@Severity(SeverityLevel.NORMAL)
		@Description("The probe never throws, whatever the target is")
		void probe_neverThrows() {
			assertThatCode(() -> SseConnectProbe.probe(URI.create("http://127.0.0.1:1/mcp")))
				.doesNotThrowAnyException();
			assertThatCode(() -> SseConnectProbe.probe(URI.create("http://no-such-host.invalid/mcp")))
				.doesNotThrowAnyException();
		}

	}

	@Nested
	@DisplayName("Probe safety (spec section 4, AC 7)")
	class Safety {

		@Test
		@Severity(SeverityLevel.CRITICAL)
		@Description("The probe sends GET with Accept: text/event-stream and the probe User-Agent, and no Authorization header")
		void probe_headersAreClean() {
			stub("/mcp", 404, null, null);
			stub("/sse", 404, null, null);
			SseConnectProbe.probe(url("/mcp"));
			assertThat(SseConnectProbeTests.this.requests).hasSize(2);
			for (final CapturedRequest request : SseConnectProbeTests.this.requests) {
				assertThat(request.method()).isEqualTo("GET");
				assertThat(request.accept()).isEqualTo("text/event-stream");
				assertThat(request.userAgent()).isEqualTo("mcp-inspector-probe/1.0");
				assertThat(request.authorization()).isNull();
			}
		}

		@Test
		@Severity(SeverityLevel.NORMAL)
		@Description("The probe stops at the first alive path (one probe sequence, no extra traffic)")
		void probe_stopsAtFirstAlivePath() {
			stub("/mcp", 200, "Content-Type", "text/event-stream");
			stub("/sse", 200, "Content-Type", "text/event-stream");
			final SseConnectProbe.ProbeResult result = SseConnectProbe.probe(url("/mcp"));
			assertThat(result.outcome()).isEqualTo(SseConnectProbe.Outcome.ALIVE);
			assertThat(result.probedPaths()).containsExactly("/mcp");
			assertThat(SseConnectProbeTests.this.requests).hasSize(1);
		}

		@Test
		@Severity(SeverityLevel.NORMAL)
		@Description("timelinePayload() carries reason, hintKind, probedPaths, probeOutcome and the redacted url")
		void timelinePayload_shape() {
			stub("/mcp", 404, null, null);
			stub("/sse", 200, "Content-Type", "text/event-stream");
			final SseConnectProbe.Diagnosis diagnosis = SseConnectProbe.diagnose(notFound(), null, url("/mcp"));
			final ObjectNode payload = SseConnectProbe.timelinePayload(JsonNodeFactory.instance, notFound(), diagnosis,
					url("/mcp"));
			assertThat(payload.path("reason").asText()).isEqualTo("not_found");
			assertThat(payload.path("probeOutcome").asText()).isEqualTo("alive");
			assertThat(payload.path("probedPaths").toString()).contains("/mcp");
			assertThat(payload.path("hintKind").asText()).isEqualTo("sse_only");
		}

		@Test
		@Severity(SeverityLevel.NORMAL)
		@Description("timelinePayload() for a closed gate reports probeOutcome=not_run and null hintKind")
		void timelinePayload_notRun() {
			final SseConnectProbe.Diagnosis diagnosis = new SseConnectProbe.Diagnosis(null, null);
			final ObjectNode payload = SseConnectProbe.timelinePayload(JsonNodeFactory.instance, notFound(), diagnosis,
					null);
			assertThat(payload.path("probeOutcome").asText()).isEqualTo("not_run");
			assertThat(payload.path("hintKind").isNull()).isTrue();
			assertThat(payload.path("url").isNull()).isTrue();
		}

	}

	private record CapturedRequest(String method, String accept, String userAgent, String authorization) {
	}

	private record StubAnswer(int status, Map<String, String> headers, long delayMs) {
	}

}
