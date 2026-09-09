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

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.modelcontextprotocol.spec.McpSchema;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration tests verifying that restricted custom header names are blocked at
 * {@link ProxyTransportFactory} build time: the upstream server receives no request when
 * a restricted header is configured, and allowed headers reach it on the first HTTP call.
 *
 * <p>
 * Unlike the unit tests in {@code ProxyTransportFactoryTests}, these tests use a real
 * HTTP server (JDK {@code HttpServer}) as the upstream target, so they prove that the
 * validation prevents any network I/O when a restricted header is present, and that
 * allowed headers survive the customizer pipeline end-to-end.
 */
@Epic("Inspector Proxy")
@Feature("Forbidden headers: integration")
class ProxyForbiddenHeadersIT {

	private static final ProxyTransportFactory FACTORY = new ProxyTransportFactory();

	private HttpServer server;

	private int serverPort;

	/**
	 * Thread-safe list of header maps received by the mock server, one entry per request.
	 */
	private final List<Map<String, List<String>>> receivedHeaders = new CopyOnWriteArrayList<>();

	/** Request counter shared with the mock handler. */
	private final AtomicInteger requestCount = new AtomicInteger(0);

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.stop(0);
		}
	}

	/**
	 * Starts a mock HTTP server that records every request's headers and returns a
	 * minimal valid MCP initialization response.
	 */
	private void startMockServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/mcp", (HttpExchange exchange) -> {
			// Record the headers from this request
			final Map<String, List<String>> headers = new HashMap<>();
			for (final Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
				headers.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			}
			receivedHeaders.add(headers);
			requestCount.incrementAndGet();

			// Return a minimal valid JSON-RPC initialize response so the MCP SDK
			// transport does not error on the first HTTP exchange.
			final String response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
					+ "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"serverInfo\":{"
					+ "\"name\":\"mock\",\"version\":\"1.0.0\"}}}";
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length());
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response.getBytes());
			}
		});
		server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
		server.start();
		serverPort = server.getAddress().getPort();
	}

	// -------------------------------------------------------------------------
	// Negative tests: restricted header blocks transport build
	// -------------------------------------------------------------------------

	@Test
	@DisplayName("restricted header blocks transport build: upstream receives no request")
	@Story("Restricted header prevention")
	@Severity(SeverityLevel.CRITICAL)
	@Description("When a restricted header name (e.g. \"host\") is passed to openStreamable(), "
			+ "the transport build throws IllegalArgumentException and the mock upstream server "
			+ "receives no request at all: proving the validation fires before any network I/O")
	void restrictedHeader_blocksTransportBuild_andUpstreamReceivesNoRequest() throws Exception {
		// given
		startMockServer();
		final URI mcpUri = URI.create("http://127.0.0.1:" + serverPort + "/mcp");

		// when & then: build fails with diagnostic message
		assertThatThrownBy(() -> FACTORY.openStreamable(mcpUri, null, Map.of("host", "evil.example.com")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("host")
			.hasMessageNotContaining("evil.example.com");

		// then: no request reached the upstream
		assertThat(requestCount.get())
			.as("upstream must not receive any request when a restricted header name is configured")
			.isZero();
	}

	@Test
	@DisplayName("restricted header: exception message never leaks the header value")
	@Story("Restricted header prevention: secrecy")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The exception message for a restricted header contains the header name "
			+ "and the JDK reason, but NEVER the header value (even a secret-looking one)")
	void restrictedHeader_exceptionMessage_neverLeaksSecretValue() throws Exception {
		// given
		startMockServer();
		final URI mcpUri = URI.create("http://127.0.0.1:" + serverPort + "/mcp");

		// when
		final Throwable thrown = catchThrowable(
				() -> FACTORY.openStreamable(mcpUri, null, Map.of("host", "super-secret-value")));

		// then: the exception is the right type
		assertThat(thrown).isInstanceOf(IllegalArgumentException.class);

		// then: the message always contains the header name
		assertThat(thrown.getMessage()).as("exception message must contain header name").contains("host");

		// then: the full stack trace NEVER contains the secret value
		final StringWriter sw = new StringWriter();
		thrown.printStackTrace(new PrintWriter(sw));
		final String fullTrace = sw.toString();
		assertThat(fullTrace).as("full stack trace must not leak the header value")
			.doesNotContain("super-secret-value");
	}

	@Test
	@DisplayName("restricted header: case-insensitive matching works end-to-end")
	@Story("Restricted header prevention: case-insensitive")
	@Severity(SeverityLevel.NORMAL)
	@Description("\"Host\" (capitalized) is the same restricted header as \"host\": "
			+ "the validation is case-insensitive, proven by the upstream receiving no request")
	void restrictedHeader_caseInsensitiveMatching_blocksTransportBuild() throws Exception {
		// given
		startMockServer();
		final URI mcpUri = URI.create("http://127.0.0.1:" + serverPort + "/mcp");

		// when & then
		assertThatThrownBy(() -> FACTORY.openStreamable(mcpUri, null, Map.of("Host", "evil.example.com")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Host")
			.hasMessageNotContaining("evil.example.com");

		// then: no request reached the upstream
		assertThat(requestCount.get())
			.as("upstream must not receive any request when \"Host\" (capitalized) is configured")
			.isZero();
	}

	@Test
	@DisplayName("restricted header: multiple offenders, first-one-wins, upstream receives no request")
	@Story("Restricted header prevention: multiple offenders")
	@Severity(SeverityLevel.NORMAL)
	@Description("When multiple restricted headers are configured, the first one throws and "
			+ "the upstream receives no request; the others are never evaluated")
	void restrictedHeader_multipleOffenders_firstOneWins_upstreamReceivesNoRequest() throws Exception {
		// given
		startMockServer();
		final URI mcpUri = URI.create("http://127.0.0.1:" + serverPort + "/mcp");

		// when & then
		assertThatThrownBy(
				() -> FACTORY.openStreamable(mcpUri, null, Map.of("host", "evil.com", "connection", "close")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Restricted custom header name");

		// then: no request reached the upstream
		assertThat(requestCount.get())
			.as("upstream must not receive any request when multiple restricted headers are configured")
			.isZero();
	}

	// -------------------------------------------------------------------------
	// Positive scenario: allowed custom header reaches upstream
	// -------------------------------------------------------------------------

	@Test
	@DisplayName("allowed custom header reaches the upstream server")
	@Story("Allowed header forwarding")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A non-restricted custom header (e.g. \"X-Custom-Header\") passes validation "
			+ "and is forwarded to the upstream MCP server on the first HTTP request")
	void allowedCustomHeader_reachesUpstream() throws Exception {
		// given
		startMockServer();
		final URI mcpUri = URI.create("http://127.0.0.1:" + serverPort + "/mcp");
		final String headerName = "X-Custom-Header";
		final String headerValue = "custom-value";

		// when: build the transport with an allowed custom header
		final var transport = FACTORY.openStreamable(mcpUri, null, Map.of(headerName, headerValue));

		// then: the transport builds successfully (no exception)
		assertThat(transport).as("transport must build successfully with an allowed custom header").isNotNull();

		// Connect the transport: sets up the internal session. The transport has
		// openConnectionOnStartup=false by default, so no HTTP request is made yet.
		transport.connect((inbound) -> inbound).block();

		// Send a ping notification to trigger the actual HTTP request to the mock
		// server. The customizer injects the allowed header into this request.
		transport.sendMessage(new McpSchema.JSONRPCNotification("2.0", "ping", null)).block();

		// then: the mock server received the request(s) with our custom headers
		assertThat(requestCount.get()).as("upstream must receive at least one request").isGreaterThanOrEqualTo(1);

		// then: the allowed header is present in the first request received by the mock
		final Map<String, List<String>> upstreamHeaders = receivedHeaders.get(0);
		assertThat(upstreamHeaders).as("mock server must have received the request headers").isNotNull();
		assertThat(upstreamHeaders.keySet()).as("X-Custom-Header must be present in the upstream request headers")
			.anyMatch(k -> k.equalsIgnoreCase(headerName));
		assertThat(upstreamHeaders.values().stream().flatMap(List::stream).anyMatch(v -> v.contains(headerValue)))
			.as("header value '%s' must reach the upstream", headerValue)
			.isTrue();
	}

	@Test
	@DisplayName("allowed custom header: multiple headers all reach upstream")
	@Story("Allowed header forwarding: multiple")
	@Severity(SeverityLevel.NORMAL)
	@Description("When multiple non-restricted custom headers are configured, all of them "
			+ "pass validation and reach the upstream server")
	void allowedCustomHeader_multipleHeaders_reachUpstream() throws Exception {
		// given
		startMockServer();
		final URI mcpUri = URI.create("http://127.0.0.1:" + serverPort + "/mcp");

		// when: build the transport with multiple allowed custom headers
		final var transport = FACTORY.openStreamable(mcpUri, null,
				Map.of("X-Custom-A", "value-a", "X-Custom-B", "value-b"));

		// then: the transport builds successfully
		assertThat(transport).as("transport must build successfully with multiple allowed headers").isNotNull();

		// Connect the transport, then send a ping to trigger the HTTP request.
		transport.connect((inbound) -> inbound).block();
		transport.sendMessage(new McpSchema.JSONRPCNotification("2.0", "ping", null)).block();

		// then: the mock server received the request(s) with our custom headers
		assertThat(requestCount.get()).as("upstream must receive at least one request").isGreaterThanOrEqualTo(1);

		// then: both headers reached the upstream with their actual values
		final Map<String, List<String>> upstreamHeaders = receivedHeaders.get(0);
		assertThat(upstreamHeaders).as("mock server must have received request headers").isNotNull();
		assertThat(upstreamHeaders.keySet()).as("X-Custom-A must be present in the upstream request headers")
			.anyMatch(k -> k.equalsIgnoreCase("X-Custom-A"));
		assertThat(upstreamHeaders.keySet()).as("X-Custom-B must be present in the upstream request headers")
			.anyMatch(k -> k.equalsIgnoreCase("X-Custom-B"));
		assertThat(upstreamHeaders.values().stream().flatMap(List::stream).anyMatch(v -> v.contains("value-a")))
			.as("X-Custom-A value 'value-a' must reach the upstream")
			.isTrue();
		assertThat(upstreamHeaders.values().stream().flatMap(List::stream).anyMatch(v -> v.contains("value-b")))
			.as("X-Custom-B value 'value-b' must reach the upstream")
			.isTrue();
	}

}