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
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the upstream inbound size guarantee the embedded proxy relies on (issue #426, the
 * GHSA-3xgv-mvj6-g78r attack class): the MCP Java SDK transports built by
 * {@link ProxyTransportFactory} abort an upstream response whose accumulated SSE event,
 * or whose single SSE line over a configured cap, exceeds the inbound size bound,
 * surfacing a typed {@link McpTransportException} instead of buffering without bound in
 * the host JVM. A hostile or broken MCP server must not be able to grow the host
 * application's heap through the inspector proxy.
 *
 * <p>
 * The default-cap pins are built through {@link ProxyTransportFactory} with the default
 * builder settings, exactly as the proxy does, so they also fail if our factory ever
 * overrides the SDK's {@code maxResponseSize}; their assertions name the SDK default byte
 * count, so the pin goes red if a BOM bump moves or drops the default. The line bound is
 * pinned at a small configured cap instead of the default: measured on this codebase,
 * detecting a single unterminated over-cap line at the 16 MiB default takes about 90
 * seconds because the JDK {@code fromLineSubscriber} adapter scans the accumulating line
 * on the HTTP client thread, which is too slow for a CI pin. The configured-cap pin
 * proves the mechanism; the default value itself is pinned by the accumulated-event
 * assertions. A happy path per transport keeps the pins from passing vacuously.
 */
@Epic("MCP Inspector Core")
@Feature("Proxy upstream transports")
class UpstreamSseSizeCapTests {

	/**
	 * The SDK's default inbound cap in bytes, 16 MiB (four times the reference servers' 4
	 * MB inbound limit, matching the upstream inspector's post-GHSA default).
	 */
	private static final int DEFAULT_MAX_RESPONSE_SIZE_BYTES = 16 * 1024 * 1024;

	/**
	 * Configured line-bound cap for the single-line pin, small enough that the JDK line
	 * subscriber adapter's scan of the unterminated line stays well under a second.
	 */
	private static final int LINE_TEST_CAP_BYTES = 256 * 1024;

	/**
	 * Event assembled from 300 data lines of 64 KiB each, about 19.6 MiB: every single
	 * line sits far below the default cap, while the accumulated event crosses it.
	 */
	private static final int EVENT_LINES = 300;

	private static final int EVENT_LINE_DATA_BYTES = 64 * 1024;

	private static final int WRITE_CHUNK_BYTES = 64 * 1024;

	/** Message fragment both SDK bounds share (line bound and event bound). */
	private static final String CAP_EXCEEDED = "exceeds the maximum allowed size";

	private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(30);

	private HttpServer server;

	private int port;

	private ProxyTransportFactory factory;

	@BeforeEach
	void setUp() throws Exception {
		UpstreamSseSizeCapTests.this.server = HttpServer.create(new InetSocketAddress(0), 0);
		UpstreamSseSizeCapTests.this.server.setExecutor((command) -> new Thread(command).start());
		UpstreamSseSizeCapTests.this.server.start();
		UpstreamSseSizeCapTests.this.port = UpstreamSseSizeCapTests.this.server.getAddress().getPort();
		UpstreamSseSizeCapTests.this.factory = new ProxyTransportFactory();
	}

	@AfterEach
	void tearDown() {
		if (UpstreamSseSizeCapTests.this.server != null) {
			UpstreamSseSizeCapTests.this.server.stop(0);
		}
	}

	/**
	 * Registers a handler answering every request with {@code contentType} and the raw
	 * body, streamed in chunks. Aborted writes (the client cancels the upstream
	 * subscription as soon as a bound fires) are swallowed: they are the expected
	 * server-side view of the guarantee under test.
	 * @param contentType the response Content-Type header
	 * @param body the response body bytes
	 */
	private void serveBody(final String contentType, final byte[] body) {
		UpstreamSseSizeCapTests.this.server.createContext("/", (exchange) -> {
			try {
				exchange.getResponseHeaders().set("Content-Type", contentType);
				exchange.sendResponseHeaders(200, body.length);
				try (var out = exchange.getResponseBody()) {
					for (int offset = 0; offset < body.length; offset += WRITE_CHUNK_BYTES) {
						out.write(body, offset, Math.min(WRITE_CHUNK_BYTES, body.length - offset));
						out.flush();
					}
				}
			}
			catch (final IOException ignored) {
				// The client aborted the upstream stream: the bound under test fired.
			}
			finally {
				exchange.close();
			}
		});
	}

	private void serveSse(final byte[] body) {
		serveBody("text/event-stream", body);
	}

	private void serveJson(final String body) {
		serveBody("application/json", body.getBytes(StandardCharsets.UTF_8));
	}

	private URI mcpUri() {
		return URI.create("http://127.0.0.1:" + UpstreamSseSizeCapTests.this.port + "/mcp");
	}

	/**
	 * One SSE event carrying a single {@code data:} line above the configured cap of the
	 * line-bound pin.
	 * @return the hostile event payload
	 */
	private static byte[] oversizeLineOverConfiguredCap() {
		return lineEventOfDataBytes(2 * LINE_TEST_CAP_BYTES);
	}

	/**
	 * One SSE event assembled from many {@code data:} lines whose accumulated size
	 * crosses the SDK default cap while every line stays below it.
	 * @return the hostile event payload
	 */
	private static byte[] oversizeAccumulatedEvent() {
		final char[] data = new char[EVENT_LINE_DATA_BYTES];
		Arrays.fill(data, 'x');
		final String dataLine = "data: " + new String(data) + "\n";
		final StringBuilder event = new StringBuilder("event: message\n");
		for (int i = 0; i < EVENT_LINES; i++) {
			event.append(dataLine);
		}
		event.append('\n');
		return event.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] lineEventOfDataBytes(final int dataBytes) {
		final char[] data = new char[dataBytes];
		Arrays.fill(data, 'x');
		return ("event: message\ndata: " + new String(data) + "\n\n").getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] endpointEvent() {
		return "event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8);
	}

