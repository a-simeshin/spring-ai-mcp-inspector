/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Verifies the owner-scoped auth-profile API (D2/D7/D8) on a real HTTP stack: inline
 * registration of all four profile types, the inspector-auth guard (401/403 without a
 * valid {@code X-MCP-Inspector-Auth}), CRUD parity (GET list / PUT / DELETE by path
 * variable) and the prefill happy path (Spring-config profile → {@code GET /prefill} →
 * {@code POST {name,type}} reference → proxied request carries the profile headers).
 *
 * <p>
 * The class lives in {@code demo-app}'s test-jar, so Failsafe's
 * {@code dependenciesToScan} runs it once per stack module — every scenario is exercised
 * on BOTH webmvc and webflux (see {@code ProxyAppHarness.stack()} in the assertion
 * messages).
 *
 * <p>
 * Every scenario keeps the signed session-owner cookie ({@code MCP_INSPECTOR_SESSION})
 * returned by the first authenticated call and re-sends it on subsequent calls, because
 * the store is owner-scoped: a request without the cookie would be minted a fresh owner
 * and would not see the registered profile (D8).
 *
 * <p>
 * The proxied-request scenario targets a tiny JDK {@link HttpServer} MCP stub that
 * records the inbound headers of the streamable-HTTP POST, so the test can prove the
 * bound profile's {@code Authorization} header really reaches the upstream server. No
 * WireMock, no Testcontainers.
 */
@Epic("Inspector Auth Profiles")
@Feature("Profile CRUD, prefill and proxied application")
class AuthProfileApiIT {

	/** Fixed inspector auth token threaded through the demo via the harness. */
	private static final String AUTH_TOKEN = "auth-profile-it-token-0123456789";

	/** Inspector API auth header ({@code InspectorAuthFilter}). */
	private static final String INSPECTOR_AUTH_HEADER = "X-MCP-Inspector-Auth";

	/** Proxy auth header used by {@code ProxyAuthFilter}. */
	private static final String PROXY_AUTH_HEADER = "X-MCP-Proxy-Auth";

	/** Signed session-owner cookie name ({@code OwnerTokenCodec}). */
	private static final String OWNER_COOKIE = "MCP_INSPECTOR_SESSION";

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
	 * Inline registration of every profile type on the real HTTP stack: bearer, API-key
	 * (header + query placement), custom headers and OAuth2 (authorization-code PENDING —
	 * no token exchange needed at registration). Each returns a server-issued
	 * {@code profileId}.
	 */
	@Nested
	@DisplayName("Registration")
	class Registration {

		@Test
		@DisplayName("all four profile types register on the real HTTP stack")
		@Story("Profile registration")
		@Severity(SeverityLevel.CRITICAL)
		@Description("POST /mcp-inspector/api/auth-profile accepts inline profiles of all four types "
				+ "(BEARER, API_KEY header, API_KEY query, CUSTOM_HEADERS, OAUTH2 authorization-code pending) "
				+ "and returns a server-issued profileId")
		void registerAllFourTypes_withValidAuth_returnsProfileIds() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();

			// when & then — bearer
			final String bearerId = registerInline(apiBase, """
					{"profile":{"name":"prod-bearer","type":"BEARER","token":"tok-123"}}""");
			assertThat(bearerId).as("bearer profileId on %s", ProxyAppHarness.stack()).isNotBlank();

			// when & then — API-key, header placement
			final String apiHeaderId = registerInline(apiBase, """
					{"profile":{"name":"prod-api","type":"API_KEY","keyName":"X-API-Key",\
					"keyValue":"key-123","placement":"HEADER"}}""");
			assertThat(apiHeaderId).as("api-key(header) profileId on %s", ProxyAppHarness.stack()).isNotBlank();

			// when & then — API-key, query placement
			final String apiQueryId = registerInline(apiBase, """
					{"profile":{"name":"prod-api-query","type":"API_KEY","keyName":"api_key",\
					"keyValue":"key-456","placement":"QUERY"}}""");
			assertThat(apiQueryId).as("api-key(query) profileId on %s", ProxyAppHarness.stack()).isNotBlank();

