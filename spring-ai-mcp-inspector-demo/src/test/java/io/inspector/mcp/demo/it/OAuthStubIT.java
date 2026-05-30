/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.demo.DemoApplication;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the demo with the {@code oauth-stub} profile active and exercises the full RFC
 * 6749 §4.1 + RFC 7636 (PKCE S256) round-trip:
 *
 * <ol>
 * <li>{@code GET /.well-known/oauth-authorization-server} yields valid JSON</li>
 * <li>{@code GET /oauth/authorize?...} renders an HTML "Approve" page</li>
 * <li>{@code POST /oauth/authorize?decision=approve} redirects with {@code code} and
 * {@code state}</li>
 * <li>{@code POST /oauth/token} (form-encoded) returns an access token</li>
 * <li>A second {@code POST /oauth/token} with a wrong {@code code_verifier} is rejected
 * with 400</li>
 * </ol>
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({ "streamable", "oauth-stub" })
@TestPropertySource(properties = { "spring.ai.mcp.inspector.auth-enabled=false",
		"spring.autoconfigure.exclude="
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebFluxAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration,"
				+ "org.springframework.ai.mcp.server.autoconfigure.McpServerStatelessWebFluxAutoConfiguration,"
				+ "io.inspector.mcp.webflux.McpInspectorWebFluxAutoConfiguration" })
class OAuthStubIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static HttpClient http;

	@LocalServerPort
	private int port;

	@BeforeAll
	static void buildHttpClient() {
		http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
	}

	@Test
	void discoveryDocumentExposesAllEndpoints() throws Exception {
		HttpResponse<String> response = http.send(
				HttpRequest.newBuilder(URI.create(base() + "/.well-known/oauth-authorization-server")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = MAPPER.readTree(response.body());
		assertThat(body.get("issuer").asText()).startsWith("http://");
		assertThat(body.get("authorization_endpoint").asText()).endsWith("/oauth/authorize");
		assertThat(body.get("token_endpoint").asText()).endsWith("/oauth/token");
		assertThat(body.get("registration_endpoint").asText()).endsWith("/oauth/register");
	}

	@Test
	void authorizeGetRendersApprovePage() throws Exception {
		HttpResponse<String> response = http.send(
				HttpRequest.newBuilder(URI.create(base() + "/oauth/authorize?response_type=code&client_id=stub-client"
						+ "&redirect_uri=http://localhost/callback&state=xyz"
						+ "&code_challenge=abc&code_challenge_method=S256"))
					.GET()
					.build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("Approve");
	}

	@Test
	void fullPkceRoundTrip() throws Exception {
		// PKCE: pick a verifier, derive S256(verifier) as the challenge.
		String verifier = "test-verifier-1234567890abcdefghij";
		String challenge = s256(verifier);

		// 1) Approve via the form submission.
		String form = "client_id=stub-client" + "&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback" + "&state=xyz"
				+ "&code_challenge=" + challenge + "&code_challenge_method=S256" + "&decision=approve";
		HttpResponse<String> approve = http.send(HttpRequest.newBuilder(URI.create(base() + "/oauth/authorize"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(approve.statusCode()).isEqualTo(302);
		String location = approve.headers().firstValue("Location").orElseThrow();
		assertThat(location).contains("code=stub-code");
		assertThat(location).contains("state=xyz");

		// 2) Exchange code → token with the correct verifier.
		String tokenForm = "grant_type=authorization_code" + "&code=stub-code"
				+ "&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback" + "&code_verifier=" + verifier
				+ "&client_id=stub-client";
		HttpResponse<String> token = http.send(HttpRequest.newBuilder(URI.create(base() + "/oauth/token"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(tokenForm))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(token.statusCode()).isEqualTo(200);
		JsonNode body = MAPPER.readTree(token.body());
		assertThat(body.get("access_token").asText()).isEqualTo("stub-access-token");
		assertThat(body.get("token_type").asText()).isEqualTo("Bearer");
		assertThat(body.get("refresh_token").asText()).isEqualTo("stub-refresh");
	}

	@Test
	void wrongCodeVerifierIsRejected() throws Exception {
		String verifier = "verifier-A";
		String challenge = s256(verifier);

		// Approve first.
		String form = "client_id=stub-client" + "&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback" + "&code_challenge="
				+ challenge + "&code_challenge_method=S256" + "&decision=approve";
		HttpResponse<String> approve = http.send(HttpRequest.newBuilder(URI.create(base() + "/oauth/authorize"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(approve.statusCode()).isEqualTo(302);

		// Token call with WRONG verifier should be rejected.
		String tokenForm = "grant_type=authorization_code" + "&code=stub-code"
				+ "&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback" + "&code_verifier=verifier-WRONG"
				+ "&client_id=stub-client";
		HttpResponse<String> token = http.send(HttpRequest.newBuilder(URI.create(base() + "/oauth/token"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(tokenForm))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(token.statusCode()).isEqualTo(400);
		JsonNode body = MAPPER.readTree(token.body());
		assertThat(body.get("error").asText()).isEqualTo("invalid_grant");
	}

	@Test
	void registerEndpointReturnsStubClientCredentials() throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/oauth/register"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(
					"{\"client_name\":\"inspector-test\"," + "\"redirect_uris\":[\"http://localhost/callback\"]}"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = MAPPER.readTree(response.body());
		assertThat(body.get("client_id").asText()).isEqualTo("stub-client");
		assertThat(body.get("client_secret").asText()).isEqualTo("stub-secret");
	}

	private String base() {
		return "http://127.0.0.1:" + port;
	}

	private static String s256(String verifier) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

}
