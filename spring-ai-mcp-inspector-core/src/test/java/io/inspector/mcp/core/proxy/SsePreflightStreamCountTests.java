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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpTransportException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	private McpClientTransport transport;

	@AfterEach
	void tearDown() {
		if (this.transport != null) {
			try {
				this.transport.closeGracefully().block(Duration.ofSeconds(2));
			}
			catch (final Exception ignored) {
				// best-effort
			}
			this.transport = null;
		}
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
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		this.transport.connect((inbound) -> inbound).then(Mono.fromRunnable(() -> {
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
		// The real SSE stream is active while the transport is connected.
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges (the real SSE stream)").isEqualTo(1);
	}

	@Test
	@Story("Preflight failure : HEAD returns non-2xx")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the HEAD preflight returns a non-2xx (non-405) status, "
			+ "the SsePreflightTransport errors and no SSE stream is ever created : "
			+ "zero orphaned streams, zero delegate connections")
	@DisplayName("Preflight failure: 0 SSE streams when HEAD returns 403, no leaked exchanges")
	void preflightFailure_whenHeadReturns403_noSseStreamLeaked() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setHeadStatus(403);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when : the preflight HEAD returns 403, so the transport errors
		// The delegate's connect() is never called.
		try {
			this.transport.connect((inbound) -> inbound).block(Duration.ofSeconds(5));
		}
		catch (final Exception ex) {
			// expected : preflight failure
		}

		// then : no SSE stream was created, the delegate never connected
		assertThat(this.stub.sseStreamCount()).as("SSE streams opened (should be 0 : preflight failed)").isEqualTo(0);
		assertThat(this.stub.headCount()).as("HEAD preflight probes").isEqualTo(1);
		assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges after preflight failure").isEqualTo(0);
	}

	@Test
	@Story("Preflight 405 fallback to GET")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the HEAD preflight returns 405, the SsePreflightTransport "
			+ "falls back to a GET-based probe. The header-only body handler cancels "
			+ "immediately, so the fallback GET exchange is closed before the delegate "
			+ "opens a real SSE stream. The test asserts that after the transport "
			+ "completes, only the real delegate's stream is active (1 active exchange), "
			+ "proving the fallback was properly closed. This test FAILS on the pre-fix "
			+ "GET-only transport because the pre-fix probe opens a real SSE stream that "
			+ "is never closed, leaving 2 active exchanges.")
	@DisplayName("Preflight 405 fallback: fallback GET is closed, only delegate stream active")
	void preflight405Fallback_whenHeadReturns405_fallbackClosedNoLeak() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setHeadStatus(405);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		this.transport.connect((inbound) -> inbound).then(Mono.fromRunnable(() -> {
			try {
				Thread.sleep(500);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		})).block(Duration.ofSeconds(10));

		// then
		// The HEAD preflight returns 405, triggering the GET fallback.
		// The GET fallback sends a GET /sse; the header-only body handler cancels
		// the subscription immediately, which closes the exchange.
		// The stub counts the fallback as a failed SSE stream attempt.
		// The real delegate then sends another GET /sse which opens the real stream.
		// Result: stub sees 2 GET /sse requests, but only 1 active exchange.
		assertThat(this.stub.headCount()).as("HEAD preflight probes").isEqualTo(1);
		assertThat(this.stub.sseStreamCount()).as("Total GET /sse requests (fallback + delegate)").isEqualTo(2);
		// Only the real delegate's stream is active: the fallback was closed.
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges (only the real SSE stream)").isEqualTo(1);
	}

	@Test
	@Story("Preflight 404 fallback to GET")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the HEAD preflight returns 404, the SsePreflightTransport "
			+ "falls back to a GET-based probe, same as 405. This ensures that servers "
			+ "which do not support HEAD (returning 404 instead of 405) still work.")
	@DisplayName("Preflight 404 fallback: HEAD 404 triggers GET fallback, no leaked exchanges")
	void preflight404Fallback_whenHeadReturns404_fallsBackToGet() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setHeadStatus(404);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		this.transport.connect((inbound) -> inbound).then(Mono.fromRunnable(() -> {
			try {
				Thread.sleep(500);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		})).block(Duration.ofSeconds(10));

		// then
		assertThat(this.stub.headCount()).as("HEAD preflight probes").isEqualTo(1);
		assertThat(this.stub.sseStreamCount()).as("Total GET /sse requests (fallback + delegate)").isEqualTo(2);
		// Only the real delegate's stream is active: the fallback was closed.
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges (only the real SSE stream)").isEqualTo(1);
	}

	@Test
	@Story("Preflight HEAD timeout")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the HEAD preflight hangs (server accepts but never responds), "
			+ "the transport times out. The test asserts that no exchanges are leaked " + "after the timeout.")
	@DisplayName("Preflight HEAD timeout: error on timeout, no leaked exchanges")
	void preflightTimeout_whenHeadHangs_errorsWithTimeoutAndNoLeak() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setHangOnHead(true);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		assertThatThrownBy(() -> this.transport.connect((inbound) -> inbound).block(Duration.ofSeconds(15)))
			.isInstanceOf(McpTransportException.class)
			.hasMessageContaining("timed out");

		// then
		assertThat(this.stub.headCount()).as("HEAD preflight probes").isEqualTo(1);
		assertThat(this.stub.sseStreamCount()).as("No SSE stream opened on timeout").isEqualTo(0);
		assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges after timeout").isEqualTo(0);
	}

	@Test
	@Story("Downstream cancellation mid-handshake")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the downstream cancels the connect Mono during the preflight "
			+ "handshake, the transport should not leak any exchanges.")
	@DisplayName("Downstream cancellation: no leaked exchanges after cancellation")
	void downstreamCancellation_midHandshake_noLeakedExchanges() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		// Make the HEAD preflight hang so the Mono is still pending
		this.stub.setHangOnHead(true);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when : cancel the connect Mono after 1 second
		final CompletableFuture<Void> cancelled = new CompletableFuture<>();
		final Mono<Void> connect = this.transport.connect((inbound) -> inbound);
		final var disposable = connect.subscribe((value) -> cancelled.complete(null),
				(error) -> cancelled.completeExceptionally(error));
		Thread.sleep(1000);
		disposable.dispose();
		cancelled.complete(null);

		// then
		assertThat(this.stub.headCount()).as("HEAD preflight probes").isEqualTo(1);
		assertThat(this.stub.sseStreamCount()).as("No SSE stream opened on cancellation").isEqualTo(0);
		assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges after cancellation").isEqualTo(0);
	}

	@Test
	@Story("Preflight HEAD carries Accept header")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The SsePreflightTransport sends a HEAD request with an Accept: "
			+ "text/event-stream header. This test exercises the real transport "
			+ "and verifies the header was received by the stub, instead of " + "sending a raw HttpClient request.")
	@DisplayName("HEAD probe through real transport carries Accept: text/event-stream header")
	void headProbeThroughRealTransport_carriesAcceptEventStreamHeader() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		// Make the stub return 403 so the preflight fails without connecting
		// the delegate. This lets us observe the HEAD probe alone.
		this.stub.setHeadStatus(403);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when : try to connect. The preflight HEAD returns 403, so the
		// transport errors before the delegate connects.
		try {
			this.transport.connect((inbound) -> inbound).block(Duration.ofSeconds(5));
		}
		catch (final Exception ex) {
			// expected : preflight failure
		}

		// then
		// The stub received the HEAD probe. The test verifies that the real
		// transport sent a HEAD request (not a raw HttpClient call).
		assertThat(this.stub.headCount()).as("HEAD probe received by stub through real transport").isEqualTo(1);
		// The HEAD probe should not open an SSE stream.
		assertThat(this.stub.sseStreamCount()).as("SSE stream count (HEAD should not open a stream)").isEqualTo(0);
		assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges after HEAD probe").isEqualTo(0);
	}

	@Test
	@Story("Cleanup after closeGracefully")
	@Severity(SeverityLevel.NORMAL)
	@Description("After the transport is used once and closeGracefully is called, "
			+ "the active exchange count should drop to 0: no leaked exchanges.")
	@DisplayName("Cleanup: no leaked exchanges after transport closeGracefully")
	void cleanup_afterTransportClose_noLeakedExchanges() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when : connect
		this.transport.connect((inbound) -> inbound).then(Mono.fromRunnable(() -> {
			try {
				Thread.sleep(500);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		})).block(Duration.ofSeconds(10));

		// then : close and verify no leaks
		// The real SSE stream is active while connected.
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges while connected").isEqualTo(1);

		this.transport.closeGracefully().block(Duration.ofSeconds(2));
		this.transport = null;

		// Allow a short delay for the server to process the close and the
		// stub's loop to exit.
		Thread.sleep(500);
		assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges after closeGracefully").isEqualTo(0);
	}

}