	private static void assertCapExceeded(final Throwable error) {
		assertThat(error).isInstanceOf(McpTransportException.class)
			.hasMessageContaining(UpstreamSseSizeCapTests.CAP_EXCEEDED);
	}

	private static void assertDefaultCapExceeded(final Throwable error) {
		assertCapExceeded(error);
		assertThat(error.getMessage())
			.contains(String.valueOf(UpstreamSseSizeCapTests.DEFAULT_MAX_RESPONSE_SIZE_BYTES));
	}

	@Nested
	@DisplayName("SSE transport connect stream")
	class SseTransport {

		@Test
		@Story("Single SSE line above a configured cap aborts the connect with a typed error")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A hostile upstream whose SSE stream carries a single unterminated data line "
				+ "above the configured inbound cap makes the SSE transport fail the connect with a "
				+ "typed McpTransportException instead of buffering the line without bound "
				+ "(issue #426); the cap is set explicitly because detection at the 16 MiB default "
				+ "measures about 90 seconds, too slow for a CI pin")
		void singleLine_overConfiguredCap_abortsConnectWithTypedError() {
			serveSse(UpstreamSseSizeCapTests.oversizeLineOverConfiguredCap());
			final McpClientTransport transport = HttpClientSseClientTransport
				.builder("http://127.0.0.1:" + UpstreamSseSizeCapTests.this.port)
				.sseEndpoint("/sse")
				.maxResponseSize(UpstreamSseSizeCapTests.LINE_TEST_CAP_BYTES)
				.build();
			StepVerifier.create(transport.connect((message) -> message))
				.expectErrorSatisfies(UpstreamSseSizeCapTests::assertCapExceeded)
				.verify(UpstreamSseSizeCapTests.VERIFY_TIMEOUT);
		}

		@Test
		@Story("Accumulated SSE event above the default cap aborts the connect with a typed error")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A hostile upstream whose SSE event accumulates many data lines past the SDK's "
				+ "default 16 MiB inbound cap (every line below it) makes the factory-built SSE "
				+ "transport fail the connect with a typed McpTransportException naming the default "
				+ "byte count, instead of growing the event buffer without bound (issue #426)")
		void accumulatedEvent_overDefaultCap_abortsConnectWithTypedError() {
			serveSse(UpstreamSseSizeCapTests.oversizeAccumulatedEvent());
			final McpClientTransport transport = UpstreamSseSizeCapTests.this.factory
				.buildSse(URI.create("http://127.0.0.1:" + UpstreamSseSizeCapTests.this.port + "/sse"));
			StepVerifier.create(transport.connect((message) -> message))
				.expectErrorSatisfies(UpstreamSseSizeCapTests::assertDefaultCapExceeded)
				.verify(UpstreamSseSizeCapTests.VERIFY_TIMEOUT);
		}

		@Test
		@Story("Upstream SSE event under the cap connects normally")
		@Severity(SeverityLevel.NORMAL)
		@Description("An upstream answering with a normal endpoint event connects, proving the pins "
				+ "above fail only when a bound fires and not for every upstream")
		void event_underDefaultCap_connectCompletes() {
			serveSse(UpstreamSseSizeCapTests.endpointEvent());
			final McpClientTransport transport = UpstreamSseSizeCapTests.this.factory
				.buildSse(URI.create("http://127.0.0.1:" + UpstreamSseSizeCapTests.this.port + "/sse"));
			StepVerifier.create(transport.connect((message) -> message))
				.expectComplete()
				.verify(UpstreamSseSizeCapTests.VERIFY_TIMEOUT);
		}

	}

	@Nested
	@DisplayName("Streamable HTTP transport POST response stream")
	class StreamableHttpTransport {

		@Test
		@Story("Upstream SSE event above the default cap in a POST response aborts with a typed error")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A hostile upstream answering a message POST with an SSE response stream whose "
				+ "accumulated event crosses the SDK's default 16 MiB inbound cap makes the "
				+ "factory-built streamable transport fail the send with a typed "
				+ "McpTransportException naming the default byte count, instead of buffering "
				+ "without bound (issue #426)")
		void oversizeEventInPostResponse_abortsSendWithTypedError() {
			serveSse(UpstreamSseSizeCapTests.oversizeAccumulatedEvent());
			final McpClientTransport transport = UpstreamSseSizeCapTests.this.factory
				.openStreamable(UpstreamSseSizeCapTests.this.mcpUri());
			StepVerifier.create(transport.sendMessage(new McpSchema.JSONRPCNotification("notifications/initialized")))
				.expectErrorSatisfies(UpstreamSseSizeCapTests::assertDefaultCapExceeded)
				.verify(UpstreamSseSizeCapTests.VERIFY_TIMEOUT);
		}

		@Test
		@Story("Normal JSON POST response completes the send")
		@Severity(SeverityLevel.NORMAL)
		@Description("An upstream answering a message POST with a normal JSON response completes the "
				+ "send, proving the hostile pin fails only when a bound fires")
		void normalJsonResponse_completesSend() {
			serveJson("{\"jsonrpc\":\"2.0\",\"method\":\"log\"}");
			final McpClientTransport transport = UpstreamSseSizeCapTests.this.factory
				.openStreamable(UpstreamSseSizeCapTests.this.mcpUri());
			StepVerifier.create(transport.sendMessage(new McpSchema.JSONRPCNotification("notifications/initialized")))
				.expectComplete()
				.verify(UpstreamSseSizeCapTests.VERIFY_TIMEOUT);
		}

	}

}
