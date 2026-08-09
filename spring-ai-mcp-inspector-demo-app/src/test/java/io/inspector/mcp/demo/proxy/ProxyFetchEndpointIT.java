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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code POST /mcp-inspector-api/fetch} on both stacks.
 *
 * <p>
 * The endpoint exists so the upstream UI can run OAuth discovery / well-known JSON
 * lookups without CORS pain in the browser. We exercise three properties:
 *
 * <ol>
 * <li>It successfully proxies a {@code GET} to a self-hosted endpoint
 * ({@code /mcp-inspector-api/health}) and surfaces the response body inside the
 * {@code body} field of the envelope.</li>
 * <li>It rejects non-{@code http(s)} schemes ({@code file://} → 400) — basic SSRF
 * tightening.</li>
 * <li>It rejects a malformed request body ({@code url} missing) → 400.</li>
 * </ol>
 *
 * <p>
 * For the success case we self-target the same demo instance so the test has zero
 * external dependencies.
 */
@Epic("Inspector Proxy")
@Feature("Fetch endpoint")
class ProxyFetchEndpointIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (app != null) {
			try {
				app.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			app = null;
		}
	}

	@Nested
	@DisplayName("POST /fetch")
	class FetchEndpoint {

		@Test
		@DisplayName("proxies a loopback GET and surfaces the body in the envelope")
		@Story("Successful proxy")
		@Severity(SeverityLevel.NORMAL)
		@Description("POST /fetch proxies a GET to the demo's own /health endpoint and surfaces the proxied "
				+ "JSON in the envelope body with ok=true and status=200")
		void fetch_withLoopbackHealthTarget_proxiesBody() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", false, null);
			int port = ProxyAppHarness.port(app);

			String selfTarget = "http://127.0.0.1:" + port + "/mcp-inspector-api/health";
			String body = "{\"url\":\"" + selfTarget + "\",\"init\":{\"method\":\"GET\"}}";

			// when
			HttpRequest request = HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/fetch"))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

			// then
			// WebMvc returns the upstream status; WebFlux normalizes to 200 (its
			// ServerResponse.ok() wraps every successful envelope). Both must hand
			// back the JSON envelope; accept either status as long as it's 2xx.
			assertThat(response.statusCode())
				.as("fetch proxied ok on %s, body=%s", ProxyAppHarness.stack(), response.body())
				.isBetween(200, 299);

			JsonNode envelope = MAPPER.readTree(response.body());
			assertThat(envelope.path("ok").asBoolean()).isTrue();
			assertThat(envelope.path("status").asInt()).isEqualTo(200);
			// The proxied body is the health JSON; assert by substring rather than
			// re-parsing (the upstream ProxyAppHarness.stack() returns it as a JSON
			// string).
			assertThat(envelope.path("body").asText()).contains("\"status\"").contains("ok");
		}

		@Test
		@DisplayName("rejects a file:// scheme target")
		@Story("SSRF tightening")
		@Severity(SeverityLevel.CRITICAL)
		@Description("POST /fetch with a file:// URL must be rejected with a non-2xx status to prevent SSRF "
				+ "/ local file disclosure")
		void fetch_withFileScheme_isRejected() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", false, null);
			int port = ProxyAppHarness.port(app);

			// when
			String body = "{\"url\":\"file:///etc/passwd\"}";
			HttpRequest request = HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/fetch"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

			// then
			// WebMvc returns 400; WebFlux wraps the IllegalArgumentException via
			// onErrorResume() → 502. Both are valid "no-SSRF" responses; we only
			// require non-2xx so the test isn't coupled to either error mode.
			assertThat(response.statusCode()).as("file:// scheme must not be proxied (%s)", ProxyAppHarness.stack())
				.isGreaterThanOrEqualTo(400);
		}

		@Test
		@DisplayName("rejects a request body missing the url field")
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("POST /fetch with a body that omits the required url field must be rejected with a "
				+ "non-2xx status")
		void fetch_withMissingUrl_isRejected() throws Exception {
			// given
			app = ProxyAppHarness.start("STREAMABLE", false, null);
			int port = ProxyAppHarness.port(app);

			// when
			HttpRequest request = HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-inspector-api/fetch"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

			// then
			// Same as above — WebMvc → 400, WebFlux → 502 via onErrorResume.
			assertThat(response.statusCode()).as("missing url must be rejected (%s)", ProxyAppHarness.stack())
				.isGreaterThanOrEqualTo(400);
		}

	}

}
