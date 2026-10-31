/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.proxy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the upstream liveness probe from both sides: upstream death must close the
 * downstream SSE stream, and a live upstream (answering or not) must never be failed by
 * the probe.
 *
 * <p>
 * Death side:
 *
 * <ul>
 * <li>{@code upstreamDeath_closesDownstreamSseWithinBound}: killing the target closes the
 * downstream stream within the probe-based bound (the next probe POST fails with a
 * transport-level connection refused).</li>
 * <li>{@code upstreamDeath_probeDisabled_streamStaysOpen}: red control - with probing
 * disabled the same kill leaves the stream open, proving the probe is what detects the
 * dead upstream.</li>
 * </ul>
 *
 * <p>
 * Alive side (the probe must not kill healthy idle sessions):
 *
 * <ul>
 * <li>{@code upstreamAlive_keepsSseStreamOpen}: an initialised live upstream survives
 * completed probe cycles; each probe POST is accepted by the target (access log) and each
 * probe answer is filtered from the browser stream.</li>
 * <li>{@code upstreamAlive_unansweredProbes_keepStreamOpen}: a live upstream that never
 * completed the MCP initialize handshake parks every probe ping on its session exchange
 * sink (spec behaviour of the MCP SDK server: the exchange is only emitted after the
 * notifications/initialized notification), so probes are never answered - the session
 * must survive them all; only transport-level death (verified by killing the target at
 * the end) may close the stream.</li>
 * <li>{@code upstreamAlive_pingRejected4xx_streamStaysOpen}: a live stub upstream whose
 * message endpoint answers every probe ping POST with HTTP 400 must not be failed either
 * (a protocol-level rejection from a live transport is not transport-level death); the
 * stub counts the rejected pings, proving the probes really ran.</li>
 * </ul>
 *
 * <p>
 * Topology: two completely separate Spring contexts.
 *
 * <ul>
 * <li>{@code inspectorApp}: boots the demo with the inspector mounted at
 * {@code /mcp-inspector-api} (proxy entry point, with liveness probing enabled).</li>
 * <li>{@code targetApp}: boots a second standalone demo that serves as the upstream MCP
 * server the proxy points at.</li>
 * </ul>
 *
 * <p>
 * Flow:
 *
 * <ol>
 * <li>Open an SSE proxy session on {@code inspectorApp} that targets {@code targetApp}'s
 * SSE endpoint: confirm the SSE stream is alive by receiving the endpoint prologue.</li>
 * <li>Post an {@code initialize} frame to confirm the session is working.</li>
 * <li>Shut down {@code targetApp}.</li>
 * <li>The SSE stream must close within 15s (the probe-based detection bound with this
 * test's config: idleThreshold(3s) + one probeInterval tick(2s) + the instantly refused
 * POST = 5s worst case, with generous headroom for JVM shutdown delays).</li>
 * </ol>
 */
