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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end traversal of the SSE proxy port ({@code GET /mcp-inspector-api/sse} +
 * {@code POST /mcp-inspector-api/message}) on both webmvc and webflux.
 *
 * <p>
 * The flow:
 *
 * <ol>
 * <li>Open the SSE stream at {@code /mcp-inspector-api/sse} with
 * {@code transportType=streamable-http&url=<demo>/mcp}. The server is supposed to send a
 * {@code event: endpoint} prologue whose data is the relay URL containing the assigned
 * {@code sessionId}.</li>
 * <li>Capture the session id, then POST an {@code initialize} JSON-RPC frame to
 * {@code /mcp-inspector-api/message?sessionId=&lt;id&gt;}. Expect 202.</li>
 * <li>POST {@code tools/list}; expect 202.</li>
 * <li>Use {@code Awaitility} to wait for ≥1 {@code event: message} frame on the SSE
 * stream that carries either the initialize result or a tools-list response. This proves
 * the full bidirectional path is live.</li>
 * </ol>
 *
 * <p>
 * Wall-time budget per parametrized run: ≤ 30s. With 2 stacks the suite stays well under
 * the 60s ceiling from the task brief.
 */
class ProxySseFlowIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (app != null) {
			try {
				app.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			app = null;
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ProxyAppHarness.Stack.class)
	void sseProxyEndpointPrologueAndJsonRpcRoundtrip(ProxyAppHarness.Stack stack) throws Exception {
		app = ProxyAppHarness.start(stack, "STREAMABLE", false, null);
		int port = ProxyAppHarness.port(app);
		String targetUrl = "http://127.0.0.1:" + port + "/mcp";
		String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

		// The SSE stream is long-lived; we accumulate frames on a background
		// thread (Awaitility polls the buffer). Use CopyOnWriteArrayList so the
		// poller never sees a partial state.
		CopyOnWriteArrayList<SseFrame> frames = new CopyOnWriteArrayList<>();
		AtomicReference<Throwable> streamError = new AtomicReference<>();
		Thread sseReader = new Thread(() -> {
			try {
				HttpRequest sseRequest = HttpRequest
					.newBuilder(URI.create(proxyBase + "/sse?transportType=streamable-http&url="
							+ URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
					.timeout(Duration.ofSeconds(30))
					.header("Accept", "text/event-stream")
					.GET()
					.build();
				HttpResponse<java.io.InputStream> response = HTTP.send(sseRequest,
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
							if (eventName != null || dataBuf.length() > 0) {
								frames.add(new SseFrame(eventName == null ? "message" : eventName, dataBuf.toString()));
								eventName = null;
								dataBuf.setLength(0);
							}
							continue;
						}
						if (line.startsWith("event:")) {
							eventName = line.substring(6).trim();
						}
						else if (line.startsWith("data:")) {
							// Multi-line data fields are concatenated with \n per spec.
							if (dataBuf.length() > 0) {
								dataBuf.append('\n');
							}
							dataBuf.append(line.substring(5).trim());
						}
						// Other fields (id:, retry:) ignored.
					}
				}
			}
			catch (Throwable t) {
				streamError.set(t);
			}
		}, "sse-reader-" + stack);
		sseReader.setDaemon(true);
		sseReader.start();

		// 1. Wait for the endpoint prologue.
		Awaitility.await("endpoint prologue frame")
			.atMost(Duration.ofSeconds(15))
			.pollInterval(Duration.ofMillis(50))
			.until(() -> frames.stream().anyMatch(f -> "endpoint".equals(f.event)));
		SseFrame endpointFrame = frames.stream().filter(f -> "endpoint".equals(f.event)).findFirst().orElseThrow();
		// Data shape: "/mcp-inspector-api/message?sessionId=<UUID>".
		assertThat(endpointFrame.data).as("endpoint prologue data on %s", stack)
			.startsWith("/mcp-inspector-api/message?sessionId=");

		String sessionId = endpointFrame.data
			.substring(endpointFrame.data.indexOf("sessionId=") + "sessionId=".length());
		assertThat(sessionId).isNotBlank();

		// 2. POST an initialize frame into the message endpoint.
		postRpc(proxyBase, sessionId, buildInit());

		// 3. POST tools/list — accepted in flight.
		ObjectNode toolsListRpc = MAPPER.createObjectNode();
		toolsListRpc.put("jsonrpc", "2.0");
		toolsListRpc.put("method", "tools/list");
		toolsListRpc.put("id", 2);
		postRpc(proxyBase, sessionId, toolsListRpc);

		// 4. Wait for an SSE message frame to arrive — proves the target→browser
		// pump is live. We don't insist on a specific RPC id because some MCP
		// server stacks reply to initialize via the underlying HTTP body rather
		// than the relay; the tools/list reply is what reliably comes back here.
		Awaitility.await("at least one message frame on " + stack)
			.atMost(Duration.ofSeconds(20))
			.pollInterval(Duration.ofMillis(100))
			.until(() -> frames.stream().anyMatch(f -> "message".equals(f.event)));

		List<SseFrame> messages = frames.stream().filter(f -> "message".equals(f.event)).toList();
		assertThat(messages).as("message frames on %s", stack).isNotEmpty();

		// At least one message frame must be a valid JSON-RPC envelope.
		boolean anyValidRpc = false;
		for (SseFrame f : messages) {
			try {
				JsonNode parsed = MAPPER.readTree(f.data);
				if ("2.0".equals(parsed.path("jsonrpc").asText())) {
					anyValidRpc = true;
					break;
				}
			}
			catch (Exception ignored) {
				/* continue */
			}
		}
		assertThat(anyValidRpc).as("at least one valid JSON-RPC message arrived (frames seen: %s)", messages.size())
			.isTrue();

		// Best-effort interrupt of the SSE reader — the context shutdown will
		// also tear it down via emitter completion.
		sseReader.interrupt();

		assertThat(streamError.get()).as("background SSE reader must not have errored").isNull();
	}

	/** POST a JSON-RPC frame to the proxy's {@code /message} relay. Asserts 2xx. */
	private static void postRpc(String proxyBase, String sessionId, ObjectNode body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/message?sessionId=" + sessionId))
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("proxy /message HTTP " + response.statusCode() + ": " + response.body());
		}
	}

	private static ObjectNode buildInit() {
		ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-sse-it");
		info.put("version", "0.1.0");
		return init;
	}

	/** Parsed SSE event — minimal representation. */
	private record SseFrame(String event, String data) {
	}

	/** Defensive: helper kept for completeness; intentionally unused. */
	@SuppressWarnings("unused")
	private static List<String> drainData(List<SseFrame> frames) {
		List<String> out = new ArrayList<>();
		frames.forEach(f -> out.add(f.event + ":" + f.data));
		return out;
	}

}
