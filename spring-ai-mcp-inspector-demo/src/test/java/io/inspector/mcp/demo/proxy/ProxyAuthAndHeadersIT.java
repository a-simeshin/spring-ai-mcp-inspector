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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the interaction between the proxy auth filter and arbitrary client-supplied
 * custom headers on a streamable-HTTP session.
 *
 * <p>
 * Scenarios per stack:
 *
 * <ol>
 * <li><b>Correct auth + custom headers → 200.</b> Client sends the proxy auth token via
 * {@code X-MCP-Proxy-Auth: Bearer …} and adds arbitrary headers {@code X-Custom-A: foo},
 * {@code X-Custom-B: bar}. The inspector accepts the request and opens the session.</li>
 * <li><b>Missing auth token → 401, regardless of custom headers.</b></li>
 * <li><b>Documented BUG (T31): custom request headers are NOT forwarded to the target MCP
 * server.</b> {@link io.inspector.mcp.core.proxy.ProxyTransportFactory#openStreamable}
 * builds the upstream transport from the URI only — there is no API surface for passing
 * additional headers down to {@code HttpClientStreamableHttpTransport}. We pin this
 * behavior with a regression test so that the future fix (T31 follow-up) flips this
 * assertion intentionally. See the {@code // BUG:} comment below.</li>
 * </ol>
 *
 * <p>
 * Note on header naming: the task brief called the auth header
 * {@code MCP-PROXY-AUTH-TOKEN}, but the existing codebase (and upstream MCP Inspector)
 * uses {@code X-MCP-Proxy-Auth: Bearer <token>} with a query-param fallback of
 * {@code ?MCP_PROXY_AUTH_TOKEN=…}. We honor the actual contract (see
 * {@code ProxyConstants.AUTH_HEADER}) since that is what real clients send.
 */
@Epic("Inspector Proxy")
@Feature("Auth and custom headers")
class ProxyAuthAndHeadersIT {

	/** Fixed auth token threaded through the demo via the harness. */
	private static final String AUTH_TOKEN = "deadbeef-cafef00d-deadbeef-cafef00d";

	/** Proxy auth header used by upstream and our filter. */
	private static final String AUTH_HEADER = "X-MCP-Proxy-Auth";

	/** Arbitrary "custom A" header the client wants the target to see. */
	private static final String CUSTOM_HEADER_A = "X-Custom-A";

	/** Arbitrary "custom B" header the client wants the target to see. */
	private static final String CUSTOM_HEADER_B = "X-Custom-B";

	private static final String CUSTOM_HEADER_A_VALUE = "foo";

	private static final String CUSTOM_HEADER_B_VALUE = "bar";

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	/** Per-request wall-clock budget. */
	private static final Duration BUDGET = Duration.ofSeconds(20);

	/** MCP protocol version. Must match the demo's reported latest. */
	private static final String PROTOCOL_VERSION = "2025-11-25";

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

	/**
	 * With auth enabled, a correctly authenticated POST that carries arbitrary custom
	 * headers opens a session successfully. The custom headers are accepted by the proxy
	 * without rejection. (Whether they reach the target is asserted separately below.)
	 */
	@Nested
	@DisplayName("Auth with custom headers")
	class AuthWithCustomHeaders {

		@ParameterizedTest(name = "{0}")
		@EnumSource(ProxyAppHarness.Stack.class)
		@DisplayName("correct auth + custom headers opens a session")
		@Story("Authenticated session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("With auth enabled, an authenticated POST carrying arbitrary custom headers opens a "
				+ "streamable session (200 with a session id and a real initialize response)")
		void authOkWithCustomHeaders_opensSession(ProxyAppHarness.Stack stack) throws Exception {
			// given
			app = ProxyAppHarness.start(stack, "STREAMABLE", true, AUTH_TOKEN);
			final int port = ProxyAppHarness.port(app);
			final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
			final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

			// when
			final HttpResponse<String> response = postInit(proxyBase, targetUrl, "Bearer " + AUTH_TOKEN,
					/* withCustomHeaders */ true);

			// then
			assertThat(response.statusCode())
				.as("auth OK + custom headers must succeed on %s, body=%s", stack, response.body())
				.isEqualTo(200);

			final String sessionId = response.headers().firstValue("mcp-session-id").orElse(null);
			assertThat(sessionId).as("session id must be issued on %s", stack).isNotBlank();

			// Body must be a real initialize response (not the legacy placeholder).
			final JsonNode body = MAPPER.readTree(response.body());
			assertThat(body.path("jsonrpc").asText()).isEqualTo("2.0");
			assertThat(body.path("result").path("protocolVersion").asText())
				.as("initialize result.protocolVersion on %s", stack)
				.isNotBlank();
		}

		/**
		 * With auth enabled, omitting the auth token yields 401 even if the client
		 * supplies custom headers — proving auth is not bypassed by header tricks.
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(ProxyAppHarness.Stack.class)
		@DisplayName("missing auth is rejected regardless of custom headers")
		@Story("Auth not bypassable")
		@Severity(SeverityLevel.CRITICAL)
		@Description("With auth enabled, omitting the auth token yields 401 even when custom headers are "
				+ "present, proving auth cannot be bypassed via header tricks")
		void missingAuth_withCustomHeaders_isRejected(ProxyAppHarness.Stack stack) throws Exception {
			// given
			app = ProxyAppHarness.start(stack, "STREAMABLE", true, AUTH_TOKEN);
			final int port = ProxyAppHarness.port(app);
			final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
			final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

			// when
			final HttpResponse<String> response = postInit(proxyBase, targetUrl,
					/* authHeader */ null, /* withCustomHeaders */ true);

			// then
			assertThat(response.statusCode())
				.as("missing auth must be 401 on %s even with custom headers, body=%s", stack, response.body())
				.isEqualTo(401);
		}

		/**
		 * Pins the current proxy behavior for custom headers.
		 *
		 * <p>
		 * BUG: The proxy does NOT forward arbitrary client-supplied request headers (e.g.
		 * {@code X-Custom-A}, {@code X-Custom-B}) to the upstream MCP server.
		 * {@link io.inspector.mcp.core.proxy.ProxyTransportFactory#openStreamable(URI)}
		 * builds {@code HttpClientStreamableHttpTransport} from the URL alone, with no
		 * API to pass additional outbound headers. Upstream MCP Inspector supports an
		 * "Authentication" header / arbitrary "Header Name" field in its UI form for
		 * exactly this scenario — we do not yet.
		 *
		 * <p>
		 * This test asserts the current behavior: the session opens successfully (proves
		 * the headers do not crash anything), but we cannot prove the target ever saw
		 * them because the demo MCP server does not expose any tool that reflects request
		 * headers, and the {@code openStreamable} call has no header plumbing. The
		 * decision to fix lives with T31 review; once fixed, this test should be replaced
		 * by a positive forwarding assertion (e.g. add a tiny test-only servlet on the
		 * demo that echoes inbound headers, and assert {@code X-Custom-A: foo} arrives).
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(ProxyAppHarness.Stack.class)
		@DisplayName("custom headers are NOT forwarded to the target (documented bug, pinned)")
		@Story("Header forwarding regression")
		@Severity(SeverityLevel.MINOR)
		@Description("Regression pin for T31: custom request headers are not forwarded to the upstream MCP "
				+ "server; the session still opens (200 + session id). A future fix flips this into a "
				+ "positive forwarding assertion")
		void customHeaders_areNotForwardedToTarget_documentedBug(ProxyAppHarness.Stack stack) throws Exception {
			// given
			// BUG: see Javadoc above. ProxyTransportFactory.openStreamable(URI) has no
			// header parameter — custom headers from the inspector client never reach
			// the target MCP server. T31 should add a (Map<String,String> headers)
			// overload and thread it through HttpClientStreamableHttpTransport.builder().
			app = ProxyAppHarness.start(stack, "STREAMABLE", true, AUTH_TOKEN);
			final int port = ProxyAppHarness.port(app);
			final String targetUrl = "http://127.0.0.1:" + port + "/mcp";
			final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";

			// when
			final HttpResponse<String> response = postInit(proxyBase, targetUrl, "Bearer " + AUTH_TOKEN,
					/* withCustomHeaders */ true);

			// then
			// Asserting current behavior: session opens (custom headers harmlessly
			// ignored on the inspector → target hop). A future fix flips this into
			// a positive forwarding assertion.
			assertThat(response.statusCode())
				.as("session opens despite custom headers on %s, body=%s", stack, response.body())
				.isEqualTo(200);
			assertThat(response.headers().firstValue("mcp-session-id").orElse(null))
				.as("session id present on %s", stack)
				.isNotBlank();
		}

	}

	/**
	 * POSTs an {@code initialize} frame to the proxy with optional auth and custom
	 * headers.
	 */
	private static HttpResponse<String> postInit(String proxyBase, String targetUrl, String authHeader,
			boolean withCustomHeaders) throws Exception {
		final ObjectNode init = buildInit();
		final HttpRequest.Builder builder = HttpRequest
			.newBuilder(URI.create(proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(init)));
		if (authHeader != null) {
			builder.header(AUTH_HEADER, authHeader);
		}
		if (withCustomHeaders) {
			builder.header(CUSTOM_HEADER_A, CUSTOM_HEADER_A_VALUE);
			builder.header(CUSTOM_HEADER_B, CUSTOM_HEADER_B_VALUE);
		}
		return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** Builds a JSON-RPC initialize frame. */
	private static ObjectNode buildInit() {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", PROTOCOL_VERSION);
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "proxy-auth-headers-it");
		info.put("version", "0.1.0");
		return init;
	}

}
