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

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.BearerProfile;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.webmvc.auth.ServletSessionOwnerResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link SseProxyController}. Collaborators are mocked; assertions cover
 * the {@code POST /message} status branches, transport-type selection (incl. validation
 * errors) and session bring-up via the registry / proxy.
 *
 * <p>
 * After the WAF-safe loopback fix, a {@code null} or blank {@code url} is resolved to the
 * loopback origin server-side (via {@code ProxyTargetResolver}). In unit tests the
 * controller is constructed without a {@code portHolder}, so {@code loopbackPort()} falls
 * back to 8080 and the resolver always produces a valid URI. Consequently tests that
 * previously asserted "blank url → no session registered" now assert that a session IS
 * opened (the mocked factory is called and {@code registry.put} is invoked).
 */
@Epic("WebMvc Inspector")
@Feature("SseProxyController")
class SseProxyControllerTests {

	/** Root-mounted request: no context path, so no prefix in the SSE prologue. */
	private static final MockHttpServletRequest REQUEST = new MockHttpServletRequest();

	private ProxySessionRegistry registry;

	private ProxyTransportFactory transportFactory;

	private McpProxy mcpProxy;

	private JsonMapper objectMapper;

	private McpInspectorProperties properties;

	private SseProxyController controller;

	@BeforeEach
	void setUp() {
		this.registry = mock(ProxySessionRegistry.class);
		this.transportFactory = mock(ProxyTransportFactory.class);
		this.mcpProxy = mock(McpProxy.class);
		this.objectMapper = new JsonMapper();
		this.properties = new McpInspectorProperties();
		given(this.mcpProxy.start(any())).willReturn(Mono.empty());
		this.controller = new SseProxyController(this.registry, this.transportFactory, this.mcpProxy, this.objectMapper,
				this.properties);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
	}

	private ProxySession newSession(final String id, final McpClientTransport target) {
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(8);
		return new ProxySession(id, target, browserToTarget, targetToBrowser);
	}

	/**
	 * Waits up to 10s for the buffered emitter frames to contain the expected snippet:
	 * the connect-phase timeout is emitted asynchronously by the start() subscription, so
	 * the assertion must tolerate the event landing slightly after openSse returns.
	 * @param expected the snippet to wait for
	 * @param frames supplier reading the current buffered frames
	 * @throws Exception when reading the buffered frames fails
	 */
	private static void awaitUntilContains(final String expected, final java.util.concurrent.Callable<String> frames)
			throws Exception {
		final long deadline = System.currentTimeMillis() + 10_000L;
		while (System.currentTimeMillis() < deadline) {
			if (frames.call().contains(expected)) {
				return;
			}
			Thread.sleep(50L);
		}
	}

	private static String bufferedFrames(final SseEmitter emitter) throws Exception {
		final java.lang.reflect.Field field = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
		field.setAccessible(true);
		final StringBuilder out = new StringBuilder();
		for (final Object chunk : (java.util.Collection<?>) field.get(emitter)) {
			out.append(chunk.getClass().getMethod("getData").invoke(chunk));
		}
		return out.toString();
	}

	/**
	 * Reads a private terminal-state field of the emitter to distinguish the quiet
	 * completion contract from the legacy {@code completeWithError}: only the latter
	 * records a {@code failure} on the emitter.
	 * @param emitter the emitter returned by the controller
	 * @param name the {@link ResponseBodyEmitter} field to read ({@code complete} or
	 * {@code failure})
	 * @return the field value
	 * @throws Exception if the field cannot be read
	 */
	private static Object emitterState(final SseEmitter emitter, final String name) throws Exception {
		final java.lang.reflect.Field field = ResponseBodyEmitter.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(emitter);
	}

	/**
	 * Builds a controller with the D8/D9 auth wiring: a signed-cookie owner resolver and
	 * an owner-scoped auth-profile store plus the OAuth2 managers.
	 * @param store the auth-profile store
	 * @param resolver the session-owner resolver
	 * @return the wired controller
	 */
	private SseProxyController wiredController(final AuthProfileStore store,
			final ServletSessionOwnerResolver resolver) {
		return new SseProxyController(SseProxyControllerTests.this.registry,
				SseProxyControllerTests.this.transportFactory, SseProxyControllerTests.this.mcpProxy,
				SseProxyControllerTests.this.objectMapper, SseProxyControllerTests.this.properties, null, resolver,
				store, mock(OAuth2ClientCredentialsTokenManager.class), mock(OAuth2AuthCodeTokenExchanger.class));
	}

