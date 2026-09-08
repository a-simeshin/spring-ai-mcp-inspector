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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * Verifies the OAuth2 authorization-code (PKCE) browser flow (D9B, issue #54) on a real
 * HTTP stack: PENDING profile creation returns the server-issued one-time {@code state}
 * and the {@code authorizationUrl}, the {@code /exchange} completes the flow against the
 * shared {@link StubTokenServer} with the exact {@code authorization_code} form fields,
 * the exchanged token is held backend-only and reaches the upstream MCP server through
 * the proxy, and state mismatch / state replay / PKCE mismatch / cross-owner exchange are
 * rejected with the exact status codes.
 *
 * <p>
 * The class lives in {@code demo-app}'s test-jar, so Failsafe's
 * {@code dependenciesToScan} runs it once per stack module — every scenario is exercised
 * on BOTH webmvc and webflux (see {@code ProxyAppHarness.stack()} in the assertion
 * messages). No WireMock, no Testcontainers.
 */
@Epic("Inspector Auth Profiles")
@Feature("OAuth2 authorization-code flow (D9B)")
class OAuth2AuthCodeFlowIT {

	/** Fixed inspector/proxy auth token threaded through the demo via the harness. */
	private static final String AUTH_TOKEN = "oauth2-ac-it-token-0123456789";

	/** Inspector API auth header ({@code InspectorAuthFilter}). */
	private static final String INSPECTOR_AUTH_HEADER = "X-MCP-Inspector-Auth";

	/** Proxy auth header used by {@code ProxyAuthFilter}. */
	private static final String PROXY_AUTH_HEADER = "X-MCP-Proxy-Auth";

	/** Signed session-owner cookie name ({@code OwnerTokenCodec}). */
	private static final String OWNER_COOKIE = "MCP_INSPECTOR_SESSION";

	/** PKCE verifier whose S256 challenge is registered with the pending profile. */
	private static final String PKCE_VERIFIER = "pkce-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";

	/** Authorization code returned by the IdP callback. */
	private static final String AUTH_CODE = "auth-code-1";

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
	 * The two-phase browser flow: PENDING creation (server-issued state, no exchange) and
	 * the full cycle to a proxied request carrying the exchanged token.
	 */
	@Nested
	@DisplayName("Authorization-code flow")
	class Flow {

		@Test
		@DisplayName("pending creation returns the server-issued state and runs no exchange")
		@Story("D9B pending creation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("POST /mcp-inspector/api/auth-profile with an AUTHORIZATION_CODE profile returns "
				+ "{profileId, state, authorizationUrl} — the state is server-issued and the token endpoint is not "
				+ "touched yet")
		void pendingCreate_returnsServerIssuedState_noExchangeAtCreate() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			try (final StubTokenServer tokenServer = new StubTokenServer()) {
				// when
				final Pending pending = registerPending(apiBase, "ac-pending", "cid-ac", tokenServer.tokenUrl(),
						PKCE_VERIFIER);

				// then — the server-issued one-time state and the authorization URL
				assertThat(pending.state()).as("server-issued state on %s", ProxyAppHarness.stack()).isNotBlank();
				assertThat(pending.authorizationUrl()).as("authorization URL on %s", ProxyAppHarness.stack())
					.isEqualTo("http://127.0.0.1:1/auth");

				// and — no token exchange happened at creation
				assertThat(tokenServer.requestCount()).as("token requests at create on %s", ProxyAppHarness.stack())
					.isZero();
			}
		}

		@Test
		@DisplayName("the full pending → exchange cycle ends in a proxied request carrying the token")
		@Story("D9B exchange happy path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Exchange with the correct state and PKCE verifier returns {profileId} (token held "
				+ "backend-only, never in the list), the token request carries the exact authorization_code form "
				+ "fields (grant_type/client_id/code/redirect_uri/code_verifier, no client_secret), and a proxied "
				+ "request bound to the ACTIVE profile reaches the upstream with Authorization: Bearer tok-1")
		void fullCycle_pendingToExchange_proxiedRequestCarriesToken() throws Exception {
			// given — a PENDING profile and its server-issued state
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			try (final StubTokenServer tokenServer = new StubTokenServer();
					final RecordingMcpStub stub = new RecordingMcpStub()) {
				final Pending pending = registerPending(apiBase, "ac-cycle", "cid-cycle", tokenServer.tokenUrl(),
						PKCE_VERIFIER);

				// when — the browser callback exchanges the code
				final HttpResponse<String> exchange = exchange(apiBase, pending.profileId(), pending.cookie(),
						AUTH_CODE, PKCE_VERIFIER, pending.state());

				// then — 200 {profileId}, tokens stay backend-only
				assertThat(exchange.statusCode())
					.as("exchange status on %s, body=%s", ProxyAppHarness.stack(), exchange.body())
					.isEqualTo(200);
				assertThat(MAPPER.readTree(exchange.body()).path("profileId").asText())
					.as("exchanged profileId on %s", ProxyAppHarness.stack())
					.isEqualTo(pending.profileId());
				assertThat(exchange.body()).as("access token must not leave the backend on %s", ProxyAppHarness.stack())
					.doesNotContain(StubTokenServer.tokenValue(1));

				// and — the token request carried the exact authorization_code fields
				assertThat(tokenServer.requestCount()).as("token requests on %s", ProxyAppHarness.stack()).isEqualTo(1);
				final StubTokenServer.TokenRequest tokenRequest = tokenServer.lastRequest();
				assertThat(tokenRequest.grantType()).as("grant_type on %s", ProxyAppHarness.stack())
					.isEqualTo("authorization_code");
				assertThat(tokenRequest.clientId()).as("client_id on %s", ProxyAppHarness.stack())
					.isEqualTo("cid-cycle");
				assertThat(tokenRequest.code()).as("code on %s", ProxyAppHarness.stack()).isEqualTo(AUTH_CODE);
				assertThat(tokenRequest.redirectUri()).as("redirect_uri on %s", ProxyAppHarness.stack())
					.isEqualTo("http://127.0.0.1:1/cb");
				assertThat(tokenRequest.codeVerifier()).as("code_verifier on %s", ProxyAppHarness.stack())
					.isEqualTo(PKCE_VERIFIER);
				assertThat(tokenRequest.clientSecret())
					.as("no client_secret for a public client on %s", ProxyAppHarness.stack())
					.isNull();

				// and — the list still carries no token
				final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null,
						pending.cookie());
				assertThat(list.body()).as("token must not appear in the list on %s", ProxyAppHarness.stack())
					.doesNotContain(StubTokenServer.tokenValue(1));

				// when — a streamable session is opened bound to the ACTIVE profile
				final HttpResponse<String> init = initializeThroughProxy(proxyBase, stub.mcpUrl(), pending.profileId(),
						pending.cookie());

				// then — the upstream received the exchanged token
				assertThat(init.statusCode())
					.as("proxied initialize on %s, body=%s", ProxyAppHarness.stack(), init.body())
					.isEqualTo(200);
				assertThat(stub.authorizations()).as("Authorization seen by upstream on %s", ProxyAppHarness.stack())
					.containsExactly("Bearer " + StubTokenServer.tokenValue(1));
			}
		}

	}

	/**
	 * Rejection contract: state mismatch, state replay (the one-time state is consumed by
	 * the first failed verification), PKCE mismatch and a cross-owner exchange.
	 */
	@Nested
	@DisplayName("Exchange rejections")
	class Rejections {

		@Test
		@DisplayName("a state mismatch returns 400 and never touches the token endpoint")
		@Story("D9B state verification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("POST /auth-profile/{profileId}/exchange with a state that is not the server-issued value "
				+ "returns 400 before any token request")
		void exchange_withWrongState_returns400() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			try (final StubTokenServer tokenServer = new StubTokenServer()) {
				final Pending pending = registerPending(apiBase, "ac-wrong-state", "cid-ws", tokenServer.tokenUrl(),
						PKCE_VERIFIER);

				// when — a state the server never issued
				final HttpResponse<String> exchange = exchange(apiBase, pending.profileId(), pending.cookie(),
						AUTH_CODE, PKCE_VERIFIER, "state-from-the-attacker");

				// then
				assertThat(exchange.statusCode())
					.as("state-mismatch status on %s, body=%s", ProxyAppHarness.stack(), exchange.body())
					.isEqualTo(400);
				assertThat(exchange.body()).as("mismatch message on %s", ProxyAppHarness.stack())
					.contains("state mismatch");
				assertThat(tokenServer.requestCount()).as("no token request on %s", ProxyAppHarness.stack()).isZero();
			}
		}

		@Test
		@DisplayName("a replayed state returns 400 (the one-time state is consumed)")
		@Story("D9B state verification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The server-issued state is one-time: a failed verification consumes it, so a second attempt "
				+ "with the CORRECT state still returns 400 and never reaches the token endpoint")
		void exchange_afterStateConsumed_replayReturns400() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			try (final StubTokenServer tokenServer = new StubTokenServer()) {
				final Pending pending = registerPending(apiBase, "ac-replay", "cid-replay", tokenServer.tokenUrl(),
						PKCE_VERIFIER);

				// when — the first attempt presents a wrong state (consumes it)
				final HttpResponse<String> first = exchange(apiBase, pending.profileId(), pending.cookie(), AUTH_CODE,
						PKCE_VERIFIER, "wrong-state");
				assertThat(first.statusCode()).as("first attempt on %s", ProxyAppHarness.stack()).isEqualTo(400);

				// then — the replay with the correct state is rejected too
				final HttpResponse<String> replay = exchange(apiBase, pending.profileId(), pending.cookie(), AUTH_CODE,
						PKCE_VERIFIER, pending.state());
				assertThat(replay.statusCode())
					.as("replay status on %s, body=%s", ProxyAppHarness.stack(), replay.body())
					.isEqualTo(400);
				assertThat(tokenServer.requestCount()).as("no token request on %s", ProxyAppHarness.stack()).isZero();
			}
		}

		@Test
		@DisplayName("a PKCE mismatch returns 400 before any token request")
		@Story("D9B PKCE verification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Exchange with the correct state but a code_verifier whose S256 does not match the profile's "
				+ "codeChallenge returns 400 (PKCE is checked before the token request)")
		void exchange_withWrongPkceVerifier_returns400() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			try (final StubTokenServer tokenServer = new StubTokenServer()) {
				final Pending pending = registerPending(apiBase, "ac-pkce", "cid-pkce", tokenServer.tokenUrl(),
						PKCE_VERIFIER);

				// when — a verifier that does not match the registered challenge
				final HttpResponse<String> exchange = exchange(apiBase, pending.profileId(), pending.cookie(),
						AUTH_CODE, "a-completely-different-verifier", pending.state());

				// then
				assertThat(exchange.statusCode())
					.as("PKCE-mismatch status on %s, body=%s", ProxyAppHarness.stack(), exchange.body())
					.isEqualTo(400);
				assertThat(exchange.body()).as("PKCE message on %s", ProxyAppHarness.stack()).contains("PKCE");
				assertThat(tokenServer.requestCount()).as("no token request on %s", ProxyAppHarness.stack()).isZero();
			}
		}

		@Test
		@DisplayName("a cross-owner exchange of a PENDING profile returns 404")
		@Story("D9B cross-owner exchange")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Owner B cannot exchange owner A's PENDING profile: the exchange on A's profileId under B's "
				+ "cookie returns 404 (existence is not leaked)")
		void crossOwner_exchangeOfPendingProfile_returns404() throws Exception {
			// given — owner A's PENDING profile and owner B's cookie
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			try (final StubTokenServer tokenServer = new StubTokenServer()) {
				final Pending pendingA = registerPending(apiBase, "ac-owner-a", "cid-a", tokenServer.tokenUrl(),
						PKCE_VERIFIER);
				final Session ownerB = register(apiBase, bearerProfileBody("owner-b", "tok-b"));

				// when — B presents A's pending profileId
				final HttpResponse<String> exchange = exchange(apiBase, pendingA.profileId(), ownerB.cookie(),
						AUTH_CODE, PKCE_VERIFIER, pendingA.state());

				// then — 404, existence is not leaked
				assertThat(exchange.statusCode())
					.as("cross-owner exchange status on %s, body=%s", ProxyAppHarness.stack(), exchange.body())
					.isEqualTo(404);
				assertThat(tokenServer.requestCount()).as("no token request on %s", ProxyAppHarness.stack()).isZero();
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

	/**
	 * Registers an auth-code PENDING profile and returns the server-issued id, the minted
	 * owner cookie and the one-time state.
	 */
	private static Pending registerPending(final String apiBase, final String name, final String clientId,
			final String tokenUrl, final String pkceVerifier) throws Exception {
		final String body = """
				{"name":"%s","type":"OAUTH2","grantMode":"AUTHORIZATION_CODE","tokenUrl":"%s",\
				"clientId":"%s","scopes":"read","authorizationUrl":"http://127.0.0.1:1/auth",\
				"redirectUri":"http://127.0.0.1:1/cb","codeChallenge":"%s","codeChallengeMethod":"S256"}"""
			.formatted(name, tokenUrl, clientId, s256(pkceVerifier));
		final HttpResponse<String> response = send(apiBase + "/auth-profile", "POST", body, AUTH_TOKEN, null, null);
		assertThat(response.statusCode())
			.as("pending registration on %s, body=%s", ProxyAppHarness.stack(), response.body())
			.isEqualTo(200);
		final JsonNode json = MAPPER.readTree(response.body());
		return new Pending(json.path("profileId").asText(), ownerCookie(response), json.path("state").asText(),
				json.path("authorizationUrl").asText());
	}

	/** Registers an inline bearer profile and returns the id plus the owner cookie. */
	private static Session register(final String apiBase, final String body) throws Exception {
		final HttpResponse<String> response = send(apiBase + "/auth-profile", "POST", body, AUTH_TOKEN, null, null);
		assertThat(response.statusCode())
			.as("registration status on %s, body=%s", ProxyAppHarness.stack(), response.body())
			.isEqualTo(200);
		return new Session(MAPPER.readTree(response.body()).path("profileId").asText(), ownerCookie(response));
	}

	private static String bearerProfileBody(final String name, final String token) {
		return "{\"profile\":{\"name\":\"" + name + "\",\"type\":\"BEARER\",\"token\":\"" + token + "\"}}";
	}

	/** POSTs an exchange payload for {@code profileId} under the given owner cookie. */
	private static HttpResponse<String> exchange(final String apiBase, final String profileId, final String cookie,
			final String code, final String codeVerifier, final String state) throws Exception {
		final String body = """
				{"code":"%s","codeVerifier":"%s","state":"%s"}""".formatted(code, codeVerifier, state);
		return send(apiBase + "/auth-profile/" + profileId + "/exchange", "POST", body, AUTH_TOKEN, null, cookie);
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
		info.put("name", "oauth2-ac-it");
		info.put("version", "0.1.0");
		return init;
	}

	/** Sends an HTTP request against the running demo app (see sibling ITs). */
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

	/** PKCE S256 challenge of {@code verifier} (base64url, unpadded). */
	private static String s256(final String verifier) throws Exception {
		final byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

	/** PENDING registration response. */
	private record Pending(String profileId, String cookie, String state, String authorizationUrl) {
	}

	/** Registration response: server-issued id + the minted owner cookie. */
	private record Session(String profileId, String cookie) {
	}

	/**
	 * Minimal streamable-HTTP MCP stub that answers every message POST with a valid
	 * {@code initialize} response and records the inbound {@code Authorization} header.
	 * {@code GET /mcp} answers 405 — request-response mode, like the sibling ITs.
	 */
	private static final class RecordingMcpStub implements AutoCloseable {

		private final HttpServer server;

		private final List<String> authorizations = new CopyOnWriteArrayList<>();

		RecordingMcpStub() throws IOException {
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
