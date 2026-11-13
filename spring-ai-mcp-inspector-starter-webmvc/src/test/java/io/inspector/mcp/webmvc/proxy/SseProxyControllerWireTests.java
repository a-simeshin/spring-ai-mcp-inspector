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

package io.inspector.mcp.webmvc.proxy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.Map;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxyConnectFailure;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wire-level tests for the SSE proxy's connect-failure contract: they drive
 * {@code GET /mcp-inspector-api/sse} through {@link MockMvc} with a REAL
 * {@link ProxyTransportFactory} and assert the HTTP response BODY, not the thrown
 * exception.
 *
 * <p>
 * Round 2 of the owner review proved the previous implementation lost the message on the
 * wire: a {@code ResponseStatusException} is resolved via {@code Response.sendError},
 * forwarded to Boot's {@code /error} dispatch, and Boot's default
 * {@code server.error.include-message=never} (Boot 4.1 {@code ErrorProperties}) drops the
 * reason phrase from both the status line and the body. A browser saw a 502 with an empty
 * body (Accept: text/event-stream) or a message-less Boot error page (Accept:
 * {@code *}/{@code *}), while a unit test asserting only the exception object stayed
 * green. The controller now answers the structured {@code MCP_CONNECT_FAILED} JSON itself
 * via {@code @ExceptionHandler(ProxyConnectFailureException.class)}; these tests fail if
 * that ever regresses to a container error page.
 *
 * <p>
 * A restricted-header rejection fires at transport build time, before any socket is
 * opened, so the target URL never matters for those cases (a closed port is used for
 * determinism). The unreachable-upstream case binds a freshly allocated closed loopback
 * port, same trick as
 * {@code WebMvcAutoConfigurationIT.postMcp_unreachableUpstream_returnsStructured502}.
 */
@Epic("WebMvc Inspector")
@Feature("SseProxyController wire contract")
class SseProxyControllerWireTests {

	private static final String SSE_PATH = "/mcp-inspector-api/sse";

