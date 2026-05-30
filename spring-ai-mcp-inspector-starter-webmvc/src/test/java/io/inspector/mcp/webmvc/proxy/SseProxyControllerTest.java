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

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SseProxyController}. Collaborators are mocked; assertions cover
 * the {@code POST /message} status branches, transport-type selection (incl. validation
 * errors) and session bring-up via the registry / proxy.
 */
@Epic("WebMvc Inspector")
@Feature("SseProxyController")
class SseProxyControllerTest {

	private ProxySessionRegistry registry;

	private ProxyTransportFactory transportFactory;

	private McpProxy mcpProxy;

	private ObjectMapper objectMapper;

	private McpInspectorProperties properties;

	private SseProxyController controller;

	@BeforeEach
	void setUp() {
		registry = mock(ProxySessionRegistry.class);
		transportFactory = mock(ProxyTransportFactory.class);
		mcpProxy = mock(McpProxy.class);
		objectMapper = new ObjectMapper();
		properties = new McpInspectorProperties();
		when(mcpProxy.start(any())).thenReturn(Mono.empty());
		controller = new SseProxyController(registry, transportFactory, mcpProxy, objectMapper, properties);
	}

	private ProxySession newSession(String id, McpClientTransport target) {
		Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(8);
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
			ProxySession session = newSession("s1", mock(McpClientTransport.class));
			when(registry.get("s1")).thenReturn(session);
			JsonNode body = objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

			// when
			ResponseEntity<Void> response = controller.postMessage("s1", body);

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
			when(registry.get("missing")).thenReturn(null);
			JsonNode body = objectMapper.readTree("{}");

			// when
			ResponseEntity<Void> response = controller.postMessage("missing", body);

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@Story("Browser to target frame")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMessage() returns 500 when the sink rejects the emit (terminated session)")
		void postMessage_whenEmitFails_returns500() throws Exception {
			// given
			ProxySession session = newSession("s1", mock(McpClientTransport.class));
			session.browserToTarget().tryEmitComplete();
			when(registry.get("s1")).thenReturn(session);
			JsonNode body = objectMapper.readTree("{}");

			// when
			ResponseEntity<Void> response = controller.postMessage("s1", body);

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
			McpClientTransport target = mock(McpClientTransport.class);
			when(transportFactory.openSse(any(URI.class))).thenReturn(target);

			// when
			SseEmitter emitter = controller.openSse("sse", "http://target/sse", null, null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(transportFactory).openSse(URI.create("http://target/sse"));
			verify(registry).put(any(ProxySession.class));
			verify(mcpProxy).start(any(ProxySession.class));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with streamable-http builds a streamable transport")
		void openSse_withStreamableUrl_buildsStreamableTransport() {
			// given
			when(transportFactory.openStreamable(any(URI.class))).thenReturn(mock(McpClientTransport.class));

			// when
			controller.openSse("streamable-http", "http://target/mcp", null, null, null);

			// then
			verify(transportFactory).openStreamable(URI.create("http://target/mcp"));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with a missing url for sse never registers a session (transport build fails)")
		void openSse_withMissingUrl_doesNotRegisterSession() {
			// when
			SseEmitter emitter = controller.openSse("sse", null, null, null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(registry, never()).put(any());
			verify(mcpProxy, never()).start(any());
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with an unsupported transportType fails to build a transport and skips registration")
		void openSse_withUnsupportedType_doesNotRegisterSession() {
			// when
			SseEmitter emitter = controller.openSse("carrier-pigeon", "x", null, null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(registry, never()).put(any());
		}

		@Test
		@Story("Open stdio session")
		@Severity(SeverityLevel.NORMAL)
		@Description("openStdio() builds a stdio transport from the command and registers a session")
		void openStdio_withCommand_buildsStdioTransportAndRegisters() {
			// given
			when(transportFactory.openStdio(any(), any())).thenReturn(mock(McpClientTransport.class));

			// when
			SseEmitter emitter = controller.openStdio("python", "-m server", "{\"KEY\":\"v\"}");

			// then
			assertThat(emitter).isNotNull();
			verify(transportFactory).openStdio(any(), any());
			verify(registry).put(any(ProxySession.class));
		}

		@Test
		@Story("Open stdio session")
		@Severity(SeverityLevel.MINOR)
		@Description("openStdio() with null args and null env still builds the transport (no args appended, empty env)")
		void openStdio_withNullArgsAndEnv_buildsTransport() {
			// given
			when(transportFactory.openStdio(any(), any())).thenReturn(mock(McpClientTransport.class));

			// when
			SseEmitter emitter = controller.openStdio("python", null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(transportFactory).openStdio(any(), any());
		}

		@Test
		@Story("Open stdio session")
		@Severity(SeverityLevel.MINOR)
		@Description("openStdio() with a blank command fails to build a transport and skips registration")
		void openStdio_withBlankCommand_doesNotRegisterSession() {
			// when
			SseEmitter emitter = controller.openStdio("   ", null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(registry, never()).put(any());
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.MINOR)
		@Description("openSse() with a null transportType defaults to the SSE transport")
		void openSse_withNullType_defaultsToSse() {
			// given
			when(transportFactory.openSse(any(URI.class))).thenReturn(mock(McpClientTransport.class));

			// when
			controller.openSse(null, "http://target/sse", null, null, null);

			// then
			verify(transportFactory).openSse(URI.create("http://target/sse"));
		}

		@Test
		@Story("Open SSE session")
		@Severity(SeverityLevel.MINOR)
		@Description("openSse() with a blank streamable url skips registration (transport build fails)")
		void openSse_withBlankStreamableUrl_doesNotRegister() {
			// when
			SseEmitter emitter = controller.openSse("streamable-http", "", null, null, null);

			// then
			assertThat(emitter).isNotNull();
			verify(registry, never()).put(any());
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
			Method m = SseProxyController.class.getDeclaredMethod("parseArgv", String.class, String.class);
			m.setAccessible(true);

			// when
			List<String> argv = (List<String>) m.invoke(null, "python", "-m server --flag");

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
			Method m = SseProxyController.class.getDeclaredMethod("parseEnv", String.class);
			m.setAccessible(true);

			// when
			Map<String, String> env = (Map<String, String>) m.invoke(controller, "{\"A\":\"1\",\"B\":\"2\"}");

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
			Method m = SseProxyController.class.getDeclaredMethod("parseEnv", String.class);
			m.setAccessible(true);

			// when
			Map<String, String> env = (Map<String, String>) m.invoke(controller, "{not-json");

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
			Method m = SseProxyController.class.getDeclaredMethod("parseEnv", String.class);
			m.setAccessible(true);

			// when
			Map<String, String> env = (Map<String, String>) m.invoke(controller, "  ");

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
			SseProxyController c = new SseProxyController(registry, transportFactory, mcpProxy, null, properties);

			// then
			assertThat(c).isNotNull();
		}

	}

}
