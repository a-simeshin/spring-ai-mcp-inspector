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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * <li>{@link #postCount()} : how many POST /message requests were received</li>
 * <li>{@link #activeExchangeCount()} : how many SSE streams are currently writing (their
 * SSE loop has not yet exited)</li>
 * </ul>
 */
final class SseStreamCountingStub implements AutoCloseable {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final AtomicLong EXECUTOR_SEQ = new AtomicLong();

	private final HttpServer server;

	private final Executor executor;

	/** Number of SSE streams opened (GET /sse that sent the endpoint prologue). */
	private final AtomicInteger sseStreamCount = new AtomicInteger();

	/** Number of HEAD /sse probes received. */
	private final AtomicInteger headCount = new AtomicInteger();

	/** Number of POST /message requests received. */
	private final AtomicInteger postCount = new AtomicInteger();

	/** Number of SSE streams currently active (writing loop still running). */
	private final AtomicInteger activeExchangeCount = new AtomicInteger();

	/** HTTP status to return on HEAD /sse. 200 = accept preflight. */
	private volatile int headStatus = 200;

	/** HTTP status to return on GET /sse. 200 = open stream. */
	private volatile int sseStatus = 200;

	/** How many of the first POSTs answer 401. */
	private volatile int rejectPosts;

	/** JSON-RPC response queue, shared across all SSE streams. */
	private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();

	/** Whether to hang on HEAD /sse (never respond). */
	private volatile boolean hangOnHead;

	private final AtomicBoolean stopped = new AtomicBoolean();

	SseStreamCountingStub() throws IOException {
		this.executor = Executors.newCachedThreadPool((runnable) -> {
			final Thread thread = new Thread(runnable, "sse-counting-stub-" + EXECUTOR_SEQ.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.setExecutor(this.executor);
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

	/** Number of POST /message requests received. */
	int postCount() {
		return this.postCount.get();
	}

	/** Number of SSE streams currently active (writing loop still running). */
	int activeExchangeCount() {
		return this.activeExchangeCount.get();
	}

	void setHeadStatus(final int status) {
		this.headStatus = status;
	}

	void setSseStatus(final int status) {
		this.sseStatus = status;
	}

	void rejectPosts(final int n) {
		this.rejectPosts = n;
	}

	void enqueueResponse(final String jsonRpcResponse) {
		this.responses.offer(jsonRpcResponse);
	}

	void setHangOnHead(final boolean hang) {
		this.hangOnHead = hang;
	}

	@Override
	public void close() {
		this.stopped.set(true);
		this.server.stop(1);
	}

	private void handleSse(final HttpExchange exchange) throws IOException {
		final String method = exchange.getRequestMethod().toUpperCase();
		switch (method) {
			case "HEAD" -> {
				this.headCount.incrementAndGet();
				if (this.hangOnHead) {
					return;
				}
				exchange.sendResponseHeaders(this.headStatus, -1);
				exchange.close();
			}
			case "GET" -> {
				if (this.sseStatus != 200) {
					exchange.sendResponseHeaders(this.sseStatus, -1);
					exchange.close();
					return;
				}
				this.sseStreamCount.incrementAndGet();
				this.activeExchangeCount.incrementAndGet();
				exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
				exchange.sendResponseHeaders(200, 0);
				runSseLoop(exchange.getResponseBody());
				this.activeExchangeCount.decrementAndGet();
			}
			default -> {
				exchange.sendResponseHeaders(405, -1);
				exchange.close();
			}
		}
	}

	/**
	 * Runs the SSE event loop: writes the endpoint event, then waits for queued
	 * responses. Every iteration writes a keep-alive newline to detect client
	 * disconnection. The JDK HttpServer only detects a closed TCP connection when the
	 * output buffer is flushed to the socket.
	 */
	private void runSseLoop(final OutputStream out) {
		try {
			out.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
			out.flush();
		}
		catch (final IOException ex) {
			return;
		}
		int keepAlive = 0;
		while (!this.stopped.get()) {
			try {
				final String response = this.responses.poll(50, TimeUnit.MILLISECONDS);
				if (response != null) {
					out.write(("event: message\ndata: " + response + "\n\n").getBytes(StandardCharsets.UTF_8));
					out.flush();
					keepAlive = 0;
				}
				// Every 4 iterations (~200ms), write a keep-alive to detect
				// client disconnection. The JDK HttpServer's output stream
				// only throws IOException on write+flush when the underlying
				// TCP socket is closed.
				keepAlive++;
				if (keepAlive >= 4) {
					keepAlive = 0;
					out.write("\n".getBytes(StandardCharsets.UTF_8));
					out.flush();
				}
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
			catch (final IOException ex) {
				// Client disconnected
				break;
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
