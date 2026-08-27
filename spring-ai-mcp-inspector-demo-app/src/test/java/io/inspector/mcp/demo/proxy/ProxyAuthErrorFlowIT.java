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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the D3 error contract (plan v13, issue #54) on a real HTTP stack: upstream
 * 401/403 answers surface the exact structured error DTO on both transports (SSE relay
 * and streamable HTTP), upstream 3xx redirects surface the {@code redirect} DTO on the
 * SSE transport only, a streamable 3xx falls back to the legacy 502/504 without any DTO,
 * and an unsuccessful initial handshake leaves no partial connect state (the proxy
 * session is deleted, the next connect starts fresh).
 *
 * <p>
 * The class lives in {@code demo-app}'s test-jar, so Failsafe's
 * {@code dependenciesToScan} runs it once per stack module — every scenario is exercised
 * on BOTH webmvc and webflux (see {@code ProxyAppHarness.stack()} in the assertion
 * messages).
 *
 * <p>
 * The upstream is a tiny JDK {@link HttpServer} MCP stub whose response status is
 * switchable per phase (SSE handshake GET / streamable message POST), so each test drives
 * the failure without WireMock or Testcontainers. All requests to the proxy namespace
 * carry {@code X-MCP-Proxy-Auth: Bearer <token>}, mirroring the browser.
 */
@Epic("Inspector Auth Profiles")
@Feature("D3 error contract — 401/403/redirect on SSE and streamable transports")
class ProxyAuthErrorFlowIT {

	/** Fixed proxy auth token threaded through the demo via the harness. */
	private static final String AUTH_TOKEN = "auth-error-flow-it-token-0123456789";

	/** Proxy auth header ({@code ProxyAuthFilter}). */
	private static final String PROXY_AUTH_HEADER = "X-MCP-Proxy-Auth";

	/** Streamable session id header ({@code ProxyConstants}). */
	private static final String MCP_SESSION_ID_HEADER = "mcp-session-id";

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/** Per-request wall-clock budget. */
	private static final Duration BUDGET = Duration.ofSeconds(20);

	/** D3 exact literals ({@code ProxyErrorMapper}). */
	private static final String REASON_401 = "The MCP server rejected the request as unauthenticated.";

	private static final String GUIDANCE_401 = "Verify the token/API key. OAuth2 profiles refresh and retry once automatically.";

	private static final String REASON_403 = "The MCP server rejected the request as not permitted.";

	private static final String GUIDANCE_403 = "Verify the credential scope or permissions, then reconnect.";

	private static final String REASON_3XX = "The MCP server redirected the request and it was not followed.";

	private static final String GUIDANCE_3XX = "Check the server URL; redirects are not followed automatically.";

	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (app != null) {
			try {
				app.close();
			}
			catch (final Exception ignored) {
				/* best-effort */
			}
			app = null;
		}
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	/** Composes the proxy base (transport relay endpoints). */
	private String proxyBase() {
		return "http://127.0.0.1:" + ProxyAppHarness.port(app) + "/mcp-inspector-api";
	}

	/** Builds an MCP {@code initialize} request frame. */
	private static ObjectNode initializeFrame() {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "auth-error-flow-it");
		info.put("version", "0.1.0");
		return init;
	}

	/** Builds a {@code tools/list} request frame. */
	private static ObjectNode toolsListFrame() {
		final ObjectNode frame = MAPPER.createObjectNode();
		frame.put("jsonrpc", "2.0");
		frame.put("method", "tools/list");
		frame.put("id", 2);
		return frame;
	}

	/**
	 * POSTs a JSON-RPC frame to the proxy relay with the proxy auth header and an
	 * optional session id.
	 * @param url the relay URL
	 * @param sessionId the {@code mcp-session-id} header value, or {@code null}
	 * @param body the frame to send
	 * @return the relay response
	 */
	private static HttpResponse<String> postJson(final String url, final String sessionId, final JsonNode body)
			throws Exception {
		final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header(PROXY_AUTH_HEADER, "Bearer " + AUTH_TOKEN);
		if (sessionId != null) {
			builder.header(MCP_SESSION_ID_HEADER, sessionId);
		}
		final HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** POSTs an {@code initialize} frame that opens a fresh streamable session. */
	private static HttpResponse<String> postInitialize(final String base, final String targetUrl) throws Exception {
		return postJson(base + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8), null,
				initializeFrame());
	}

	/** Asserts the exact D3 DTO on an HTTP response body. */
	private static void assertDto(final HttpResponse<String> response, final int status, final String code,
			final String reason, final String guidance) throws Exception {
		assertThat(response.statusCode()).as("DTO HTTP status on %s, body=%s", ProxyAppHarness.stack(), response.body())
			.isEqualTo(status);
		assertDto(MAPPER.readTree(response.body()), status, code, reason, guidance);
	}

	/** Asserts the exact D3 DTO fields on a parsed JSON node. */
	private static void assertDto(final JsonNode dto, final int status, final String code, final String reason,
			final String guidance) {
		assertThat(dto.path("status").asInt()).as("DTO status on %s", ProxyAppHarness.stack()).isEqualTo(status);
		assertThat(dto.path("code").asText()).as("DTO code on %s", ProxyAppHarness.stack()).isEqualTo(code);
		assertThat(dto.path("reason").asText()).as("DTO reason on %s", ProxyAppHarness.stack()).isEqualTo(reason);
		assertThat(dto.path("guidance").asText()).as("DTO guidance on %s", ProxyAppHarness.stack()).isEqualTo(guidance);
	}

	/** Asserts the D3 code for the given upstream status. */
	private static String codeFor(final int status) {
		return switch (status) {
			case 401 -> "unauthorized";
			case 403 -> "forbidden";
			case 302 -> "redirect";
			default -> throw new IllegalArgumentException("unsupported status: " + status);
		};
	}

	private static String reasonFor(final int status) {
		return switch (status) {
			case 401 -> REASON_401;
			case 403 -> REASON_403;
			case 302 -> REASON_3XX;
			default -> throw new IllegalArgumentException("unsupported status: " + status);
		};
	}

	private static String guidanceFor(final int status) {
		return switch (status) {
			case 401 -> GUIDANCE_401;
			case 403 -> GUIDANCE_403;
			case 302 -> GUIDANCE_3XX;
			default -> throw new IllegalArgumentException("unsupported status: " + status);
		};
	}

	/** Returns the session id header of a successful handshake response. */
	private static String requireSessionId(final HttpResponse<String> response) {
		final String id = response.headers().firstValue(MCP_SESSION_ID_HEADER).orElse(null);
		assertThat(id).as("mcp-session-id on %s", ProxyAppHarness.stack()).isNotBlank();
		return id;
	}

	/**
	 * Polls the relay {@code /message} endpoint until the failed session is gone (404) —
	 * the no-partial-connect-state proof: a session whose handshake failed must not
	 * remain usable.
	 * @param base the proxy base URL
	 * @param sessionId the session id that must be gone
	 */
	private static void awaitSessionGone(final String base, final String sessionId) {
		Awaitility.await("session " + sessionId + " removed on " + ProxyAppHarness.stack())
			.atMost(Duration.ofSeconds(10))
			.pollInterval(Duration.ofMillis(100))
			.until(() -> {
				try {
					return postJson(base + "/message?sessionId=" + sessionId, null, initializeFrame())
						.statusCode() == 404;
				}
				catch (final Exception ex) {
					return false;
				}
			});
	}

	@Nested
	@DisplayName("Streamable transport (POST /mcp-inspector-api/mcp)")
	class Streamable {

		@ParameterizedTest(name = "upstream {0}")
		@ValueSource(ints = { 401, 403 })
		@DisplayName("handshake 401/403 answers the structured DTO and leaves no partial state")
		@Story("Streamable handshake errors")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The first POST /mcp (initialize) against an upstream answering 401/403 returns the exact D3 "
				+ "DTO with the matching HTTP status and no mcp-session-id; a fresh connect against a recovered "
				+ "upstream still succeeds — no partial connect state")
		void handshake_whenUpstreamAnswersAuthError_returnsStructuredDto(final int status) throws Exception {
			// given
			ProxyAuthErrorFlowIT.this.app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String base = proxyBase();
			try (final ScriptedMcpStub stub = new ScriptedMcpStub()) {
				stub.postStatus(status);

				// when — the first POST is the initialize handshake
				final HttpResponse<String> failed = postInitialize(base, stub.mcpUrl());

				// then — the exact D3 DTO with the upstream status
				assertDto(failed, status, codeFor(status), reasonFor(status), guidanceFor(status));

				// and — a failed handshake never issues a session id
				assertThat(failed.headers().firstValue(MCP_SESSION_ID_HEADER))
					.as("failed handshake must not issue a session id on %s", ProxyAppHarness.stack())
					.isEmpty();

				// when — the upstream recovers and the browser connects again
				stub.postStatus(200);
				final HttpResponse<String> recovered = postInitialize(base, stub.mcpUrl());

				// then — a fresh session is established (no partial connect state)
				assertThat(recovered.statusCode())
					.as("recovered handshake on %s, body=%s", ProxyAppHarness.stack(), recovered.body())
					.isEqualTo(200);
				assertThat(recovered.headers().firstValue(MCP_SESSION_ID_HEADER))
					.as("recovered session id on %s", ProxyAppHarness.stack())
					.isPresent();
			}
		}

		@Test
		@DisplayName("handshake 3xx falls back to legacy 502/504 without a DTO")
		@Story("Streamable redirect fallback")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A streamable handshake answered with 3xx never yields a DTO (the redirect DTO is SSE-only): "
				+ "the response is the legacy 502/504 envelope and no session id is issued")
		void handshake_whenUpstreamRedirects_fallsBackToLegacyWithoutDto() throws Exception {
			// given
			ProxyAuthErrorFlowIT.this.app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String base = proxyBase();
			try (final ScriptedMcpStub stub = new ScriptedMcpStub()) {
				stub.postStatus(302);

				// when
				final HttpResponse<String> failed = postInitialize(base, stub.mcpUrl());

				// then — legacy 502/504, never a D3 DTO (the envelope shape is
				// stack-specific, the contract is the status and the absent DTO)
				assertThat(failed.statusCode())
					.as("streamable 3xx status on %s, body=%s", ProxyAppHarness.stack(), failed.body())
					.isIn(502, 504);
				assertThat(failed.body()).as("legacy envelope (no D3 DTO) on %s", ProxyAppHarness.stack())
					.doesNotContain("\"guidance\"", "\"redirect\"");
				assertThat(failed.headers().firstValue(MCP_SESSION_ID_HEADER))
					.as("failed handshake must not issue a session id on %s", ProxyAppHarness.stack())
					.isEmpty();
			}
		}

		@ParameterizedTest(name = "upstream {0}")
		@ValueSource(ints = { 401, 403 })
		@DisplayName("a 401/403 on a later call answers the structured DTO")
		@Story("Streamable per-call errors")
		@Severity(SeverityLevel.CRITICAL)
		@Description("After a successful handshake, a later call (tools/list) answered with 401/403 returns the "
				+ "exact D3 DTO with the matching HTTP status")
		void laterCall_whenUpstreamAnswersAuthError_returnsStructuredDto(final int status) throws Exception {
			// given — a healthy handshake establishes the session
			ProxyAuthErrorFlowIT.this.app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String base = proxyBase();
			try (final ScriptedMcpStub stub = new ScriptedMcpStub()) {
				final HttpResponse<String> handshake = postInitialize(base, stub.mcpUrl());
				assertThat(handshake.statusCode())
					.as("handshake on %s, body=%s", ProxyAppHarness.stack(), handshake.body())
					.isEqualTo(200);
				final String sessionId = requireSessionId(handshake);

				// when — the upstream starts rejecting the session's calls
				stub.postStatus(status);
				final HttpResponse<String> failed = postJson(
						base + "/mcp?url=" + URLEncoder.encode(stub.mcpUrl(), StandardCharsets.UTF_8), sessionId,
						toolsListFrame());

				// then — the exact D3 DTO
				assertDto(failed, status, codeFor(status), reasonFor(status), guidanceFor(status));
			}
		}

	}

	@Nested
	@DisplayName("SSE transport (GET /mcp-inspector-api/sse)")
	class Sse {

		@ParameterizedTest(name = "upstream {0}")
		@ValueSource(ints = { 401, 403 })
		@DisplayName("handshake 401/403 emits the structured error event and deletes the session")
		@Story("SSE handshake errors")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An SSE handshake whose upstream connect is answered 401/403 emits the endpoint prologue, then "
				+ "a structured error event with the exact D3 DTO (status/code/reason/guidance/redacted url), then "
				+ "completes the stream; the failed session is deleted (POST /message answers 404) — no partial "
				+ "connect state")
		void handshake_whenUpstreamAnswersAuthError_emitsErrorEventAndDeletesSession(final int status)
				throws Exception {
			// given
			ProxyAuthErrorFlowIT.this.app = ProxyAppHarness.start("SSE", true, AUTH_TOKEN);
			final String base = proxyBase();
			try (final ScriptedMcpStub stub = new ScriptedMcpStub()) {
				stub.sseStatus(status);
				final SseStream stream = new SseStream(base, stub.sseUrl());

				// when & then — the prologue announces the session, the error event
				// carries the DTO
				final String sessionId = stream.sessionId();
				final SseFrame errorFrame = stream.awaitFrame("error");
				assertDto(MAPPER.readTree(errorFrame.data()), status, codeFor(status), reasonFor(status),
						guidanceFor(status));
				assertThat(MAPPER.readTree(errorFrame.data()).path("url").asText())
					.as("redacted url on %s", ProxyAppHarness.stack())
					.isEqualTo(stub.sseUrl());

				// and — the stream ends normally, no messages were relayed
				stream.awaitEnd();
				assertThat(stream.error()).as("SSE reader on %s", ProxyAppHarness.stack()).isNull();
				assertThat(stream.frames()).as("no message frames on %s", ProxyAppHarness.stack())
					.noneMatch((frame) -> "message".equals(frame.event()));

				// and — the failed handshake's session is deleted
				awaitSessionGone(base, sessionId);
			}
		}

		@Test
		@DisplayName("handshake 3xx emits the redirect event (SSE-only) and deletes the session")
		@Story("SSE handshake redirect")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An SSE handshake whose upstream connect is answered 3xx emits a structured error event with "
				+ "the exact redirect DTO — the redirect DTO is SSE-only; the failed session is deleted (POST "
				+ "/message answers 404) — no partial connect state")
		void handshake_whenUpstreamRedirects_emitsRedirectEventAndDeletesSession() throws Exception {
			// given
			ProxyAuthErrorFlowIT.this.app = ProxyAppHarness.start("SSE", true, AUTH_TOKEN);
			final String base = proxyBase();
			try (final ScriptedMcpStub stub = new ScriptedMcpStub()) {
				stub.sseStatus(302);
				final SseStream stream = new SseStream(base, stub.sseUrl());

				// when & then — the error event carries the redirect DTO
				final String sessionId = stream.sessionId();
				final SseFrame errorFrame = stream.awaitFrame("error");
				assertDto(MAPPER.readTree(errorFrame.data()), 302, codeFor(302), reasonFor(302), guidanceFor(302));
				assertThat(MAPPER.readTree(errorFrame.data()).path("url").asText())
					.as("redacted url on %s", ProxyAppHarness.stack())
					.isEqualTo(stub.sseUrl());

				// and — the stream ends normally and the session is deleted
				stream.awaitEnd();
				assertThat(stream.error()).as("SSE reader on %s", ProxyAppHarness.stack()).isNull();
				awaitSessionGone(base, sessionId);
			}
		}

		@ParameterizedTest(name = "upstream {0}")
		@ValueSource(ints = { 401, 403 })
		@DisplayName("a 401/403 on a later call terminates the stream and deletes the session")
		@Story("SSE per-call errors")
		@Severity(SeverityLevel.CRITICAL)
		@Description("After the SSE prologue, a relayed initialize answered 401/403 terminates the stream within "
				+ "bounded time; when the stack maps the failure it emits the exact D3 error event; the failed "
				+ "session is deleted (POST /message answers 404) — no partial connect state")
		void laterCall_whenUpstreamAnswersAuthError_terminatesStreamAndDeletesSession(final int status)
				throws Exception {
			// given — the upstream accepts the SSE handshake and the session opens
			ProxyAuthErrorFlowIT.this.app = ProxyAppHarness.start("SSE", true, AUTH_TOKEN);
			final String base = proxyBase();
			try (final ScriptedMcpStub stub = new ScriptedMcpStub()) {
				final SseStream stream = new SseStream(base, stub.sseUrl());
				final String sessionId = stream.sessionId();

				// when — the initialize frame is relayed and the upstream answers
				// 401/403
				stub.postStatus(status);
				final HttpResponse<String> accepted = postJson(base + "/message?sessionId=" + sessionId, null,
						initializeFrame());

				// then — the relay accepts the frame, then the stream terminates
				assertThat(accepted.statusCode()).as("relay /message on %s", ProxyAppHarness.stack()).isEqualTo(202);
				stream.awaitEnd();

				// and — the structured error event is emitted when the stack maps
				// the failure
				final List<SseFrame> errors = stream.frames()
					.stream()
					.filter((frame) -> "error".equals(frame.event()))
					.toList();
				if (!errors.isEmpty()) {
					assertDto(MAPPER.readTree(errors.get(0).data()), status, codeFor(status), reasonFor(status),
							guidanceFor(status));
				}

				// and — the failed session is deleted (no partial connect state)
				awaitSessionGone(base, sessionId);
			}
		}

	}

	// ---------------------------------------------------------------------
	// fixtures
	// ---------------------------------------------------------------------

	/**
	 * Minimal streamable/SSE MCP stub whose response status is switchable per phase: the
	 * SSE handshake GET and the message/initialize POST are controlled independently, so
	 * each test drives the exact failure it asserts. No WireMock, no Testcontainers.
	 */
	private static final class ScriptedMcpStub implements AutoCloseable {

		private final HttpServer server;

		private final AtomicBoolean stopped = new AtomicBoolean();

		/** Status for {@code GET /sse} (the SSE transport handshake). */
		private volatile int sseStatus = 200;

		/** Status for {@code POST /message} / {@code POST /mcp} (relayed frames). */
		private volatile int postStatus = 200;

		/**
		 * Body of a failed handshake answer. The SDK's SSE line subscriber only emits a
		 * response event when the body carries at least one line, and its per-event
		 * status gate (2xx-only) is what turns the failure into a connect error the proxy
		 * can map — an empty 401/3xx body would complete the connect silently.
		 */
		private static final byte[] REJECTED = "request rejected\n".getBytes(StandardCharsets.UTF_8);

		ScriptedMcpStub() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/sse", this::handleSse);
			this.server.createContext("/message", this::handleMessage);
			this.server.createContext("/mcp", this::handleMcp);
			this.server.start();
		}

		int port() {
			return this.server.getAddress().getPort();
		}

		String sseUrl() {
			return "http://127.0.0.1:" + port() + "/sse";
		}

		String mcpUrl() {
			return "http://127.0.0.1:" + port() + "/mcp";
		}

		void sseStatus(final int status) {
			this.sseStatus = status;
		}

		void postStatus(final int status) {
			this.postStatus = status;
		}

		@Override
		public void close() {
			this.stopped.set(true);
			this.server.stop(0);
		}

		/**
		 * Answers the SSE handshake. A non-200 status fails the connect with a
		 * body-bearing response (see {@link #REJECTED}); a 200 writes the
		 * {@code endpoint} prologue pointing at {@code /message} and holds the stream
		 * open until the stub is closed.
		 */
		private void handleSse(final HttpExchange exchange) throws IOException {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				exchange.close();
				return;
			}
			final int status = this.sseStatus;
			if (status != 200) {
				exchange.sendResponseHeaders(status, REJECTED.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(REJECTED);
				}
				return;
			}
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
				out.flush();
				while (!this.stopped.get()) {
					Thread.sleep(50);
				}
			}
			catch (final InterruptedException | IOException ignored) {
				// stream ended or the stub was stopped
			}
		}

		/**
		 * Answers the SSE message POST (202 when healthy, the configured status
		 * otherwise).
		 */
		private void handleMessage(final HttpExchange exchange) throws IOException {
			final int status = (this.postStatus == 200) ? 202 : this.postStatus;
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		}

		/**
		 * Answers the streamable transport like a real streamable MCP server in
		 * request-response mode: {@code GET /mcp} answers 405, so the SDK does not open
		 * an SSE channel and every {@code POST /mcp} carries the JSON-RPC answer in its
		 * own body (the same shape the profile CRUD ITs use); a non-200
		 * {@code postStatus} answers the POST directly.
		 */
		private void handleMcp(final HttpExchange exchange) throws IOException {
			if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				exchange.close();
				return;
			}
			final int status = this.postStatus;
			if (status != 200) {
				exchange.sendResponseHeaders(status, -1);
				exchange.close();
				return;
			}
			final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			final JsonNode request = MAPPER.readTree(requestBody);
			final String responseBody = """
					{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-11-25",\
					"capabilities":{},"serverInfo":{"name":"stub-mcp","version":"1.0.0"}}}"""
				.formatted(request.path("id").asText());
			final byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.getResponseHeaders().add("Mcp-Session-Id", "stub-session-1");
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(payload);
			}
		}

	}

	/** One SSE frame of the relay stream. */
	private record SseFrame(String event, String data) {
	}

	/**
	 * Long-lived SSE stream reader against {@code GET /mcp-inspector-api/sse}: frames are
	 * accumulated on a background thread and awaited with Awaitility, mirroring the
	 * browser's EventSource.
	 */
	private static final class SseStream {

		private final CopyOnWriteArrayList<SseFrame> frames = new CopyOnWriteArrayList<>();

		private final AtomicReference<Throwable> error = new AtomicReference<>();

		private final AtomicBoolean finished = new AtomicBoolean();

		SseStream(final String base, final String targetUrl) {
			final Thread thread = new Thread(() -> {
				try {
					final HttpRequest request = HttpRequest
						.newBuilder(URI.create(base + "/sse?transportType=sse&url="
								+ URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
						.timeout(Duration.ofSeconds(30))
						.header("Accept", "text/event-stream")
						.header(PROXY_AUTH_HEADER, "Bearer " + AUTH_TOKEN)
						.GET()
						.build();
					final HttpResponse<java.io.InputStream> response = HTTP.send(request,
							HttpResponse.BodyHandlers.ofInputStream());
					if (response.statusCode() != 200) {
						this.error.set(new IllegalStateException("SSE handshake HTTP " + response.statusCode()));
						return;
					}
					try (BufferedReader reader = new BufferedReader(
							new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
						String eventName = null;
						StringBuilder dataBuf = new StringBuilder();
						String line;
						while ((line = reader.readLine()) != null) {
							if (line.isEmpty()) {
								if (eventName != null || dataBuf.length() > 0) {
									this.frames.add(new SseFrame(eventName == null ? "message" : eventName,
											dataBuf.toString()));
									eventName = null;
									dataBuf.setLength(0);
								}
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
				}
				catch (final Throwable t) {
					this.error.set(t);
				}
				finally {
					this.finished.set(true);
				}
			}, "sse-reader-" + ProxyAppHarness.stack());
			thread.setDaemon(true);
			thread.start();
		}

		/** Awaits the first frame with the given event name and returns it. */
		SseFrame awaitFrame(final String event) {
			Awaitility.await("SSE " + event + " frame on " + ProxyAppHarness.stack())
				.atMost(Duration.ofSeconds(15))
				.pollInterval(Duration.ofMillis(50))
				.until(() -> this.frames.stream().anyMatch((frame) -> event.equals(frame.event())));
			return this.frames.stream().filter((frame) -> event.equals(frame.event())).findFirst().orElseThrow();
		}

		/** Awaits the endpoint prologue and extracts the relay session id. */
		String sessionId() {
			final SseFrame endpoint = awaitFrame("endpoint");
			final String data = endpoint.data();
			assertThat(data).as("endpoint prologue on %s", ProxyAppHarness.stack())
				.startsWith("/mcp-inspector-api/message?sessionId=");
			return data.substring(data.indexOf("sessionId=") + "sessionId=".length());
		}

		/** Awaits the end of the stream (the relay completed or aborted it). */
		void awaitEnd() {
			Awaitility.await("SSE stream end on " + ProxyAppHarness.stack())
				.atMost(Duration.ofSeconds(15))
				.pollInterval(Duration.ofMillis(50))
				.until(this.finished::get);
		}

		List<SseFrame> frames() {
			return this.frames;
		}

		Throwable error() {
			return this.error.get();
		}

	}

}