	/**
	 * Reads a numeric field out of the buffered proxy-error payload text.
	 * @param frames the buffered emitter frames
	 * @param field the JSON field name
	 * @return the field value
	 */
	private static long longField(final String frames, final String field) {
		final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + field + "\":(\\d+)")
			.matcher(frames);
		assertThat(matcher.find()).as("proxy-error payload carries %s", field).isTrue();
		return Long.parseLong(matcher.group(1));
	}

	@Nested
	@DisplayName("postMessage()")
	class PostMessage {

		@Test
		@Story("Browser to target frame")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMessage() emits the frame to the session's browserToTarget sink and returns 202")
		void postMessage_withKnownSession_emitsFrameAndReturns202() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(SseProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode body = SseProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Void> response = SseProxyControllerTests.this.controller.postMessage("s1", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
			assertThat(session.browserToTarget().asFlux().blockFirst()).isEqualTo(body);
		}

		@Test
		@Story("Browser to target frame")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMessage() with an unknown session id returns 404")
		void postMessage_withUnknownSession_returns404() throws Exception {
			// given
			given(SseProxyControllerTests.this.registry.get("missing")).willReturn(null);
			final JsonNode body = SseProxyControllerTests.this.objectMapper.readTree("{}");

			// when
			final ResponseEntity<Void> response = SseProxyControllerTests.this.controller.postMessage("missing", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Browser to target frame")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMessage() returns 500 when the sink rejects the emit (terminated session)")
		void postMessage_whenEmitFails_returns500() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			session.browserToTarget().tryEmitComplete();
			given(SseProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final JsonNode body = SseProxyControllerTests.this.objectMapper.readTree("{}");

			// when
			final ResponseEntity<Void> response = SseProxyControllerTests.this.controller.postMessage("s1", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}

	}

	@Nested
	@DisplayName("openSse() — session bring-up")
	class BuildSse {

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() builds an SSE transport, registers a session and starts the proxy")
		void openSse_withSseUrl_registersSessionAndStartsProxy() {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, REQUEST);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.transportFactory).buildSse(URI.create("http://target/sse"));
			verify(SseProxyControllerTests.this.registry).put(any(ProxySession.class));
			verify(SseProxyControllerTests.this.mcpProxy).start(any(ProxySession.class));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with streamable-http builds a streamable transport")
		void openSse_withStreamableUrl_buildsStreamableTransport() {
			// given
			given(SseProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));

			// when
			SseProxyControllerTests.this.controller.openSse("streamable-http", "http://target/mcp", null, null, null,
					null, REQUEST);

			// then
			verify(SseProxyControllerTests.this.transportFactory).openStreamable(URI.create("http://target/mcp"));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with a missing (null) url resolves to the loopback /sse endpoint and registers a session")
		void openSse_withMissingUrl_resolvesToLoopbackAndRegistersSession() {
			// given — no url stub: the mock returns null from openSse(URI), which is a
			// valid (if inert) target for the session in unit-test context
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));

			// when — null url is now resolved to http://127.0.0.1:8080/sse by
			// ProxyTargetResolver (loopbackPort() falls back to 8080 when portHolder is
			// null)
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", null, null, null, null,
					null, REQUEST);

			// then — the resolver succeeds, a session IS opened and registered
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.transportFactory).buildSse(URI.create("http://127.0.0.1:8080/sse"));
			verify(SseProxyControllerTests.this.registry).put(any(ProxySession.class));
			verify(SseProxyControllerTests.this.mcpProxy).start(any(ProxySession.class));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with an unsupported transportType fails to build a transport and skips registration")
		void openSse_withUnsupportedType_doesNotRegisterSession() {
			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("carrier-pigeon", "x", null,
					null, null, null, REQUEST);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.registry, never()).put(any());
		}

		@Test
		@Story("Context path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() under a context path emits a prologue carrying the prefix so the first browser frame is routable")
		void openSse_underContextPath_prologueCarriesPrefix() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
			final MockHttpServletRequest request = new MockHttpServletRequest();
			request.setContextPath("/app");

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, request);

			// then
			// the "data:" anchor pins the start of the value, so a doubled prefix fails
			assertThat(bufferedFrames(emitter)).contains("data:/app/mcp-inspector-api/message?sessionId=");
		}

		@Test
		@Story("Context path")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() on a root-mounted application emits the unprefixed prologue")
		void openSse_withoutContextPath_prologueHasNoPrefix() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, REQUEST);

			// then
			assertThat(bufferedFrames(emitter)).contains("data:/mcp-inspector-api/message?sessionId=");
		}

		@Test
		@Story("Context path")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() treats a \"/\" context path as no prefix, so the prologue never becomes protocol-relative")
		void openSse_withRootContextPath_prologueHasNoDoubleSlash() throws Exception {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
			final MockHttpServletRequest request = new MockHttpServletRequest();
			request.setContextPath("/");

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, request);

			// then
			assertThat(bufferedFrames(emitter)).contains("data:/mcp-inspector-api/message?sessionId=");
		}

		@Test
		@Story("Open stdio session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() builds a stdio transport from the command and registers a session")
		void openStdio_withCommand_buildsStdioTransportAndRegisters() {
			// given
			given(SseProxyControllerTests.this.transportFactory.openStdio(any(), any()))
				.willReturn(mock(McpClientTransport.class));

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openStdio("python", "-m server",
					"{\"KEY\":\"v\"}", null, REQUEST);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.transportFactory).openStdio(any(), any());
			verify(SseProxyControllerTests.this.registry).put(any(ProxySession.class));
		}

		@Test
		@Story("Open stdio session")
		@Severity(SeverityLevel.MINOR)
		@Description("openStdio() with null args and null env still builds the transport (no args appended, empty env)")
		void openStdio_withNullArgsAndEnv_buildsTransport() {
			// given
			given(SseProxyControllerTests.this.transportFactory.openStdio(any(), any()))
				.willReturn(mock(McpClientTransport.class));

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openStdio("python", null, null, null,
					REQUEST);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.transportFactory).openStdio(any(), any());
		}

		@Test
		@Story("Open stdio session")
		@Severity(SeverityLevel.MINOR)
		@Description("openStdio() with a blank command fails to build a transport and skips registration")
		void openStdio_withBlankCommand_doesNotRegisterSession() {
			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openStdio("   ", null, null, null,
					REQUEST);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.registry, never()).put(any());
		}

		@Test
		@Story("Connection timeout")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() with connectionTimeout=1s and a start() that never completes emits a structured proxy-error event naming the connect phase")
		void openSse_connectPhaseOverrun_emitsStructuredProxyErrorEvent() throws Exception {
			// given: an SSE open whose bring-up never completes; the 1s budget is the
			// only timer on this path
			final McpClientTransport target = mock(McpClientTransport.class);
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class))).willReturn(target);
			given(SseProxyControllerTests.this.mcpProxy.start(any())).willReturn(Mono.never());

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, "1", REQUEST);

			// then: the buffered frames end with a proxy-error event carrying the
			// structured payload (phase=connect, budget=1000ms)
			final String expected = "\"phase\":\"connect\"";
			awaitUntilContains(expected, () -> bufferedFrames(emitter));
			final String frames = bufferedFrames(emitter);
			assertThat(frames).contains("proxy-error")
				.contains("MCP_CONNECT_FAILED")
				.contains(expected)
				.contains("\"budgetMs\":1000");
			// elapsedMs is measured from the start of the bring-up and the Reactor
			// timeout callback lands on a shared parallel scheduler thread, so under
			// load it can trail the budget (a full-suite local run measured 2562ms for
			// this 1000ms budget): pin that the timer fires no earlier than the budget
			// and lags it by seconds at most.
			final long elapsedMs = longField(frames, "elapsedMs");
			assertThat(elapsedMs).isGreaterThanOrEqualTo(1000L);
			assertThat(elapsedMs).isLessThan(1000L + 5000L);
			// removeAndClose runs on the start-handler thread, racing the sink
			// subscriber that delivered the event above: poll instead of asserting
			// the call has already happened.
			verify(SseProxyControllerTests.this.registry, timeout(5000).atLeastOnce())
				.removeAndClose(any(String.class));
		}

		@Test
		@Story("Connection timeout")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openStdio() with connectionTimeout=1s and a start() that never completes emits a structured proxy-error event naming the connect phase")
		void openStdio_connectPhaseOverrun_emitsStructuredProxyErrorEvent() throws Exception {
			// given: a stdio open whose bring-up never completes
			final McpClientTransport target = mock(McpClientTransport.class);
			given(SseProxyControllerTests.this.transportFactory.openStdio(any(), any())).willReturn(target);
			given(SseProxyControllerTests.this.mcpProxy.start(any())).willReturn(Mono.never());

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openStdio("python", null, null, "1",
					REQUEST);

			// then: same structured payload as the SSE variant
			final String expected = "\"phase\":\"connect\"";
			awaitUntilContains(expected, () -> bufferedFrames(emitter));
			final String frames = bufferedFrames(emitter);
			assertThat(frames).contains("proxy-error")
				.contains("MCP_CONNECT_FAILED")
				.contains(expected)
				.contains("\"budgetMs\":1000");
			// elapsedMs is measured from the start of the bring-up and the Reactor
			// timeout callback lands on a shared parallel scheduler thread, so under
			// load it can trail the budget (a full-suite local run measured 2562ms for
			// this 1000ms budget): pin that the timer fires no earlier than the budget
			// and lags it by seconds at most.
			final long elapsedMs = longField(frames, "elapsedMs");
			assertThat(elapsedMs).isGreaterThanOrEqualTo(1000L);
			assertThat(elapsedMs).isLessThan(1000L + 5000L);
			verify(SseProxyControllerTests.this.registry, timeout(5000).atLeastOnce())
				.removeAndClose(any(String.class));
		}

		@Test
		@Story("Connection timeout")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() and openStdio() with an invalid connectionTimeout answer 400 with the structured error body (parity with the reactive stack)")
		void openSse_invalidParameter_answers400structuredBody() throws Exception {
			// given: a MockMvc entry point so the full annotation pipeline
			// (argument resolution + @ExceptionHandler) runs, as in production
			final MockMvc mvc = MockMvcBuilders.standaloneSetup(SseProxyControllerTests.this.controller)
				.addPlaceholderValue("spring.ai.mcp.inspector.path", "/mcp-inspector")
				.build();

			// when: an invalid value on both endpoints
			final MvcResult sse = mvc
				.perform(get("/mcp-inspector-api/sse?transportType=sse&url=http://target/sse&connectionTimeout=abc"))
				.andExpect(status().isBadRequest())
				.andReturn();
			final MvcResult stdio = mvc.perform(get("/mcp-inspector-api/stdio?command=python&connectionTimeout=1.5"))
				.andExpect(status().isBadRequest())
				.andReturn();

			// then: the structured MCP_CONNECT_FAILED validation body, no session opened
			final JsonNode sseError = SseProxyControllerTests.this.objectMapper
				.readTree(sse.getResponse().getContentAsString())
				.path("error");
			assertThat(sseError.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
			assertThat(sseError.path("reason").asText()).isEqualTo("validation");
			assertThat(sseError.path("connectionTimeout").asText()).isEqualTo("abc");
			assertThat(sseError.path("retryable").asBoolean()).isFalse();
			final JsonNode stdioError = SseProxyControllerTests.this.objectMapper
				.readTree(stdio.getResponse().getContentAsString())
				.path("error");
			assertThat(stdioError.path("code").asText()).isEqualTo("MCP_CONNECT_FAILED");
			assertThat(stdioError.path("connectionTimeout").asText()).isEqualTo("1.5");
			assertThat(stdioError.path("retryable").asBoolean()).isFalse();
			verify(SseProxyControllerTests.this.registry, never()).put(any(ProxySession.class));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with a null transportType defaults to the SSE transport")
		void openSse_withNullType_defaultsToSse() {
			// given
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));

			// when
			SseProxyControllerTests.this.controller.openSse(null, "http://target/sse", null, null, null, null, REQUEST);

			// then
			verify(SseProxyControllerTests.this.transportFactory).buildSse(URI.create("http://target/sse"));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.MINOR)
		@Description("openSse() with a blank streamable url resolves to the loopback /mcp endpoint and registers a session")
		void openSse_withBlankStreamableUrl_resolvesToLoopbackAndRegisters() {
			// given — blank url is now resolved to http://127.0.0.1:8080/mcp by
			// ProxyTargetResolver
			given(SseProxyControllerTests.this.transportFactory.openStreamable(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("streamable-http", "", null,
					null, null, null, REQUEST);

			// then — resolver produces a valid loopback URI, session IS registered
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.transportFactory)
				.openStreamable(URI.create("http://127.0.0.1:8080/mcp"));
			verify(SseProxyControllerTests.this.registry).put(any(ProxySession.class));
		}

	}

	@Nested
	@DisplayName("buildTargetTransport() — inbound header forwarding")
	class HeaderForwarding {

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with no request attributes uses the single-arg SSE factory overload")
		void openSse_withoutRequestAttributes_usesSingleArgOverload() {
			// given — no RequestContextHolder bound (tearDown clears it)
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));

			// when
			SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null, null, null, null,
					REQUEST);

			// then
			verify(SseProxyControllerTests.this.transportFactory).buildSse(URI.create("http://target/sse"));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() forwards the inbound Authorization and custom headers via the header-aware SSE overload")
		void openSse_withInboundHeaders_usesHeaderAwareSseOverload() {
			// given
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector-api/sse");
			request.addHeader("Authorization", "Bearer tok");
			request.addHeader("x-custom-auth-headers", "X-Tenant");
			request.addHeader("X-Tenant", "acme");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class), any(), any()))
				.willReturn(mock(McpClientTransport.class));

			// when
			SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null, null, null, null,
					REQUEST);

			// then
			verify(SseProxyControllerTests.this.transportFactory).buildSse(eq(URI.create("http://target/sse")),
					eq("Bearer tok"), eq(Map.of("X-Tenant", "acme")));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with streamable-http forwards inbound headers via the header-aware streamable overload")
		void openSse_streamableWithInboundHeaders_usesHeaderAwareStreamableOverload() {
			// given
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector-api/sse");
			request.addHeader("Authorization", "Bearer tok");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			given(SseProxyControllerTests.this.transportFactory.openStreamable(any(URI.class), any(), any()))
				.willReturn(mock(McpClientTransport.class));

			// when
			SseProxyControllerTests.this.controller.openSse("streamable-http", "http://target/mcp", null, null, null,
					null, REQUEST);

			// then
			verify(SseProxyControllerTests.this.transportFactory).openStreamable(eq(URI.create("http://target/mcp")),
					eq("Bearer tok"), eq(Map.of()));
		}

		@Test
		@Story("Header forwarding")
		@Severity(SeverityLevel.MINOR)
		@Description("openSse() with a bound request but no relevant headers falls back to the single-arg overload")
		void openSse_withRequestButNoHeaders_usesSingleArgOverload() {
			// given — bound request that carries neither Authorization nor the
			// custom-header list
			final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp-inspector-api/sse");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));

			// when
			SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null, null, null, null,
					REQUEST);

			// then
			verify(SseProxyControllerTests.this.transportFactory).buildSse(URI.create("http://target/sse"));
		}

	}

	@Nested
	@DisplayName("parseArgv() / parseEnv() helpers")
	class Helpers {

		@Test
		@Story("Stdio argument parsing")
		@Severity(SeverityLevel.MINOR)
		@Description("parseArgv() prepends the command and splits args on whitespace")
		@SuppressWarnings("unchecked")
		void parseArgv_withArgs_splitsOnWhitespace() throws Exception {
			// given
			final Method m = SseProxyController.class.getDeclaredMethod("parseArgv", String.class, String.class);
			m.setAccessible(true);

			// when
			final List<String> argv = (List<String>) m.invoke(null, "python", "-m server --flag");

			// then
			assertThat(argv).containsExactly("python", "-m", "server", "--flag");
		}

		@Test
		@Story("Stdio env parsing")
		@Severity(SeverityLevel.MINOR)
		@Description("parseEnv() parses a JSON object into a string map")
		@SuppressWarnings("unchecked")
		void parseEnv_withValidJson_parsesMap() throws Exception {
			// given
			final Method m = SseProxyController.class.getDeclaredMethod("parseEnv", String.class);
			m.setAccessible(true);

			// when
			final Map<String, String> env = (Map<String, String>) m.invoke(SseProxyControllerTests.this.controller,
					"{\"A\":\"1\",\"B\":\"2\"}");

			// then
			assertThat(env).containsEntry("A", "1").containsEntry("B", "2");
		}

		@Test
		@Story("Stdio env parsing")
		@Severity(SeverityLevel.MINOR)
		@Description("parseEnv() returns an empty map for malformed JSON")
		@SuppressWarnings("unchecked")
		void parseEnv_withMalformedJson_returnsEmptyMap() throws Exception {
			// given
			final Method m = SseProxyController.class.getDeclaredMethod("parseEnv", String.class);
			m.setAccessible(true);

			// when
			final Map<String, String> env = (Map<String, String>) m.invoke(SseProxyControllerTests.this.controller,
					"{not-json");

			// then
			assertThat(env).isEmpty();
		}

		@Test
		@Story("Stdio env parsing")
		@Severity(SeverityLevel.MINOR)
		@Description("parseEnv() returns an empty map when env is null or blank")
		@SuppressWarnings("unchecked")
		void parseEnv_withBlank_returnsEmptyMap() throws Exception {
			// given
			final Method m = SseProxyController.class.getDeclaredMethod("parseEnv", String.class);
			m.setAccessible(true);

			// when
			final Map<String, String> env = (Map<String, String>) m.invoke(SseProxyControllerTests.this.controller,
					"  ");

			// then
			assertThat(env).isEmpty();
		}

	}

	@Nested
	@DisplayName("D8 owner-check on existing session")
	class OwnerCheck {

		@Test
		@Story("Session ownership")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMessage() returns 404 when the session owner differs from the caller")
		void postMessage_foreignOwner_returns404() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			session.bindProfile("owner-A", "profile-1");
			given(SseProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			given(resolver.resolve(any(), any())).willReturn("owner-B");
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final SseProxyController wired = wiredController(store, resolver);
			final JsonNode body = SseProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Void> response = wired.postMessage("s1", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Session ownership")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMessage() returns 404 when the caller owner cannot be resolved")
		void postMessage_nullCallerOwner_returns404() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			session.bindProfile("owner-A", "profile-1");
			given(SseProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			given(resolver.resolve(any(), any())).willReturn(null);
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final SseProxyController wired = wiredController(store, resolver);
			final JsonNode body = SseProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Void> response = wired.postMessage("s1", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Session ownership")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMessage() allows access when session has no bound owner")
		void postMessage_noBoundOwner_returns202() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			given(SseProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			given(resolver.resolve(any(), any())).willReturn("owner-A");
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final SseProxyController wired = wiredController(store, resolver);
			final JsonNode body = SseProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Void> response = wired.postMessage("s1", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

		@Test
		@Story("Session ownership")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMessage() allows access when session owner matches caller")
		void postMessage_sameOwner_returns202() throws Exception {
			// given
			final ProxySession session = newSession("s1", mock(McpClientTransport.class));
			session.bindProfile("owner-A", "profile-1");
			given(SseProxyControllerTests.this.registry.get("s1")).willReturn(session);
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			given(resolver.resolve(any(), any())).willReturn("owner-A");
			final AuthProfileStore store = mock(AuthProfileStore.class);
			final SseProxyController wired = wiredController(store, resolver);
			final JsonNode body = SseProxyControllerTests.this.objectMapper
				.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			final ResponseEntity<Void> response = wired.postMessage("s1", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

	}

	@Nested
	@DisplayName("D8 legacy-session WARN")
	class LegacySessionWarn {

		@Test
		@Story("Legacy session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("repeat access to the same owner-less session logs the legacy WARN exactly once")
		void legacySession_repeatAccess_logsWarnOnce() throws Exception {
			// given - an unbound (legacy) session
			final ProxySession session = newSession("s-legacy", mock(McpClientTransport.class));
			given(SseProxyControllerTests.this.registry.get("s-legacy")).willReturn(session);
			final ch.qos.logback.classic.Logger warnerLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
				.getLogger(io.inspector.mcp.core.proxy.LegacySessionAccessWarner.class);
			final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
			appender.start();
			warnerLogger.addAppender(appender);
			try {
				final JsonNode body = SseProxyControllerTests.this.objectMapper
					.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

				// when - the same legacy session is touched repeatedly
				SseProxyControllerTests.this.controller.postMessage("s-legacy", body);
				SseProxyControllerTests.this.controller.postMessage("s-legacy", body);
				SseProxyControllerTests.this.controller.postMessage("s-legacy", body);

				// then - exactly one WARN was emitted for the session
				final long warns = appender.list.stream()
					.filter((e) -> e.getFormattedMessage() != null
							&& e.getFormattedMessage().contains("legacy session without owner binding"))
					.count();
				assertThat(warns).isEqualTo(1);
			}
			finally {
				warnerLogger.detachAppender(appender);
			}
		}

	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("constructor falls back to a default JsonMapper when none supplied")
		void constructor_withNullMapper_usesDefault() {
			// given / when
			final SseProxyController c = new SseProxyController(SseProxyControllerTests.this.registry,
					SseProxyControllerTests.this.transportFactory, SseProxyControllerTests.this.mcpProxy, null,
					SseProxyControllerTests.this.properties);

			// then
			assertThat(c).isNotNull();
		}

	}

	@Nested
	@DisplayName("D8 auth-profile session bring-up")
	class AuthProfileSession {

		@Test
		@Story("Auth profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with a profileId but no wired auth store/resolver throws IllegalStateException")
		void openSse_withProfileButUnwired_throwsIllegalState() {
			// given — the default controller is built without an auth-profile store

			// when / then
			assertThatThrownBy(() -> SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, "p", null, REQUEST))
				.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@Story("Auth profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with a foreign/unknown profileId emits a structured 400 error event and opens no session")
		void openSse_withUnknownProfile_emitsStructured400() throws Exception {
			// given — the owner resolves but the store has no such profile
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			final AuthProfileStore store = mock(AuthProfileStore.class);
			given(resolver.resolve(any(), any())).willReturn("owner-1");
			given(store.resolve(any(), any())).willReturn(java.util.Optional.empty());
			final SseProxyController wired = wiredController(store, resolver);

			// when
			final SseEmitter emitter = wired.openSse("sse", "http://target/sse", null, null, null, "foreign", null,
					REQUEST);

			// then — structured 400 and no session registered
			assertThat(bufferedFrames(emitter)).contains("bad_request");
			verify(SseProxyControllerTests.this.registry, never()).put(any());
		}

		@Test
		@Story("Auth profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() with an owned profile applies auth headers via the header-aware transport overload")
		void openSse_withOwnedProfile_usesAuthTransportOverload() throws Exception {
			// given — the profile resolves and binds
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			final AuthProfileStore store = mock(AuthProfileStore.class);
			given(resolver.resolve(any(), any())).willReturn("owner-1");
			given(store.resolve(any(), any())).willReturn(java.util.Optional.of(new BearerProfile("p", "tok")));
			given(store.bind(any(), any(), any())).willReturn(true);
			given(SseProxyControllerTests.this.transportFactory.buildSseWithAuth(any(), any(), any()))
				.willReturn(mock(McpClientTransport.class));
			final SseProxyController wired = wiredController(store, resolver);

			// when
			final SseEmitter emitter = wired.openSse("sse", "http://target/sse", null, null, null, "p", null, REQUEST);

			// then — the auth-bearing transport is used and the session registers
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.transportFactory).buildSseWithAuth(any(), any(), any());
			verify(store).bind(any(), any(), any());
			verify(SseProxyControllerTests.this.registry).put(any(ProxySession.class));
		}

		@Test
		@Story("Auth profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with a profile whose one-time bind is rejected emits a structured 400 and closes the session")
		void openSse_withBindRejected_emitsStructured400AndClosesSession() throws Exception {
			// given — the profile resolves but the one-time bind is rejected
			final ServletSessionOwnerResolver resolver = mock(ServletSessionOwnerResolver.class);
			final AuthProfileStore store = mock(AuthProfileStore.class);
			given(resolver.resolve(any(), any())).willReturn("owner-1");
			given(store.resolve(any(), any())).willReturn(java.util.Optional.of(new BearerProfile("p", "tok")));
			given(store.bind(any(), any(), any())).willReturn(false);
			given(SseProxyControllerTests.this.transportFactory.buildSseWithAuth(any(), any(), any()))
				.willReturn(mock(McpClientTransport.class));
			final SseProxyController wired = wiredController(store, resolver);

			// when
			final SseEmitter emitter = wired.openSse("sse", "http://target/sse", null, null, null, "p", null, REQUEST);

			// then — structured 400, the freshly opened session is torn down, nothing
			// registered
			assertThat(bufferedFrames(emitter)).contains("bad_request");
			verify(SseProxyControllerTests.this.registry).removeAndClose(any(String.class));
			verify(SseProxyControllerTests.this.registry, never()).put(any());
		}

	}

	@Nested
	@DisplayName("D3 proxy-start failure mapping")
	class ProxyStartFailure {

		@Test
		@Story("Proxy start failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() whose proxy fails to start with an unclassifiable non-death failure ends the stream quietly: no structured event, and no completeWithError Boot /error dispatch")
		void openSse_startFailure_unclassified_endsQuietly() throws Exception {
			// given: the proxy pump fails with a failure carrying no HTTP status and no
			// death classification (neither mapped nor upstream death)
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));
			given(SseProxyControllerTests.this.mcpProxy.start(any()))
				.willReturn(Mono.error(new RuntimeException("boom")));

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, REQUEST);

			// then: no structured event is invented for an unclassified failure, and the
			// legacy completeWithError (whose Boot /error dispatch cannot render JSON
			// into
			// text/event-stream, HttpMessageNotWritableException) is not used either
			assertThat(bufferedFrames(emitter)).as("unclassified failures emit no DTO frame")
				.doesNotContain("upstream_");
		}

		@Test
		@Story("Proxy start failure")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() whose proxy fails to start against a dead upstream emits the upstream_unreachable error event")
		void openSse_startFailure_deadUpstream_emitsUpstreamUnreachableEvent() throws Exception {
			// given: the upstream connect is refused; no HTTP status, classified as death
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));
			given(SseProxyControllerTests.this.mcpProxy.start(any()))
				.willReturn(Mono.error(new java.net.ConnectException("Connection refused")));

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, REQUEST);

			// then: the browser learns WHY the stream died: the fallback death DTO rides
			// the named error event before the stream closes
			assertThat(bufferedFrames(emitter)).contains("upstream_unreachable");
		}

		@Test
		@Story("Proxy start failure")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() whose proxy fails to start with an auth failure emits a structured unauthorized error event")
		void openSse_startFailure_authMappable_emitsStructuredError() throws Exception {
			// given — the proxy pump fails with a 401 authorization failure
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));
			given(SseProxyControllerTests.this.mcpProxy.start(any()))
				.willReturn(Mono.error(new RuntimeException("401 Unauthorized")));

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, REQUEST);

			// then — the structured unauthorized DTO is emitted
			assertThat(bufferedFrames(emitter)).contains("unauthorized");
		}

	}

	@Nested
	@DisplayName("transport-build failure mapping")
	class TransportBuildFailure {

		@Test
		@Story("Transport build failure")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() whose target transport cannot be built with a mappable auth failure emits the structured unauthorized error event instead of the legacy completeWithError")
		void openSse_buildFailure_mappable_emitsStructuredError() throws Exception {
			// given: the factory rejects the transport build with a 401-classified
			// failure
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willThrow(new RuntimeException("401 Unauthorized"));

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, REQUEST);

			// then: the browser learns WHY the stream died: the mapped DTO rides the
			// named
			// error event; a session is never registered for a transport that was not
			// built
			assertThat(bufferedFrames(emitter)).contains("unauthorized");
			verify(SseProxyControllerTests.this.registry, never()).put(any());
		}

		@Test
		@Story("Transport build failure")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() whose target transport build fails on a dead upstream emits the upstream_unreachable error event")
		void openSse_buildFailure_deadUpstream_emitsUpstreamUnreachableEvent() throws Exception {
			// given: the upstream connect is refused while the transport is being built
			// (the refusal surfaces as a runtime wrapper, as the SDK transport rethrows)
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willThrow(new RuntimeException("connect failed", new java.net.ConnectException("Connection refused")));

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, REQUEST);

			// then: the fallback death DTO rides the named error event before the stream
			// closes
			assertThat(bufferedFrames(emitter)).contains("upstream_unreachable");
		}

		@Test
		@Story("Transport build failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() whose target transport build fails with an unclassifiable failure ends the stream quietly without the legacy completeWithError")
		void openSse_buildFailure_unclassified_endsQuietly() throws Exception {
			// given: the build fails with a failure carrying no HTTP status and no death
			// classification (neither mapped nor upstream death)
			given(SseProxyControllerTests.this.transportFactory.buildSse(any(URI.class)))
				.willThrow(new RuntimeException("boom"));

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null, null, REQUEST);

			// then: no structured event is invented for an unclassified failure, and the
			// stream ends quietly: complete == true and no failure recorded. The legacy
			// completeWithError would leave a failure on the emitter and dispatch to
			// Boot /error, whose JSON body has no converter for text/event-stream
			assertThat(bufferedFrames(emitter)).as("unclassified failures emit no DTO frame")
				.doesNotContain("upstream_");
			assertThat(emitterState(emitter, "complete")).as("the stream is still completed").isEqualTo(true);
			assertThat(emitterState(emitter, "failure")).as("legacy completeWithError is not used").isNull();
			verify(SseProxyControllerTests.this.registry, never()).put(any());
		}

	}

}
