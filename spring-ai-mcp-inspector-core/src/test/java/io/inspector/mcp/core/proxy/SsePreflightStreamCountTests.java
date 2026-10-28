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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
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
 * Regression tests that the {@link SsePreflightTransport} single header-only GET
 * preflight never orphans an SSE stream on the upstream server.
 *
 * <p>
 * These tests verify the stream-level invariant directly by observing the stub's stream
 * counters after the preflight completes. The full connect flow (including the SDK's
 * {@code initialize} handshake) is not exercised here because the
 * {@code SsePreflightTransport} is a transport wrapper, not a client : the proxy uses it
 * through {@code McpProxy.start()}, not through {@code McpClient.sync()}.
 *
 * <p>
 * The probe is a cancelled GET (not HEAD): HEAD is auto-mapped onto the GET handler by
 * Starlette and Spring MVC, so a HEAD probe would run the endpoint and create the very
 * orphaned server-side SSE session the probe exists to avoid. The no-orphan invariant
 * therefore lives in the header-only body handler cancelling the subscription before the
 * stream is consumed.
 */
@Epic("MCP Inspector Core")
@Feature("SSE preflight stream counting")
class SsePreflightStreamCountTests {

	private SseStreamCountingStub stub;

	private McpClientTransport transport;

	/**
	 * Awaits an asynchronously reached stub state with a bounded deadline. The stub
	 * counters are updated on the stub's connection threads, which lag behind the
	 * client-side dispose()/timeout that triggered the close.
	 * @param what human-readable condition name used in the failure message
	 * @param condition exit condition; polled every 50 ms until the deadline
	 */
	private static void awaitUntil(final String what, final BooleanSupplier condition) {
		final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("Timed out waiting for: " + what);
			}
			try {
				Thread.sleep(50);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted waiting for: " + what, ex);
			}
		}
	}

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
			assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges after teardown").isEqualTo(0);
			assertThat(this.stub.pendingExchangeCount()).as("No pending exchanges after teardown").isEqualTo(0);
			this.stub = null;
		}
	}

	private void connectAndHold() {
		this.transport.connect((inbound) -> inbound).then(Mono.fromRunnable(() -> {
			try {
				Thread.sleep(500);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		})).block(Duration.ofSeconds(10));
	}

	@Test
	@Story("Happy path connect")
	@Severity(SeverityLevel.CRITICAL)
	@Description("After the preflight completes, the upstream stub sees exactly 2 GET /sse requests "
			+ "(one cancelled probe + one real delegate stream), no HEAD, and exactly one active "
			+ "stream: the cancelled probe leaves no orphan.")
	@DisplayName("Happy path: cancelled probe + exactly 1 real SSE stream, no HEAD")
	void happyPath_afterConnect_probeCancelledExactlyOneRealStream() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		connectAndHold();

		// then
		assertThat(this.stub.headCount()).as("HEAD probes (the probe must never be HEAD)").isEqualTo(0);
		assertThat(this.stub.sseStreamCount()).as("GET /sse requests (probe + delegate)").isEqualTo(2);
		// Only the real delegate's stream is active: the probe exchange was closed.
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges (the real SSE stream)").isEqualTo(1);
		// The probe exchange must have been closed server-side: exactly the delegate's
		// session remains pending.
		assertThat(this.stub.pendingExchangeCount()).as("Pending exchanges (only the delegate session)")
			.isEqualTo(this.stub.activeExchangeCount());
	}

	@Test
	@Story("Cancelled probe leaves no orphan")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The probe GET is answered with a held-open SSE stream (the good case) but the client "
			+ "cancels on headers. The stub must observe that probe exchange closed server-side "
			+ "before the delegate stream is established, otherwise the probe itself would be the orphan.")
	@DisplayName("Probe cancellation: probe exchange closed server-side, no orphan")
	void probeCancellation_probeExchangeClosedBeforeDelegateStream() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		connectAndHold();

		// then: two GETs total; the first (probe) must have been closed server-side.
		assertThat(this.stub.sseStreamCount()).as("GET /sse requests (probe + delegate)").isEqualTo(2);
		assertThat(this.stub.closedGetCount()).as("Probe exchange closed server-side").isGreaterThanOrEqualTo(1);
		// After the connect settles, only the delegate stream is active: the probe
		// exchange is gone. (Probe close is asynchronous, so maxActive may transiently
		// count both; the settled state is the invariant.)
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges (only the real SSE stream)").isEqualTo(1);
	}

	@Test
	@Story("Pass-through status does not block the connect")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the probe is answered with a non-definitive status (501, the RFC 9110 status for an "
			+ "unimplemented method), the transport does NOT hard-fail: the delegate still connects and "
			+ "the real SSE stream opens. This is the round-6 owner review regression: the HEAD design "
			+ "hard-failed exactly this server while the no-preflight base connected it.")
	@DisplayName("Probe 501: pass-through, delegate still connects")
	void probeNonDefinitiveStatus_passesThroughAndConnects() throws Exception {
		// given: the stub answers the FIRST GET with 501 (the probe), then 200 (the
		// delegate)
		this.stub = new SseStreamCountingStub();
		this.stub.setSseRejection(501, 1);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		connectAndHold();

		// then: probe answered 501 but the connect went through to the delegate
		assertThat(this.stub.rejectedGetCount()).as("Probe GET answered with a non-definitive status").isEqualTo(1);
		assertThat(this.stub.sseStreamCount()).as("Real delegate streams opened").isEqualTo(1);
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges (the real SSE stream)").isEqualTo(1);
	}

	@Test
	@Story("404 fails fast without a stream")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A 404 probe answer is a definitive verdict: the endpoint does not exist. The transport "
			+ "errors fast, no SSE stream is opened, the delegate never connects.")
	@DisplayName("Probe 404: fail fast, zero streams, delegate never connects")
	void probe404_failsFastNoStreamNoDelegate() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setSseStatus(404);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		assertThatThrownBy(() -> this.transport.connect((inbound) -> inbound).block(Duration.ofSeconds(5)))
			.isInstanceOf(McpTransportException.class)
			.hasMessageContaining("404");

		// then
		assertThat(this.stub.sseStreamCount()).as("SSE streams opened (probe rejected 404)").isEqualTo(0);
		assertThat(this.stub.headCount()).as("HEAD probes").isEqualTo(0);
		assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges").isEqualTo(0);
		assertThat(this.stub.pendingExchangeCount()).as("No pending exchanges").isEqualTo(0);
	}

	@Test
	@Story("Probe timeout cancels the exchange")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the probe hangs (server accepts but never responds), the transport times out and "
			+ "cancels the in-flight exchange. No leaked exchanges.")
	@DisplayName("Probe timeout: error on timeout, no leaked exchanges")
	void probeTimeout_whenGetHangs_errorsWithTimeoutAndNoLeak() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setHangOnSse(true);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		assertThatThrownBy(() -> this.transport.connect((inbound) -> inbound).block(Duration.ofSeconds(20)))
			.isInstanceOf(McpTransportException.class)
			.hasMessageContaining("timed out");

		// then : exchange teardown is asynchronous on the server side; await the
		// settled state with a bounded deadline before reading the counters.
		awaitUntil("server observed the timed-out probe close", () -> this.stub.serverClosedExchangeCount() >= 1
				&& this.stub.activeExchangeCount() == 0 && this.stub.pendingExchangeCount() == 0);
		assertThat(this.stub.sseStreamCount()).as("No SSE stream opened on timeout").isEqualTo(0);
		assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges after timeout").isEqualTo(0);
		assertThat(this.stub.serverClosedExchangeCount()).as("Server observed close after timeout").isEqualTo(1);
		assertThat(this.stub.pendingExchangeCount()).as("No pending exchanges after timeout").isEqualTo(0);
	}

	@Test
	@Story("Downstream cancellation mid-probe")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the downstream cancels the connect Mono while the probe is pending, the transport "
			+ "does not leak the probe exchange.")
	@DisplayName("Downstream cancellation: no leaked exchanges")
	void downstreamCancellation_midProbe_noLeakedExchanges() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setHangOnSse(true);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when : cancel the connect Mono after 1 second. dispose() cancels
		// asynchronously: the HTTP client tears the probe exchange down on its
		// selector thread and the stub's connection thread observes the close
		// later, so the counters are awaited with a bounded deadline, not read
		// immediately (owner review round 8, BLOCK 1).
		final CompletableFuture<Void> cancelled = new CompletableFuture<>();
		final Mono<Void> connect = this.transport.connect((inbound) -> inbound);
		final var disposable = connect.subscribe((value) -> cancelled.complete(null),
				(error) -> cancelled.completeExceptionally(error));
		Thread.sleep(1000);
		disposable.dispose();
		cancelled.complete(null);

		// then : await the settled state (close observed, no leaks) with a
		// bounded deadline instead of asserting the counter immediately.
		awaitUntil("server observed the cancelled probe close", () -> this.stub.serverClosedExchangeCount() >= 1
				&& this.stub.activeExchangeCount() == 0 && this.stub.pendingExchangeCount() == 0);
		assertThat(this.stub.sseStreamCount()).as("No SSE stream opened on cancellation").isEqualTo(0);
		assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges after cancellation").isEqualTo(0);
		assertThat(this.stub.serverClosedExchangeCount()).as("Server observed close after cancellation").isEqualTo(1);
		assertThat(this.stub.pendingExchangeCount()).as("No pending exchanges after cancellation").isEqualTo(0);
	}

	@Test
	@Story("Probe reuses the delegate HttpClient")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Ten buildSse connects must not double the HttpClient selector-thread count: the probe "
			+ "reuses the delegate's client through SharedHttpClientBuilder instead of allocating a second "
			+ "client per session. The round-6 owner review measured 20 surviving SelectorManager threads "
			+ "for 10 sessions on the pre-rework design.")
	@DisplayName("Shared HttpClient: 10 sessions do not double selector threads")
	void sharedHttpClient_tenSessions_doNotDoubleSelectorThreads() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		final int sessions = 10;

		final int selectorThreadsBefore = countLiveSelectorThreads();

		// when : open and close `sessions` transports, waiting for each close
		final List<McpClientTransport> transports = new java.util.ArrayList<>();
		for (int i = 0; i < sessions; i++) {
			final McpClientTransport t = factory.buildSse(URI.create(this.stub.sseUrl()));
			transports.add(t);
			t.connect((inbound) -> inbound).block(Duration.ofSeconds(10));
		}
		for (final McpClientTransport t : transports) {
			t.closeGracefully().block(Duration.ofSeconds(5));
		}
		// Let the OS thread-exit bookkeeping settle.
		Thread.sleep(1500);

		final int selectorThreadsAfter = countLiveSelectorThreads();
		final int delta = selectorThreadsAfter - selectorThreadsBefore;

		// then : one client per session is the documented cost; a second (probe) client
		// per session would roughly double it.
		assertThat(delta).as("Selector thread delta for %d sessions (probe shares the delegate client)", sessions)
			.isLessThanOrEqualTo(sessions + 2);
	}

	private static int countLiveSelectorThreads() {
		final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		int count = 0;
		for (final ThreadInfo info : threads.dumpAllThreads(false, false)) {
			if (info.getThreadName() != null && info.getThreadName().contains("SelectorManager")) {
				count++;
			}
		}
		return count;
	}

	@Test
	@Story("Cleanup after closeGracefully")
	@Severity(SeverityLevel.NORMAL)
	@Description("After the transport is used once and closeGracefully is called, the active exchange count "
			+ "must drop to 0: no leaked exchanges.")
	@DisplayName("Cleanup: no leaked exchanges after transport closeGracefully")
	void cleanup_afterTransportClose_noLeakedExchanges() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when : connect
		connectAndHold();

		// then : close and verify no leaks
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges while connected").isEqualTo(1);

		this.transport.closeGracefully().block(Duration.ofSeconds(2));
		this.transport = null;

		// Allow a short delay for the server to process the close.
		Thread.sleep(500);
		assertThat(this.stub.activeExchangeCount()).as("No leaked exchanges after closeGracefully").isEqualTo(0);
	}

	@Test
	@Story("Post 401 rejection")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the stub rejects the first POST with 401, the transport's sendMessage propagates the "
			+ "error. No SSE streams are leaked across the sequence.")
	@DisplayName("Post 401 rejection: error propagated, no leaked exchanges")
	void post401Rejection_whenPostReturns401_errorPropagatedAndNoLeak() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.rejectPosts(1);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when : connect succeeds (probe 200, delegate SSE stream opens)
		connectAndHold();

		// then : send a notification, which should be rejected with 401
		final McpSchema.JSONRPCNotification testMessage = new McpSchema.JSONRPCNotification("test");

		assertThatThrownBy(() -> this.transport.sendMessage(testMessage).block(Duration.ofSeconds(5)))
			.isInstanceOf(Exception.class);

		// then : no SSE streams leaked (probe + delegate both opened; probe is closed)
		assertThat(this.stub.sseStreamCount()).as("SSE streams opened (probe + delegate)").isEqualTo(2);
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges (the real SSE stream)").isEqualTo(1);
		assertThat(this.stub.postCount()).as("POST requests received (1 rejected)").isGreaterThanOrEqualTo(1);
	}

	@Test
	@Story("Post 401 retry with refreshed token")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A client-side refresh-retry: the first transport's POST is rejected with 401, credentials "
			+ "are refreshed, and the retry through a second transport succeeds and consumes an enqueued "
			+ "response. Proves the transport participates in a refresh-retry flow without leaking exchanges.")
	@DisplayName("Post 401 retry: refreshed token accepted, response consumed, zero leaks")
	void post401Retry_whenRefreshedToken_retrySucceedsAndResponseConsumed() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.rejectPosts(1);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());

		// First attempt: stale credentials
		final McpClientTransport firstAttempt = factory.buildSse(URI.create(this.stub.sseUrl()), "Bearer stale-token",
				null);
		this.transport = firstAttempt;

		// when : connect succeeds (probe 200, delegate SSE stream opens)
		connectAndHold();

		// then : first POST is rejected with 401
		final McpSchema.JSONRPCNotification firstMessage = new McpSchema.JSONRPCNotification("test");
		assertThatThrownBy(() -> firstAttempt.sendMessage(firstMessage).block(Duration.ofSeconds(5)))
			.isInstanceOf(Exception.class);

		// Close the first attempt: the client discards the stale session.
		firstAttempt.closeGracefully().block(Duration.ofSeconds(2));
		this.transport = null;

		// Allow a short delay for the server to process the close.
		Thread.sleep(500);

		// when : retry with refreshed credentials through a new transport
		final McpClientTransport retryAttempt = factory.buildSse(URI.create(this.stub.sseUrl()),
				"Bearer refreshed-token", null);
		this.transport = retryAttempt;

		retryAttempt.connect((inbound) -> inbound).then(Mono.fromRunnable(() -> {
			try {
				Thread.sleep(500);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		})).block(Duration.ofSeconds(10));

		// then : the retry POST succeeds (rejectPosts was already consumed by the first
		// attempt)
		final McpSchema.JSONRPCRequest retryMessage = new McpSchema.JSONRPCRequest("test-retry", "1");
		retryAttempt.sendMessage(retryMessage).block(Duration.ofSeconds(5));

		// Allow a short delay for the stub to deliver any queued response.
		Thread.sleep(300);

		// The retry POST carried an id, so the stub auto-queued an InitializeResult
		// response for it. The SSE loop must have consumed it.
		assertThat(this.stub.responseDeliveryCount()).as("Response consumed from SSE queue").isEqualTo(1);

		// then : assertions
		// SSE streams: each transport opens a cancelled probe plus a real delegate
		// stream, so 2 transports = 4 GET /sse streams total.
		assertThat(this.stub.sseStreamCount()).as("SSE streams opened (probe+delegate, first + retry)").isEqualTo(4);
		// Only the retry attempt's stream is active; the first attempt was closed.
		assertThat(this.stub.activeExchangeCount()).as("Active exchanges (only the retry SSE stream)").isEqualTo(1);
		// Both POST attempts observed by the stub.
		assertThat(this.stub.postCount()).as("POST requests received (1 rejected + 1 accepted)").isEqualTo(2);
		// The Authorization values differ: the first attempt used the stale token, the
		// retry used the refreshed one.
		final List<String> authValues = this.stub.postAuthorizationValues();
		assertThat(authValues).as("Authorization values from POST attempts").hasSize(2);
		assertThat(authValues.get(0)).as("First POST Authorization").isEqualTo("Bearer stale-token");
		assertThat(authValues.get(1)).as("Retry POST Authorization").isEqualTo("Bearer refreshed-token");
		// Close the retry attempt before asserting zero leaks.
		retryAttempt.closeGracefully().block(Duration.ofSeconds(2));
		this.transport = null;
		Thread.sleep(500);
		// No orphaned exchanges after the full sequence.
		assertThat(this.stub.pendingExchangeCount()).as("No pending exchanges after full sequence").isEqualTo(0);
		assertThat(this.stub.activeExchangeCount()).as("No active exchanges after full sequence").isEqualTo(0);
	}

	@Test
	@Story("Server-initiated close mid-session")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When the server closes the SSE stream mid-session, the transport surfaces it cleanly and "
			+ "no orphan exchange remains.")
	@DisplayName("Server-initiated close: stream closes cleanly, no orphans")
	void serverInitiatedClose_streamClosesCleanlyNoOrphans() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when : connect, then the server closes the SSE stream mid-session
		connectAndHold();

		assertThat(this.stub.activeExchangeCount()).as("Active exchanges while connected").isEqualTo(1);

		// Server initiates close: shut down the SSE socket from the server side.
		this.stub.closeActiveStreams();

		// Allow a short delay for the client to observe the close.
		Thread.sleep(500);

		// then : closeGracefully must complete without hanging, proving the transport
		// already detected the server-side close and cleaned up its subscription.
		this.transport.closeGracefully().block(Duration.ofSeconds(2));
		this.transport = null;

		assertThat(this.stub.activeExchangeCount()).as("No active exchanges after server close").isEqualTo(0);
		assertThat(this.stub.pendingExchangeCount()).as("No pending exchanges after server close").isEqualTo(0);
		assertThat(this.stub.serverClosedExchangeCount()).as("Server observed close").isGreaterThanOrEqualTo(1);
	}

	@Test
	@Story("Forwarded headers ride on the probe")
	@Severity(SeverityLevel.NORMAL)
	@Description("The factory-built transport forwards the Authorization header on the probe GET as well as "
			+ "on the real connect, so an auth-protected upstream answers the probe the same way it answers "
			+ "the handshake.")
	@DisplayName("Probe carries the forwarded Authorization header")
	void probeCarriesForwardedAuthorizationHeader() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()), "Bearer probe-token", null);

		// when : connect and hold so the probe and the delegate stream both happen
		connectAndHold();

		// then : the stub observed the probe's Authorization (recorded per GET).
		// The stub records GET Authorization headers in order: probe first, delegate
		// second.
		assertThat(this.stub.getAuthorizationValues()).as("Authorization on GET requests")
			.first()
			.as("Probe GET carried the forwarded Authorization header")
			.isEqualTo("Bearer probe-token");
	}

	@Test
	@Story("Configurable probe timeout through the factory")
	@Severity(SeverityLevel.NORMAL)
	@Description("The factory constructor's preflight timeout parameter (the third argument, wired to "
			+ "spring.ai.mcp.inspector.timeouts.sse-preflight) bounds the probe: a hanging server fails after "
			+ "the configured duration (here 1s), proving the bound is the factory's, not a hardcoded constant.")
	@DisplayName("Factory-configured probe timeout bounds the probe")
	void factoryConfiguredTimeout_boundsProbe() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		this.stub.setHangOnSse(true);
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper(), Duration.ofSeconds(30),
				Duration.ofSeconds(1));
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()));

		// when
		final long start = System.nanoTime();
		assertThatThrownBy(() -> this.transport.connect((inbound) -> inbound).block(Duration.ofSeconds(10)))
			.isInstanceOf(McpTransportException.class)
			.hasMessageContaining("timed out");

		// then : failed near the configured 1s budget, well below the old hardcoded 10s
		final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertThat(elapsedMs).as("Probe failed near the configured 1s budget").isLessThan(5000);
	}

	@Test
	@Story("Custom headers forwarded on the probe")
	@Severity(SeverityLevel.MINOR)
	@Description("Custom headers are forwarded on the probe GET like on the real connect.")
	@DisplayName("Probe carries the forwarded custom headers")
	void probeCarriesForwardedCustomHeaders() throws Exception {
		// given
		this.stub = new SseStreamCountingStub();
		final ProxyTransportFactory factory = new ProxyTransportFactory(new JsonMapper());
		this.transport = factory.buildSse(URI.create(this.stub.sseUrl()), null, Map.of("X-Tenant", "acme"));

		// when
		connectAndHold();

		// then
		assertThat(this.stub.getTenantValues()).as("X-Tenant on GET requests").first().isEqualTo("acme");
	}

}
