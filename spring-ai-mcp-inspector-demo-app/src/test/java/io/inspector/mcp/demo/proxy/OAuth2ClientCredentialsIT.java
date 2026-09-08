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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the OAuth2 client-credentials token lifecycle (D9A, issue #54) on a real HTTP
 * stack: the backend exchanges {@code client_credentials} at registration, a 401 from the
 * MCP server triggers exactly ONE token refresh and retry, a second 401 yields the D3
 * {@code unauthorized} DTO, a token that expired WITHOUT a refresh token triggers a FRESH
 * {@code client_credentials} re-exchange (never a {@code refresh_token} grant), a
 * credential-bearing PUT never reuses the old token, and a failed initial exchange is
 * rolled back with the structured {@code 502 token_exchange_failed} DTO.
 *
 * <p>
 * The class lives in {@code demo-app}'s test-jar, so Failsafe's
 * {@code dependenciesToScan} runs it once per stack module — every scenario is exercised
 * on BOTH webmvc and webflux (see {@code ProxyAppHarness.stack()} in the assertion
 * messages).
 *
 * <p>
 * The token endpoint is the shared {@link StubTokenServer} (JDK {@link HttpServer}); the
 * upstream MCP server is a tiny recording stub whose first {@code N} message POSTs answer
 * 401, so each test drives the exact failure. No WireMock, no Testcontainers.
 */
@Epic("Inspector Auth Profiles")
@Feature("OAuth2 client-credentials token lifecycle (D9A)")
class OAuth2ClientCredentialsIT {

	/** Fixed inspector/proxy auth token threaded through the demo via the harness. */
	private static final String AUTH_TOKEN = "oauth2-cc-it-token-0123456789";

	/** Inspector API auth header ({@code InspectorAuthFilter}). */
	private static final String INSPECTOR_AUTH_HEADER = "X-MCP-Inspector-Auth";

	/** Proxy auth header used by {@code ProxyAuthFilter}. */
	private static final String PROXY_AUTH_HEADER = "X-MCP-Proxy-Auth";

	/** Streamable session id header ({@code ProxyConstants}). */
	private static final String MCP_SESSION_ID_HEADER = "mcp-session-id";

	/** Signed session-owner cookie name ({@code OwnerTokenCodec}). */
	private static final String OWNER_COOKIE = "MCP_INSPECTOR_SESSION";

	/** D3 exact literals ({@code ProxyErrorMapper}). */
	private static final String REASON_401 = "The MCP server rejected the request as unauthenticated.";

	private static final String GUIDANCE_401 = "Verify the token/API key. OAuth2 profiles refresh and retry once automatically.";

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
	 * The client-credentials core: registration performs the initial exchange, the token
	 * is held backend-only, and a 401 triggers exactly one refresh + retry — a second 401
	 * surfaces the D3 DTO.
	 */
	@Nested
	@DisplayName("Client-credentials lifecycle")
	class Lifecycle {

		@Test
		@DisplayName("registration exchanges client_credentials and holds the token backend-only")
		@Story("D9A registration exchange")
		@Severity(SeverityLevel.CRITICAL)
		@Description("POST /mcp-inspector/api/auth-profile with a CLIENT_CREDENTIALS profile runs the initial "
				+ "client_credentials exchange at the token URL (grant_type/client_id/client_secret/scope asserted) "
				+ "and returns {profileId}; the secret and the access token never appear in the list response")
		void registerClientCredentials_exchangesAtTokenUrl_tokenHeldBackendOnly() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			try (final StubTokenServer tokenServer = new StubTokenServer()) {
				// when — register the client-credentials profile
				final Session session = register(apiBase,
						ccProfileBody("cc-register", "cid-reg", "secret-reg", tokenServer.tokenUrl(), "read write"));

				// then — the initial exchange happened with the exact form fields
				assertThat(tokenServer.requestCount()).as("token requests on %s", ProxyAppHarness.stack()).isEqualTo(1);
				final StubTokenServer.TokenRequest exchange = tokenServer.lastRequest();
				assertThat(exchange.grantType()).as("grant_type on %s", ProxyAppHarness.stack())
					.isEqualTo("client_credentials");
				assertThat(exchange.clientId()).as("client_id on %s", ProxyAppHarness.stack()).isEqualTo("cid-reg");
				assertThat(exchange.clientSecret()).as("client_secret on %s", ProxyAppHarness.stack())
					.isEqualTo("secret-reg");
				assertThat(exchange.scope()).as("scope on %s", ProxyAppHarness.stack()).isEqualTo("read write");
				assertThat(exchange.refreshToken()).as("no refresh_token grant on %s", ProxyAppHarness.stack())
					.isNull();

				// and — the profile is listed without the secret or the token
				final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null,
						session.cookie());
				assertThat(list.statusCode()).as("list status on %s", ProxyAppHarness.stack()).isEqualTo(200);
				assertThat(list.body()).as("client secret must never leave the backend on %s", ProxyAppHarness.stack())
					.doesNotContain("secret-reg");
				assertThat(list.body()).as("access token must never leave the backend on %s", ProxyAppHarness.stack())
					.doesNotContain(StubTokenServer.tokenValue(1));
			}
		}

		@Test
		@DisplayName("upstream 401 refreshes the token once and retries with the fresh token")
		@Story("D9A one-retry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A streamable initialize answered 401 triggers a single client_credentials re-exchange and a "
				+ "re-send with the NEW token: the response is 200, the token server saw exactly two exchanges and the "
				+ "upstream stub received Bearer tok-1 then Bearer tok-2")
		void upstream401_refreshesTokenOnce_andRetriesWithFreshToken() throws Exception {
			// given — a registered client-credentials profile and an upstream that
			// rejects the first message POST
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN,
					"--logging.level.io.inspector.mcp.core.proxy=DEBUG",
					"--logging.level.io.inspector.mcp.core.auth=DEBUG",
					"--logging.level.io.modelcontextprotocol.client.transport=DEBUG");
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			try (final StubTokenServer tokenServer = new StubTokenServer();
					final AuthRejectingStub stub = new AuthRejectingStub(1)) {
				final Session session = register(apiBase,
						ccProfileBody("cc-retry", "cid-retry", "secret-retry", tokenServer.tokenUrl(), null));

				// when — the initialize frame is relayed
				final HttpResponse<String> init = initializeThroughProxy(proxyBase, stub.mcpUrl(), session.profileId(),
						session.cookie());

				// then — the retried round-trip succeeded
				assertThat(init.statusCode())
					.as("retried initialize on %s, body=%s", ProxyAppHarness.stack(), init.body())
					.isEqualTo(200);

				// and — exactly one refresh happened: a fresh client_credentials
				// exchange, never a refresh_token grant
				assertThat(tokenServer.requestCount()).as("token requests on %s", ProxyAppHarness.stack()).isEqualTo(2);
				assertThat(tokenServer.anyRequestWithField("refresh_token"))
					.as("no refresh_token grant on %s", ProxyAppHarness.stack())
					.isFalse();

				// and — the upstream saw the original token, then the refreshed one
				assertThat(stub.authorizations()).as("Authorization per upstream POST on %s", ProxyAppHarness.stack())
					.containsExactly("Bearer " + StubTokenServer.tokenValue(1),
							"Bearer " + StubTokenServer.tokenValue(2));
			}
		}

		@Test
		@DisplayName("a second 401 after the one retry yields the unauthorized DTO")
		@Story("D9A one-retry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An upstream that keeps answering 401: the first 401 refreshes (one exchange), the retried "
				+ "request is answered 401 again — no further refresh — and the browser receives the exact D3 "
				+ "unauthorized DTO without a session id (no partial connect state)")
		void second401_afterOneRetry_yieldsUnauthorizedDto() throws Exception {
			// given — an upstream that always rejects
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			try (final StubTokenServer tokenServer = new StubTokenServer();
					final AuthRejectingStub stub = new AuthRejectingStub(Integer.MAX_VALUE)) {
				final Session session = register(apiBase,
						ccProfileBody("cc-second-401", "cid-401", "secret-401", tokenServer.tokenUrl(), null));

				// when
				final HttpResponse<String> failed = initializeThroughProxy(proxyBase, stub.mcpUrl(),
						session.profileId(), session.cookie());

				// then — the exact D3 unauthorized DTO
				assertThat(failed.statusCode())
					.as("second-401 status on %s, body=%s", ProxyAppHarness.stack(), failed.body())
					.isEqualTo(401);
				final JsonNode dto = MAPPER.readTree(failed.body());
				assertThat(dto.path("status").asInt()).isEqualTo(401);
				assertThat(dto.path("code").asText()).isEqualTo("unauthorized");
				assertThat(dto.path("reason").asText()).isEqualTo(REASON_401);
				assertThat(dto.path("guidance").asText()).isEqualTo(GUIDANCE_401);

				// and — exactly one refresh (registration + the single 401 refresh),
				// no retry loop
				assertThat(tokenServer.requestCount()).as("token requests on %s", ProxyAppHarness.stack()).isEqualTo(2);

				// and — the failed handshake issued no session id
				assertThat(failed.headers().firstValue(MCP_SESSION_ID_HEADER))
					.as("failed handshake must not issue a session id on %s", ProxyAppHarness.stack())
					.isEmpty();
			}
		}

		@Test
		@DisplayName("expiry without a refresh token triggers a fresh client_credentials exchange")
		@Story("D9A expiry re-exchange")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A token that already expired (expires_in=1, inside the 30s skew) is re-exchanged with a FRESH "
				+ "client_credentials grant — the token server never sees a refresh_token grant — and the proxied "
				+ "request carries the fresh token")
		void expiredToken_withoutRefreshToken_reExchangesClientCredentials() throws Exception {
			// given — a token that expires immediately
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			try (final StubTokenServer tokenServer = new StubTokenServer();
					final AuthRejectingStub stub = new AuthRejectingStub(0)) {
				tokenServer.expiresIn(1);
				final Session session = register(apiBase,
						ccProfileBody("cc-expiry", "cid-exp", "secret-exp", tokenServer.tokenUrl(), null));

				// when — a proxied request needs a token
				final HttpResponse<String> init = initializeThroughProxy(proxyBase, stub.mcpUrl(), session.profileId(),
						session.cookie());

				// then — the round-trip succeeded with the FRESH token, not the expired
				// one
				assertThat(init.statusCode())
					.as("expiry-replaced initialize on %s, body=%s", ProxyAppHarness.stack(), init.body())
					.isEqualTo(200);
				assertThat(stub.authorizations()).as("Authorization on %s", ProxyAppHarness.stack())
					.containsExactly("Bearer " + StubTokenServer.tokenValue(2));

				// and — the re-exchange was a client_credentials grant, never a
				// refresh_token grant
				assertThat(tokenServer.requestCount()).as("token requests on %s", ProxyAppHarness.stack()).isEqualTo(2);
				assertThat(tokenServer.requests()).as("every grant on %s", ProxyAppHarness.stack())
					.allSatisfy((request) -> {
						assertThat(request.grantType()).isEqualTo("client_credentials");
						assertThat(request.refreshToken()).isNull();
					});
			}
		}

		@Test
		@DisplayName("a credential-bearing PUT never reuses the old token")
		@Story("D9A update eviction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("PUT with a new clientSecret evicts the cached token and stores the new credentials: the next "
				+ "proxied request carries a token minted with the NEW secret (the token server records client_secret "
				+ "per exchange), never the old one")
		void putWithNewCredentials_doesNotReuseOldToken() throws Exception {
			// given — a registered client-credentials profile
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			try (final StubTokenServer tokenServer = new StubTokenServer();
					final AuthRejectingStub stub = new AuthRejectingStub(0)) {
				final Session session = register(apiBase,
						ccProfileBody("cc-update", "cid-upd", "secret-old", tokenServer.tokenUrl(), null));

				// when — the credentials are replaced
				final HttpResponse<String> put = send(apiBase + "/auth-profile/" + session.profileId(), "PUT",
						ccProfileBody("cc-update", "cid-upd", "secret-new", tokenServer.tokenUrl(), null), AUTH_TOKEN,
						null, session.cookie());
				assertThat(put.statusCode()).as("PUT status on %s", ProxyAppHarness.stack()).isEqualTo(204);

				// and — a proxied request is issued
				final HttpResponse<String> init = initializeThroughProxy(proxyBase, stub.mcpUrl(), session.profileId(),
						session.cookie());

				// then — the round-trip succeeded with a token minted from the NEW
				// credentials
				assertThat(init.statusCode())
					.as("post-update initialize on %s, body=%s", ProxyAppHarness.stack(), init.body())
					.isEqualTo(200);
				assertThat(tokenServer.lastRequest()).as("last exchange on %s", ProxyAppHarness.stack()).isNotNull();
				assertThat(tokenServer.lastRequest().clientSecret())
					.as("exchanged secret on %s", ProxyAppHarness.stack())
					.isEqualTo("secret-new");
				assertThat(stub.authorizations()).as("Authorization on %s", ProxyAppHarness.stack())
					.containsExactly("Bearer " + StubTokenServer.tokenValue(2));
				assertThat(tokenServer.anyRequestWithField("refresh_token"))
					.as("no refresh_token grant on %s", ProxyAppHarness.stack())
					.isFalse();
			}
		}

		@Test
		@DisplayName("a failing token endpoint rolls back the registration with the 502 DTO")
		@Story("D9A failed-acquire rollback")
		@Severity(SeverityLevel.CRITICAL)
		@Description("When the initial client_credentials exchange fails (token endpoint answers 500), registration "
				+ "returns the structured 502 token_exchange_failed DTO, the GET list stays empty (no orphan profile, "
				+ "no retained secret) and a corrected re-registration succeeds")
		void registerWithFailingTokenEndpoint_returns502_andLeavesNoOrphan() throws Exception {
			// given — a token endpoint that fails
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			try (final StubTokenServer tokenServer = new StubTokenServer()) {
				tokenServer.status(500);

				// when
				final HttpResponse<String> failed = send(apiBase + "/auth-profile", "POST",
						ccProfileBody("cc-fail", "cid-fail", "secret-fail", tokenServer.tokenUrl(), null), AUTH_TOKEN,
						null, null);

				// then — the structured 502 DTO
				assertThat(failed.statusCode())
					.as("failed-register status on %s, body=%s", ProxyAppHarness.stack(), failed.body())
					.isEqualTo(502);
				final JsonNode dto = MAPPER.readTree(failed.body());
				assertThat(dto.path("status").asInt()).isEqualTo(502);
				assertThat(dto.path("code").asText()).isEqualTo("token_exchange_failed");

				// and — no orphan profile remains
				final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null,
						ownerCookie(failed));
				assertThat(list.body()).as("no orphan profile on %s", ProxyAppHarness.stack()).isEqualTo("[]");

				// when — the token endpoint recovers
				tokenServer.status(200);

				// then — a corrected re-registration succeeds
				final HttpResponse<String> retry = send(apiBase + "/auth-profile", "POST",
						ccProfileBody("cc-fail-2", "cid-fail", "secret-fail", tokenServer.tokenUrl(), null), AUTH_TOKEN,
						null, ownerCookie(failed));
				assertThat(retry.statusCode())
					.as("re-register status on %s, body=%s", ProxyAppHarness.stack(), retry.body())
					.isEqualTo(200);
				assertThat(MAPPER.readTree(retry.body()).path("profileId").asText())
					.as("re-registered profileId on %s", ProxyAppHarness.stack())
					.isNotBlank();
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

	/** Composes the proxy base (streamable HTTP relay). */
	private String proxyBase() {
		return "http://127.0.0.1:" + ProxyAppHarness.port(app) + "/mcp-inspector-api";
	}

	/** Body of an inline CLIENT_CREDENTIALS profile registration/update. */
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

	/**
	 * Registers an inline client-credentials profile and returns the server-issued id
	 * plus the owner cookie minted by the registration call.
	 */
	private static Session register(final String apiBase, final String body) throws Exception {
		final HttpResponse<String> response = send(apiBase + "/auth-profile", "POST", body, AUTH_TOKEN, null, null);
		assertThat(response.statusCode())
			.as("registration status on %s, body=%s", ProxyAppHarness.stack(), response.body())
			.isEqualTo(200);
		return new Session(MAPPER.readTree(response.body()).path("profileId").asText(), ownerCookie(response));
	}

	/**
	 * Opens a streamable proxy session bound to {@code profileId} by POSTing the MCP
	 * {@code initialize} frame through the relay, reusing the owner cookie minted by the
	 * registration call.
	 */
	private static HttpResponse<String> initializeThroughProxy(final String proxyBase, final String targetUrl,
			final String profileId, final String cookie) throws Exception {
		return send(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8) + "&profileId="
						+ profileId,
				"POST", MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN, cookie);
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
		info.put("name", "oauth2-cc-it");
		info.put("version", "0.1.0");
		return init;
	}

	/**
	 * Sends an HTTP request against the running demo app. Auth is passed either as the
	 * inspector header (raw token) or the proxy header ({@code Bearer <token>}); the
	 * owner cookie is re-sent when non-null.
	 */
	private static HttpResponse<String> send(final String url, final String method, final String body,
			final String inspectorAuth, final String proxyAuth, final String cookie) throws Exception {
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
	 * Minimal streamable-HTTP MCP stub whose message POSTs answer 401 for the first
	 * {@code failFirstPosts} calls and 200 afterwards, recording the inbound
	 * {@code Authorization} header of every POST. {@code GET /mcp} answers 405 — the SDK
	 * then stays in request-response mode, exactly like the other shared proxy ITs.
	 */
	private static final class AuthRejectingStub implements AutoCloseable {

		private static final byte[] REJECTED = "request rejected\n".getBytes(StandardCharsets.UTF_8);

		private final HttpServer server;

		private final AtomicInteger posts = new AtomicInteger();

		private final int failFirstPosts;

		private final List<String> authorizations = new CopyOnWriteArrayList<>();

		AuthRejectingStub(final int failFirstPosts) throws IOException {
			this.failFirstPosts = failFirstPosts;
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/mcp", this::handleMcp);
			this.server.start();
		}

		String mcpUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/mcp";
		}

		/** The {@code Authorization} values seen on each message POST, in order. */
		List<String> authorizations() {
			return this.authorizations;
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
			final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
			if (authorization != null) {
				this.authorizations.add(authorization);
			}
			if (this.posts.incrementAndGet() <= this.failFirstPosts) {
				exchange.sendResponseHeaders(401, REJECTED.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(REJECTED);
				}
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

}