	/** A loopback port nothing listens on; connect would be refused if ever reached. */
	private static final String DEAD_TARGET = "http://127.0.0.1:9/sse";

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		final McpProxy mcpProxy = mock(McpProxy.class);
		given(mcpProxy.start(any())).willReturn(reactor.core.publisher.Mono.empty());
		final SseProxyController controller = new SseProxyController(new ProxySessionRegistry(),
				new ProxyTransportFactory(), mcpProxy, new JsonMapper(), new McpInspectorProperties());
		this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	@DisplayName("GET /sse with a restricted custom header answers 502 whose BODY carries header name and JDK reason")
	@Story("Open SSE session")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A restricted custom header name (host) is rejected at transport build time and the response body "
			+ "carries MCP_CONNECT_FAILED with reason invalid_request, the offending header name and the JDK "
			+ "reason - never the header value, and never an empty or message-less container error page")
	void openSse_whenRestrictedCustomHeaderRejected_answersStructured502Body() throws Exception {
		this.mockMvc
			.perform(get(SSE_PATH).queryParam("transportType", "sse")
				.queryParam("url", DEAD_TARGET)
				.header("x-custom-auth-headers", "host")
				.header("host", "secret-value-that-must-leak-nowhere")
				.accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.code").value("MCP_CONNECT_FAILED"))
			.andExpect(jsonPath("$.error.reason").value("invalid_request"))
			.andExpect(jsonPath("$.error.message", containsString("custom header 'host' is not allowed")))
			.andExpect(jsonPath("$.error.message", containsString("restricted header name")))
			.andExpect(jsonPath("$.error.retryable").value(false))
			.andExpect((result) -> assertThat(result.getResponse().getContentAsString())
				.doesNotContain("secret-value-that-must-leak-nowhere"));
	}

	@Test
	@DisplayName("GET /sse with a colon-containing custom header name reports the name verbatim in the body")
	@Story("Open SSE session")
	@Severity(SeverityLevel.NORMAL)
	@Description("A header name containing a colon (X-Cu:stom) is rejected by the JDK as an invalid header name; the "
			+ "structured body reports the full name verbatim, proving offenders travel as name-to-reason pairs and "
			+ "not as a colon-joined string that truncates the name at its own colon")
	void openSse_whenHeaderNameContainsColon_reportsNameVerbatim() throws Exception {
		this.mockMvc
			.perform(get(SSE_PATH).queryParam("transportType", "sse")
				.queryParam("url", DEAD_TARGET)
				.header("x-custom-auth-headers", "X-Cu:stom")
				.header("X-Cu:stom", "v")
				.accept(MediaType.ALL))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.reason").value("invalid_request"))
			.andExpect(jsonPath("$.error.message", containsString("'X-Cu:stom'")))
			.andExpect(jsonPath("$.error.message", containsString("invalid header name")));
	}

	@Test
	@DisplayName("GET /sse with an unreachable upstream opens the stream (200); the refusal is asynchronous")
	@Story("Open SSE session")
	@Severity(SeverityLevel.NORMAL)
	@Description("The SSE transport connects lazily, so an unreachable upstream is NOT a synchronous failure on "
			+ "this route: the stream opens with 200 and the refusal surfaces asynchronously on it. This pins two "
			+ "boundaries: the @ExceptionHandler must not intercept the happy path, and only build-time rejections "
			+ "(restricted headers, unsupported type) answer synchronously. The synchronous structured "
			+ "connection_refused body is the streamable POST route's contract, covered by "
			+ "WebMvcAutoConfigurationIT.postMcp_unreachableUpstream_returnsStructured502")
	void openSse_whenUpstreamRefused_opensStreamInsteadOfFailingSynchronously() throws Exception {
		final int deadPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			deadPort = socket.getLocalPort();
		}
		this.mockMvc
			.perform(get(SSE_PATH).queryParam("transportType", "sse")
				.queryParam("url", "http://127.0.0.1:" + deadPort + "/sse")
				.accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("GET /sse with an unsupported transportType answers 502 with a structured body")
	@Story("Open SSE session")
	@Severity(SeverityLevel.MINOR)
	@Description("The unsupported-transportType rejection travels the same structured contract instead of a bare "
			+ "container error page")
	void openSse_withUnsupportedType_answersStructured502Body() throws Exception {
		this.mockMvc
			.perform(get(SSE_PATH).queryParam("transportType", "carrier-pigeon")
				.queryParam("url", DEAD_TARGET)
				.accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.reason").value("invalid_request"))
			.andExpect(jsonPath("$.error.message", containsString("unsupported transportType")));
	}

	@Test
	@DisplayName("every Reason this handler can serialize stays within the browser-side allow-list")
	@Story("Wire contract")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The UI parser (connectErrors.ts CONNECT_FAILURE_REASONS) rejects any reason outside its allow-list "
			+ "and degrades the failure to unknown in the alert; invalid_request was missing from it when the "
			+ "reason was introduced. This pins the server-side half of the contract: a new Reason cannot ship "
			+ "without the UI allow-list learning it")
	void wireReasons_stayWithinUiAllowList() {
		final List<String> uiAllowList = List.of("timeout", "connection_refused", "dns", "invalid_request",
				"unauthorized", "not_found", "unknown");
		for (final ProxyConnectFailure.Reason reason : ProxyConnectFailure.Reason.values()) {
			assertThat(reason.wire()).as("reason %s must be in the UI allow-list", reason).isIn(uiAllowList);
		}
	}

	@Test
	@DisplayName("the JDK this build runs on still restricts the 'host' header name")
	@Story("Header validation")
	@Severity(SeverityLevel.MINOR)
	@Description("Pins the JDK behaviour the body assertions depend on: 'host' is a restricted header name here, so "
			+ "the restricted-header wire test cannot silently degrade to a green connect")
	void jdkRestrictsHostHeaderName() {
		try {
			java.net.http.HttpRequest.newBuilder().setHeader("host", "probe");
			throw new AssertionError("JDK accepted restricted header 'host': the wire tests above are vacuous");
		}
		catch (final IllegalArgumentException ex) {
			assertThat(ex.getMessage()).contains("restricted header name");
		}
	}

	@Test
	@DisplayName("classify() maps the factory's restricted-header rejection onto invalid_request, value-safe")
	@Story("Header validation")
	@Severity(SeverityLevel.NORMAL)
	@Description("Unit-level anchor for the wire tests: the exception the controller wraps carries "
			+ "Reason.INVALID_REQUEST and a message containing the header name and JDK reason but never the header "
			+ "value")
	void classify_mapsFactoryRejectionValueSafe() {
		final ProxyTransportFactory factory = new ProxyTransportFactory();
		try {
			factory.buildSse(URI.create(DEAD_TARGET), null, Map.of("host", "value-not-in-any-message"));
			throw new AssertionError("expected IllegalArgumentException from the factory");
		}
		catch (final IllegalArgumentException ex) {
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(ex);
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.INVALID_REQUEST);
			assertThat(failure.message()).contains("custom header 'host' is not allowed")
				.contains("restricted header name");
			assertThat(failure.message()).doesNotContain("value-not-in-any-message");
		}
	}

}
