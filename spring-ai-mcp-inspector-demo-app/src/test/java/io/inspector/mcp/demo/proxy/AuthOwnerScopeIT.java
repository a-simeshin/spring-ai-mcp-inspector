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
import java.util.UUID;
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
 * Verifies the D8 session-owner contract on a real HTTP stack: cookie bootstrap (the
 * first authenticated request mints a signed {@code MCP_INSPECTOR_SESSION} cookie, a
 * valid cookie is reused without re-minting, a corrupted/forged cookie is re-minted as a
 * NEW owner — 200, never 401 — and a missing {@code X-MCP-Inspector-Auth} is 401 with no
 * cookie), forged/fixated-cookie resistance (an unsigned valid UUID does not reach
 * another owner's list/resolve/update/delete/bind/exchange), two-owner isolation (owner B
 * cannot see, use, change, delete or exchange owner A's profile) and the proxy namespace
 * owner handoff (the owner is resolved on {@code /mcp-inspector-api/**} by the per-stack
 * resolver — {@code ServletSessionOwnerResolver} / {@code ReactiveSessionOwnerResolver} —
 * and a cross-owner proxy request is rejected).
 *
 * <p>
 * The class lives in {@code demo-app}'s test-jar, so Failsafe's
 * {@code dependenciesToScan} runs it once per stack module — every scenario is exercised
 * on BOTH webmvc and webflux (see {@code ProxyAppHarness.stack()} in the assertion
 * messages).
 *
 * <p>
 * The proxied-request scenario targets a tiny JDK {@link HttpServer} MCP stub that
 * records the inbound headers of the streamable-HTTP POST, so the test can prove the
 * owner-scoped profile's {@code Authorization} header really reaches the upstream server.
 * No WireMock, no Testcontainers.
 */
@Epic("Inspector Auth Profiles")
@Feature("Session-owner bootstrap, cookie forgery resistance and two-owner isolation (D8)")
class AuthOwnerScopeIT {

	/** Fixed inspector auth token threaded through the demo via the harness. */
	private static final String AUTH_TOKEN = "auth-owner-scope-it-token-0123456789";

	/** Inspector API auth header ({@code InspectorAuthFilter}). */
	private static final String INSPECTOR_AUTH_HEADER = "X-MCP-Inspector-Auth";

	/** Proxy auth header used by {@code ProxyAuthFilter}. */
	private static final String PROXY_AUTH_HEADER = "X-MCP-Proxy-Auth";

	/** Signed session-owner cookie name ({@code OwnerTokenCodec}). */
	private static final String OWNER_COOKIE = "MCP_INSPECTOR_SESSION";

	/** Unreachable loopback target for proxy requests that must fail before transport. */
	private static final String DUMMY_TARGET = "http://127.0.0.1:1/mcp";

	/** Valid exchange payload (never reaches the IdP in these scenarios). */
	private static final String EXCHANGE_BODY = "{\"code\":\"auth-code\",\"codeVerifier\":\"pkce-verifier-123\","
			+ "\"state\":\"state-123\"}";

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
	 * Cookie bootstrap (D8): the first authenticated request mints the signed owner
	 * cookie, a valid cookie is reused without re-minting, a corrupted cookie is
	 * re-minted as a NEW owner (200, never 401) and a missing inspector auth header is
	 * 401 with NO cookie.
	 */
	@Nested
	@DisplayName("Cookie bootstrap")
	class Bootstrap {

		@Test
		@DisplayName("first authenticated request mints the signed owner cookie (200, never 401)")
		@Story("Cookie bootstrap")
		@Severity(SeverityLevel.CRITICAL)
		@Description("POST /mcp-inspector/api/auth-profile with a valid X-MCP-Inspector-Auth and no cookie returns 200 "
				+ "and a Set-Cookie with the signed MCP_INSPECTOR_SESSION owner token")
		void firstRequest_withValidAuth_andNoCookie_mintsSignedCookie() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();

			// when — first request carries no owner cookie
			final HttpResponse<String> response = send(apiBase + "/auth-profile", "POST",
					bearerProfileBody("boot-bearer", "boot-tok"), AUTH_TOKEN, null, null);

			// then — 200 (never 401) and the signed owner cookie is minted
			assertThat(response.statusCode())
				.as("bootstrap status on %s, body=%s", ProxyAppHarness.stack(), response.body())
				.isEqualTo(200);
			final String cookie = ownerCookie(response);
			assertThat(cookie.split("\\.")).as("signed owner token shape on %s", ProxyAppHarness.stack()).hasSize(3);
		}

		@Test
		@DisplayName("reusing the valid cookie keeps the same owner and does not re-mint")
		@Story("Cookie bootstrap")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A profile registered under the minted cookie stays visible when the SAME cookie is re-sent, "
				+ "and the response carries no new Set-Cookie (the valid token is not re-minted)")
		void validCookieReuse_keepsSameOwner_withoutRemint() throws Exception {
			// given — registration mints the owner cookie
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final Session session = register(apiBase, bearerProfileBody("reuse-bearer", "reuse-tok"));

			// when — the same valid cookie is re-sent
			final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null,
					session.cookie());

			// then — the same owner scope is served (the profile is visible) and no new
			// cookie is minted
			assertThat(list.statusCode()).as("list status on %s", ProxyAppHarness.stack()).isEqualTo(200);
			assertThat(findByName(MAPPER.readTree(list.body()), "reuse-bearer"))
				.as("profile of the reused cookie's owner on %s", ProxyAppHarness.stack())
				.isNotNull();
			assertThat(list.headers().firstValue("Set-Cookie"))
				.as("valid cookie must not be re-minted on %s", ProxyAppHarness.stack())
				.isEmpty();
		}

		@Test
		@DisplayName("corrupted cookie is re-minted as a NEW owner — 200, never 401")
		@Story("Cookie bootstrap")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A cookie with a corrupted HMAC is re-minted on the fly: the request answers 200 with a fresh "
				+ "Set-Cookie and the new owner does not see the old owner's profile")
		void corruptedCookie_isReminted_asNewOwner_not401() throws Exception {
			// given — a profile under a valid cookie, then the same cookie with a
			// corrupted signature
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final Session session = register(apiBase, bearerProfileBody("corrupt-bearer", "corrupt-tok"));
			final String corrupted = corruptHmac(session.cookie());

			// when — the corrupted cookie is presented
			final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null, corrupted);

			// then — 200 (never 401), a FRESH cookie is issued and the old owner's scope
			// is not inherited
			assertThat(list.statusCode())
				.as("corrupted cookie status on %s, body=%s", ProxyAppHarness.stack(), list.body())
				.isEqualTo(200);
			final String reissued = ownerCookie(list);
			assertThat(reissued).as("re-minted cookie differs on %s", ProxyAppHarness.stack()).isNotEqualTo(corrupted);
			assertThat(findByName(MAPPER.readTree(list.body()), "corrupt-bearer"))
				.as("corrupted cookie must not inherit the owner scope on %s", ProxyAppHarness.stack())
				.isNull();

			// when — the re-minted cookie is used again
			final HttpResponse<String> again = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null, reissued);

			// then — it is a valid owner now: 200 and no further re-mint
			assertThat(again.statusCode()).as("re-minted cookie status on %s", ProxyAppHarness.stack()).isEqualTo(200);
			assertThat(again.headers().firstValue("Set-Cookie"))
				.as("re-minted cookie must be stable on %s", ProxyAppHarness.stack())
				.isEmpty();
		}

		@Test
		@DisplayName("missing X-MCP-Inspector-Auth is 401 and mints NO cookie")
		@Story("Cookie bootstrap")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A request without X-MCP-Inspector-Auth is rejected with 401 BEFORE any owner cookie is "
				+ "minted — the response carries no Set-Cookie")
		void missingInspectorAuth_is401_andNoCookie() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();

			// when — no inspector auth header
			final HttpResponse<String> missing = send(apiBase + "/auth-profile", "POST",
					bearerProfileBody("no-auth", "tok"), null, null, null);

			// then — 401 and no cookie
			assertThat(missing.statusCode()).as("missing auth status on %s", ProxyAppHarness.stack()).isEqualTo(401);
			assertThat(missing.headers().firstValue("Set-Cookie"))
				.as("401 must not mint an owner cookie on %s", ProxyAppHarness.stack())
				.isEmpty();
		}

	}

	/**
	 * Forged/fixated cookie resistance (D8): an unsigned valid UUID is re-minted to a
	 * fresh owner that has no access to the original owner's data — list, resolve,
	 * update, delete, bind and exchange all fail.
	 */
	@Nested
	@DisplayName("Forged and fixated cookies")
	class Forgery {

		@Test
		@DisplayName("an unsigned valid UUID cookie does not reach the owner's list/resolve/update/delete/bind/exchange")
		@Story("Forged and fixated cookies")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A bare unsigned UUID (fixation attempt) is re-minted to a fresh owner: the owner's list does "
				+ "not leak, PUT/DELETE/exchange of the owner's profileId answer 404 and a proxy session bound to the "
				+ "owner's profileId is rejected with the exact 400 DTO")
		void unsignedUuidCookie_getsNoAccess_toOwnersProfile() throws Exception {
			// given — owner A's profile plus an unsigned UUID cookie (fixation attempt)
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			final Session ownerA = register(apiBase, bearerProfileBody("forged-bearer", "forged-tok"));
			final String forged = UUID.randomUUID().toString();

			// when & then — list: 200 with an EMPTY list (no leak of A's profile)
			final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null, forged);
			assertThat(list.statusCode()).as("forged-cookie list status on %s", ProxyAppHarness.stack()).isEqualTo(200);
			assertThat(findByName(MAPPER.readTree(list.body()), "forged-bearer"))
				.as("forged cookie must not see A's profile on %s", ProxyAppHarness.stack())
				.isNull();

			// when & then — update / delete / exchange: 404 (existence is not leaked)
			final HttpResponse<String> put = send(apiBase + "/auth-profile/" + ownerA.profileId(), "PUT",
					bearerProfileBody("hijack", "x"), AUTH_TOKEN, null, forged);
			assertThat(put.statusCode()).as("forged-cookie PUT on %s", ProxyAppHarness.stack()).isEqualTo(404);
			final HttpResponse<String> delete = send(apiBase + "/auth-profile/" + ownerA.profileId(), "DELETE", null,
					AUTH_TOKEN, null, forged);
			assertThat(delete.statusCode()).as("forged-cookie DELETE on %s", ProxyAppHarness.stack()).isEqualTo(404);
			final HttpResponse<String> exchange = send(apiBase + "/auth-profile/" + ownerA.profileId() + "/exchange",
					"POST", EXCHANGE_BODY, AUTH_TOKEN, null, forged);
			assertThat(exchange.statusCode()).as("forged-cookie exchange on %s", ProxyAppHarness.stack())
				.isEqualTo(404);

			// when & then — resolve/bind on the proxy namespace: 400 with the exact DTO
			final HttpResponse<String> proxy = send(
					proxyBase + "/mcp?url=" + URLEncoder.encode(DUMMY_TARGET, StandardCharsets.UTF_8) + "&profileId="
							+ ownerA.profileId(),
					"POST", MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN, forged);
			assertThat(proxy.statusCode())
				.as("forged-cookie proxy resolve on %s, body=%s", ProxyAppHarness.stack(), proxy.body())
				.isEqualTo(400);
			assertBadRequestDto(proxy.body());
		}

	}

	/**
	 * Two-owner isolation (D8): owner B cannot see, use, change, delete or exchange owner
	 * A's profile, and A keeps working after B's attempts.
	 */
	@Nested
	@DisplayName("Two-owner isolation")
	class Isolation {

		@Test
		@DisplayName("owner B cannot see, use, change, delete or exchange owner A's profile")
		@Story("Two-owner isolation")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Two owners minted in the same app: B's list never contains A's profile, PUT/DELETE/exchange of "
				+ "A's profileId answer 404 under B's cookie, a proxy session bound to A's profileId is rejected under "
				+ "B's cookie with the exact 400 DTO, and A's own cookie keeps working")
		void ownerB_cannotSeeUseChangeDeleteOrExchange_ownerAProfile() throws Exception {
			// given — two owners in one app instance (the second registration mints B's
			// cookie)
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			final Session ownerA = register(apiBase, bearerProfileBody("iso-a", "tok-a"));
			final Session ownerB = register(apiBase, bearerProfileBody("iso-b", "tok-b"));
			assertThat(ownerB.cookie()).as("distinct owner cookies on %s", ProxyAppHarness.stack())
				.isNotEqualTo(ownerA.cookie());

			// when & then — B's list: only B's profile, never A's
			final HttpResponse<String> list = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null,
					ownerB.cookie());
			assertThat(list.statusCode()).as("B list status on %s", ProxyAppHarness.stack()).isEqualTo(200);
			final JsonNode listBody = MAPPER.readTree(list.body());
			assertThat(findByName(listBody, "iso-a")).as("B must not see A's profile on %s", ProxyAppHarness.stack())
				.isNull();
			assertThat(findByName(listBody, "iso-b")).as("B sees own profile on %s", ProxyAppHarness.stack())
				.isNotNull();

			// when & then — B cannot change / delete / exchange A's profile
			final HttpResponse<String> put = send(apiBase + "/auth-profile/" + ownerA.profileId(), "PUT",
					bearerProfileBody("hijack", "x"), AUTH_TOKEN, null, ownerB.cookie());
			assertThat(put.statusCode()).as("B PUT on A's profile on %s", ProxyAppHarness.stack()).isEqualTo(404);
			final HttpResponse<String> delete = send(apiBase + "/auth-profile/" + ownerA.profileId(), "DELETE", null,
					AUTH_TOKEN, null, ownerB.cookie());
			assertThat(delete.statusCode()).as("B DELETE on A's profile on %s", ProxyAppHarness.stack()).isEqualTo(404);
			final HttpResponse<String> exchange = send(apiBase + "/auth-profile/" + ownerA.profileId() + "/exchange",
					"POST", EXCHANGE_BODY, AUTH_TOKEN, null, ownerB.cookie());
			assertThat(exchange.statusCode()).as("B exchange on A's profile on %s", ProxyAppHarness.stack())
				.isEqualTo(404);

			// when & then — B cannot use (resolve/bind) A's profile through the proxy
			// namespace
			final HttpResponse<String> proxy = send(
					proxyBase + "/mcp?url=" + URLEncoder.encode(DUMMY_TARGET, StandardCharsets.UTF_8) + "&profileId="
							+ ownerA.profileId(),
					"POST", MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN,
					ownerB.cookie());
			assertThat(proxy.statusCode())
				.as("B proxy resolve on A's profile on %s, body=%s", ProxyAppHarness.stack(), proxy.body())
				.isEqualTo(400);
			assertBadRequestDto(proxy.body());

			// when & then — A's own cookie still works: no damage from B's attempts
			final HttpResponse<String> aList = send(apiBase + "/auth-profile", "GET", null, AUTH_TOKEN, null,
					ownerA.cookie());
			assertThat(findByName(MAPPER.readTree(aList.body()), "iso-a"))
				.as("A's profile intact on %s", ProxyAppHarness.stack())
				.isNotNull();
		}

	}

	/**
	 * Proxy-namespace owner handoff (D8, finding #3): the owner is resolved on
	 * {@code /mcp-inspector-api/**} by the per-stack resolver — the bound profile's
	 * headers reach the upstream, a foreign owner's cookie is rejected and a cookie-less
	 * proxy request still mints an owner before the rejection.
	 */
	@Nested
	@DisplayName("Proxy-namespace owner handoff")
	class ProxyOwnerHandoff {

		@Test
		@DisplayName("the owner cookie resolves the bound profile on the proxy namespace")
		@Story("Proxy-namespace owner handoff")
		@Severity(SeverityLevel.CRITICAL)
		@Description("POST /mcp-inspector-api/mcp?profileId=... with the owner cookie opens a streamable session and "
				+ "the stub MCP server receives the profile's Authorization header — the per-stack resolver "
				+ "(Servlet/ReactiveSessionOwnerResolver) resolved the owner on /mcp-inspector-api/**")
		void ownerCookie_resolvesProfile_onProxyNamespace_appliesHeaders() throws Exception {
			// given — a registered bearer profile and a stub MCP server recording
			// inbound headers
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			final Session owner = register(apiBase, bearerProfileBody("handoff-bearer", "handoff-tok"));
			final RecordedHeaders recorded = new RecordedHeaders();
			final HttpServer stub = startMcpStub(recorded);
			try {
				// when — a streamable session opens on the proxy namespace bound to the
				// profile
				final HttpResponse<String> init = initializeThroughProxy(proxyBase, stubUrl(stub), owner.profileId(),
						owner.cookie());

				// then — the round-trip succeeded and the upstream saw the profile's
				// header
				assertThat(init.statusCode())
					.as("initialize through owner-bound proxy on %s, body=%s", ProxyAppHarness.stack(), init.body())
					.isEqualTo(200);
				assertThat(recorded.authorization()).as("Authorization seen by upstream on %s", ProxyAppHarness.stack())
					.isEqualTo("Bearer handoff-tok");
			}
			finally {
				stub.stop(0);
			}
		}

		@Test
		@DisplayName("a foreign owner's cookie is rejected on the proxy namespace")
		@Story("Proxy-namespace owner handoff")
		@Severity(SeverityLevel.CRITICAL)
		@Description("POST /mcp-inspector-api/mcp?profileId=<A's id> with owner B's cookie answers the exact 400 DTO — "
				+ "cross-owner use of the profile is rejected")
		void foreignOwnerCookie_isRejected_onProxyNamespace() throws Exception {
			// given — owners A and B, A's profile
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			final Session ownerA = register(apiBase, bearerProfileBody("cross-bearer", "cross-tok"));
			final Session ownerB = register(apiBase, bearerProfileBody("cross-b", "x"));

			// when — B tries to open a session bound to A's profile
			final HttpResponse<String> proxy = send(
					proxyBase + "/mcp?url=" + URLEncoder.encode(DUMMY_TARGET, StandardCharsets.UTF_8) + "&profileId="
							+ ownerA.profileId(),
					"POST", MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN,
					ownerB.cookie());

			// then — rejected with the exact 400 DTO
			assertThat(proxy.statusCode())
				.as("cross-owner proxy status on %s, body=%s", ProxyAppHarness.stack(), proxy.body())
				.isEqualTo(400);
			assertBadRequestDto(proxy.body());
		}

		@Test
		@DisplayName("a cookie-less proxy request mints an owner and still rejects A's profile")
		@Story("Proxy-namespace owner handoff")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The proxy namespace resolves the owner via the per-stack resolver even without a cookie (a fresh "
				+ "owner is minted), and that fresh owner cannot open a session bound to A's profile — 400 DTO, with "
				+ "Set-Cookie proving the mint")
		void cookieLessProxyRequest_mintsOwner_rejectsForeignProfile() throws Exception {
			// given — A's profile
			app = ProxyAppHarness.start("STREAMABLE", true, AUTH_TOKEN);
			final String apiBase = apiBase();
			final String proxyBase = proxyBase();
			final Session ownerA = register(apiBase, bearerProfileBody("fresh-bearer", "fresh-tok"));

			// when — a proxy request without any owner cookie, bound to A's profile
			final HttpResponse<String> proxy = send(
					proxyBase + "/mcp?url=" + URLEncoder.encode(DUMMY_TARGET, StandardCharsets.UTF_8) + "&profileId="
							+ ownerA.profileId(),
					"POST", MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN, null);

			// then — the fresh owner is minted (Set-Cookie) and A's profile is rejected
			// with the exact 400 DTO
			assertThat(proxy.statusCode())
				.as("cookie-less proxy status on %s, body=%s", ProxyAppHarness.stack(), proxy.body())
				.isEqualTo(400);
			assertBadRequestDto(proxy.body());
			assertThat(proxy.headers().firstValue("Set-Cookie"))
				.as("owner minted on the proxy namespace on %s", ProxyAppHarness.stack())
				.isPresent();
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
	 * Registers an inline bearer profile and returns the server-issued id plus the owner
	 * cookie minted by the registration call.
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
	 * @param proxyBase the proxy base URL
	 * @param targetUrl the stub MCP endpoint URL
	 * @param profileId the owner-scoped profile id
	 * @param cookie the signed owner cookie from the registration response
	 * @return the relay response
	 */
	private static HttpResponse<String> initializeThroughProxy(final String proxyBase, final String targetUrl,
			final String profileId, final String cookie) throws Exception {
		return send(
				proxyBase + "/mcp?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8) + "&profileId="
						+ profileId,
				"POST", MAPPER.writeValueAsString(initializeFrame()), null, "Bearer " + AUTH_TOKEN, cookie);
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

	/** Builds an inline BEARER registration body. */
	private static String bearerProfileBody(final String name, final String token) {
		return "{\"profile\":{\"name\":\"" + name + "\",\"type\":\"BEARER\",\"token\":\"" + token + "\"}}";
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
		info.put("name", "auth-owner-scope-it");
		info.put("version", "0.1.0");
		return init;
	}

	/**
	 * Returns {@code cookie} with the first hex digit of its HMAC part flipped — a
	 * structurally valid token that fails signature verification.
	 */
	private static String corruptHmac(final String cookie) {
		final String[] parts = cookie.split("\\.");
		final String hmac = parts[2];
		final char flipped = (hmac.charAt(0) == 'a') ? 'b' : 'a';
		return parts[0] + "." + parts[1] + "." + flipped + hmac.substring(1);
	}

	/**
	 * Asserts the exact D3/D8 {@code bad_request} DTO returned for a foreign or unknown
	 * {@code profileId} on the proxy namespace.
	 */
	private static void assertBadRequestDto(final String body) throws Exception {
		final JsonNode node = MAPPER.readTree(body);
		assertThat(node.path("status").asInt()).isEqualTo(400);
		assertThat(node.path("code").asText()).isEqualTo("bad_request");
		assertThat(node.path("reason").asText()).isEqualTo("Invalid or missing auth profile or session reference.");
		assertThat(node.path("guidance").asText()).isEqualTo("Check the profile fields and profileId, then reconnect.");
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
