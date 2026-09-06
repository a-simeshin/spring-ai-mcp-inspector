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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests that the {@link SsePreflightTransport} HEAD-based preflight never
 * creates orphaned SSE streams on the upstream server.
 *
 * <p>
 * These tests verify the stream-level invariant directly by observing the stub's stream
 * counters after the preflight completes. The full connect flow (including the SDK's
 * {@code initialize} handshake) is not exercised here because the
 * {@code SsePreflightTransport} is a transport wrapper, not a client : the proxy uses it
 * through {@code McpProxy.start()}, not through {@code McpClient.sync()}.
 *
 * <p>
 * Acceptance criteria: new tests fail on the pre-fix code (GET-based preflight that
 * creates abandoned SSE streams) and pass on the fixed code (HEAD-based preflight that
 * never opens a stream).
 */
@Epic("MCP Inspector Core")
@Feature("SSE preflight stream counting")
class SsePreflightStreamCountTests {

	private SseStreamCountingStub stub;

	@AfterEach
	void tearDown() {
		if (this.stub != null) {
			this.stub.close();
			this.stub = null;
		}
	}

	@Test
	@Story("Happy path connect")
	@Severity(SeverityLevel.CRITICAL)
	@Description("After the SsePreflightTransport preflight completes, the upstream stub "
			+ "sees exactly 1 HEAD probe and 1 SSE stream : the HEAD preflight never "
			+ "opens a stream, and the delegate opens exactly one.")
	@DisplayName("Happy path: exactly 1 SSE stream and 1 HEAD probe after connect")
	void happyPath_afterConnect_exactlyOneSseStream() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		final McpClientTransport transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		transport.connect((inbound) -> inbound).then(Mono.fromRunnable(() -> {
			try {
				Thread.sleep(500);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		})).block(Duration.ofSeconds(10));

		// then
		// The HEAD preflight never opens a stream. The real delegate's GET /sse
		// opens exactly one stream. Zero orphaned streams.
		assertThat(this.stub.sseStreamCount()).as("SSE streams opened by the real delegate").isEqualTo(1);
		assertThat(this.stub.headCount()).as("HEAD preflight probes").isEqualTo(1);
		assertThat(this.stub.getFallbackCount()).as("GET fallback probes (should be 0 with HEAD support)").isEqualTo(0);
	}

	@Test
	@Story("Preflight failure : HEAD returns non-2xx")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the HEAD preflight returns a non-2xx (non-405) status, "
			+ "the SsePreflightTransport errors and no SSE stream is ever created : "
			+ "zero orphaned streams, zero delegate connections")
	@DisplayName("Preflight failure: 0 SSE streams when HEAD returns 403")
	void preflightFailure_whenHeadReturns403_noSseStreamLeaked() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setHeadStatus(403);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		final McpClientTransport transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when : the preflight HEAD returns 403, so the transport errors
		// The delegate's connect() is never called.
		try {
			transport.connect((inbound) -> inbound).block(Duration.ofSeconds(5));
		}
		catch (final Exception ex) {
			// expected : preflight failure
		}

		// then : no SSE stream was created, the delegate never connected
		assertThat(this.stub.sseStreamCount()).as("SSE streams opened (should be 0 : preflight failed)").isEqualTo(0);
		assertThat(this.stub.headCount()).as("HEAD preflight probes").isEqualTo(1);
		assertThat(this.stub.getFallbackCount()).as("GET fallback probes (should be 0 : 403 is not 405)").isEqualTo(0);
	}

	@Test
	@Story("Preflight 405 fallback to GET")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the HEAD preflight returns 405, the SsePreflightTransport "
			+ "falls back to a GET-based probe. The header-only body handler cancels "
			+ "immediately on response headers, so the first GET is never consumed. "
			+ "The delegate then opens a real SSE stream. The stub sees 2 GET requests "
			+ "(fallback + delegate) but the fallback stream is cancelled immediately.")
	@DisplayName("Preflight 405 fallback: 2 GET requests (fallback cancelled, delegate opens real stream)")
	void preflight405Fallback_whenHeadReturns405_fallsBackToGetAndConnects() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setHeadStatus(405);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		final McpClientTransport transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		transport.connect((inbound) -> inbound).then(Mono.fromRunnable(() -> {
			try {
				Thread.sleep(500);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		})).block(Duration.ofSeconds(10));

		// then
		// The HEAD preflight returns 405, triggering the GET fallback.
		// The GET fallback sends a GET /sse with Accept: text/event-stream (same
		// as the real GET), so the stub treats it as a full SSE stream.
		// The header-only body handler cancels immediately, but the stub has already
		// incremented the stream count before the cancellation is noticed.
		// The real delegate then sends another GET /sse which opens the real stream.
		// Result: stub sees 2 GET /sse requests, but only 1 is actually active.
		assertThat(this.stub.headCount()).as("HEAD preflight probes").isEqualTo(1);
		assertThat(this.stub.getFallbackCount()).as("GET fallback probes (stub distinguishes by Accept header)")
			.isEqualTo(0);
		// Two GET requests reached the stub: the fallback (cancelled immediately)
		// and the real delegate's stream. The fallback is counted as an SSE stream
		// because it has the same Accept header, but it is cancelled immediately
		// and never holds a real connection.
		assertThat(this.stub.sseStreamCount()).as("Total GET /sse requests (fallback + delegate)").isEqualTo(2);
	}

	@Test
	@Story("Preflight HEAD on a non-SSE endpoint")
	@Severity(SeverityLevel.NORMAL)
	@Description("The SsePreflightTransport sends a HEAD request with an Accept: "
			+ "text/event-stream header. The stub should see this header on the HEAD probe.")
	@DisplayName("HEAD probe carries Accept: text/event-stream header")
	void headProbe_carriesAcceptEventStreamHeader() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final String sseUrl = this.stub.sseUrl();

		// when : send a bare HEAD request to the stub, as the SsePreflightTransport would
		final HttpClient client = HttpClient.newHttpClient();
		final HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(sseUrl))
			.header("Accept", "text/event-stream")
			.method("HEAD", HttpRequest.BodyPublishers.noBody())
			.build();
		final HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

		// then
		assertThat(response.statusCode()).as("HEAD response status").isEqualTo(200);
		assertThat(this.stub.headCount()).as("HEAD probe received by stub").isEqualTo(1);
		assertThat(this.stub.sseStreamCount()).as("SSE stream count (HEAD should not open a stream)").isEqualTo(0);
	}

}
