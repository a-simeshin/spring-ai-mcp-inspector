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

package io.inspector.mcp.demo.e2e;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal SSE MCP upstream stub (JDK {@link HttpServer}) for the {@link InspectorUiIT
 * OAuth2ClientCredentials} Selenide E2E scenarios: it serves the SSE handshake at
 * {@code GET /sse} (writing the {@code endpoint} prologue and holding the stream open),
 * and answers {@code POST /message} with the configured number of {@code 401} rejections
 * first and {@code 202} + an {@code initialize} JSON-RPC response pushed over the stream
 * afterwards. Every message POST's {@code Authorization} header is recorded so a test can
 * assert the token refresh actually re-sent with the fresh token. No WireMock, no
 * Testcontainers.
 *
 * <p>
 * The proxy's {@code HttpClientSseClientTransport} (mcp-core) drives this exactly like a
 * real SSE MCP server: {@code GET /sse} for the endpoint prologue, then {@code POST} to
 * the advertised message endpoint, with JSON-RPC responses delivered as SSE
 * {@code message} events on the held-open stream.
 *
 * <p>
 * <strong>Active stream tracking.</strong> The proxy's {@code SsePreflightTransport}
 * sends its own {@code GET /sse} before the SDK's real connect, and that preflight stream
 * is abandoned immediately (header-only body handler). Without tracking, the preflight's
 * abandoned {@code handleSse} loop would steal queued responses from the delegate's real
 * stream via the shared {@code responses} queue. To prevent this, the stub tracks the
 * <em>most recently opened</em> SSE stream as the active stream; only the active stream
 * polls the shared queue. The preflight's stream opens first, becomes active, then the
 * delegate's stream opens second and replaces it. The preflight's loop exits because it
 * is no longer the active stream.
 */
final class E2eSseMcpStub implements AutoCloseable {

	private static final JsonMapper MAPPER = new JsonMapper();

	private final HttpServer server;

	/** JSON-RPC responses to push over the held-open SSE stream, in order. */
	private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();

	/**
	 * The most recently opened SSE stream's id. Only the stream matching this id polls
	 * the shared {@code responses} queue, so the preflight's abandoned stream cannot
	 * steal responses from the delegate's real stream.
	 */
	private final AtomicReference<Integer> activeStreamId = new AtomicReference<>();

	/** {@code Authorization} header of every message POST, in arrival order. */
	private final List<String> authorizations = new CopyOnWriteArrayList<>();

	/** Message POST sequence. */
	private final AtomicInteger postCount = new AtomicInteger();

	/** SSE stream sequence (for active stream tracking). */
	private final AtomicInteger streamSeq = new AtomicInteger();

	/** How many of the first message POSTs answer {@code 401}. */
	private volatile int rejectPosts;

	private final AtomicBoolean stopped = new AtomicBoolean();

	E2eSseMcpStub() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		// The held-open SSE stream blocks one handler thread; give the server an
		// unbounded pool so message POSTs are never starved of a thread.
		this.server.setExecutor(Executors.newCachedThreadPool());
		this.server.createContext("/sse", this::handleSse);
		this.server.createContext("/message", this::handleMessage);
		this.server.start();
	}

	/** The SSE handshake URL to put in the inspector's server-URL input. */
	String sseUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/sse";
	}

	/** Makes the first {@code n} message POSTs answer {@code 401}. */
	void rejectPosts(final int n) {
		this.rejectPosts = n;
	}

	/** The {@code Authorization} header of every message POST, in arrival order. */
	List<String> authorizations() {
		return this.authorizations;
	}

	@Override
	public void close() {
		this.stopped.set(true);
		this.server.stop(0);
	}

	/**
	 * SSE handshake: writes the {@code endpoint} prologue pointing at {@code /message},
	 * then holds the stream open, pushing each queued JSON-RPC response as a
	 * {@code message} event until the stub is closed. Only the most recently opened
	 * stream (the "active" stream) polls the queue; earlier streams exit immediately.
	 */
	private void handleSse(final HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			exchange.close();
			return;
		}
		final int myStreamId = this.streamSeq.incrementAndGet();
		this.activeStreamId.set(myStreamId);
		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
			out.flush();
			while (!this.stopped.get()) {
				// Only the active stream polls the queue. The preflight's abandoned
				// stream (replaced by the delegate's stream) exits here immediately.
				if (this.activeStreamId.get() != null && this.activeStreamId.get() != myStreamId) {
					break;
				}
				final String response = this.responses.poll(200, TimeUnit.MILLISECONDS);
				if (response != null) {
					out.write(("event: message\ndata: " + response + "\n\n").getBytes(StandardCharsets.UTF_8));
					out.flush();
				}
			}
		}
		catch (final InterruptedException | IOException ignored) {
			// stream ended or the stub was stopped
		}
	}

	/**
	 * Message POST: records the {@code Authorization} header; answers {@code 401} for the
	 * configured number of leading posts, otherwise {@code 202} and queues an
	 * {@code initialize}-style JSON-RPC response for request frames.
	 */
	private void handleMessage(final HttpExchange exchange) throws IOException {
		final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (authorization != null) {
			this.authorizations.add(authorization);
		}
		final int n = this.postCount.incrementAndGet();
		if (n <= this.rejectPosts) {
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
			return;
		}
		final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		final String id = extractId(body);
		if (id != null) {
			final String response = """
					{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-11-25",\
					"capabilities":{},"serverInfo":{"name":"e2e-stub-mcp","version":"1.0.0"}}}""".formatted(id);
			this.responses.offer(response);
		}
		exchange.sendResponseHeaders(202, -1);
		exchange.close();
	}

	/**
	 * Extracts the JSON-RPC {@code id} from a request frame, or {@code null} for
	 * notifications.
	 */
	private static String extractId(final String body) {
		try {
			final JsonNode node = MAPPER.readTree(body);
			final JsonNode id = node.path("id");
			return (id.isMissingNode() || id.isNull()) ? null : id.asText();
		}
		catch (final JacksonException ex) {
			return null;
		}
	}

}
