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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Test stand token server for the OAuth2 token-refresh pinning (issue #375): a loopback
 * HTTP stub of the OAuth2 client-credentials token endpoint that hands out a scripted
 * sequence of access tokens, so a test can pin that the first exchange yields token A and
 * the refresh exchange yields token B.
 *
 * <p>
 * Start/stop per test; point an {@code OAuth2Profile} at {@link #url()}. Every exchange
 * (request body, order) is recorded for grant-type assertions.
 *
 * @author Artem Simeshin
 */
public final class ScriptedTokenServer {

	private final HttpServer server;

	private final List<String> requestBodies = new ArrayList<>();

	private final AtomicInteger requestCount = new AtomicInteger();

	private final List<String> scriptedTokens = new ArrayList<>();

	private volatile int status = 200;

	private volatile String rawBody;

	/**
	 * Creates the loopback server bound to an ephemeral port; call {@link #start()}
	 * before use.
	 * @throws IOException when the loopback bind fails
	 */
	public ScriptedTokenServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/token", this::handle);
		this.server.setExecutor(null);
	}

	/**
	 * Starts accepting token exchanges.
	 */
	public void start() {
		this.server.start();
	}

	/**
	 * Stops the server and releases the port.
	 */
	public void stop() {
		this.server.stop(0);
	}

	/**
	 * Returns the token endpoint URL to put into an {@code OAuth2Profile}.
	 * @return the loopback token endpoint URL
	 */
	public String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
	}

	/**
	 * Scripts the access tokens handed out in exchange order: the first exchange returns
	 * the first token, the second exchange the second token, and every exchange past the
	 * script repeats the last token. Switches the server from a scripted fixed response
	 * back to token-sequence mode.
	 * @param tokens the access tokens in exchange order (at least one)
	 */
	public void issueTokensInSequence(final String... tokens) {
		if (tokens == null || tokens.length == 0) {
			throw new IllegalArgumentException("script at least one token");
		}
		this.rawBody = null;
		synchronized (this.scriptedTokens) {
			this.scriptedTokens.clear();
			for (final String token : tokens) {
				this.scriptedTokens.add(token);
			}
		}
	}

	/**
	 * Scripts a fixed status and response body that overrides the token sequence until
	 * {@link #issueTokensInSequence(String...)} is called again.
	 * @param status the HTTP status to respond with
	 * @param body the response body
	 */
	public void respond(final int status, final String body) {
		this.status = status;
		this.rawBody = body;
	}

	/**
	 * Returns how many token exchanges hit the endpoint so far.
	 * @return the exchange count
	 */
	public int requestCount() {
		return this.requestCount.get();
	}

	/**
	 * Returns the recorded request bodies, in arrival order, for grant-type assertions.
	 * @return an immutable ordered list of request bodies
	 */
	public List<String> requestBodies() {
		synchronized (this.requestBodies) {
			return List.copyOf(this.requestBodies);
		}
	}

	private void handle(final HttpExchange exchange) throws IOException {
		final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		final int index;
		synchronized (this.requestBodies) {
			this.requestBodies.add(requestBody);
			index = this.requestCount.getAndIncrement();
		}
		final String body = (this.rawBody != null) ? this.rawBody : tokenJson(scriptedTokenAt(index));
		final byte[] response = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(this.status, response.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(response);
		}
	}

	private String scriptedTokenAt(final int index) {
		final List<String> tokens;
		synchronized (this.scriptedTokens) {
			tokens = List.copyOf(this.scriptedTokens);
		}
		if (tokens.isEmpty()) {
			throw new IllegalStateException("no response scripted: call issueTokensInSequence or respond first");
		}
		return tokens.get(Math.min(index, tokens.size() - 1));
	}

	private static String tokenJson(final String token) {
		return "{\"access_token\":\"" + token + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
	}

}