@Epic("Inspector Proxy")
@Feature("SSE liveness probe")
@ExtendWith(OutputCaptureExtension.class)
class ProxySseLivenessIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/** Wall-clock budget for normal HTTP exchanges. */
	private static final Duration BUDGET = Duration.ofSeconds(20);

	/**
	 * Max time to wait for the SSE stream to close after the target is killed. With the
	 * fast test config (2s interval, 3s idle threshold) the probe-based detection bound
	 * is about 5s (3s idle + one 2s tick + the instantly refused POST); 15s adds generous
	 * headroom for JVM shutdown delays. The red control below out-waits this entire
	 * budget, so the positive/red pair stays falsifiable.
	 */
	private static final Duration SSE_CLOSE_BUDGET = Duration.ofSeconds(15);

	/** Inspector app: hosts the proxy with liveness probing. */
	private ConfigurableApplicationContext inspectorApp;

	/** Target app: the upstream MCP server we kill mid-session. */
	private ConfigurableApplicationContext targetApp;

	@AfterEach
	void stopApps() {
		if (targetApp != null) {
			try {
				targetApp.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			targetApp = null;
		}
		if (inspectorApp != null) {
			try {
				inspectorApp.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			inspectorApp = null;
		}
	}

	@Test
	@DisplayName("Upstream death closes the downstream SSE stream within 15s")
	@Story("SSE liveness detection")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Kills the upstream target mid-session and verifies the SSE stream is closed "
			+ "within the probe-based detection bound (idleThreshold 3s + one probeInterval 2s tick "
			+ "+ the instantly refused POST = 5s worst case)")
	void upstreamDeath_closesDownstreamSseWithinBound() throws Exception {
		// given
		// Use fast probe intervals to keep the test bounded
		inspectorApp = ProxyAppHarness.start("SSE", false, null,
				"--spring.ai.mcp.inspector.upstream-liveness-probe-enabled=true",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-interval=PT2S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-timeout=PT3S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-idle-threshold=PT3S");
		targetApp = ProxyAppHarness.start("SSE", false, null);

		final int inspectorPort = ProxyAppHarness.port(inspectorApp);
		final int targetPort = ProxyAppHarness.port(targetApp);
		final String proxyBase = "http://127.0.0.1:" + inspectorPort + "/mcp-inspector-api";
		final String targetUrl = "http://127.0.0.1:" + targetPort + "/sse";

		// 1. Open the SSE stream and capture the endpoint prologue.
		final AtomicBoolean streamClosed = new AtomicBoolean(false);
		final AtomicReference<String> endpointData = new AtomicReference<>();
		final AtomicReference<Throwable> streamError = new AtomicReference<>();
		final Thread sseReader = new Thread(() -> {
			try {
				final HttpRequest sseRequest = HttpRequest
					.newBuilder(URI.create(proxyBase + "/sse?transportType=sse&url="
							+ URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
					.timeout(Duration.ofSeconds(60))
					.header("Accept", "text/event-stream")
					.GET()
					.build();
				final HttpResponse<java.io.InputStream> response = HTTP.send(sseRequest,
						HttpResponse.BodyHandlers.ofInputStream());
				if (response.statusCode() != 200) {
					streamError.set(new IllegalStateException("SSE handshake HTTP " + response.statusCode()));
					return;
				}
				try (var reader = new java.io.BufferedReader(
						new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
					String eventName = null;
					StringBuilder dataBuf = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						if (line.isEmpty()) {
							if ("endpoint".equals(eventName) && dataBuf.length() > 0) {
								endpointData.set(dataBuf.toString());
							}
							eventName = null;
							dataBuf.setLength(0);
							continue;
						}
						if (line.startsWith("event:")) {
							eventName = line.substring(6).trim();
						}
						else if (line.startsWith("data:")) {
							if (dataBuf.length() > 0) {
								dataBuf.append('\n');
							}
							dataBuf.append(line.substring(5).trim());
						}
					}
				}
				// readLine returned null - the stream closed
				streamClosed.set(true);
			}
			catch (java.net.http.HttpTimeoutException ex) {
				// Timeout reading the stream is also a signal that the stream ended
				streamClosed.set(true);
			}
			catch (Throwable t) {
				streamError.set(t);
			}
		}, "sse-liveness-reader-" + ProxyAppHarness.stack());
		sseReader.setDaemon(true);
		sseReader.start();

		// 2. Wait for the endpoint prologue.
		Awaitility.await("endpoint prologue frame on " + ProxyAppHarness.stack())
			.atMost(BUDGET)
			.pollInterval(Duration.ofMillis(100))
			.until(() -> endpointData.get() != null);

		final String data = endpointData.get();
		assertThat(data).as("endpoint prologue data on %s", ProxyAppHarness.stack())
			.startsWith("/mcp-inspector-api/message?sessionId=");

		final String sessionId = data.substring(data.indexOf("sessionId=") + "sessionId=".length());
		assertThat(sessionId).isNotBlank();

		// 3. Post an initialize frame to confirm the session is working.
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-sse-liveness-it");
		info.put("version", "0.1.0");

		final HttpRequest initRequest = HttpRequest
			.newBuilder(URI.create(proxyBase + "/message?sessionId=" + sessionId))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		final HttpResponse<String> initResponse = HTTP.send(initRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(initResponse.statusCode()).as("initialize POST must be 2xx on %s", ProxyAppHarness.stack())
			.isBetween(200, 202);

		// when
		// 4. Kill the target app.
		targetApp.close();
		targetApp = null;

		// then
		// 5. The SSE stream must close (reader detects EOF or error) within the
		// probe-based detection bound. The prober's failUpstream errors the
		// targetToBrowser sink, which causes the SSE subscriber to call
		// emitter.completeWithError(), so the reader sees either EOF or an
		// IOException - either is valid.
		Awaitility.await("SSE stream closed after target loss on " + ProxyAppHarness.stack())
			.atMost(SSE_CLOSE_BUDGET)
			.pollInterval(Duration.ofMillis(200))
			.until(() -> streamClosed.get() || streamError.get() != null);

		// 6. The stream is expected to close with an error (emitter.completeWithError
		// from the failUpstream path). A reader error is the expected outcome.
		// If the stream closed cleanly (EOF), that is also valid - it means the
		// emitter was completed normally by some other path.
	}

	@Test
	@DisplayName("With probing disabled, upstream death does NOT close the SSE stream promptly")
	@Story("SSE liveness detection")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Red control for upstreamDeath_closesDownstreamSseWithinBound: with "
			+ "upstream-liveness-probe-enabled=false the same kill must leave the stream open past the "
			+ "entire 15s budget within which the probe-based run closes it, proving the probe (not "
			+ "reactor-netty resource sharing) is what detects the dead upstream")
	void upstreamDeath_probeDisabled_streamStaysOpen() throws Exception {
		// given: probing disabled; other timeouts at defaults so nothing else fires
		inspectorApp = ProxyAppHarness.start("SSE", false, null,
				"--spring.ai.mcp.inspector.upstream-liveness-probe-enabled=false");
		targetApp = ProxyAppHarness.start("SSE", false, null);

		final int inspectorPort = ProxyAppHarness.port(inspectorApp);
		final int targetPort = ProxyAppHarness.port(targetApp);
		final String proxyBase = "http://127.0.0.1:" + inspectorPort + "/mcp-inspector-api";
		final String targetUrl = "http://127.0.0.1:" + targetPort + "/sse";

		// Open the SSE stream and capture the endpoint prologue.
		final AtomicBoolean streamClosed = new AtomicBoolean(false);
		final AtomicReference<String> endpointData = new AtomicReference<>();
		final AtomicReference<Throwable> streamError = new AtomicReference<>();
		final Thread sseReader = new Thread(() -> {
			try {
				final HttpRequest sseRequest = HttpRequest
					.newBuilder(URI.create(proxyBase + "/sse?transportType=sse&url="
							+ URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
					.timeout(Duration.ofSeconds(60))
					.header("Accept", "text/event-stream")
					.GET()
					.build();
				final HttpResponse<java.io.InputStream> response = HTTP.send(sseRequest,
						HttpResponse.BodyHandlers.ofInputStream());
				if (response.statusCode() != 200) {
					streamError.set(new IllegalStateException("SSE handshake HTTP " + response.statusCode()));
					return;
				}
				try (var reader = new java.io.BufferedReader(
						new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
					String eventName = null;
					StringBuilder dataBuf = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						if (line.isEmpty()) {
							if ("endpoint".equals(eventName) && dataBuf.length() > 0) {
								endpointData.set(dataBuf.toString());
							}
							eventName = null;
							dataBuf.setLength(0);
							continue;
						}
						if (line.startsWith("event:")) {
							eventName = line.substring(6).trim();
						}
						else if (line.startsWith("data:")) {
							if (dataBuf.length() > 0) {
								dataBuf.append('\n');
							}
							dataBuf.append(line.substring(5).trim());
						}
					}
				}
				streamClosed.set(true);
			}
			catch (java.net.http.HttpTimeoutException ex) {
				streamClosed.set(true);
			}
			catch (Throwable t) {
				streamError.set(t);
			}
		}, "sse-liveness-disabled-reader-" + ProxyAppHarness.stack());
		sseReader.setDaemon(true);
		sseReader.start();

		// Wait for the endpoint prologue.
		Awaitility.await("endpoint prologue frame (probe disabled) on " + ProxyAppHarness.stack())
			.atMost(BUDGET)
			.pollInterval(Duration.ofMillis(100))
			.until(() -> endpointData.get() != null);

		// when: kill the target exactly as in the positive test
		targetApp.close();
		targetApp = null;

		// then: with no probe, nothing detects the dead upstream: the stream must
		// still be open after 16s, past the probe-enabled run's ENTIRE 15s close
		// budget (SSE_CLOSE_BUDGET). Any closure mechanism that also ran with the
		// probe disabled would have fired inside that budget in the probe-enabled
		// run too; that run closes and this one does not, so the probe is the
		// detector. The JDK HTTP client only surfaces the dead TCP peer on a
		// write, and the idle SSE connection performs none.
		Thread.sleep(Duration.ofSeconds(16).toMillis());
		assertThat(streamClosed.get())
			.as("SSE stream must stay open with probing disabled on %s", ProxyAppHarness.stack())
			.isFalse();
		assertThat(streamError.get())
			.as("SSE stream must not error with probing disabled on %s", ProxyAppHarness.stack())
			.isNull();
	}

	@Test
	@DisplayName("Alive upstream keeps the SSE stream open across completed probe cycles")
	@Story("SSE liveness detection")
	@Severity(SeverityLevel.NORMAL)
	@Description("With the target alive and the MCP handshake completed (initialize + "
			+ "notifications/initialized), the 12s observation window covers idle threshold (2s) + "
			+ "probe interval (2s) + answer window (2s) twice over (one full probe cycle is 4-6s "
			+ "worst case including tick alignment), so at least two full cycles run inside it. "
			+ "The stream must stay open and error-free; the target's log must show the probe pings "
			+ "reached its MCP session (logged at DEBUG by the SDK session class shared by both "
			+ "stacks), and no probe answer may leak downstream (no mcpi-probe id ever appears in "
			+ "the browser stream)")
	void upstreamAlive_keepsSseStreamOpen(final CapturedOutput output) throws Exception {
		// given: fast probe config (2s interval, 2s answer window, 2s idle
		// threshold); target with its access log enabled so the test can prove
		// the probe POSTs really reached the upstream message endpoint.
		inspectorApp = ProxyAppHarness.start("SSE", false, null,
				"--spring.ai.mcp.inspector.upstream-liveness-probe-enabled=true",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-interval=PT2S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-timeout=PT2S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-idle-threshold=PT2S");
		targetApp = ProxyAppHarness.start("SSE", false, null, "--logging.level.io.modelcontextprotocol=DEBUG");

		final int inspectorPort = ProxyAppHarness.port(inspectorApp);
		final int targetPort = ProxyAppHarness.port(targetApp);
		final String proxyBase = "http://127.0.0.1:" + inspectorPort + "/mcp-inspector-api";
		final String targetUrl = "http://127.0.0.1:" + targetPort + "/sse";

		final SseStream sse = new SseStream(proxyBase, targetUrl, "sse-alive-reader");

		// 1. Open the SSE stream and capture the endpoint prologue.
		Awaitility.await("endpoint prologue frame (alive) on " + ProxyAppHarness.stack())
			.atMost(BUDGET)
			.pollInterval(Duration.ofMillis(100))
			.until(() -> sse.endpointData.get() != null);

		final String data = sse.endpointData.get();
		assertThat(data).as("endpoint prologue data on %s", ProxyAppHarness.stack())
			.startsWith("/mcp-inspector-api/message?sessionId=");
		final String sessionId = data.substring(data.indexOf("sessionId=") + "sessionId=".length());

		// 2. Post an initialize frame so the target answers probes with a
		// JSON-RPC response (an uninitialised SDK server parks pings instead;
		// that variant is covered by upstreamAlive_unansweredProbes_keepStreamOpen).
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-sse-liveness-it");
		info.put("version", "0.1.0");

		final HttpRequest initRequest = HttpRequest
			.newBuilder(URI.create(proxyBase + "/message?sessionId=" + sessionId))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)))
			.build();
		final HttpResponse<String> initResponse = HTTP.send(initRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(initResponse.statusCode()).as("initialize POST must be 2xx on %s", ProxyAppHarness.stack())
			.isBetween(200, 202);

		// Complete the MCP handshake: the SDK server only emits its session
		// exchange (the thing every non-initialize request waits on, ping
		// included) once the client sends notifications/initialized. Without
		// this notification the target would park every probe ping forever -
		// that half-initialised variant is covered by
		// upstreamAlive_unansweredProbes_keepStreamOpen.
		final ObjectNode initialized = MAPPER.createObjectNode();
		initialized.put("jsonrpc", "2.0");
		initialized.put("method", "notifications/initialized");
		final HttpRequest initializedRequest = HttpRequest
			.newBuilder(URI.create(proxyBase + "/message?sessionId=" + sessionId))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(initialized)))
			.build();
		final HttpResponse<String> initializedResponse = HTTP.send(initializedRequest,
				HttpResponse.BodyHandlers.ofString());
		assertThat(initializedResponse.statusCode())
			.as("notifications/initialized POST must be 2xx on %s", ProxyAppHarness.stack())
			.isBetween(200, 202);

		// 3. Observe the idle stream for longer than two full probe cycles: one
		// full cycle is idle threshold (2s) + tick alignment (0-2s) + answer
		// window (2s) = 4-6s worst case, so 12s covers at least two completed
		// cycles with headroom. The baseline marks the captured console output
		// at window start: only target log lines emitted after it count as
		// this test's probe cycles (each test boots fresh apps).
		final int logBaseline = output.getAll().length();
		Thread.sleep(Duration.ofSeconds(12).toMillis());

		// 4. The stream MUST still be open and error-free: killing it here means
		// the probe produced a false positive on a healthy idle upstream.
		assertThat(sse.closed.get())
			.as("SSE stream must not close while target is alive on %s", ProxyAppHarness.stack())
			.isFalse();
		assertThat(sse.error.get()).as("SSE stream must not error while target is alive on %s", ProxyAppHarness.stack())
			.isNull();

		// 5. No probe answer may leak downstream: every frame relayed to the
		// browser stream must be free of probe ids (the proxy filters probe
		// answers by their JSON-RPC id).
		synchronized (sse.frames) {
			assertThat(sse.frames).as("relayed frames on %s", ProxyAppHarness.stack())
				.noneMatch((frame) -> frame.contains("mcpi-probe-"));
		}

		// 6. The probe cycles must really have run: the target's log shows at
		// least two probe pings were delivered to its MCP session. The session
		// logs every incoming request at DEBUG (io.modelcontextprotocol, the
		// SDK class shared by both stacks), so "Received request" lines with
		// method ping prove the probe POSTs reached the target. On a fully
		// initialised session a received ping is always answered (spec
		// behaviour), and the answer travels back over SSE: its absence from
		// the browser stream (step 5) proves the proxy filtered it - a full
		// cycle: probe sent, ping received, answer relayed and filtered.
		final long receivedPings = Awaitility.await("probe pings received by the target on " + ProxyAppHarness.stack())
			.atMost(BUDGET)
			.pollInterval(Duration.ofMillis(200))
			.until(() -> countReceivedPingRequests(output, logBaseline), (n) -> n >= 2);
		assertThat(receivedPings).as("probe pings received by the target on %s", ProxyAppHarness.stack())
			.isGreaterThanOrEqualTo(2);
	}

	@Test
	@DisplayName("Alive upstream that never answers probes keeps the SSE stream open")
	@Story("SSE liveness detection")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A healthy SSE upstream that NEVER answers probes must not be failed: a "
			+ "spec-compliant SDK server parks every non-initialize request (ping included) on its "
			+ "session exchange sink until the client completes the initialize handshake, so an "
			+ "idle half-initialised or uninitialised upstream never answers probes while its "
			+ "transport stays alive. The stream must stay open across two full probe cycles (12s); "
			+ "killing the target afterwards must still close the stream within the probe bound, "
			+ "proving the probes ran the whole time and only transport-level death is fatal")
	void upstreamAlive_unansweredProbes_keepStreamOpen() throws Exception {
		// given: fast probe config; NO initialize frame - the target parks
		// every probe ping on its uninitialised session exchange sink, so no
		// probe is ever answered.
		inspectorApp = ProxyAppHarness.start("SSE", false, null,
				"--spring.ai.mcp.inspector.upstream-liveness-probe-enabled=true",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-interval=PT2S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-timeout=PT2S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-idle-threshold=PT2S");
		targetApp = ProxyAppHarness.start("SSE", false, null);

		final int inspectorPort = ProxyAppHarness.port(inspectorApp);
		final int targetPort = ProxyAppHarness.port(targetApp);
		final String proxyBase = "http://127.0.0.1:" + inspectorPort + "/mcp-inspector-api";
		final String targetUrl = "http://127.0.0.1:" + targetPort + "/sse";

		final SseStream sse = new SseStream(proxyBase, targetUrl, "sse-unanswered-reader");

		Awaitility.await("endpoint prologue frame (unanswered) on " + ProxyAppHarness.stack())
			.atMost(BUDGET)
			.pollInterval(Duration.ofMillis(100))
			.until(() -> sse.endpointData.get() != null);

		// when: stay idle for longer than two full probe cycles (one full cycle
		// is 4-6s worst case). Every probe POST the proxy sends is accepted by
		// the live transport and simply never answered.
		Thread.sleep(Duration.ofSeconds(12).toMillis());

		// then: the stream MUST still be open and error-free - an unanswered
		// ping is not transport-level death, the session must survive it.
		assertThat(sse.closed.get())
			.as("SSE stream must not close while the never-answering target is alive on %s", ProxyAppHarness.stack())
			.isFalse();
		assertThat(sse.error.get())
			.as("SSE stream must not error while the never-answering target is alive on %s", ProxyAppHarness.stack())
			.isNull();

		// And the probe must have been running the whole time: killing the
		// target now closes the stream within the probe-based bound (the next
		// probe POST fails with connection refused). With probing disabled the
		// same kill leaves the stream open (upstreamDeath_probeDisabled_
		// streamStaysOpen), so this closure proves the probes really ran.
		targetApp.close();
		targetApp = null;

		Awaitility.await("SSE stream closed after target loss on " + ProxyAppHarness.stack())
			.atMost(SSE_CLOSE_BUDGET)
			.pollInterval(Duration.ofMillis(200))
			.until(() -> sse.closed.get() || sse.error.get() != null);
	}

	@Test
	@DisplayName("Live upstream rejecting probe pings with HTTP 4xx keeps the SSE stream open")
	@Story("SSE liveness detection")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A live upstream that answers every probe ping POST with HTTP 4xx must not "
			+ "be failed: the rejection is a protocol-level error from a LIVE transport (the "
			+ "exact wire shape a server produces for pings it refuses), not transport-level "
			+ "death. A stub upstream serves the SSE prologue forever and rejects every POST "
			+ "with 400; the stream must stay open across two full probe cycles while the stub "
			+ "counts at least two rejected probe pings, proving the probes really ran")
	void upstreamAlive_pingRejected4xx_streamStaysOpen() throws Exception {
		// given: fast probe config (2s interval, 2s answer window, 2s idle
		// threshold) and a stub upstream whose SSE endpoint never ends but whose
		// message endpoint rejects every POST with HTTP 400: a live transport
		// refusing the ping at the protocol level.
		inspectorApp = ProxyAppHarness.start("SSE", false, null,
				"--spring.ai.mcp.inspector.upstream-liveness-probe-enabled=true",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-interval=PT2S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-timeout=PT2S",
				"--spring.ai.mcp.inspector.timeouts.upstream-probe-idle-threshold=PT2S");
		final java.util.concurrent.atomic.AtomicInteger pingPosts = new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.atomic.AtomicBoolean stubStopped = new java.util.concurrent.atomic.AtomicBoolean(
				false);
		final com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		stub.createContext("/", (exchange) -> {
			if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				pingPosts.incrementAndGet();
				final byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":\"mcpi-probe-stub\",\"error\":{\"code\":-32000}}"
					.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(400, body.length);
				try (var out = exchange.getResponseBody()) {
					out.write(body);
				}
				return;
			}
			// GET: serve the SSE prologue, then hold the stream open until stopped
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (var out = exchange.getResponseBody()) {
				out.write("event: endpoint\ndata: /message?sessionId=stub-sse-1\n\n".getBytes(StandardCharsets.UTF_8));
				out.flush();
				while (!stubStopped.get()) {
					try {
						Thread.sleep(1000);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		});
		final java.util.concurrent.ThreadFactory stubThreads = (runnable) -> {
			final Thread thread = new Thread(runnable, "sse-4xx-stub-" + ProxyAppHarness.stack());
			thread.setDaemon(true);
			return thread;
		};
		stub.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(stubThreads));
		stub.start();
		try {
			final int inspectorPort = ProxyAppHarness.port(inspectorApp);
			final String proxyBase = "http://127.0.0.1:" + inspectorPort + "/mcp-inspector-api";
			final String targetUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/sse";

			final SseStream sse = new SseStream(proxyBase, targetUrl, "sse-4xx-reader");

			// 1. The proxy relays the stub's endpoint prologue.
			Awaitility.await("endpoint prologue frame (4xx stub) on " + ProxyAppHarness.stack())
				.atMost(BUDGET)
				.pollInterval(Duration.ofMillis(100))
				.until(() -> sse.endpointData.get() != null);

			// 2. Stay idle for longer than two full probe cycles (4-6s worst case
			// each): every probe POST is answered with HTTP 400.
			Thread.sleep(Duration.ofSeconds(12).toMillis());

			// 3. The stream MUST still be open and error-free: a 4xx from a live
			// upstream is not transport-level death, the session must survive it.
			assertThat(sse.closed.get())
				.as("SSE stream must not close while the 4xx-rejecting target is alive on %s", ProxyAppHarness.stack())
				.isFalse();
			assertThat(sse.error.get())
				.as("SSE stream must not error while the 4xx-rejecting target is alive on %s", ProxyAppHarness.stack())
				.isNull();

			// 4. The probes really ran: the stub counted at least two ping POSTs
			// (answering each with 400), so survival is not "nothing happened".
			Awaitility.await("probe pings rejected with 400 by the stub on " + ProxyAppHarness.stack())
				.atMost(BUDGET)
				.pollInterval(Duration.ofMillis(200))
				.until(pingPosts::get, (count) -> count >= 2);
		}
		finally {
			stubStopped.set(true);
			stub.stop(0);
		}
	}

	/**
	 * Counts probe pings the target's MCP session received, from the captured console
	 * output: the target runs in the same JVM with {@code io.modelcontextprotocol} on
	 * DEBUG, and the SDK session class shared by both stacks logs every incoming request,
	 * so lines matching {@code Received request ... method=ping, id=mcpi-probe-...} prove
	 * the probe POSTs were delivered.
	 * @param output the captured console output of this test JVM
	 * @param baseline only console output appended after this offset counts
	 * @return the number of ping requests the target session received so far
	 */
	private static long countReceivedPingRequests(final CapturedOutput output, final int baseline) {
		return output.getAll()
			.substring(baseline)
			.lines()
			.filter((line) -> line.contains("Received request") && line.contains("method=ping, id=mcpi-probe-"))
			.count();
	}

	/**
	 * Reader handle for one open downstream SSE stream: tracks whether the stream closed
	 * or errored, captures the endpoint prologue and collects every relayed
	 * {@code message} frame so tests can assert on what the browser stream actually
	 * received.
	 */
	private static final class SseStream {

		final AtomicBoolean closed = new AtomicBoolean(false);

		final AtomicReference<String> endpointData = new AtomicReference<>();

		final AtomicReference<Throwable> error = new AtomicReference<>();

		final List<String> frames = Collections.synchronizedList(new ArrayList<>());

		SseStream(final String proxyBase, final String targetUrl, final String threadName) {
			final Thread reader = new Thread(() -> {
				try {
					final HttpRequest sseRequest = HttpRequest
						.newBuilder(URI.create(proxyBase + "/sse?transportType=sse&url="
								+ URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
						.timeout(Duration.ofSeconds(60))
						.header("Accept", "text/event-stream")
						.GET()
						.build();
					final HttpResponse<java.io.InputStream> response = HTTP.send(sseRequest,
							HttpResponse.BodyHandlers.ofInputStream());
					if (response.statusCode() != 200) {
						SseStream.this.error
							.set(new IllegalStateException("SSE handshake HTTP " + response.statusCode()));
						return;
					}
					try (var lines = new java.io.BufferedReader(
							new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
						String eventName = null;
						StringBuilder dataBuf = new StringBuilder();
						String line;
						while ((line = lines.readLine()) != null) {
							if (line.isEmpty()) {
								if ("endpoint".equals(eventName) && dataBuf.length() > 0) {
									SseStream.this.endpointData.set(dataBuf.toString());
								}
								else if ("message".equals(eventName) && dataBuf.length() > 0) {
									SseStream.this.frames.add(dataBuf.toString());
								}
								eventName = null;
								dataBuf.setLength(0);
								continue;
							}
							if (line.startsWith("event:")) {
								eventName = line.substring(6).trim();
							}
							else if (line.startsWith("data:")) {
								if (dataBuf.length() > 0) {
									dataBuf.append('\n');
								}
								dataBuf.append(line.substring(5).trim());
							}
						}
					}
					SseStream.this.closed.set(true);
				}
				catch (java.net.http.HttpTimeoutException ex) {
					SseStream.this.closed.set(true);
				}
				catch (Throwable t) {
					SseStream.this.error.set(t);
				}
			}, threadName + "-" + ProxyAppHarness.stack());
			reader.setDaemon(true);
			reader.start();
		}

	}

}
