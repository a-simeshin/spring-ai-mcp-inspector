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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the OAuth2 client-credentials token lifecycle (D9A, issue #54) on a real HTTP
 * stack: the backend exchanges {@code client_credentials} at registration, a 401 on an
 * SSE session triggers exactly ONE token refresh and retry (on 1.x the retry needs the
 * SSE "non-OK HTTP code" literal; a streamable 401 carries no status and never refreshes
 * - the approved shrink, covered by a dedicated negative test), a second 401 yields the
 * D3 {@code unauthorized} DTO, a token that expired WITHOUT a refresh token triggers a
 * FRESH {@code client_credentials} re-exchange (never a {@code refresh_token} grant), a
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

	private static final ObjectMapper MAPPER = new ObjectMapper();

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
	 * is held backend-only, and a 401 on an SSE session triggers exactly one refresh +
	 * retry (a streamable 401 never refreshes on 1.x) - a second 401 surfaces the D3 DTO.
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
		@DisplayName("SSE: an upstream 401 refreshes the token once and retries with the fresh token")
		@Story("D9A one-retry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("On 1.x the D9 refresh fires on SSE sessions only: a relayed initialize answered 401 raises "
				+ "\"Sending message failed with a non-OK HTTP code: 401\", the mapper extracts the status, and the "
				+ "proxy performs a single client_credentials re-exchange and re-send with the NEW token: the result "
				+ "frame flows back over the SSE stream, the token server saw exactly two exchanges and the upstream "
				+ "stub received Bearer tok-1 then Bearer tok-2")
		void upstream401_refreshesTokenOnce_andRetriesWithFreshToken() throws Exception {
			// given — a registered client-credentials profile and an upstream that
			// rejects the first message POST
			app = ProxyAppHarness.start("SSE", true, AUTH_TOKEN, "--logging.level.io.inspector.mcp.core.proxy=DEBUG",
					"--logging.level.io.inspector.mcp.core.auth=DEBUG");
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			try (final StubTokenServer tokenServer = new StubTokenServer();
					final AuthRejectingStub stub = new AuthRejectingStub(1)) {
				final Session session = register(apiBase,
						ccProfileBody("cc-retry", "cid-retry", "secret-retry", tokenServer.tokenUrl(), null));

				// when — the initialize frame is relayed over an SSE session
				final SseReader stream = new SseReader(proxyBase + "/sse?transportType=sse&url="
						+ URLEncoder.encode(stub.sseUrl(), StandardCharsets.UTF_8) + "&profileId="
						+ session.profileId(), session.cookie());
				final String messageEndpoint = stream.awaitFrame("endpoint").data();
				final HttpResponse<String> accepted = send(
						"http://127.0.0.1:" + ProxyAppHarness.port(app) + messageEndpoint, "POST",
						MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN, session.cookie());
				assertThat(accepted.statusCode())
					.as("message POST accepted on %s, body=%s", ProxyAppHarness.stack(), accepted.body())
					.isEqualTo(202);

				// then — the retried round-trip succeeded: the result frame came back
				final SseReader.SseFrame result = stream.awaitFrame("message");
				assertThat(MAPPER.readTree(result.data()).path("id").asInt())
					.as("initialize result id on %s, frame=%s", ProxyAppHarness.stack(), result.data())
					.isEqualTo(1);

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
		@DisplayName("SSE: a second 401 after the one retry yields the unauthorized DTO")
		@Story("D9A one-retry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("On an SSE session whose upstream keeps answering 401: the first 401 refreshes (one exchange), "
				+ "the retried send fails again, and the failure surfaces as the exact D3 unauthorized error event on "
				+ "the SSE stream; the session is deleted (a later POST to the message endpoint answers 404) and the "
				+ "token server saw exactly two exchanges - no retry loop")
		void second401_afterOneRetry_yieldsUnauthorizedDto() throws Exception {
			// given — an upstream that always rejects
			app = ProxyAppHarness.start("SSE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			try (final StubTokenServer tokenServer = new StubTokenServer();
					final AuthRejectingStub stub = new AuthRejectingStub(Integer.MAX_VALUE)) {
				final Session session = register(apiBase,
						ccProfileBody("cc-second-401", "cid-401", "secret-401", tokenServer.tokenUrl(), null));

				// when — the initialize frame is relayed over an SSE session
				final SseReader stream = new SseReader(proxyBase + "/sse?transportType=sse&url="
						+ URLEncoder.encode(stub.sseUrl(), StandardCharsets.UTF_8) + "&profileId="
						+ session.profileId(), session.cookie());
				final String messageEndpoint = stream.awaitFrame("endpoint").data();
				final HttpResponse<String> accepted = send(
						"http://127.0.0.1:" + ProxyAppHarness.port(app) + messageEndpoint, "POST",
						MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN, session.cookie());
				assertThat(accepted.statusCode())
					.as("message POST accepted on %s, body=%s", ProxyAppHarness.stack(), accepted.body())
					.isEqualTo(202);

				// then — the exact D3 unauthorized DTO arrives as the error event
				final SseReader.SseFrame errorFrame = stream.awaitFrame("error");
				final JsonNode dto = MAPPER.readTree(errorFrame.data());
				assertThat(dto.path("status").asInt())
					.as("second-401 status on %s, frame=%s", ProxyAppHarness.stack(), errorFrame.data())
					.isEqualTo(401);
				assertThat(dto.path("code").asText()).isEqualTo("unauthorized");
				assertThat(dto.path("reason").asText()).isEqualTo(REASON_401);
				assertThat(dto.path("guidance").asText()).isEqualTo(GUIDANCE_401);

				// and — the stream ends and the failed session is deleted: a later
				// POST to the message endpoint answers 404
				stream.awaitEnd();
				final HttpResponse<String> gone = send(
						"http://127.0.0.1:" + ProxyAppHarness.port(app) + messageEndpoint, "POST",
						MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN, session.cookie());
				assertThat(gone.statusCode()).as("deleted session on %s, body=%s", ProxyAppHarness.stack(), gone.body())
					.isEqualTo(404);

				// and — exactly one refresh (registration + the single 401 refresh),
				// no retry loop
				assertThat(tokenServer.requestCount()).as("token requests on %s", ProxyAppHarness.stack()).isEqualTo(2);
			}
		}

		@Test
		@DisplayName("streamable: an upstream 401 never refreshes and answers the legacy 502 (1.x shrink)")
		@Story("D9A one-retry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The 1.x shrink of D9: on a streamable session an upstream 401 surfaces as a generic send "
				+ "failure whose message carries no extractable status, so the refresh never fires - the token server "
				+ "sees exactly ONE exchange (the registration one), the upstream saw only the ORIGINAL token, and the "
				+ "browser receives the legacy 502 MCP_CONNECT_FAILED body, never the D3 DTO")
		void streamable401_neverRefreshes_answersLegacyError() throws Exception {
			// given — a registered profile and an upstream that always rejects
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			try (final StubTokenServer tokenServer = new StubTokenServer();
					final AuthRejectingStub stub = new AuthRejectingStub(Integer.MAX_VALUE)) {
				final Session session = register(apiBase,
						ccProfileBody("cc-streamable-401", "cid-s401", "secret-s401", tokenServer.tokenUrl(), null));

				// when — the initialize frame is POSTed through the streamable relay
				final HttpResponse<String> failed = initializeThroughProxy(proxyBase, stub.mcpUrl(),
						session.profileId(), session.cookie());

				// then — the legacy 502 contract, never the D3 DTO
				assertThat(failed.statusCode())
					.as("streamable shrink status on %s, body=%s", ProxyAppHarness.stack(), failed.body())
					.isEqualTo(502);
				final JsonNode error = MAPPER.readTree(failed.body()).path("error");
				assertThat(error.path("code").asText()).as("legacy error code on %s", ProxyAppHarness.stack())
					.isEqualTo("MCP_CONNECT_FAILED");
				assertThat(failed.body()).as("no D3 reason text on %s", ProxyAppHarness.stack())
					.doesNotContain(REASON_401);

				// and — the refresh never fired: only the registration exchange, and
				// the upstream saw only the original token
				assertThat(tokenServer.requestCount())
					.as("token requests (no refresh on streamable) on %s", ProxyAppHarness.stack())
					.isEqualTo(1);
				assertThat(stub.authorizations()).as("Authorization per upstream POST on %s", ProxyAppHarness.stack())
					.containsExactly("Bearer " + StubTokenServer.tokenValue(1));
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
		assertThat(setCookie).as("Set-Cookie HttpOnly on %s", ProxyAppHarness.stack()).containsIgnoringCase("HttpOnly");
		assertThat(setCookie).as("Set-Cookie SameSite on %s", ProxyAppHarness.stack()).contains("SameSite=Lax");
		assertThat(setCookie).as("Set-Cookie Path on %s", ProxyAppHarness.stack()).contains("Path=/");
		assertThat(setCookie).as("Set-Cookie Secure absent on HTTP %s", ProxyAppHarness.stack())
			.doesNotContain("Secure");
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
	 * Minimal MCP stub serving BOTH transports: {@code GET /sse} streams the endpoint
	 * prologue, {@code POST /message} (SSE) and {@code POST /mcp} (streamable) answer 401
	 * for the first {@code failFirstPosts} calls and 200 afterwards, recording the
	 * inbound {@code Authorization} header of every POST. {@code GET /mcp} answers 405 -
	 * the streamable SDK then stays in request-response mode, exactly like the other
	 * shared proxy ITs.
	 */
	/**
	 * Minimal SSE reader for the profile-bound proxy stream: opens {@code GET /sse} with
	 * the owner cookie, buffers named frames, and exposes bounded awaits for the frames
	 * the tests need (endpoint, message, error) plus the stream end.
	 */
	private static final class SseReader {

		record SseFrame(String event, String data) {
		}

		private final CopyOnWriteArrayList<SseFrame> frames = new CopyOnWriteArrayList<>();

		private final AtomicReference<Throwable> error = new AtomicReference<>();

		private final AtomicBoolean finished = new AtomicBoolean();

		SseReader(final String url, final String cookie) {
			final Thread thread = new Thread(() -> {
				try {
					final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(30))
						.header("Accept", "text/event-stream")
						.header(PROXY_AUTH_HEADER, "Bearer " + AUTH_TOKEN)
						.header("Cookie", OWNER_COOKIE + "=" + cookie)
						.GET()
						.build();
					final HttpResponse<java.io.InputStream> response = HTTP.send(request,
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
			}, "cc-it-sse-reader-" + ProxyAppHarness.stack());
			thread.setDaemon(true);
			thread.start();
		}

		/**
		 * Awaits the first frame with the given event name and returns it.
		 */
		SseFrame awaitFrame(final String event) {
			try {
				Awaitility.await("SSE " + event + " frame on " + ProxyAppHarness.stack())
					.atMost(Duration.ofSeconds(20))
					.pollInterval(Duration.ofMillis(50))
					.until(() -> this.frames.stream().anyMatch((frame) -> event.equals(frame.event())));
			}
			catch (final org.awaitility.core.ConditionTimeoutException ex) {
				throw new AssertionError("no '" + event + "' frame on " + ProxyAppHarness.stack() + "; frames="
						+ this.frames + ", readerError=" + this.error.get() + ", finished=" + this.finished.get(), ex);
			}
			return this.frames.stream().filter((frame) -> event.equals(frame.event())).findFirst().orElseThrow();
		}

		/**
		 * Awaits the end of the stream (the relay completed or aborted it).
		 */
		void awaitEnd() {
			try {
				Awaitility.await("SSE stream end on " + ProxyAppHarness.stack())
					.atMost(Duration.ofSeconds(20))
					.pollInterval(Duration.ofMillis(50))
					.until(this.finished::get);
			}
			catch (final org.awaitility.core.ConditionTimeoutException ex) {
				throw new AssertionError("SSE stream did not end on " + ProxyAppHarness.stack() + "; frames="
						+ this.frames + ", readerError=" + this.error.get(), ex);
			}
		}

	}

	private static final class AuthRejectingStub implements AutoCloseable {

		private static final byte[] REJECTED = "request rejected\n".getBytes(StandardCharsets.UTF_8);

		private final HttpServer server;

		private final ExecutorService pool = Executors.newCachedThreadPool();

		/**
		 * Response queue of the most recently opened SSE stream. Each stream polls its
		 * own queue, so the preflight's abandoned stream can never take a response meant
		 * for the delegate's real stream.
		 */
		private final AtomicReference<BlockingQueue<String>> activeStream = new AtomicReference<>();

		private final AtomicInteger posts = new AtomicInteger();

		private final int failFirstPosts;

		private final List<String> authorizations = new CopyOnWriteArrayList<>();

		private final AtomicBoolean stopped = new AtomicBoolean();

		AuthRejectingStub(final int failFirstPosts) throws IOException {
			this.failFirstPosts = failFirstPosts;
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			// A held-open SSE exchange must not block the other endpoints: without an
			// explicit executor the JDK server serves exchanges on one dispatcher
			// thread, and the relayed POST would hang behind the stream (E2eSseMcpStub
			// uses the same cached pool).
			this.server.setExecutor(this.pool);
			this.server.createContext("/sse", this::handleSse);
			this.server.createContext("/message", this::handleMessage);
			this.server.createContext("/mcp", this::handleMcp);
			this.server.start();
		}

		String mcpUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/mcp";
		}

		String sseUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/sse";
		}

		/** The {@code Authorization} values seen on each message POST, in order. */
		List<String> authorizations() {
			return this.authorizations;
		}

		@Override
		public void close() {
			this.stopped.set(true);
			this.server.stop(0);
			this.pool.shutdownNow();
		}

		/**
		 * Serves the SSE transport handshake: the endpoint event names the message
		 * endpoint, then the stream stays open until the stub is stopped.
		 */
		private void handleSse(final HttpExchange exchange) throws IOException {
			final BlockingQueue<String> mine = new LinkedBlockingQueue<>();
			this.activeStream.set(mine);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
				out.flush();
				while (!this.stopped.get()) {
					final String frame = mine.poll(100, TimeUnit.MILLISECONDS);
					if (frame != null) {
						out.write(("event: message\ndata: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8));
						out.flush();
					}
				}
			}
			catch (final InterruptedException | IOException ignored) {
				// the stream ended or the stub was stopped
			}
		}

		private void handleMessage(final HttpExchange exchange) throws IOException {
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
			// SSE: the JSON-RPC answer travels as a message event on the held-open
			// stream; the
			// POST itself is just accepted.
			exchange.sendResponseHeaders(202, -1);
			exchange.close();
			final BlockingQueue<String> stream = this.activeStream.get();
			if (stream != null) {
				stream.offer(responseBody);
			}
		}

		private void handleMcp(final HttpExchange exchange) throws IOException {
			if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				exchange.close();
				return;
			}
			answerPost(exchange);
		}

		private void answerPost(final HttpExchange exchange) throws IOException {
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