			// when & then — custom headers
			final String customId = registerInline(apiBase, """
					{"profile":{"name":"prod-custom","type":"CUSTOM_HEADERS",\
					"headers":[{"name":"X-Tenant","value":"acme"}]}}""");
			assertThat(customId).as("custom-headers profileId on %s", ProxyAppHarness.stack()).isNotBlank();

			// when & then — OAuth2 authorization-code PENDING (no exchange at
			// registration)
			final String oauthId = registerInline(apiBase, """
					{"profile":{"name":"prod-oauth","type":"OAUTH2","grantMode":"AUTHORIZATION_CODE",\
					"tokenUrl":"http://127.0.0.1:1/token","clientId":"cid",\
					"authorizationUrl":"http://127.0.0.1:1/auth","redirectUri":"http://127.0.0.1:1/cb",\
					"codeChallenge":"challenge","codeChallengeMethod":"S256"}}""");
			assertThat(oauthId).as("oauth2 pending profileId on %s", ProxyAppHarness.stack()).isNotBlank();
		}

		@Test
		@DisplayName("registration without a valid X-MCP-Inspector-Auth is rejected")
		@Story("Inspector auth guard")
		@Severity(SeverityLevel.CRITICAL)
		@Description("POST /mcp-inspector/api/auth-profile answers 401 both when X-MCP-Inspector-Auth is "
				+ "missing and when it carries a wrong token")
		void register_withoutOrWrongInspectorAuth_isRejected() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String body = """
					{"profile":{"name":"no-auth","type":"BEARER","token":"tok"}}""";

			// when — no auth header
			final HttpResponse<String> missing = send(apiBase + "/auth-profile", "POST", body, null, null, null);

			// then
			assertThat(missing.statusCode())
				.as("missing inspector auth must be 401 on %s, body=%s", ProxyAppHarness.stack(), missing.body())
				.isEqualTo(401);

			// when — wrong auth token
			final HttpResponse<String> wrong = send(apiBase + "/auth-profile", "POST", body, "definitely-wrong", null,
					null);

