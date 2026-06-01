/*
 * Copyright 2025-present the original author or authors.
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link SseProxyController}. Collaborators are mocked; assertions cover
 * the {@code POST /message} status branches, transport-type selection (incl. validation
 * errors) and session bring-up via the registry / proxy.
 */
@Epic("WebMvc Inspector")
@Feature("SseProxyController")
class SseProxyControllerTests {

	private ProxySessionRegistry registry;

	private ProxyTransportFactory transportFactory;

	private McpProxy mcpProxy;

	private ObjectMapper objectMapper;

	private McpInspectorProperties properties;

	private SseProxyController controller;

	@BeforeEach
	void setUp() {
		this.registry = mock(ProxySessionRegistry.class);
		this.transportFactory = mock(ProxyTransportFactory.class);
		this.mcpProxy = mock(McpProxy.class);
		this.objectMapper = new ObjectMapper();
		this.properties = new McpInspectorProperties();
		given(this.mcpProxy.start(any())).willReturn(Mono.empty());
		this.controller = new SseProxyController(this.registry, this.transportFactory, this.mcpProxy, this.objectMapper,
				this.properties);
	}

	private ProxySession newSession(final String id, final McpClientTransport target) {
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(8);
		return new ProxySession(id, target, browserToTarget, targetToBrowser);
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
	class OpenSse {

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() builds an SSE transport, registers a session and starts the proxy")
		void openSse_withSseUrl_registersSessionAndStartsProxy() {
			// given
			final McpClientTransport target = mock(McpClientTransport.class);
			given(SseProxyControllerTests.this.transportFactory.openSse(any(URI.class))).willReturn(target);

			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", "http://target/sse", null,
					null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.transportFactory).openSse(URI.create("http://target/sse"));
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
			SseProxyControllerTests.this.controller.openSse("streamable-http", "http://target/mcp", null, null, null);

			// then
			verify(SseProxyControllerTests.this.transportFactory).openStreamable(URI.create("http://target/mcp"));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with a missing url for sse never registers a session (transport build fails)")
		void openSse_withMissingUrl_doesNotRegisterSession() {
			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("sse", null, null, null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.registry, never()).put(any());
			verify(SseProxyControllerTests.this.mcpProxy, never()).start(any());
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with an unsupported transportType fails to build a transport and skips registration")
		void openSse_withUnsupportedType_doesNotRegisterSession() {
			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("carrier-pigeon", "x", null,
					null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.registry, never()).put(any());
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
					"{\"KEY\":\"v\"}");

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
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openStdio("python", null, null);

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
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openStdio("   ", null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.registry, never()).put(any());
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.MINOR)
		@Description("openSse() with a null transportType defaults to the SSE transport")
		void openSse_withNullType_defaultsToSse() {
			// given
			given(SseProxyControllerTests.this.transportFactory.openSse(any(URI.class)))
				.willReturn(mock(McpClientTransport.class));

			// when
			SseProxyControllerTests.this.controller.openSse(null, "http://target/sse", null, null, null);

			// then
			verify(SseProxyControllerTests.this.transportFactory).openSse(URI.create("http://target/sse"));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.MINOR)
		@Description("openSse() with a blank streamable url skips registration (transport build fails)")
		void openSse_withBlankStreamableUrl_doesNotRegister() {
			// when
			final SseEmitter emitter = SseProxyControllerTests.this.controller.openSse("streamable-http", "", null,
					null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(SseProxyControllerTests.this.registry, never()).put(any());
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
	@DisplayName("constructor")
	class Constructor {

		@Test
		@Story("Construction")
		@Severity(SeverityLevel.MINOR)
		@Description("constructor falls back to a default ObjectMapper when none supplied")
		void constructor_withNullMapper_usesDefault() {
			// given / when
			final SseProxyController c = new SseProxyController(SseProxyControllerTests.this.registry,
					SseProxyControllerTests.this.transportFactory, SseProxyControllerTests.this.mcpProxy, null,
					SseProxyControllerTests.this.properties);

			// then
			assertThat(c).isNotNull();
		}

	}

}
