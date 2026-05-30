/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.proxy;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProxyFetchController}. Validation / error branches are exercised
 * directly; the success path injects a mocked {@link HttpClient} so the upstream envelope
 * mapping is asserted without a live socket.
 */
@Epic("WebMvc Inspector")
@Feature("ProxyFetchController")
class ProxyFetchControllerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private ProxyFetchController controller;

	@BeforeEach
	void setUp() {
		controller = new ProxyFetchController();
	}

	private JsonNode json(String raw) throws Exception {
		return objectMapper.readTree(raw);
	}

	private void injectClient(HttpClient client) throws Exception {
		Field f = ProxyFetchController.class.getDeclaredField("httpClient");
		f.setAccessible(true);
		// strip final modifier via Unsafe-free reflection is not needed: the field is
		// effectively-final but reflection set still works on instance fields here.
		f.set(controller, client);
	}

	@Nested
	@DisplayName("fetch() — validation")
	class Validation {

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("fetch() with a missing url returns 400")
		void fetch_withMissingUrl_returns400() throws Exception {
			// when
			ResponseEntity<Object> response = controller.fetch(json("{}"));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody().toString()).contains("missing or invalid url");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("fetch() with a blank url returns 400")
		void fetch_withBlankUrl_returns400() throws Exception {
			// when
			ResponseEntity<Object> response = controller.fetch(json("{\"url\":\"\"}"));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("fetch() rejects non-http(s) schemes with 400")
		void fetch_withFileScheme_returns400() throws Exception {
			// when
			ResponseEntity<Object> response = controller.fetch(json("{\"url\":\"file:///etc/passwd\"}"));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody().toString()).contains("only http/https URLs are allowed");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.MINOR)
		@Description("fetch() rejects a scheme-less (relative) url with 400")
		void fetch_withSchemelessUrl_returns400() throws Exception {
			// when
			ResponseEntity<Object> response = controller.fetch(json("{\"url\":\"/relative/path\"}"));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody().toString()).contains("only http/https URLs are allowed");
		}

	}

	@Nested
	@DisplayName("fetch() — relay")
	class Relay {

		@Test
		@Story("Outbound fetch")
		@Severity(SeverityLevel.CRITICAL)
		@Description("fetch() maps a successful upstream response into the ok/status/headers/body envelope")
		@SuppressWarnings("unchecked")
		void fetch_withSuccessfulResponse_mapsEnvelope() throws Exception {
			// given
			HttpClient client = mock(HttpClient.class);
			HttpResponse<String> upstream = mock(HttpResponse.class);
			when(upstream.statusCode()).thenReturn(200);
			when(upstream.body()).thenReturn("{\"hello\":\"world\"}");
			when(upstream.headers()).thenReturn(java.net.http.HttpHeaders
				.of(Map.of("Content-Type", java.util.List.of("application/json")), (a, b) -> true));
			when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(upstream);
			injectClient(client);

			// when
			ResponseEntity<Object> response = controller
				.fetch(json("{\"url\":\"https://idp/.well-known\",\"init\":{\"method\":\"GET\","
						+ "\"headers\":{\"Accept\":\"application/json\"}}}"));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			Map<String, Object> envelope = (Map<String, Object>) response.getBody();
			assertThat(envelope).containsEntry("ok", true)
				.containsEntry("status", 200)
				.containsEntry("body", "{\"hello\":\"world\"}");
			assertThat((Map<String, String>) envelope.get("headers")).containsEntry("Content-Type", "application/json");
		}

		@Test
		@Story("Outbound fetch")
		@Severity(SeverityLevel.NORMAL)
		@Description("fetch() marks ok=false and preserves a non-2xx upstream status")
		@SuppressWarnings("unchecked")
		void fetch_withErrorStatus_marksNotOk() throws Exception {
			// given
			HttpClient client = mock(HttpClient.class);
			HttpResponse<String> upstream = mock(HttpResponse.class);
			when(upstream.statusCode()).thenReturn(404);
			when(upstream.body()).thenReturn("nope");
			when(upstream.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
			when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(upstream);
			injectClient(client);

			// when
			ResponseEntity<Object> response = controller.fetch(json("{\"url\":\"https://idp/x\"}"));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
			Map<String, Object> envelope = (Map<String, Object>) response.getBody();
			assertThat(envelope).containsEntry("ok", false).containsEntry("status", 404);
		}

		@Test
		@Story("Outbound fetch")
		@Severity(SeverityLevel.NORMAL)
		@Description("fetch() forwards a POST body and explicit headers, and skips empty / restricted response headers")
		@SuppressWarnings("unchecked")
		void fetch_withPostBodyAndHeaders_forwardsAndMapsHeaders() throws Exception {
			// given
			HttpClient client = mock(HttpClient.class);
			HttpResponse<String> upstream = mock(HttpResponse.class);
			when(upstream.statusCode()).thenReturn(201);
			when(upstream.body()).thenReturn("created");
			when(upstream.headers()).thenReturn(java.net.http.HttpHeaders
				.of(Map.of("X-Present", java.util.List.of("v1"), "X-Empty", java.util.List.of()), (a, b) -> true));
			when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(upstream);
			injectClient(client);

			// when — init carries a method, a body and a restricted header (Host) plus a
			// regular one; the restricted name is skipped without failing the request
			ResponseEntity<Object> response = controller.fetch(json("{\"url\":\"https://idp/token\",\"init\":{"
					+ "\"method\":\"post\",\"body\":\"grant=x\",\"headers\":{\"Host\":\"evil\",\"X-Trace\":\"1\"}}}"));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
			Map<String, Object> envelope = (Map<String, Object>) response.getBody();
			assertThat(envelope).containsEntry("ok", true).containsEntry("status", 201);
			Map<String, String> headers = (Map<String, String>) envelope.get("headers");
			assertThat(headers).containsEntry("X-Present", "v1").doesNotContainKey("X-Empty");
		}

		@Test
		@Story("Outbound fetch")
		@Severity(SeverityLevel.NORMAL)
		@Description("fetch() returns 502 when the upstream call throws")
		void fetch_whenClientThrows_returns502() throws Exception {
			// given
			HttpClient client = mock(HttpClient.class);
			when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenThrow(new java.io.IOException("connection refused"));
			injectClient(client);

			// when
			ResponseEntity<Object> response = controller.fetch(json("{\"url\":\"https://idp/x\"}"));

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			assertThat(response.getBody().toString()).contains("connection refused");
		}

	}

}