			// then
			assertThat(wrong.statusCode())
				.as("wrong inspector auth must be 401 on %s, body=%s", ProxyAppHarness.stack(), wrong.body())
				.isEqualTo(401);
		}

	}

	/**
	 * Full CRUD cycle on the real stack: GET list returns secret-free summaries, PUT by
	 * path variable returns 204 and renames the profile, DELETE by path variable returns
	 * 204 and removes it, unknown ids answer 404.
	 */
	@Nested
	@DisplayName("CRUD parity")
	class CrudParity {

		@Test
		@DisplayName("GET list / PUT / DELETE by path variable work on both stacks")
		@Story("Profile CRUD")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Registers a bearer profile, lists it as a secret-free summary, PUT-renames it (204), "
				+ "asserts the new name, DELETE-removes it (204) and proves unknown ids answer 404")
		void fullCrudCycle_listPutDelete_byPathVariable() throws Exception {
			// given — registration mints the owner cookie that scopes the whole cycle
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final Session session = register(apiBase, """
					{"profile":{"name":"crud-bearer","type":"BEARER","token":"crud-tok"}}""");
			assertThat(session.profileId()).as("profileId on %s", ProxyAppHarness.stack()).isNotBlank();

			// when — GET list with the owner cookie
			final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null,
					session.cookie());

			// then — the profile is present as a secret-free summary
			assertThat(list.statusCode()).as("GET list status on %s", ProxyAppHarness.stack()).isEqualTo(200);
			final JsonNode listBody = MAPPER.readTree(list.body());
			final JsonNode created = findByName(listBody, "crud-bearer");
			assertThat(created).as("created profile in list on %s", ProxyAppHarness.stack()).isNotNull();
			assertThat(created.path("profileId").asText()).isEqualTo(session.profileId());
			assertThat(created.path("type").asText()).isEqualTo("BEARER");
			assertThat(list.body()).as("bearer secret must never leave the backend on %s", ProxyAppHarness.stack())
				.doesNotContain("crud-tok");

			// when — PUT rename (with the owner cookie)
			final HttpResponse<String> put = send(apiBase + "/auth-profile/" + session.profileId(), "PUT", """
					{"profile":{"name":"crud-renamed","type":"BEARER","token":"crud-tok-2"}}""", AUTH_TOKEN, null,
					session.cookie());

			// then — 204 and the new name in the list
			assertThat(put.statusCode()).as("PUT status on %s", ProxyAppHarness.stack()).isEqualTo(204);
			final JsonNode afterPut = MAPPER
				.readTree(send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null, session.cookie()).body());
			assertThat(findByName(afterPut, "crud-renamed")).as("renamed profile on %s", ProxyAppHarness.stack())
				.isNotNull();
			assertThat(findByName(afterPut, "crud-bearer")).as("old name gone on %s", ProxyAppHarness.stack()).isNull();
			assertThat(afterPut.toString()).as("updated secret must not leak on %s", ProxyAppHarness.stack())
				.doesNotContain("crud-tok-2");

			// when — DELETE by path variable (with the owner cookie)
			final HttpResponse<String> delete = send(apiBase + "/auth-profile/" + session.profileId(), "DELETE", null,
					AUTH_TOKEN, null, session.cookie());

			// then — 204 and the list no longer contains the profile
			assertThat(delete.statusCode()).as("DELETE status on %s", ProxyAppHarness.stack()).isEqualTo(204);
			final JsonNode afterDelete = MAPPER
				.readTree(send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null, session.cookie()).body());
			assertThat(afterDelete).as("deleted profile gone from list on %s", ProxyAppHarness.stack()).isEmpty();

			// when — DELETE / PUT unknown ids (with the owner cookie)
			final HttpResponse<String> deleteUnknown = send(apiBase + "/auth-profile/" + session.profileId(), "DELETE",
					null, AUTH_TOKEN, null, session.cookie());
			final HttpResponse<String> putUnknown = send(apiBase + "/auth-profile/" + session.profileId(), "PUT", """
					{"profile":{"name":"ghost","type":"BEARER","token":"x"}}""", AUTH_TOKEN, null, session.cookie());

			// then — 404, existence is not leaked
			assertThat(deleteUnknown.statusCode()).as("DELETE unknown on %s", ProxyAppHarness.stack()).isEqualTo(404);
			assertThat(putUnknown.statusCode()).as("PUT unknown on %s", ProxyAppHarness.stack()).isEqualTo(404);
		}

	}

	/**
	 * Name uniqueness (D1/AC1): a duplicate register or a rename onto an existing profile
	 * name is a client error surfaced as 400, and the owner's listing is left unchanged —
	 * the store rejects before mutating, so no duplicate and no overwrite ever persists.
	 */
	@Nested
	@DisplayName("Name uniqueness")
	class NameUniqueness {

		@Test
		@DisplayName("duplicate register and duplicate-name update return 400 and leave the listing unchanged")
		@Story("Profile uniqueness")
		@Severity(SeverityLevel.CRITICAL)
		@Description("After registering 'prod' and 'other', a duplicate register of 'prod' and a PUT rename of "
				+ "'other' onto 'prod' both answer 400, and the GET list still shows exactly the two original "
				+ "profiles — the store rejected both mutations without persisting anything")
		void duplicateRegisterAndRename_return400_listingUnchanged() throws Exception {
			// given — two distinct profiles on the real store, scoped to one owner
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final Session prod = register(apiBase, """
					{"profile":{"name":"prod","type":"BEARER","token":"prod-tok"}}""");
			final HttpResponse<String> otherReg = send(apiBase + "/auth-profile", "POST", """
					{"profile":{"name":"other","type":"BEARER","token":"other-tok"}}""", AUTH_TOKEN, null,
					prod.cookie());
			assertThat(otherReg.statusCode())
				.as("second registration on %s, body=%s", ProxyAppHarness.stack(), otherReg.body())
				.isEqualTo(200);
			final String otherId = MAPPER.readTree(otherReg.body()).path("profileId").asText();

			// when — a duplicate register of an existing name
			final HttpResponse<String> duplicate = send(apiBase + "/auth-profile", "POST", """
					{"profile":{"name":"prod","type":"BEARER","token":"dup-tok"}}""", AUTH_TOKEN, null, prod.cookie());

			// then — 400 (client error) and the list is unchanged
			assertThat(duplicate.statusCode())
				.as("duplicate register status on %s, body=%s", ProxyAppHarness.stack(), duplicate.body())
				.isEqualTo(400);
			assertListedExactly(apiBase, prod.cookie(), "prod", "other");

			// when — a rename of 'other' onto the existing 'prod' name
			final HttpResponse<String> rename = send(apiBase + "/auth-profile/" + otherId, "PUT", """
					{"profile":{"name":"prod","type":"BEARER","token":"other-tok"}}""", AUTH_TOKEN, null,
					prod.cookie());

			// then — 400 and the list is still exactly the two originals, untouched
			assertThat(rename.statusCode())
				.as("duplicate-name update status on %s, body=%s", ProxyAppHarness.stack(), rename.body())
				.isEqualTo(400);
			assertListedExactly(apiBase, prod.cookie(), "prod", "other");
		}

		/** Asserts the GET list contains exactly the given profile names (any order). */
		private static void assertListedExactly(final String apiBase, final String cookie, final String... names)
				throws Exception {
			final JsonNode list = MAPPER
				.readTree(send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null, cookie).body());
			final List<String> listedNames = new ArrayList<>();
			for (final JsonNode entry : list) {
				listedNames.add(entry.path("name").asText());
			}
			assertThat(listedNames).as("listing on %s", ProxyAppHarness.stack()).containsExactlyInAnyOrder(names);
		}

	}

	/**
	 * Prefill happy path (D7): a Spring-config profile is listed by {@code GET /prefill}
	 * (secret-free), registered by {@code POST {name,type}}, and the proxied request
	 * bound to the resulting profile carries its {@code Authorization} header to the
	 * upstream MCP server.
	 */
	@Nested
	@DisplayName("Prefill happy path")
	class Prefill {

		@Test
		@DisplayName("config profile is listed, referenced and applied to a proxied request")
		@Story("Prefill profiles")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Boots the demo with a Spring-config bearer prefill profile, lists it via GET /prefill "
				+ "without the secret, registers it by {name,type} reference, then opens a streamable proxy "
				+ "session bound to the profile and proves the upstream stub MCP server received "
				+ "Authorization: Bearer <config token>")
		void prefillProfile_fromConfig_toProxiedRequest_carriesAuthHeaders() throws Exception {
			// given — a Spring-config bearer prefill profile
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN,
					"--spring.ai.mcp.inspector.auth-profiles.profiles[0].name=prod-bearer",
					"--spring.ai.mcp.inspector.auth-profiles.profiles[0].type=BEARER",
					"--spring.ai.mcp.inspector.auth-profiles.profiles[0].bearer.token=prefill-tok-789");
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();

			// when — GET /prefill lists the config profile (mints the owner cookie)
			final HttpResponse<String> prefill = send(apiBase + "/auth-profile/prefill", "GET", null, AUTH_TOKEN, null,
					null);
			final String cookie = ownerCookie(prefill);

			// then — secret-free summary with the declared name
			assertThat(prefill.statusCode()).as("GET /prefill status on %s", ProxyAppHarness.stack()).isEqualTo(200);
			final JsonNode prefillBody = MAPPER.readTree(prefill.body());
			final JsonNode summary = findByName(prefillBody, "prod-bearer");
			assertThat(summary).as("prefill summary on %s", ProxyAppHarness.stack()).isNotNull();
			assertThat(summary.path("type").asText()).isEqualTo("BEARER");
			assertThat(prefill.body()).as("prefill secret must never leave the backend on %s", ProxyAppHarness.stack())
				.doesNotContain("prefill-tok-789");

			// when — POST {name,type} registers the referenced profile (same owner
			// cookie)
			final HttpResponse<String> registered = send(apiBase + "/auth-profile", "POST", """
					{"name":"prod-bearer","type":"BEARER"}""", AUTH_TOKEN, null, cookie);
			assertThat(registered.statusCode()).as("prefill reference status on %s", ProxyAppHarness.stack())
				.isEqualTo(200);
			final String profileId = MAPPER.readTree(registered.body()).path("profileId").asText();
			assertThat(profileId).as("prefill-referenced profileId on %s", ProxyAppHarness.stack()).isNotBlank();

			// when — a streamable proxy session is opened bound to the profile, targeting
			// a JDK HttpServer MCP stub that records inbound headers
			final RecordedHeaders recorded = new RecordedHeaders();
			final HttpServer stub = startMcpStub(recorded);
			try {
				final HttpResponse<String> init = initializeThroughProxy(proxyBase, stubUrl(stub), profileId, cookie);

				// then — the initialize round-trip succeeded and the stub saw the
				// profile's
				// Authorization header
				assertThat(init.statusCode())
					.as("initialize through profile-bound proxy on %s, body=%s", ProxyAppHarness.stack(), init.body())
					.isEqualTo(200);
				assertThat(recorded.authorization()).as("Authorization seen by upstream on %s", ProxyAppHarness.stack())
					.isEqualTo("Bearer prefill-tok-789");
			}
			finally {
				stub.stop(0);
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
	 * Registers an inline profile (or prefill reference) and returns the server-issued id
	 * plus the owner cookie minted by the registration call.
	 */
	private static Session register(final String apiBase, final String body) throws Exception {
		final HttpResponse<String> response = send(apiBase + "/auth-profile", "POST", body, AUTH_TOKEN, null, null);
		assertThat(response.statusCode())
			.as("registration status on %s, body=%s", ProxyAppHarness.stack(), response.body())
			.isEqualTo(200);
		return new Session(MAPPER.readTree(response.body()).path("profileId").asText(), ownerCookie(response));
	}

	/** Registers and returns only the profile id (per-type registration scenario). */
	private static String registerInline(final String apiBase, final String body) throws Exception {
		return register(apiBase, body).profileId();
	}

	/**
	 * Opens a streamable proxy session bound to {@code profileId} by POSTing the MCP
	 * {@code initialize} frame through the relay, reusing the owner cookie minted by the
	 * registration call.
	 * @param proxyBase the proxy base URL
	 * @param targetUrl the stub MCP endpoint URL
	 * @param profileId the owner-scoped profile id
	 * @param cookie the signed owner cookie from the registration response
	 * @return the relay response
	 */
	private static HttpResponse<String> initializeThroughProxy(final String proxyBase, final String targetUrl,
			final String profileId, final String cookie) throws Exception {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "auth-profile-api-it");
		info.put("version", "0.1.0");

		return send(proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8) + "&profileId="
				+ profileId, "POST", MAPPER.writeValueAsString(init), null, "Bearer " + AUTH_TOKEN, cookie);
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

	/** Finds a profile summary by {@code name} in the list body. */
	private static JsonNode findByName(final JsonNode listBody, final String name) {
		if (!listBody.isArray()) {
			return null;
		}
		for (final JsonNode entry : listBody) {
			if (name.equals(entry.path("name").asText())) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * Starts a minimal streamable-HTTP MCP stub on a random port. It answers {@code POST
	 * /mcp} with a valid {@code initialize} JSON-RPC response and records every inbound
	 * header of the POST into {@code recorded}. {@code GET /mcp} answers 405 — the SDK
	 * then falls back to request-response mode (no SSE channel), which is exactly what
	 * the relay uses.
	 * @param recorded the header recorder to fill
	 * @return the started server (caller stops it)
	 */
	private static HttpServer startMcpStub(final RecordedHeaders recorded) throws IOException {
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", (exchange) -> {
			if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				exchange.close();
				return;
			}
			recorded.record(exchange);
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
		});
		server.start();
		return server;
	}

	private static String stubUrl(final HttpServer stub) {
		return "http://127.0.0.1:" + stub.getAddress().getPort() + "/mcp";
	}

	/** Registration response: server-issued id + the minted owner cookie. */
	private record Session(String profileId, String cookie) {
	}

	/** Inbound headers of the stub's POST requests, kept for assertions. */
	private static final class RecordedHeaders {

		private final List<String> authorizations = new CopyOnWriteArrayList<>();

		private final List<Map<String, List<String>>> all = new CopyOnWriteArrayList<>();

		void record(final HttpExchange exchange) {
			this.all.add(copy(exchange));
			final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
			if (authorization != null) {
				this.authorizations.add(authorization);
			}
		}

		String authorization() {
			return this.authorizations.isEmpty() ? null : this.authorizations.get(0);
		}

		private static Map<String, List<String>> copy(final HttpExchange exchange) {
			final Map<String, List<String>> out = new java.util.LinkedHashMap<>();
			exchange.getRequestHeaders().forEach((name, values) -> out.put(name, new ArrayList<>(values)));
			return out;
		}

	}

}
