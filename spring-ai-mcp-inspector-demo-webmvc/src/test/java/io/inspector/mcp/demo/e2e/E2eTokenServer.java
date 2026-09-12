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
 * Minimal OAuth2 token-endpoint stub (JDK {@link HttpServer}) for the
 * {@link InspectorUiIT OAuth2ClientCredentials} Selenide E2E scenarios: every
 * {@code POST /token} is recorded and answered with a JSON token response whose
 * {@code access_token} is {@code tok-<n>} on a per-server sequence, so a test can
 * correlate which exchange produced the token a proxied request carried. This local copy
 * mirrors {@code StubTokenServer} (which is package-private in demo-app and cannot be
 * reused from the demo-webmvc e2e package); no WireMock, no Testcontainers.
 */
final class E2eTokenServer implements AutoCloseable {

	private static final JsonMapper MAPPER = new JsonMapper();

	private final HttpServer server;

	/** Every token request in arrival order. */
	private final List<GrantRecord> requests = new CopyOnWriteArrayList<>();

	/** Per-server exchange sequence for {@code tok-<n>} values. */
	private final AtomicInteger sequence = new AtomicInteger();

	E2eTokenServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/token", this::handleToken);
		this.server.start();
	}

	/** The token endpoint URL the profiles point at. */
	String tokenUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
	}

	/** Number of recorded token exchanges. */
	int requestCount() {
		return this.requests.size();
	}

	/** The {@code access_token} value issued by the {@code n}-th exchange (1-based). */
	static String tokenValue(final int n) {
		return "tok-" + n;
	}

	/** Whether any recorded exchange carried the given form field. */
	boolean anyRequestWithField(final String field) {
		return this.requests.stream().anyMatch((r) -> r.has(field));
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	private void handleToken(final HttpExchange exchange) throws IOException {
		final Map<String, String> form = parseForm(
				new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		this.requests.add(GrantRecord.parse(form));
		final int n = this.sequence.incrementAndGet();
		final ObjectNode body = MAPPER.createObjectNode();
		body.put("access_token", tokenValue(n));
		body.put("token_type", "Bearer");
		body.put("expires_in", 3600);
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
			form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return form;
	}

	/** One recorded token exchange. */
	private record GrantRecord(String grantType, String refreshToken) {

		private static GrantRecord parse(final Map<String, String> form) {
			return new GrantRecord(form.get("grant_type"), form.get("refresh_token"));
		}

		private boolean has(final String field) {
			return switch (field) {
				case "grant_type" -> this.grantType != null;
				case "refresh_token" -> this.refreshToken != null;
				default -> false;
			};
		}
	}

}
