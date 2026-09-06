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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal SSE MCP server stub that counts the number of SSE streams opened. Used by
 * {@link SsePreflightStreamCountTests} to verify that the {@code SsePreflightTransport}
 * does not orphan SSE streams.
 *
 * <p>
 * The stub tracks:
 * <ul>
 * <li>{@link #sseStreamCount()} : how many GET /sse opened a stream</li>
 * <li>{@link #headCount()} : how many HEAD /sse probes were received</li>
 * <li>{@link #getFallbackCount()} : how many GET /sse with header-only handling</li>
 * <li>{@link #postCount()} : how many POST /message requests were received</li>
 * </ul>
 *
 * <p>
 * The stub can be configured to:
 * <ul>
 * <li>Return a configurable HTTP status on HEAD /sse for preflight testing</li>
 * <li>Return a configurable HTTP status on GET /sse for SSE stream testing</li>
 * <li>Reject the first N POSTs with 401 for retry testing</li>
 * </ul>
 */
final class SseStreamCountingStub implements AutoCloseable {

	private static final JsonMapper MAPPER = new JsonMapper();

	private final HttpServer server;

	/** Number of SSE streams opened (GET /sse that sent the endpoint prologue). */
	private final AtomicInteger sseStreamCount = new AtomicInteger();

	/** Number of HEAD /sse probes received. */
	private final AtomicInteger headCount = new AtomicInteger();

	/** Number of GET /sse with header-only handling (fallback from 405). */
	private final AtomicInteger getFallbackCount = new AtomicInteger();

	/** Number of POST /message requests received. */
	private final AtomicInteger postCount = new AtomicInteger();

	/** HTTP status to return on HEAD /sse. 200 = accept preflight. */
	private volatile int headStatus = 200;

	/** HTTP status to return on GET /sse. 200 = open stream. */
	private volatile int sseStatus = 200;

	/** How many of the first POSTs answer 401. */
	private volatile int rejectPosts;

	/** JSON-RPC response queue, shared across all SSE streams. */
	private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();

	private final AtomicBoolean stopped = new AtomicBoolean();

	SseStreamCountingStub() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.setExecutor(Executors.newCachedThreadPool());
		this.server.createContext("/sse", this::handleSse);
		this.server.createContext("/message", this::handleMessage);
		this.server.start();
	}

	/** The SSE handshake URL. */
	String sseUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/sse";
	}

	/** Number of SSE streams opened (GET /sse that sent the endpoint prologue). */
	int sseStreamCount() {
		return this.sseStreamCount.get();
	}

	/** Number of HEAD /sse probes received. */
	int headCount() {
		return this.headCount.get();
	}

	/** Number of GET /sse with header-only handling (fallback from 405). */
	int getFallbackCount() {
		return this.getFallbackCount.get();
	}

	/** Number of POST /message requests received. */
	int postCount() {
		return this.postCount.get();
	}

	/**
	 * Sets the HTTP status for HEAD /sse. When set to a non-2xx value, the stub returns
	 * that status without creating an SSE stream, simulating a preflight-failure
	 * condition. 405 triggers the GET fallback.
	 */
	void setHeadStatus(final int status) {
		this.headStatus = status;
	}

	/**
	 * Sets the HTTP status for GET /sse. When set to a non-2xx value, the stub returns
	 * that status without creating an SSE stream.
	 */
	void setSseStatus(final int status) {
		this.sseStatus = status;
	}

	/** Makes the first {@code n} POSTs answer 401. */
	void rejectPosts(final int n) {
		this.rejectPosts = n;
	}

	/** Queues a JSON-RPC response to be pushed over the next SSE stream. */
	void enqueueResponse(final String jsonRpcResponse) {
		this.responses.offer(jsonRpcResponse);
	}

	@Override
	public void close() {
		this.stopped.set(true);
		this.server.stop(0);
	}

	private void handleSse(final HttpExchange exchange) throws IOException {
		final String method = exchange.getRequestMethod().toUpperCase();
		switch (method) {
			case "HEAD" -> {
				this.headCount.incrementAndGet();
				exchange.sendResponseHeaders(this.headStatus, -1);
				exchange.close();
			}
			case "GET" -> {
				// Distinguish between a full SSE stream and a header-only fallback
				// by checking if the client sends a non-empty Accept header.
				final String accept = exchange.getRequestHeaders().getFirst("Accept");
				final boolean isFallback = (accept == null || accept.isBlank());
				if (isFallback) {
					this.getFallbackCount.incrementAndGet();
					// Header-only fallback : the client cancels immediately.
					exchange.sendResponseHeaders((this.headStatus == 405) ? 200 : this.sseStatus, -1);
					exchange.close();
					return;
				}
				// Full SSE stream open.
				if (this.sseStatus != 200) {
					exchange.sendResponseHeaders(this.sseStatus, -1);
					exchange.close();
					return;
				}
				this.sseStreamCount.incrementAndGet();
				exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
				exchange.sendResponseHeaders(200, 0);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
					out.flush();
					while (!this.stopped.get()) {
						final String response = this.responses.poll(200, TimeUnit.MILLISECONDS);
						if (response != null) {
							out.write(("event: message\ndata: " + response + "\n\n").getBytes(StandardCharsets.UTF_8));
							out.flush();
						}
					}
				}
				catch (final InterruptedException | IOException ignored) {
					// stream ended or stub was stopped
				}
			}
			default -> {
				exchange.sendResponseHeaders(405, -1);
				exchange.close();
			}
		}
	}

	private void handleMessage(final HttpExchange exchange) throws IOException {
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
					"capabilities":{},"serverInfo":{"name":"counting-stub-mcp","version":"1.0.0"}}}""".formatted(id);
			this.responses.offer(response);
		}
		exchange.sendResponseHeaders(202, -1);
		exchange.close();
	}

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
