/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.oauth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.1 stub server controller used by the inspector's <em>AuthDebugger</em>
 * end-to-end flow. Implements just enough of:
 * <ul>
 * <li>RFC 8414 — Authorization Server Metadata discovery</li>
 * <li>RFC 7591 — Dynamic Client Registration</li>
 * <li>RFC 6749 §4.1 — Authorization Code Grant</li>
 * <li>RFC 7636 — PKCE (S256)</li>
 * </ul>
 * to drive a one-shot interactive round-trip against the inspector. Not suitable for any
 * other use.
 *
 * <p>
 * The fixed authorization code returned by {@code /authorize} is {@value #STUB_CODE} and
 * the issued access token is {@value #STUB_ACCESS_TOKEN}. PKCE is verified for
 * {@code S256}; {@code plain} is also accepted to keep the stub resilient against clients
 * that downgrade.
 */
@RestController
@ConditionalOnProperty(prefix = "demo.oauth-stub", name = "enabled", havingValue = "true")
public class OAuthStubController {

	/** Fixed authorization code returned by {@code /authorize}. */
	static final String STUB_CODE = "stub-code";

	/** Fixed access token returned by {@code /token}. */
	static final String STUB_ACCESS_TOKEN = "stub-access-token";

	/** Fixed refresh token returned by {@code /token}. */
	static final String STUB_REFRESH_TOKEN = "stub-refresh";

	/** Fixed client id returned by {@code /register}. */
	static final String STUB_CLIENT_ID = "stub-client";

	/** Fixed client secret returned by {@code /register}. */
	static final String STUB_CLIENT_SECRET = "stub-secret";

	private final OAuthStubStore store;

	public OAuthStubController(OAuthStubStore store) {
		this.store = store;
	}

	// ---------------------------------------------------------------------
	// RFC 8414 — discovery
	// ---------------------------------------------------------------------

	/**
	 * RFC 8414 authorization-server metadata. Endpoints point back to the same host the
	 * request arrived on so the document remains valid on any random test port.
	 */
	@GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> discovery(HttpServletRequest request) {
		String issuer = baseUrl(request);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("issuer", issuer);
		body.put("authorization_endpoint", issuer + "/oauth/authorize");
		body.put("token_endpoint", issuer + "/oauth/token");
		body.put("registration_endpoint", issuer + "/oauth/register");
		body.put("response_types_supported", new String[] { "code" });
		body.put("grant_types_supported", new String[] { "authorization_code", "refresh_token" });
		body.put("code_challenge_methods_supported", new String[] { "S256", "plain" });
		body.put("token_endpoint_auth_methods_supported",
				new String[] { "none", "client_secret_basic", "client_secret_post" });
		return body;
	}

	// ---------------------------------------------------------------------
	// RFC 7591 — dynamic client registration
	// ---------------------------------------------------------------------

	/** Returns a fixed client id / secret regardless of submitted metadata. */
	@PostMapping(value = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> register(@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("client_id", STUB_CLIENT_ID);
		response.put("client_secret", STUB_CLIENT_SECRET);
		response.put("client_id_issued_at", System.currentTimeMillis() / 1000L);
		if (body != null && body.containsKey("redirect_uris")) {
			response.put("redirect_uris", body.get("redirect_uris"));
		}
		if (body != null && body.containsKey("client_name")) {
			response.put("client_name", body.get("client_name"));
		}
		return response;
	}

	// ---------------------------------------------------------------------
	// RFC 6749 §4.1 — authorization endpoint
	// ---------------------------------------------------------------------

	/**
	 * Renders a minimal HTML approve/deny page. The {@code approve} link is a
	 * self-redirect into {@link #authorizePost} that finally redirects the caller back to
	 * {@code redirect_uri} with {@code code} and {@code state}.
	 */
	@GetMapping(value = "/oauth/authorize", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> authorizeGet(
			@RequestParam(value = "response_type", required = false) String responseType,
			@RequestParam("client_id") String clientId, @RequestParam("redirect_uri") String redirectUri,
			@RequestParam(value = "state", required = false) String state,
			@RequestParam(value = "code_challenge", required = false) String codeChallenge,
			@RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
			@RequestParam(value = "scope", required = false) String scope) {
		String method = codeChallengeMethod == null ? "plain" : codeChallengeMethod;
		String form = "<!doctype html><html><head><meta charset=\"utf-8\"><title>OAuth Stub — Approve</title></head><body>"
				+ "<h1>Approve</h1>" + "<p>client_id=" + escape(clientId) + " response_type=" + escape(responseType)
				+ " scope=" + escape(scope) + "</p>" + "<form method=\"post\" action=\"/oauth/authorize\">"
				+ hidden("client_id", clientId) + hidden("redirect_uri", redirectUri) + hidden("state", state)
				+ hidden("code_challenge", codeChallenge) + hidden("code_challenge_method", method)
				+ "<button type=\"submit\" name=\"decision\" value=\"approve\">Approve</button>"
				+ "<button type=\"submit\" name=\"decision\" value=\"deny\">Deny</button>" + "</form></body></html>";
		return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(form);
	}

	/**
	 * Receives the approve/deny decision and redirects to the requested
	 * {@code redirect_uri} carrying either {@code code+state} or an {@code error}
	 * parameter.
	 */
	@PostMapping(value = "/oauth/authorize")
	public ResponseEntity<Void> authorizePost(@RequestParam("client_id") String clientId,
			@RequestParam("redirect_uri") String redirectUri,
			@RequestParam(value = "state", required = false) String state,
			@RequestParam(value = "code_challenge", required = false) String codeChallenge,
			@RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
			@RequestParam(value = "decision", required = false) String decision) {
		String method = codeChallengeMethod == null ? "plain" : codeChallengeMethod;
		String location;
		if ("approve".equalsIgnoreCase(decision)) {
			store.put(STUB_CODE, new OAuthStubStore.PendingCode(codeChallenge, method, redirectUri));
			location = appendQuery(redirectUri,
					"code=" + urlEncode(STUB_CODE) + (state == null ? "" : "&state=" + urlEncode(state)));
		}
		else {
			location = appendQuery(redirectUri,
					"error=access_denied" + (state == null ? "" : "&state=" + urlEncode(state)));
		}
		HttpHeaders headers = new HttpHeaders();
		headers.setLocation(URI.create(location));
		return new ResponseEntity<>(headers, HttpStatus.FOUND);
	}

	// ---------------------------------------------------------------------
	// RFC 6749 §3.2 / RFC 7636 — token endpoint
	// ---------------------------------------------------------------------

	/**
	 * Issues an access token for {@code grant_type=authorization_code}, after verifying
	 * the PKCE {@code code_verifier} matches the recorded {@code code_challenge}.
	 *
	 * <p>
	 * Returns {@code 400 invalid_grant} on a bad code or PKCE mismatch.
	 */
	@PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> token(@RequestParam("grant_type") String grantType,
			@RequestParam(value = "code", required = false) String code,
			@RequestParam(value = "redirect_uri", required = false) String redirectUri,
			@RequestParam(value = "code_verifier", required = false) String codeVerifier,
			@RequestParam(value = "client_id", required = false) String clientId,
			@RequestParam(value = "refresh_token", required = false) String refreshToken) {

		if ("refresh_token".equals(grantType)) {
			if (!STUB_REFRESH_TOKEN.equals(refreshToken)) {
				return errorJson("invalid_grant", "unknown refresh_token");
			}
			return ResponseEntity.ok(tokenBody());
		}

		if (!"authorization_code".equals(grantType)) {
			return errorJson("unsupported_grant_type", "only authorization_code and refresh_token are supported");
		}
		if (code == null) {
			return errorJson("invalid_request", "missing 'code' parameter");
		}
		OAuthStubStore.PendingCode pending = store.consume(code);
		if (pending == null) {
			return errorJson("invalid_grant", "unknown or already-redeemed authorization code");
		}
		if (pending.redirectUri() != null && redirectUri != null && !pending.redirectUri().equals(redirectUri)) {
			return errorJson("invalid_grant", "redirect_uri does not match authorize request");
		}
		if (!verifyPkce(pending, codeVerifier)) {
			return errorJson("invalid_grant", "PKCE code_verifier does not match code_challenge");
		}
		return ResponseEntity.ok(tokenBody());
	}

	private Map<String, Object> tokenBody() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("access_token", STUB_ACCESS_TOKEN);
		body.put("token_type", "Bearer");
		body.put("expires_in", 3600);
		body.put("refresh_token", STUB_REFRESH_TOKEN);
		body.put("scope", "mcp");
		return body;
	}

	private ResponseEntity<Map<String, Object>> errorJson(String error, String description) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		body.put("error_description", description);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
	}

	// ---------------------------------------------------------------------
	// PKCE helpers
	// ---------------------------------------------------------------------

	private static boolean verifyPkce(OAuthStubStore.PendingCode pending, String verifier) {
		// No PKCE on the original authorize request → no verification required.
		if (pending.codeChallenge() == null || pending.codeChallenge().isBlank()) {
			return true;
		}
		if (verifier == null) {
			return false;
		}
		String method = pending.codeChallengeMethod() == null ? "plain" : pending.codeChallengeMethod();
		if ("plain".equalsIgnoreCase(method)) {
			return pending.codeChallenge().equals(verifier);
		}
		if ("S256".equalsIgnoreCase(method)) {
			return pending.codeChallenge().equals(s256(verifier));
		}
		return false;
	}

	private static String s256(String verifier) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	// ---------------------------------------------------------------------
	// misc helpers
	// ---------------------------------------------------------------------

	private static String baseUrl(HttpServletRequest request) {
		StringBuilder sb = new StringBuilder().append(request.getScheme())
			.append("://")
			.append(request.getServerName());
		int port = request.getServerPort();
		boolean defaultHttp = "http".equals(request.getScheme()) && port == 80;
		boolean defaultHttps = "https".equals(request.getScheme()) && port == 443;
		if (!defaultHttp && !defaultHttps) {
			sb.append(':').append(port);
		}
		return sb.toString();
	}

	private static String appendQuery(String url, String params) {
		if (url == null) {
			return "/?" + params;
		}
		return url + (url.contains("?") ? "&" : "?") + params;
	}

	private static String urlEncode(String s) {
		return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}

	private static String hidden(String name, String value) {
		return "<input type=\"hidden\" name=\"" + escape(name) + "\" value=\"" + escape(value) + "\"/>";
	}

	private static String escape(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/** Keep the parameter-only {@code RequestMapping} import warning-free. */
	@SuppressWarnings("unused")
	private static Class<?> keepImports() {
		return RequestMapping.class;
	}

}
