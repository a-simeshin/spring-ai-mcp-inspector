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

package io.inspector.mcp.demo.proxy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal OAuth2 token-endpoint stub (JDK {@link HttpServer}) for the D9 integration
 * tests (issue #54): {@code POST /token} records every request's form fields and answers
 * with a JSON token response whose {@code access_token} is {@code tok-<n>} on a
 * per-server sequence, so a test can correlate which exchange produced the token a
 * proxied request carried. No WireMock, no Testcontainers.
 *
 * <p>
 * The response is switchable per test: {@link #status(int)} answers non-2xx (failed
 * exchange), {@link #expiresIn(int)} shortens the token lifetime (expiry-without-refresh
 * scenario) and {@link #includeRefreshToken(boolean)} adds a {@code refresh_token} to the
 * auth-code response.
 */
final class StubTokenServer implements AutoCloseable {

	private static final JsonMapper MAPPER = new JsonMapper();

	private final HttpServer server;

	/** Every token request in arrival order. */
	private final List<TokenRequest> requests = new CopyOnWriteArrayList<>();

	/** Per-server exchange sequence for {@code tok-<n>} values. */
	private final AtomicInteger sequence = new AtomicInteger();

	/** HTTP status of the token endpoint. */
	private volatile int status = 200;

	/** {@code expires_in} of issued tokens. */
	private volatile int expiresIn = 3600;

	/** Whether the token response carries a {@code refresh_token}. */
	private volatile boolean includeRefreshToken;

	StubTokenServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/token", this::handleToken);
		this.server.start();
	}

	/** The token endpoint URL the profiles point at. */
	String tokenUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
	}

	/** Sets the HTTP status the endpoint answers. */
	void status(final int status) {
		this.status = status;
	}

	/** Sets the {@code expires_in} of issued tokens. */
	void expiresIn(final int expiresIn) {
		this.expiresIn = expiresIn;
	}

	/** Sets whether the token response carries a {@code refresh_token}. */
	void includeRefreshToken(final boolean includeRefreshToken) {
		this.includeRefreshToken = includeRefreshToken;
	}

	/** Number of recorded token requests. */
	int requestCount() {
		return this.requests.size();
	}

	/** The {@code access_token} value issued by the {@code n}-th exchange (1-based). */
	static String tokenValue(final int n) {
		return "tok-" + n;
	}

	/** All recorded token requests, in arrival order. */
	List<TokenRequest> requests() {
		return this.requests;
	}

	/** The last recorded token request, or {@code null} when none arrived yet. */
	TokenRequest lastRequest() {
		return this.requests.isEmpty() ? null : this.requests.get(this.requests.size() - 1);
	}

	/** Whether any recorded request carried the given form field. */
	boolean anyRequestWithField(final String field) {
		return this.requests.stream().anyMatch((request) -> request.has(field));
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	private void handleToken(final HttpExchange exchange) throws IOException {
		final Map<String, String> form = parseForm(
				new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		this.requests.add(TokenRequest.parse(form));
		final int status = this.status;
		if (status < 200 || status >= 300) {
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
			return;
		}
		final int n = this.sequence.incrementAndGet();
		final ObjectNode body = MAPPER.createObjectNode();
		body.put("access_token", tokenValue(n));
		body.put("token_type", "Bearer");
		body.put("expires_in", this.expiresIn);
		if (this.includeRefreshToken) {
			body.put("refresh_token", "rt-" + n);
		}
		final byte[] payload = MAPPER.writeValueAsBytes(body);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, payload.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(payload);
		}
	}

	private static Map<String, String> parseForm(final String raw) {
		final Map<String, String> form = new LinkedHashMap<>();
		if (raw == null || raw.isBlank()) {
			return form;
		}
		for (final String pair : raw.split("&")) {
			final int eq = pair.indexOf('=');
			if (eq < 0) {
				continue;
			}
			final String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
			final String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			form.put(key, value);
		}
		return form;
	}

	/**
	 * One recorded token request.
	 *
	 * @param grantType the {@code grant_type} form field
	 * @param clientId the {@code client_id} form field
	 * @param clientSecret the {@code client_secret} form field (absent for auth-code)
	 * @param code the {@code code} form field (auth-code grant)
	 * @param redirectUri the {@code redirect_uri} form field (auth-code grant)
	 * @param codeVerifier the {@code code_verifier} form field (auth-code grant)
	 * @param refreshToken the {@code refresh_token} form field (never sent by the backend
	 * — asserted absent)
	 * @param scope the {@code scope} form field
	 */
	record TokenRequest(String grantType, String clientId, String clientSecret, String code, String redirectUri,
			String codeVerifier, String refreshToken, String scope) {

		private static TokenRequest parse(final Map<String, String> form) {
			return new TokenRequest(form.get("grant_type"), form.get("client_id"), form.get("client_secret"),
					form.get("code"), form.get("redirect_uri"), form.get("code_verifier"), form.get("refresh_token"),
					form.get("scope"));
		}

		private boolean has(final String field) {
			return switch (field) {
				case "grant_type" -> this.grantType != null;
				case "client_id" -> this.clientId != null;
				case "client_secret" -> this.clientSecret != null;
				case "code" -> this.code != null;
				case "redirect_uri" -> this.redirectUri != null;
				case "code_verifier" -> this.codeVerifier != null;
				case "refresh_token" -> this.refreshToken != null;
				case "scope" -> this.scope != null;
				default -> false;
			};
		}
	}

}
