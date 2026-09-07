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

package io.inspector.mcp.core.proxy;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ProxyErrorMapper} — the exact D3 literal table and status
 * extraction (incl. handshake).
 */
@Epic("MCP Inspector Core")
@Feature("ProxyErrorMapper (D3 error contract)")
class ProxyErrorMapperTests {

	private static final String REASON_400 = "Invalid or missing auth profile or session reference.";

	private static final String GUIDANCE_400 = "Check the profile fields and profileId, then reconnect.";

	private static final String REASON_401 = "The MCP server rejected the request as unauthenticated.";

	private static final String GUIDANCE_401 = "Verify the token/API key. OAuth2 profiles refresh and retry once automatically.";

	private static final String REASON_403 = "The MCP server rejected the request as not permitted.";

	private static final String GUIDANCE_403 = "Verify the credential scope or permissions, then reconnect.";

	private static final String REASON_404 = "The proxy session no longer exists or has expired.";

	private static final String GUIDANCE_404 = "Reconnect to establish a new session.";

	private static final String REASON_3XX = "The MCP server redirected the request and it was not followed.";

	private static final String GUIDANCE_3XX = "Check the server URL; redirects are not followed automatically.";

	private static McpHttpClientTransportAuthorizationException authorizationException(final int status) {
		final HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);
		given(responseInfo.statusCode()).willReturn(status);
		return new McpHttpClientTransportAuthorizationException("unauthorized: HTTP " + status,
				new HttpRequestSnapshot(URI.create("https://target/mcp"), "POST",
						HttpHeaders.of(java.util.Map.of(), (a, b) -> true)),
				responseInfo);
	}

	@Nested
	@DisplayName("SSE mapping")
	class SseMapping {

		@Test
		@Story("Exact D3 table")
		@Severity(SeverityLevel.CRITICAL)
		@Description("SSE 400 maps to the exact bad_request DTO with the literal reason/guidance")
		void sse_400_mapsToBadRequestDto() {
			// when
			final ProxyErrorDto dto = ProxyErrorMapper
				.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 400"), TransportKind.SSE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(400);
			assertThat(dto.code()).isEqualTo("bad_request");
			assertThat(dto.reason()).isEqualTo(REASON_400);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_400);
		}

		@Test
		@Story("Exact D3 table")
		@Severity(SeverityLevel.CRITICAL)
		@Description("SSE 401 maps to the exact unauthorized DTO")
		void sse_401_mapsToUnauthorizedDto() {
			// when
			final ProxyErrorDto dto = ProxyErrorMapper
				.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 401"), TransportKind.SSE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(401);
			assertThat(dto.code()).isEqualTo("unauthorized");
			assertThat(dto.reason()).isEqualTo(REASON_401);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_401);
		}

		@Test
		@Story("Exact D3 table")
		@Severity(SeverityLevel.CRITICAL)
		@Description("SSE 403 maps to the exact forbidden DTO")
		void sse_403_mapsToForbiddenDto() {
			// when
			final ProxyErrorDto dto = ProxyErrorMapper
				.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 403"), TransportKind.SSE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(403);
			assertThat(dto.code()).isEqualTo("forbidden");
			assertThat(dto.reason()).isEqualTo(REASON_403);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_403);
		}

		@Test
		@Story("Exact D3 table")
		@Severity(SeverityLevel.CRITICAL)
		@Description("SSE 404 maps to the exact session_not_found DTO")
		void sse_404_mapsToSessionNotFoundDto() {
			// when
			final ProxyErrorDto dto = ProxyErrorMapper
				.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 404"), TransportKind.SSE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(404);
			assertThat(dto.code()).isEqualTo("session_not_found");
			assertThat(dto.reason()).isEqualTo(REASON_404);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_404);
		}

		@Test
		@Story("Exact D3 table")
		@Severity(SeverityLevel.CRITICAL)
		@Description("SSE 3xx maps to the exact redirect DTO")
		void sse_3xx_mapsToRedirectDto() {
			// when
			final ProxyErrorDto dto = ProxyErrorMapper
				.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 302"), TransportKind.SSE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(302);
			assertThat(dto.code()).isEqualTo("redirect");
			assertThat(dto.reason()).isEqualTo(REASON_3XX);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_3XX);
		}

	}

	@Nested
	@DisplayName("STREAMABLE mapping")
	class StreamableMapping {

		@Test
		@Story("Authz exception")
		@Severity(SeverityLevel.CRITICAL)
		@Description("STREAMABLE 401 via McpHttpClientTransportAuthorizationException maps to the unauthorized DTO")
		void streamable_401AuthorizationException_mapsToUnauthorizedDto() {
			// given
			final Throwable error = authorizationException(401);

			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(error, TransportKind.STREAMABLE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(401);
			assertThat(dto.code()).isEqualTo("unauthorized");
			assertThat(dto.reason()).isEqualTo(REASON_401);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_401);
		}

		@Test
		@Story("Authz exception")
		@Severity(SeverityLevel.CRITICAL)
		@Description("STREAMABLE 403 via the authz exception maps to the forbidden DTO")
		void streamable_403AuthorizationException_mapsToForbiddenDto() {
			// given
			final Throwable error = authorizationException(403);

			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(error, TransportKind.STREAMABLE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(403);
			assertThat(dto.code()).isEqualTo("forbidden");
			assertThat(dto.reason()).isEqualTo(REASON_403);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_403);
		}

		@Test
		@Story("TransportKind gate")
		@Severity(SeverityLevel.CRITICAL)
		@Description("STREAMABLE 3xx maps to the redirect DTO (D3: redirects surface on both transports)")
		void streamable_3xx_mapsToRedirectDto() {
			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(
					new RuntimeException("Sending message failed with a non-OK HTTP code: 302"),
					TransportKind.STREAMABLE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(302);
			assertThat(dto.code()).isEqualTo("redirect");
			assertThat(dto.reason()).isEqualTo(REASON_3XX);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_3XX);
		}

		@Test
		@Story("TransportKind gate")
		@Severity(SeverityLevel.CRITICAL)
		@Description("STREAMABLE 404 NEVER yields a DTO — null")
		void streamable_404_returnsNull() {
			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(
					new RuntimeException("Sending message failed with a non-OK HTTP code: 404"),
					TransportKind.STREAMABLE);

			// then
			assertThat(dto).isNull();
		}

		@Test
		@Story("TransportKind gate")
		@Severity(SeverityLevel.CRITICAL)
		@Description("STREAMABLE 400 NEVER yields a DTO — null")
		void streamable_400_returnsNull() {
			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(
					new RuntimeException("Sending message failed with a non-OK HTTP code: 400"),
					TransportKind.STREAMABLE);

			// then
			assertThat(dto).isNull();
		}

		@Test
		@Story("Authz exception")
		@Severity(SeverityLevel.CRITICAL)
		@Description("STREAMABLE 401 status also surfaces when the authz exception is nested as the cause")
		void streamable_401NestedCause_mapsToUnauthorizedDto() {
			// given
			final Throwable error = new RuntimeException("transport failure", authorizationException(401));

			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(error, TransportKind.STREAMABLE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(401);
			assertThat(dto.code()).isEqualTo("unauthorized");
		}

	}

	@Nested
	@DisplayName("Status extraction")
	class StatusExtraction {

		@Test
		@Story("Handshake")
		@Severity(SeverityLevel.CRITICAL)
		@Description("extracts the status from the SSE initial-handshake message text (mcp-core connect() style)")
		void extractStatus_handshakeMessage_returnsStatus() {
			// given — HttpClientSseClientTransport.connect() surfaces the response event
			// in the message
			final RuntimeException error = new RuntimeException(
					"Failed to send message: [401 Unauthorized] POST https://target/mcp");

			// when
			final java.util.Optional<Integer> status = ProxyErrorMapper.extractStatus(error);

			// then
			assertThat(status).contains(401);
		}

		@Test
		@Story("Handshake")
		@Severity(SeverityLevel.CRITICAL)
		@Description("extracts the status from a wrapped handshake cause")
		void extractStatus_wrappedCause_returnsStatus() {
			// given
			final Throwable error = new RuntimeException("handshake failed",
					new RuntimeException("Failed to send message: [403 Forbidden]"));

			// when
			final java.util.Optional<Integer> status = ProxyErrorMapper.extractStatus(error);

			// then
			assertThat(status).contains(403);
		}

		@Test
		@Story("Handshake")
		@Severity(SeverityLevel.NORMAL)
		@Description("handshake 401/403 surfaces the DTO on SSE (structured error, not legacy)")
		void sse_handshake401_mapsToDto() {
			// given
			final Throwable error = new RuntimeException(
					"Failed to send message: [401 Unauthorized] GET https://target/sse");

			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(error, TransportKind.SSE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(401);
			assertThat(dto.code()).isEqualTo("unauthorized");
		}

		@Test
		@Story("Handshake")
		@Severity(SeverityLevel.CRITICAL)
		@Description("handshake 3xx redirect surfaces the DTO on SSE ONLY")
		void sse_handshake302_mapsToRedirectDto() {
			// given
			final Throwable error = new RuntimeException("Failed to send message: [302 Found] GET https://target/sse");

			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(error, TransportKind.SSE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(302);
			assertThat(dto.code()).isEqualTo("redirect");
		}

		@Test
		@Story("Handshake")
		@Severity(SeverityLevel.CRITICAL)
		@Description("handshake 3xx on streamable maps to the redirect DTO (D3)")
		void streamable_handshake302_mapsToRedirectDto() {
			// given
			final Throwable error = new RuntimeException("Failed to send message: [302 Found] GET https://target/mcp");

			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(error, TransportKind.STREAMABLE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(302);
			assertThat(dto.code()).isEqualTo("redirect");
			assertThat(dto.reason()).isEqualTo(REASON_3XX);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_3XX);
		}

		@Test
		@Story("Unknown failures")
		@Severity(SeverityLevel.CRITICAL)
		@Description("failures without any HTTP status yield null — never a fabricated DTO")
		void map_withoutStatus_returnsNull() {
			// when/then
			assertThat(ProxyErrorMapper.map(new RuntimeException("connection refused"), TransportKind.SSE)).isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("connection refused"), TransportKind.STREAMABLE))
				.isNull();
			assertThat(ProxyErrorMapper.map(null, TransportKind.SSE)).isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("no status here"), TransportKind.SSE)).isNull();
		}

		@Test
		@Story("Unknown failures")
		@Severity(SeverityLevel.NORMAL)
		@Description("non-mapped statuses (500, 502, 599) yield null on both transports")
		void map_unmappedStatuses_returnsNull() {
			// when/then
			assertThat(ProxyErrorMapper.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 500"),
					TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 502"),
					TransportKind.STREAMABLE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 599"),
					TransportKind.SSE))
				.isNull();
		}

		@Test
		@Story("Unknown failures")
		@Severity(SeverityLevel.NORMAL)
		@Description("2xx statuses carry no DTO on either transport — legacy fallback")
		void map_2xxStatus_returnsNull() {
			// when/then
			assertThat(ProxyErrorMapper.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 200"),
					TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("Sending message failed with a non-OK HTTP code: 299"),
					TransportKind.STREAMABLE))
				.isNull();
		}

		@Test
		@Story("Handshake")
		@Severity(SeverityLevel.NORMAL)
		@Description("an authorization exception without response info falls back to message parsing")
		void extractStatus_authzWithoutResponseInfo_parsesMessage() {
			// given
			final Throwable error = new McpHttpClientTransportAuthorizationException("unauthorized: HTTP 401",
					new HttpRequestSnapshot(URI.create("https://target/mcp"), "POST",
							HttpHeaders.of(java.util.Map.of(), (a, b) -> true)),
					null);

			// when
			final java.util.Optional<Integer> status = ProxyErrorMapper.extractStatus(error);

			// then
			assertThat(status).contains(401);
		}

		@Test
		@Story("Handshake")
		@Severity(SeverityLevel.NORMAL)
		@Description("an authorization exception with a zero response status falls back to message parsing")
		void extractStatus_authzZeroStatus_parsesMessage() {
			// given
			final HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);
			given(responseInfo.statusCode()).willReturn(0);
			final Throwable error = new McpHttpClientTransportAuthorizationException("unauthorized: HTTP 401",
					new HttpRequestSnapshot(URI.create("https://target/mcp"), "POST",
							HttpHeaders.of(java.util.Map.of(), (a, b) -> true)),
					responseInfo);

			// when
			final java.util.Optional<Integer> status = ProxyErrorMapper.extractStatus(error);

			// then
			assertThat(status).contains(401);
		}

		@Test
		@Story("Unknown failures")
		@Severity(SeverityLevel.NORMAL)
		@Description("a throwable with a null message is skipped — the cause chain is still walked")
		void extractStatus_nullMessage_walksCauseChain() {
			// given
			final Throwable error = new RuntimeException(null,
					new RuntimeException("Failed to send message: [403 Forbidden]"));

			// when
			final java.util.Optional<Integer> status = ProxyErrorMapper.extractStatus(error);

			// then
			assertThat(status).contains(403);
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("connection refused with port number does NOT map to a DTO")
		void map_connectionRefusedWithPort_returnsNull() {
			// when/then
			assertThat(
					ProxyErrorMapper.map(new RuntimeException("Connection refused: localhost:8443"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("Connection refused: localhost:443"),
					TransportKind.STREAMABLE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("http://host:5000/path timeout"), TransportKind.SSE))
				.isNull();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("extractStatus returns empty for port-only messages")
		void extractStatus_portOnly_returnsEmpty() {
			// when/then
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("Connection refused: localhost:8443")))
				.isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("http://host:5000/path timeout"))).isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("Connection refused: 127.0.0.1:8080")))
				.isEmpty();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("bare number without HTTP context does NOT map to a DTO")
		void map_bareNumberWithoutContext_returnsNull() {
			// when/then
			assertThat(ProxyErrorMapper.map(new RuntimeException("Got 404 from server"), TransportKind.SSE)).isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("Error 500 occurred"), TransportKind.STREAMABLE))
				.isNull();
		}

		@Test
		@Story("Explicit context")
		@Severity(SeverityLevel.CRITICAL)
		@Description("HTTP status with explicit context maps correctly")
		void map_explicitHttpContext_mapsToDto() {
			// when/then
			assertThat(ProxyErrorMapper.map(new RuntimeException("HTTP 404 Not Found"), TransportKind.SSE)).isNotNull()
				.satisfies((dto) -> {
					assertThat(dto.status()).isEqualTo(404);
					assertThat(dto.code()).isEqualTo("session_not_found");
				});
			assertThat(ProxyErrorMapper.map(new RuntimeException("status code: 503"), TransportKind.STREAMABLE))
				.isNull(); // 503 is not in the D3 mapping table, so null DTO
		}

		@Test
		@Story("Explicit context")
		@Severity(SeverityLevel.NORMAL)
		@Description("extractStatus with explicit context returns the status")
		void extractStatus_explicitContext_returnsStatus() {
			// when/then
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("HTTP 404 Not Found"))).contains(404);
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("status code: 503"))).contains(503);
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("status: 401"))).contains(401);
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("code: 302"))).contains(302);
		}

		// ========== Regression tests (task t_4528e10e) ==========
		// Group 1: false positives - port/URL numbers must NOT map to HTTP status.
		// On the OLD implementation (loose \b([1-5][0-9][0-9])\b pattern), each of these
		// messages would incorrectly extract a port number as an HTTP status.

		@Test
		@Story("Port safety regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("'Connection refused: 127.0.0.1:8080' does NOT map to a DTO (port 8080 in IP:port)")
		void map_connectionRefusedIpPort_returnsNull() {
			// old regex would match "808" from "8080" (word boundary at '0' before ':')
			assertThat(
					ProxyErrorMapper.map(new RuntimeException("Connection refused: 127.0.0.1:8080"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("Connection refused: 127.0.0.1:8080"),
					TransportKind.STREAMABLE))
				.isNull();
		}

		@Test
		@Story("Port safety regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("'http://backend:5000/api timeout' does NOT map to a DTO (port in URL authority)")
		void map_urlWithPort_returnsNull() {
			// old regex would match "500" from "5000"
			assertThat(ProxyErrorMapper.map(new RuntimeException("http://backend:5000/api timeout"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("http://backend:5000/api timeout"),
					TransportKind.STREAMABLE))
				.isNull();
		}

		@Test
		@Story("Port safety regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("'failed to connect to host:443' does NOT map to a DTO (HTTPS port)")
		void map_hostPort443_returnsNull() {
			// old regex would match "443"
			assertThat(ProxyErrorMapper.map(new RuntimeException("failed to connect to host:443"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("failed to connect to host:443"),
					TransportKind.STREAMABLE))
				.isNull();
		}

		@Test
		@Story("Port safety regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("boundary ports (100, 599) in authority URLs do NOT map to a DTO")
		void map_boundaryPortsInUrl_returnsNull() {
			// old regex would match "100" and "599" from these URLs
			assertThat(ProxyErrorMapper.map(new RuntimeException("http://backend:100/api timeout"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("http://backend:599/api timeout"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("http://backend:100/api timeout")))
				.isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("http://backend:599/api timeout")))
				.isEmpty();
		}

		@Test
		@Story("Port safety regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("extractStatus returns empty for all port-only regression cases")
		void extractStatus_portRegressionCases_returnsEmpty() {
			// old regex would extract these as statuses
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("Connection refused: 127.0.0.1:8080")))
				.isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("http://backend:5000/api timeout")))
				.isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("failed to connect to host:443"))).isEmpty();
		}

		// Group 2: real statuses continue to map correctly

		@Test
		@Story("Real status regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("'HTTP 503 Service Unavailable' extracts status (not mapped to DTO: 503 not in D3 table)")
		void map_http503_extractsStatusButReturnsNullDto() {
			// status extraction works
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("HTTP 503 Service Unavailable")))
				.contains(503);
			// but D3 table has no 503 entry, so map returns null (legacy fallback)
			assertThat(ProxyErrorMapper.map(new RuntimeException("HTTP 503 Service Unavailable"), TransportKind.SSE))
				.isNull();
		}

		@Test
		@Story("Real status regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("'status code: 429' extracts status (not mapped to DTO: 429 not in D3 table)")
		void map_statusCode429_extractsStatusButReturnsNullDto() {
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("status code: 429"))).contains(429);
			assertThat(ProxyErrorMapper.map(new RuntimeException("status code: 429"), TransportKind.SSE)).isNull();
		}

		@Test
		@Story("Real status regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("'404 Not Found' maps to session_not_found DTO on SSE")
		void map_404NotFound_mapsToSessionNotFound() {
			final ProxyErrorDto dto = ProxyErrorMapper.map(new RuntimeException("HTTP 404 Not Found"),
					TransportKind.SSE);
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(404);
			assertThat(dto.code()).isEqualTo("session_not_found");
			assertThat(dto.reason()).isEqualTo(REASON_404);
			assertThat(dto.guidance()).isEqualTo(GUIDANCE_404);
		}

		// Group 3: unknown error without recognized status -> null (legacy fallback)

		@Test
		@Story("Unknown failure regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("unrecognized error without status yields null DTO (legacy 502/504 path)")
		void map_unrecognizedError_returnsNull() {
			assertThat(ProxyErrorMapper.map(new RuntimeException("something went wrong"), TransportKind.SSE)).isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("something went wrong"), TransportKind.STREAMABLE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException(""), TransportKind.SSE)).isNull();
		}

		// Group 4: structured exception with status field -> status from exception

		@Test
		@Story("Structured exception regression")
		@Severity(SeverityLevel.CRITICAL)
		@Description("McpHttpClientTransportAuthorizationException status field takes priority over message parsing")
		void map_structuredExceptionStatus_overridesMessage() {
			// message says 500, but structured field says 401: 401 wins
			final HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);
			given(responseInfo.statusCode()).willReturn(401);
			final Throwable error = new McpHttpClientTransportAuthorizationException("HTTP 500 Internal Server Error",
					new HttpRequestSnapshot(URI.create("https://target/mcp"), "POST",
							HttpHeaders.of(java.util.Map.of(), (a, b) -> true)),
					responseInfo);

			final ProxyErrorDto dto = ProxyErrorMapper.map(error, TransportKind.STREAMABLE);
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(401);
			assertThat(dto.code()).isEqualTo("unauthorized");
		}

	}

}
