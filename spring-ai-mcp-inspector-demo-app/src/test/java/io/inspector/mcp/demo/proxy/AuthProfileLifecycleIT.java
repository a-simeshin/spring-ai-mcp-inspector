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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OwnerTokenCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the profile lifecycle and production wiring (D4/D5/D6, issue #54) on a real
 * HTTP stack: a session-bound profile is unbound when the session is closed (the
 * {@code DELETE /mcp} teardown path), expired profiles are swept automatically by the
 * scheduled reaper (TTL, with the token cache evicted), the error DTO {@code url} is
 * D5-redacted (the API-key QUERY value never leaks), and the production wiring smoke
 * proves the {@code OwnerTokenCodec}, the per-stack owner resolver, the
 * {@code OAuth2ClientCredentialsTokenManager} and the auth-code exchanger beans are
 * present with the manager wired as the store's {@code TokenEvictor}.
 *
 * <p>
 * The class lives in {@code demo-app}'s test-jar, so Failsafe's
 * {@code dependenciesToScan} runs it once per stack module — every scenario is exercised
 * on BOTH webmvc and webflux (see {@code ProxyAppHarness.stack()} in the assertion
 * messages). No WireMock, no Testcontainers.
 */
@Epic("Inspector Auth Profiles")
@Feature("Profile lifecycle, URL redaction and production wiring (D4/D5/D6)")
class AuthProfileLifecycleIT {

	/** Fixed inspector/proxy auth token threaded through the demo via the harness. */
	private static final String AUTH_TOKEN = "auth-lifecycle-it-token-0123456789";

	/** Inspector API auth header ({@code InspectorAuthFilter}). */
	private static final String INSPECTOR_AUTH_HEADER = "X-MCP-Inspector-Auth";

	/** Proxy auth header used by {@code ProxyAuthFilter}. */
	private static final String PROXY_AUTH_HEADER = "X-MCP-Proxy-Auth";

	/** Streamable session id header ({@code ProxyConstants}). */
	private static final String MCP_SESSION_ID_HEADER = "mcp-session-id";

	/** Signed session-owner cookie name ({@code OwnerTokenCodec}). */
	private static final String OWNER_COOKIE = "MCP_INSPECTOR_SESSION";

	/** Bean names from the webmvc/webflux auto-configurations (D6). */
	private static final String OWNER_CODEC_BEAN = "mcpInspectorOwnerTokenCodec";

	private static final String TOKEN_MANAGER_BEAN = "mcpInspectorTokenManager";

	private static final String STORE_BEAN = "mcpInspectorAuthProfileStore";

	private static final String EXCHANGER_BEAN = "mcpInspectorAuthCodeTokenExchanger";

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	/** Per-request wall-clock budget. */
	private static final Duration BUDGET = Duration.ofSeconds(20);

	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (app != null) {
			try {
				app.close();
			}
			catch (final Exception ignored) {
				/* best-effort */
			}
			app = null;
		}
	}

	/**
	 * Cleanup lifecycle (D4): a profile bound to a proxy session is removed from the
	 * store when the session is closed, and the automatic TTL sweep removes an expired
	 * unbound profile together with its cached token.
	 */
	@Nested
	@DisplayName("Cleanup lifecycle (D4)")
	class Cleanup {

		@Test
		@DisplayName("closing the bound session unbinds the profile from the store")
		@Story("D4 session teardown")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A streamable session bound to a profile is closed via DELETE /mcp-inspector-api/mcp "
				+ "(mcp-session-id header): the teardown routes through removeAndClose → clearBySession and the "
				+ "profile disappears from the owner's list")
		void boundProfile_isUnbound_whenSessionClosed() throws Exception {
			// given — a registered profile and a live bound session
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			try (final RecordingMcpStub stub = new RecordingMcpStub()) {
				final Session session = register(apiBase, bearerProfileBody("cleanup-bearer", "cleanup-tok"));
				final HttpResponse<String> init = initializeThroughProxy(proxyBase, stub.mcpUrl(), session.profileId(),
						session.cookie());
				assertThat(init.statusCode())
					.as("bound initialize on %s, body=%s", ProxyAppHarness.stack(), init.body())
					.isEqualTo(200);
				final String sessionId = init.headers().firstValue(MCP_SESSION_ID_HEADER).orElseThrow();
				assertThat(MAPPER.readTree(
						send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null, session.cookie(), null).body()))
					.as("bound profile listed on %s", ProxyAppHarness.stack())
					.isNotEmpty();

				// when — the session is closed through the teardown endpoint
				final HttpResponse<String> delete = send(proxyBase + "/mcp", "DELETE", null, null,
						"Bearer " + AUTH_TOKEN, session.cookie(), sessionId);
				assertThat(delete.statusCode()).as("DELETE /mcp status on %s", ProxyAppHarness.stack()).isEqualTo(200);

				// then — the bound profile was removed with the session
				final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null,
						session.cookie(), null);
				assertThat(list.body()).as("profile after session close on %s", ProxyAppHarness.stack())
					.isEqualTo("[]");
			}
		}

		@Test
		@DisplayName("the scheduled reaper sweeps an expired profile and its token automatically")
		@Story("D4 TTL sweep")
		@Severity(SeverityLevel.CRITICAL)
		@Description("With a 2s profile TTL and a 1s reaper interval, an unbound client-credentials profile is "
				+ "removed by the scheduled sweep with NO manual call — the list empties and the token manager's "
				+ "cache and credential registry are evicted with it")
		void expiredProfile_isSweptByReaper_automatically() throws Exception {
			// given — a fast reaper and a short profile TTL
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN,
					"--spring.ai.mcp.inspector.timeouts.reaper-interval=PT1S");
			final AuthProfileStore store = app.getBean(AuthProfileStore.class);
			store.setProfileTtl(Duration.ofSeconds(2));
			final OAuth2ClientCredentialsTokenManager manager = app.getBean(OAuth2ClientCredentialsTokenManager.class);
			final String apiBase = apiBase();
			try (final StubTokenServer tokenServer = new StubTokenServer()) {
				final Session session = register(apiBase,
						ccProfileBody("ttl-cc", "cid-ttl", "secret-ttl", tokenServer.tokenUrl(), null));
				assertThat(manager.cacheSize()).as("token cached on %s", ProxyAppHarness.stack()).isEqualTo(1);

				// when — the profile expires and the reaper runs (no manual call)
				Awaitility.await("TTL sweep on " + ProxyAppHarness.stack())
					.atMost(Duration.ofSeconds(20))
					.pollInterval(Duration.ofMillis(200))
					.until(() -> {
						final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null,
								session.cookie(), null);
						return list.body().equals("[]") && manager.cacheSize() == 0 && manager.credentialCount() == 0;
					});
			}
		}

	}

	/**
	 * Production wiring smoke (D6): the owner codec, the per-stack owner resolver, the
	 * token manager and the auth-code exchanger beans are present, and the manager is
	 * wired as the store's {@code TokenEvictor} (a delete evicts the cached token AND the
	 * stored credentials).
	 */
	@Nested
	@DisplayName("Production wiring (D6)")
	class Wiring {

		@Test
		@DisplayName("the auth beans are present and the token manager is the store's evictor")
		@Story("D6 wiring smoke")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The context exposes OwnerTokenCodec, the per-stack owner resolver (ServletSessionOwnerResolver "
				+ "on webmvc, ReactiveSessionOwnerResolver on webflux), OAuth2ClientCredentialsTokenManager and "
				+ "OAuth2AuthCodeTokenExchanger; deleting a client-credentials profile evicts the manager's cached "
				+ "token AND its stored credentials — the TokenEvictor hook is wired")
		void authBeans_present_andManagerWiredAsTokenEvictor() throws Exception {
			// given — a running demo
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final boolean webflux = "webflux".equals(ProxyAppHarness.stack());
			final String resolverBean = webflux ? "mcpInspectorReactiveSessionOwnerResolver"
					: "mcpInspectorServletSessionOwnerResolver";
			final String resolverClass = webflux ? "io.inspector.mcp.webflux.auth.ReactiveSessionOwnerResolver"
					: "io.inspector.mcp.webmvc.auth.ServletSessionOwnerResolver";

			// then — the D6 beans are present
			assertThat(app.containsBean(OWNER_CODEC_BEAN)).as("OwnerTokenCodec bean on %s", ProxyAppHarness.stack())
				.isTrue();
			assertThat(app.getBean(OWNER_CODEC_BEAN)).isInstanceOf(OwnerTokenCodec.class);
			assertThat(app.containsBean(resolverBean)).as("per-stack resolver bean on %s", ProxyAppHarness.stack())
				.isTrue();
			assertThat(app.getType(resolverBean).getName()).as("resolver type on %s", ProxyAppHarness.stack())
				.isEqualTo(resolverClass);
			assertThat(app.containsBean(TOKEN_MANAGER_BEAN)).as("token manager bean on %s", ProxyAppHarness.stack())
				.isTrue();
			assertThat(app.getBean(TOKEN_MANAGER_BEAN)).isInstanceOf(OAuth2ClientCredentialsTokenManager.class);
			assertThat(app.containsBean(EXCHANGER_BEAN)).as("exchanger bean on %s", ProxyAppHarness.stack()).isTrue();
			assertThat(app.getBean(EXCHANGER_BEAN)).isInstanceOf(OAuth2AuthCodeTokenExchanger.class);
			assertThat(app.containsBean(STORE_BEAN)).as("store bean on %s", ProxyAppHarness.stack()).isTrue();

			// when — a client-credentials profile is registered, then deleted
			final OAuth2ClientCredentialsTokenManager manager = app.getBean(OAuth2ClientCredentialsTokenManager.class);
			try (final StubTokenServer tokenServer = new StubTokenServer()) {
				final Session session = register(apiBase,
						ccProfileBody("wiring-cc", "cid-wiring", "secret-wiring", tokenServer.tokenUrl(), null));
				assertThat(manager.cacheSize()).as("token cached on %s", ProxyAppHarness.stack()).isEqualTo(1);
				assertThat(manager.credentialCount()).as("credentials stored on %s", ProxyAppHarness.stack())
					.isEqualTo(1);

				final HttpResponse<String> delete = send(apiBase + "/auth-profile/" + session.profileId(), "DELETE",
						null, AUTH_TOKEN, null, session.cookie(), null);

				// then — the eviction hook removed the token AND the credentials
				assertThat(delete.statusCode()).as("DELETE status on %s", ProxyAppHarness.stack()).isEqualTo(204);
				assertThat(manager.cacheSize()).as("token cache after delete on %s", ProxyAppHarness.stack()).isZero();
				assertThat(manager.credentialCount()).as("credentials after delete on %s", ProxyAppHarness.stack())
					.isZero();
			}
		}

	}

	/**
	 * D5 URL redaction: the error DTO {@code url} is the scheme/host/path core with the
	 * query stripped, so an API-key QUERY value never leaks.
	 */
	@Nested
	@DisplayName("Error DTO URL redaction (D5)")
	class Redaction {

		@Test
		@DisplayName("the error DTO url strips the query carrying the API-key value")
		@Story("D5 redaction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An SSE handshake against an upstream URL with an api_key QUERY value, answered 401, emits "
				+ "the D3 DTO whose url is the redacted scheme://host[:port]/path — neither the target's nor the "
				+ "profile's API-key value ever appears in the error body")
		void errorDtoUrl_redactsQuery_secretApiKeyNeverLeaks() throws Exception {
			// given — an API_KEY QUERY profile whose value must never leak
			app = ProxyAppHarness.start("SSE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			final Session session = register(apiBase,
					"{\"profile\":{\"name\":\"api-q\",\"type\":\"API_KEY\",\"keyName\":\"api_key\","
							+ "\"keyValue\":\"profile-secret-77\",\"placement\":\"QUERY\"}}");
			try (final AuthRejectingSseStub stub = new AuthRejectingSseStub()) {
				// when — the SSE handshake targets a URL carrying an api_key query and
				// the upstream answers 401
				final String target = "http://127.0.0.1:" + stub.port() + "/sse?api_key=target-secret-42";
				final SseStream stream = new SseStream(proxyBase, target, session.profileId(), session.cookie());

				// then — the error event carries the D3 DTO with the redacted url
				final SseFrame errorFrame = stream.awaitFrame("error");
				final JsonNode dto = MAPPER.readTree(errorFrame.data());
				assertThat(dto.path("status").asInt()).as("DTO status on %s", ProxyAppHarness.stack()).isEqualTo(401);
				assertThat(dto.path("code").asText()).as("DTO code on %s", ProxyAppHarness.stack())
					.isEqualTo("unauthorized");
				assertThat(dto.path("url").asText()).as("redacted url on %s", ProxyAppHarness.stack())
					.isEqualTo("http://127.0.0.1:" + stub.port() + "/sse");
				assertThat(errorFrame.data()).as("no query on %s", ProxyAppHarness.stack())
					.doesNotContain("?")
					.doesNotContain("target-secret-42")
					.doesNotContain("profile-secret-77");

				// and — the stream ends normally
				stream.awaitEnd();
				assertThat(stream.error()).as("SSE reader on %s", ProxyAppHarness.stack()).isNull();
			}
		}

	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	/** Composes the inspector API base (auth-profile endpoints). */
	private String apiBase() {
		return "http://127.0.0.1:" + ProxyAppHarness.port(app) + "/mcp-inspector/api";
	}

	/** Composes the proxy base (transport relay endpoints). */
	private String proxyBase() {
		return "http://127.0.0.1:" + ProxyAppHarness.port(app) + "/mcp-inspector-api";
	}

	private static String bearerProfileBody(final String name, final String token) {
		return "{\"profile\":{\"name\":\"" + name + "\",\"type\":\"BEARER\",\"token\":\"" + token + "\"}}";
	}

	private static String ccProfileBody(final String name, final String clientId, final String clientSecret,
			final String tokenUrl, final String scopes) {
		final StringBuilder sb = new StringBuilder();
		sb.append("{\"profile\":{\"name\":\"")
			.append(name)
			.append("\",\"type\":\"OAUTH2\",")
			.append("\"grantMode\":\"CLIENT_CREDENTIALS\",\"tokenUrl\":\"")
			.append(tokenUrl)
			.append("\",\"clientId\":\"")
			.append(clientId)
			.append("\",\"clientSecret\":\"")
			.append(clientSecret)
			.append('"');
		if (scopes != null) {
			sb.append(",\"scopes\":\"").append(scopes).append('"');
		}
		sb.append("}}");
		return sb.toString();
	}

	/** Registers an inline profile and returns the id plus the minted owner cookie. */
	private static Session register(final String apiBase, final String body) throws Exception {
		final HttpResponse<String> response = send(apiBase + "/auth-profile", "POST", body, AUTH_TOKEN, null, null,
				null);
		assertThat(response.statusCode())
			.as("registration status on %s, body=%s", ProxyAppHarness.stack(), response.body())
			.isEqualTo(200);
		return new Session(MAPPER.readTree(response.body()).path("profileId").asText(), ownerCookie(response));
	}

	/**
	 * Opens a streamable proxy session bound to {@code profileId} by POSTing the MCP
	 * {@code initialize} frame through the relay.
	 */
	private static HttpResponse<String> initializeThroughProxy(final String proxyBase, final String targetUrl,
			final String profileId, final String cookie) throws Exception {
		return send(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8) + "&profileId="
						+ profileId,
				"POST", MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN, cookie, null);
	}

	/** Builds an MCP {@code initialize} request frame. */
	private static ObjectNode initializeFrame() {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "auth-lifecycle-it");
		info.put("version", "0.1.0");
		return init;
	}

	/**
	 * Sends an HTTP request against the running demo app (see sibling ITs). The session
	 * id header is attached when non-null.
	 */
	private static HttpResponse<String> send(final String url, final String method, final String body,
			final String inspectorAuth, final String proxyAuth, final String cookie, final String sessionId)
			throws Exception {
		final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
			.timeout(BUDGET)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream");
		if (inspectorAuth != null) {
			builder.header(INSPECTOR_AUTH_HEADER, inspectorAuth);
		}
		if (proxyAuth != null) {
			builder.header(PROXY_AUTH_HEADER, proxyAuth);
		}
		if (cookie != null) {
			builder.header("Cookie", OWNER_COOKIE + "=" + cookie);
		}
		if (sessionId != null) {
			builder.header(MCP_SESSION_ID_HEADER, sessionId);
		}
		final HttpRequest request = switch (method) {
			case "GET" -> builder.GET().build();
			case "DELETE" -> builder.DELETE().build();
			case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
			default -> builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		};
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Extracts the signed owner cookie value from a {@code Set-Cookie} response header.
	 */
	private static String ownerCookie(final HttpResponse<?> response) {
		final String setCookie = response.headers().firstValue("Set-Cookie").orElse(null);
		assertThat(setCookie).as("Set-Cookie owner cookie on %s", ProxyAppHarness.stack()).isNotNull();
		final String prefix = OWNER_COOKIE + "=";
		final int start = setCookie.indexOf(prefix);
		assertThat(start).as("owner cookie named %s on %s", OWNER_COOKIE, ProxyAppHarness.stack()).isNotNegative();
		final int end = setCookie.indexOf(';', start + prefix.length());
		if (end > 0) {
			return setCookie.substring(start + prefix.length(), end);
		}
		return setCookie.substring(start + prefix.length());
	}

	/** Registration response: server-issued id + the minted owner cookie. */
	private record Session(String profileId, String cookie) {
	}

	/**
	 * Minimal streamable-HTTP MCP stub answering every message POST with a valid
	 * {@code initialize} response. {@code GET /mcp} answers 405 — request-response mode.
	 */
	private static final class RecordingMcpStub implements AutoCloseable {

		private final HttpServer server;

		RecordingMcpStub() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/mcp", this::handleMcp);
			this.server.start();
		}

		String mcpUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/mcp";
		}

		@Override
		public void close() {
			this.server.stop(0);
		}

		private void handleMcp(final HttpExchange exchange) throws IOException {
			if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				exchange.close();
				return;
			}
			final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			final JsonNode request = MAPPER.readTree(requestBody);
			final String responseBody = """
					{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-11-25",\
					"capabilities":{},"serverInfo":{"name":"stub-mcp","version":"1.0.0"}}}"""
				.formatted(request.path("id").asText());
			final byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.getResponseHeaders().add("Mcp-Session-Id", "stub-session-1");
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(payload);
			}
		}

	}

	/**
	 * SSE stub answering the handshake {@code GET /sse} with 401 and a body (the SDK's
	 * line subscriber needs at least one body line to surface the status as a connect
	 * error, see the sibling error-flow IT).
	 */
	private static final class AuthRejectingSseStub implements AutoCloseable {

		private static final byte[] REJECTED = "request rejected\n".getBytes(StandardCharsets.UTF_8);

		private final HttpServer server;

		AuthRejectingSseStub() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/sse", this::handleSse);
			this.server.start();
		}

		int port() {
			return this.server.getAddress().getPort();
		}

		@Override
		public void close() {
			this.server.stop(0);
		}

		private void handleSse(final HttpExchange exchange) throws IOException {
			exchange.sendResponseHeaders(401, REJECTED.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(REJECTED);
			}
		}

	}

	/** One SSE frame of the relay stream. */
	private record SseFrame(String event, String data) {
	}

	/**
	 * SSE stream reader against {@code GET /mcp-inspector-api/sse} carrying the owner
	 * cookie and the proxy auth header, mirroring the browser's EventSource.
	 */
	private static final class SseStream {

		private final CopyOnWriteArrayList<SseFrame> frames = new CopyOnWriteArrayList<>();

		private final AtomicReference<Throwable> error = new AtomicReference<>();

		private final AtomicBoolean finished = new AtomicBoolean();

		SseStream(final String proxyBase, final String targetUrl, final String profileId, final String cookie) {
			final Thread thread = new Thread(() -> {
				try {
					final HttpRequest request = HttpRequest
						.newBuilder(URI.create(proxyBase + "/sse?transportType=sse&url="
								+ URLEncoder.encode(targetUrl, StandardCharsets.UTF_8) + "&profileId=" + profileId))
						.timeout(Duration.ofSeconds(30))
						.header("Accept", "text/event-stream")
						.header(PROXY_AUTH_HEADER, "Bearer " + AUTH_TOKEN)
						.header("Cookie", OWNER_COOKIE + "=" + cookie)
						.GET()
						.build();
					final HttpResponse<InputStream> response = HTTP.send(request,
							HttpResponse.BodyHandlers.ofInputStream());
					if (response.statusCode() != 200) {
						this.error.set(new IllegalStateException("SSE handshake HTTP " + response.statusCode()));
						return;
					}
					try (BufferedReader reader = new BufferedReader(
							new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
						String eventName = null;
						StringBuilder dataBuf = new StringBuilder();
						String line;
						while ((line = reader.readLine()) != null) {
							if (line.isEmpty()) {
								if (eventName != null || dataBuf.length() > 0) {
									this.frames.add(new SseFrame(eventName == null ? "message" : eventName,
											dataBuf.toString()));
									eventName = null;
									dataBuf.setLength(0);
								}
								continue;
							}
							if (line.startsWith("event:")) {
								eventName = line.substring(6).trim();
							}
							else if (line.startsWith("data:")) {
								if (dataBuf.length() > 0) {
									dataBuf.append('\n');
								}
								dataBuf.append(line.substring(5).trim());
							}
						}
					}
				}
				catch (final Throwable t) {
					this.error.set(t);
				}
				finally {
					this.finished.set(true);
				}
			}, "sse-reader-" + ProxyAppHarness.stack());
			thread.setDaemon(true);
			thread.start();
		}

		/** Awaits the first frame with the given event name and returns it. */
		SseFrame awaitFrame(final String event) {
			Awaitility.await("SSE " + event + " frame on " + ProxyAppHarness.stack())
				.atMost(Duration.ofSeconds(15))
				.pollInterval(Duration.ofMillis(50))
				.until(() -> this.frames.stream().anyMatch((frame) -> event.equals(frame.event())));
			return this.frames.stream().filter((frame) -> event.equals(frame.event())).findFirst().orElseThrow();
		}

		/** Awaits the end of the stream. */
		void awaitEnd() {
			Awaitility.await("SSE stream end on " + ProxyAppHarness.stack())
				.atMost(Duration.ofSeconds(15))
				.pollInterval(Duration.ofMillis(50))
				.until(this.finished::get);
		}

		Throwable error() {
			return this.error.get();
		}

	}

}
