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
 * Unit tests for {@link ProxyErrorMapper} - the exact D3 literal table and status
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
		@Description("STREAMABLE 404 NEVER yields a DTO - null")
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
		@Description("STREAMABLE 400 NEVER yields a DTO - null")
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
			// given - HttpClientSseClientTransport.connect() surfaces the response event
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
		@Description("failures without any HTTP status yield null - never a fabricated DTO")
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
		@Description("2xx statuses carry no DTO on either transport - legacy fallback")
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
		@Description("a throwable with a null message is skipped - the cause chain is still walked")
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

		@Test
		@Story("Bare status + reason phrase")
		@Severity(SeverityLevel.CRITICAL)
		@Description("bare status code at start of message followed by reason phrase maps to the DTO")
		void map_bareStatusWithReasonPhrase_mapsToDto() {
			// when/then - format used by controller tests: "401 Unauthorized"
			assertThat(ProxyErrorMapper.map(new RuntimeException("401 Unauthorized"), TransportKind.SSE)).isNotNull()
				.satisfies((dto) -> {
					assertThat(dto.status()).isEqualTo(401);
					assertThat(dto.code()).isEqualTo("unauthorized");
				});
			assertThat(ProxyErrorMapper.map(new RuntimeException("403 Forbidden"), TransportKind.STREAMABLE))
				.isNotNull()
				.satisfies((dto) -> {
					assertThat(dto.status()).isEqualTo(403);
					assertThat(dto.code()).isEqualTo("forbidden");
				});
		}

		@Test
		@Story("Bare status + reason phrase")
		@Severity(SeverityLevel.NORMAL)
		@Description("bare status code at start of message with reason phrase is extracted")
		void extractStatus_bareStatusWithReasonPhrase_returnsStatus() {
			// when/then
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("401 Unauthorized"))).contains(401);
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("403 Forbidden"))).contains(403);
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("302 Found"))).contains(302);
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("404 Not Found"))).contains(404);
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("host:port patterns do NOT map to a DTO - both host:404 and host:500 are safe")
		void map_hostPort_returnsNull() {
			// when/then - port numbers are NOT HTTP status codes
			assertThat(ProxyErrorMapper.map(new RuntimeException("Connection refused: host:404"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("Connection refused: host:500"),
					TransportKind.STREAMABLE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("http://host:404/timeout"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("http://host:500/timeout"), TransportKind.STREAMABLE))
				.isNull();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("extractStatus returns empty for host:port patterns - both host:404 and host:500 are safe")
		void extractStatus_hostPort_returnsEmpty() {
			// when/then - port numbers are NOT HTTP status codes; extractStatus must not
			// extract them even when map() would return null for an unmapped status
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("Connection refused: host:404"))).isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("Connection refused: host:500"))).isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("http://host:404/timeout"))).isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("http://host:500/timeout"))).isEmpty();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("extractStatus returns empty for numbers in URL path and query")
		void extractStatus_urlPathAndQuery_returnsEmpty() {
			// when/then
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("GET https://host/api/404"))).isEmpty();
			assertThat(
					ProxyErrorMapper.extractStatus(new RuntimeException("Failed to call https://host/api/500/status")))
				.isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("GET https://host/path?code=404&page=500")))
				.isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("POST https://host/path?id=302"))).isEmpty();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.NORMAL)
		@Description("extractStatus returns empty for multipart messages with multiple non-status numbers")
		void extractStatus_multipartNumbers_returnsEmpty() {
			// when/then
			assertThat(ProxyErrorMapper
				.extractStatus(new RuntimeException("boundary=----WebKitFormBoundary404; part=2 of 500; total=404")))
				.isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("part=1 size=403 content-length=302")))
				.isEmpty();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("numbers in URL path and query are NOT treated as HTTP status codes")
		void map_urlPathAndQuery_returnsNull() {
			// when/then
			assertThat(ProxyErrorMapper.map(new RuntimeException("GET https://host/api/404"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("Failed to call https://host/api/500/status"),
					TransportKind.STREAMABLE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("GET https://host/path?code=404&page=500"),
					TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("POST https://host/path?id=302"),
					TransportKind.STREAMABLE))
				.isNull();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.NORMAL)
		@Description("multipart messages with multiple non-status numbers do NOT produce a false positive")
		void map_multipartNumbers_returnsNull() {
			// when/then - boundary, part numbers, etc. inside a multipart message
			assertThat(ProxyErrorMapper.map(
					new RuntimeException("boundary=----WebKitFormBoundary404; part=2 of 500; total=404"),
					TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("part=1 size=403 content-length=302"),
					TransportKind.STREAMABLE))
				.isNull();
		}

		@Test
		@Story("Cause chain")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a deeply nested cause chain with a bare status at the root is extracted")
		void extractStatus_nestedCauseChain_bareStatus() {
			// given
			final Throwable error = new RuntimeException("outer wrapper",
					new RuntimeException("middle layer", new RuntimeException("401 Unauthorized")));

			// when
			final java.util.Optional<Integer> status = ProxyErrorMapper.extractStatus(error);

			// then
			assertThat(status).contains(401);
		}

		@Test
		@Story("Cause chain")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a deeply nested cause chain with bare status maps to the DTO")
		void map_nestedCauseChain_bareStatus_mapsToDto() {
			// given
			final Throwable error = new RuntimeException("outer wrapper",
					new RuntimeException("middle layer", new RuntimeException("403 Forbidden")));

			// when
			final ProxyErrorDto dto = ProxyErrorMapper.map(error, TransportKind.STREAMABLE);

			// then
			assertThat(dto).isNotNull();
			assertThat(dto.status()).isEqualTo(403);
			assertThat(dto.code()).isEqualTo("forbidden");
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("extractStatus returns empty for ?code:404 in URI query string (colon, not equals)")
		void extractStatus_queryCodeColon_returnsEmpty() {
			// when/then - ?code:404 in query string is NOT an HTTP status
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("GET https://host/path?code:404")))
				.isEmpty();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("extractStatus returns empty for /status:404 in URI path")
		void extractStatus_pathStatusColon_returnsEmpty() {
			// when/then - /status:404 in URI path is NOT an HTTP status
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("GET https://host/status:404"))).isEmpty();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("?code:404 in URI query does NOT map to a DTO")
		void map_queryCodeColon_returnsNull() {
			// when/then
			assertThat(ProxyErrorMapper.map(new RuntimeException("GET https://host/path?code:404"), TransportKind.SSE))
				.isNull();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("/status:404 in URI path does NOT map to a DTO")
		void map_pathStatusColon_returnsNull() {
			// when/then
			assertThat(
					ProxyErrorMapper.map(new RuntimeException("GET https://host/status:404"), TransportKind.STREAMABLE))
				.isNull();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("extractStatus returns empty for boundary port numbers host:100 and host:599")
		void extractStatus_boundaryPorts_returnsEmpty() {
			// when/then
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("Connection refused: host:100"))).isEmpty();
			assertThat(ProxyErrorMapper.extractStatus(new RuntimeException("Connection refused: host:599"))).isEmpty();
		}

		@Test
		@Story("Port safety")
		@Severity(SeverityLevel.CRITICAL)
		@Description("boundary port numbers host:100 and host:599 do NOT map to a DTO")
		void map_boundaryPorts_returnsNull() {
			// when/then
			assertThat(ProxyErrorMapper.map(new RuntimeException("Connection refused: host:100"), TransportKind.SSE))
				.isNull();
			assertThat(ProxyErrorMapper.map(new RuntimeException("Connection refused: host:599"),
					TransportKind.STREAMABLE))
				.isNull();
		}

	}

}
