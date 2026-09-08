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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * <li>{@link #serverClosedExchangeCount()} : how many exchanges were observed closed
 * server-side (socket close detected by the handler thread)</li>
 * </ul>
 */
final class SseStreamCountingStub implements AutoCloseable {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final AtomicLong EXECUTOR_SEQ = new AtomicLong();

	private final ServerSocket serverSocket;

	private final ExecutorService executor;

	/** Number of SSE streams opened (GET /sse that sent the endpoint prologue). */
	private final AtomicInteger sseStreamCount = new AtomicInteger();

	/** Number of HEAD /sse probes received. */
	private final AtomicInteger headCount = new AtomicInteger();

	/** Number of POST /message requests received. */
	private final AtomicInteger postCount = new AtomicInteger();

	/** Number of SSE streams currently active (writing loop still running). */
	private final AtomicInteger activeExchangeCount = new AtomicInteger();

	/** Peak number of concurrently active SSE streams. */
	private final AtomicInteger maxActiveExchangeCount = new AtomicInteger();

	/**
	 * Number of GET exchanges whose SSE loop was exited (closed). This proves the
	 * fallback GET was closed server-side before the delegate stream was established.
	 */
	private final AtomicInteger closedGetCount = new AtomicInteger();

	/**
	 * Set when a HEAD probe has been answered with a 405/404 status. The next GET /sse
	 * that arrives while this flag is set is the fallback probe issued by the preflight
	 * wrapper, not the real delegate. The flag is consumed by that first GET, so the
	 * delegate's own GET does not count as a fallback.
	 */
	private final AtomicBoolean headRejected = new AtomicBoolean();

	/**
	 * Number of GET /sse exchanges classified as preflight fallbacks: they arrived right
	 * after a rejected HEAD probe and were closed server-side before the delegate stream
	 * was established. A GET-only preflight (the pre-fix shape) never sends a HEAD, so
	 * this stays 0 for any transport that does not perform the HEAD-based preflight.
	 */
	private final AtomicInteger closedFallbackGetCount = new AtomicInteger();

	/** Number of exchanges currently pending (HEAD or GET, opened but not yet closed). */
	private final AtomicInteger pendingExchangeCount = new AtomicInteger();

	/** Number of exchanges whose server-side close was observed by the handler. */
	private final AtomicInteger serverClosedExchangeCount = new AtomicInteger();

	/** Authorization header values from POST /message requests, in order. */
	private final List<String> postAuthorizationValues = Collections.synchronizedList(new ArrayList<>());

	/** Number of responses consumed from the SSE queue by the SSE loop. */
	private final AtomicInteger responseDeliveryCount = new AtomicInteger();

	int pendingExchangeCount() {
		return this.pendingExchangeCount.get();
	}

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

	/** Whether to hang on GET /sse (never respond). */
	private volatile boolean hangOnSse;

	private final AtomicBoolean stopped = new AtomicBoolean();

	SseStreamCountingStub() throws IOException {
		this.executor = Executors.newCachedThreadPool((runnable) -> {
			final Thread thread = new Thread(runnable, "sse-counting-stub-" + EXECUTOR_SEQ.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		this.serverSocket = new ServerSocket();
		this.serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
		this.executor.submit(this::acceptLoop);
	}

	/** The SSE handshake URL. */
	String sseUrl() {
		return "http://127.0.0.1:" + this.serverSocket.getLocalPort() + "/sse";
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

	/** Peak number of concurrently active SSE streams. */
	int maxActiveExchangeCount() {
		return this.maxActiveExchangeCount.get();
	}

	/**
	 * Number of GET exchanges whose SSE loop was exited (closed server-side). Proves the
	 * fallback GET was closed before the delegate stream was established.
	 */
	int closedGetCount() {
		return this.closedGetCount.get();
	}

	/**
	 * Number of GET /sse exchanges classified as preflight fallbacks and observed closed
	 * server-side. Zero on any transport that never issues a HEAD probe.
	 */
	int closedFallbackGetCount() {
		return this.closedFallbackGetCount.get();
	}

	/** Number of exchanges whose server-side close was observed by the handler. */
	int serverClosedExchangeCount() {
		return this.serverClosedExchangeCount.get();
	}

	/** Authorization header values from POST /message requests, in order. */
	List<String> postAuthorizationValues() {
		return List.copyOf(this.postAuthorizationValues);
	}

	/** Number of responses consumed from the SSE queue by the SSE loop. */
	int responseDeliveryCount() {
		return this.responseDeliveryCount.get();
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

	void setHangOnSse(final boolean hang) {
		this.hangOnSse = hang;
	}

	@Override
	public void close() {
		this.stopped.set(true);
		try {
			this.serverSocket.close();
		}
		catch (final IOException ignored) {
			// best-effort
		}
		this.executor.shutdown();
		try {
			this.executor.awaitTermination(2, TimeUnit.SECONDS);
		}
		catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private void acceptLoop() {
		while (!this.stopped.get()) {
			try {
				final Socket socket = this.serverSocket.accept();
				this.executor.submit(() -> handleConnection(socket));
			}
			catch (final IOException ex) {
				if (!this.stopped.get()) {
					// unexpected
				}
				break;
			}
		}
	}

	private void handleConnection(final Socket socket) {
		this.pendingExchangeCount.incrementAndGet();
		try {
			final InputStream in = socket.getInputStream();
			final OutputStream out = socket.getOutputStream();
			final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

			// Parse request line
			final String requestLine = reader.readLine();
			if (requestLine == null) {
				return;
			}
			final String[] parts = requestLine.split(" ");
			final String method = parts[0].toUpperCase();
			final String path = parts[1];

			// Parse headers
			String line;
			String authHeader = null;
			int contentLength = 0;
			while ((line = reader.readLine()) != null && !line.isEmpty()) {
				if (line.startsWith("Authorization:")) {
					authHeader = line.substring("Authorization:".length()).trim();
				}
				if (line.startsWith("Content-Length:")) {
					contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
				}
			}

			// Read body if present
			String body = "";
			if (contentLength > 0) {
				final char[] buf = new char[contentLength];
				int read = 0;
				while (read < contentLength) {
					final int r = reader.read(buf, read, contentLength - read);
					if (r == -1) {
						break;
					}
					read += r;
				}
				body = new String(buf, 0, read);
			}

			switch (path) {
				case "/sse" -> handleSse(socket, in, out, method);
				case "/message" -> handleMessage(socket, in, out, method, authHeader, body);
				default -> sendResponse(out, 404, "Not Found", -1);
			}
		}
		catch (final IOException ex) {
			// client disconnected or socket error
		}
		finally {
			try {
				socket.close();
			}
			catch (final IOException ignored) {
				// best-effort
			}
			this.pendingExchangeCount.decrementAndGet();
		}
	}

	private void handleSse(final Socket socket, final InputStream in, final OutputStream out, final String method)
			throws IOException {
		switch (method) {
			case "HEAD" -> {
				this.headCount.incrementAndGet();
				if (this.hangOnHead) {
					// Block until the client disconnects. Reading from the
					// socket returns -1 when the client closes the connection.
					try {
						while (!this.stopped.get()) {
							if (in.read() == -1) {
								this.serverClosedExchangeCount.incrementAndGet();
								return;
							}
						}
					}
					catch (final IOException ex) {
						this.serverClosedExchangeCount.incrementAndGet();
						return;
					}
					return;
				}
				sendResponse(out, this.headStatus, null, -1);
				if (this.headStatus == 405 || this.headStatus == 404) {
					this.headRejected.set(true);
				}
			}
			case "GET" -> {
				if (this.hangOnSse) {
					try {
						while (!this.stopped.get()) {
							if (in.read() == -1) {
								this.serverClosedExchangeCount.incrementAndGet();
								return;
							}
						}
					}
					catch (final IOException ex) {
						this.serverClosedExchangeCount.incrementAndGet();
						return;
					}
					return;
				}
				if (this.sseStatus != 200) {
					sendResponse(out, this.sseStatus, null, -1);
					return;
				}
				this.sseStreamCount.incrementAndGet();
				final boolean fallback = this.headRejected.getAndSet(false);
				final int current = this.activeExchangeCount.incrementAndGet();
				this.maxActiveExchangeCount.updateAndGet((prev) -> Math.max(prev, current));
				out.write(("HTTP/1.1 200 OK\r\n" + "Content-Type: text/event-stream\r\n"
						+ "Transfer-Encoding: chunked\r\n" + "\r\n")
					.getBytes(StandardCharsets.UTF_8));
				out.flush();
				runSseLoop(socket, in, out);
				this.activeExchangeCount.decrementAndGet();
				this.closedGetCount.incrementAndGet();
				if (fallback) {
					this.closedFallbackGetCount.incrementAndGet();
				}
			}
			default -> sendResponse(out, 405, "Method Not Allowed", -1);
		}
	}

	/**
	 * Runs the SSE event loop: writes the endpoint event, then waits for queued
	 * responses. Every iteration writes a keep-alive newline to detect client
	 * disconnection. The loop also monitors the socket input stream for client close
	 * (read returns -1).
	 */
	private void runSseLoop(final Socket socket, final InputStream in, final OutputStream out) {
		try {
			writeChunk(out, "event: endpoint\ndata: /message\n\n");
		}
		catch (final IOException ex) {
			this.serverClosedExchangeCount.incrementAndGet();
			return;
		}
		int keepAlive = 0;
		while (!this.stopped.get()) {
			try {
				// Check if client closed the connection
				if (in.available() > 0) {
					final int r = in.read();
					if (r == -1) {
						this.serverClosedExchangeCount.incrementAndGet();
						return;
					}
				}
				final String response = this.responses.poll(50, TimeUnit.MILLISECONDS);
				if (response != null) {
					writeChunk(out, "event: message\ndata: " + response + "\n\n");
					this.responseDeliveryCount.incrementAndGet();
					keepAlive = 0;
				}
				// Every 4 iterations (~200ms), write a keep-alive to detect
				// client disconnection via IOException on flush.
				keepAlive++;
				if (keepAlive >= 4) {
					keepAlive = 0;
					writeChunk(out, "\n");
				}
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
			catch (final IOException ex) {
				// Client disconnected
				this.serverClosedExchangeCount.incrementAndGet();
				break;
			}
		}
	}

	private void handleMessage(final Socket socket, final InputStream in, final OutputStream out, final String method,
			final String authHeader, final String body) throws IOException {
		if (!"POST".equals(method)) {
			sendResponse(out, 405, "Method Not Allowed", -1);
			return;
		}
		final int n = this.postCount.incrementAndGet();
		this.postAuthorizationValues.add((authHeader != null) ? authHeader : "");
		if (n <= this.rejectPosts) {
			sendResponse(out, 401, "Unauthorized", -1);
			return;
		}
		final String id = extractId(body);
		if (id != null) {
			final String response = """
					{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-11-25",\
					"capabilities":{},"serverInfo":{"name":"counting-stub-mcp","version":"1.0.0"}}}""".formatted(id);
			this.responses.offer(response);
		}
		sendResponse(out, 202, "Accepted", -1);
	}

	private static void sendResponse(final OutputStream out, final int status, final String statusText,
			final int contentLength) throws IOException {
		final String text = (statusText != null) ? statusText : defaultStatusText(status);
		final StringBuilder sb = new StringBuilder();
		sb.append("HTTP/1.1 ").append(status).append(' ').append(text).append("\r\n");
		if (contentLength >= 0) {
			sb.append("Content-Length: ").append(contentLength).append("\r\n");
		}
		sb.append("Connection: close\r\n");
		sb.append("\r\n");
		out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static String defaultStatusText(final int status) {
		return switch (status) {
			case 200 -> "OK";
			case 202 -> "Accepted";
			case 400 -> "Bad Request";
			case 401 -> "Unauthorized";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			case 405 -> "Method Not Allowed";
			default -> "Status " + status;
		};
	}

	private static void writeChunk(final OutputStream out, final String data) throws IOException {
		final byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
		out.write(String.format("%x\r\n", bytes.length).getBytes(StandardCharsets.UTF_8));
		out.write(bytes);
		out.write("\r\n".getBytes(StandardCharsets.UTF_8));
		out.flush();
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
