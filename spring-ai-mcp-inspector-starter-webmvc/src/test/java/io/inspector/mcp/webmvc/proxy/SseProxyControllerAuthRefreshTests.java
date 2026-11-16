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

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.AuthorizationCaptureTransport;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.proxy.ScriptedTokenServer;
import io.inspector.mcp.webmvc.auth.ServletSessionOwnerResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Layer-level pinning for issue #375: after the real one-retry refresh inside
 * {@link McpProxy}, the retried outgoing request carries the fresh Authorization token,
 * when the session is driven through the production {@link SseProxyController} open path
 * (controller-resolved OAuth2 profile, controller-created shared authorization reference,
 * registry + postMessage entry point).
 *
 * <p>
 * Reuses the auth-refresh stand from the core test-jar: the {@link ScriptedTokenServer}
 * serves token A on the first exchange and token B on the refresh exchange, and the
 * {@link AuthorizationCaptureTransport} records every outgoing Authorization header
 * through the transport the controller built, so the frame really travels
 * SseProxyController -> ProxySession -> McpProxy -> transport.
 *
 * @author Artem Simeshin
 */
@Epic("MCP Inspector WebMVC")
@Feature("SseProxyController auth refresh (fresh header after retry)")
class SseProxyControllerAuthRefreshTests {

	private static final String OWNER = "owner-1";

	private static final MockHttpServletRequest REQUEST = new MockHttpServletRequest();

	private ScriptedTokenServer tokenServer;

	private OAuth2ClientCredentialsTokenManager ccTokenManager;

	private AuthProfileStore authProfileStore;

	private String profileId;

	private final JsonMapper objectMapper = new JsonMapper();

	private final AtomicReference<AuthorizationCaptureTransport> capturedTransport = new AtomicReference<>();

	@BeforeEach
	void setUp() throws IOException {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(REQUEST));
		this.tokenServer = new ScriptedTokenServer();
		this.tokenServer.start();
		this.tokenServer.issueTokensInSequence("tok-a", "tok-b");
		this.ccTokenManager = new OAuth2ClientCredentialsTokenManager(
				java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), null);
		this.authProfileStore = new AuthProfileStore();
		final OAuth2Profile cc = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS, this.tokenServer.url(),
				"client-1", "secret-1", "mcp.read", null, null, null, null);
		this.profileId = this.authProfileStore.register(OWNER, cc);
		assertThat(this.profileId).isNotBlank();
		// production registers OAuth2 profiles with the token manager at profile
		// creation; AuthHeaders.resolve then acquires through the manager
		this.ccTokenManager.acquire(this.profileId, cc);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
		this.tokenServer.stop();
	}

	@Test
	@Story("Fresh header after refresh")
	@Severity(SeverityLevel.CRITICAL)
	@Description("a 401 on the first outgoing frame drives the real McpProxy refresh through the "
			+ "SseProxyController open path, and the retried outgoing request captured at the "
			+ "transport carries the refreshed token: [prologue(A), send(A), retry(B)]")
	void afterRefresh_retriedOutgoingRequestCarriesFreshHeader() {
		// given: a controller wired like production, with the transport factory
		// returning the capturing transport around the controller-created reference
		final ProxySessionRegistry registry = spy(new ProxySessionRegistry());
		final AtomicReference<ProxySession> capturedSession = new AtomicReference<>();
		org.mockito.BDDMockito.willAnswer((invocation) -> {
			capturedSession.set(invocation.getArgument(0));
			return invocation.callRealMethod();
		}).given(registry).put(any(ProxySession.class));
		final ProxyTransportFactory transportFactory = mock(ProxyTransportFactory.class);
		given(transportFactory.buildSseWithAuth(any(URI.class), any(), any())).willAnswer((invocation) -> {
			final AuthorizationCaptureTransport transport = new AuthorizationCaptureTransport(
					invocation.getArgument(2));
			SseProxyControllerAuthRefreshTests.this.capturedTransport.set(transport);
			return transport;
		});
		final ServletSessionOwnerResolver sessionOwnerResolver = mock(ServletSessionOwnerResolver.class);
		given(sessionOwnerResolver.resolve(any(), any())).willReturn(OWNER);
		final McpProxy mcpProxy = new McpProxy(this.objectMapper, this.authProfileStore, this.ccTokenManager, null);
		final SseProxyController controller = new SseProxyController(registry, transportFactory, mcpProxy,
				this.objectMapper, new McpInspectorProperties(), null, null, sessionOwnerResolver,
				this.authProfileStore, this.ccTokenManager, null);

		// when: the session opens through the controller (prologue goes out with token
		// A), the first frame 401s, the one-retry path refreshes to token B and re-sends
		final var emitter = controller.openSse("sse", "http://up/sse", null, null, null, this.profileId, null, REQUEST);
		assertThat(emitter).isNotNull();
		assertThat(this.capturedTransport.get()).isNotNull();

		this.capturedTransport.get().failNextSendWithUnauthorized();
		final JsonMapper mapper = new JsonMapper();
		final JsonNode frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/list");
		final ResponseEntity<Void> posted = controller.postMessage(capturedSession.get().sessionId(), frame);

		// then: the frame was accepted, and the call order at the transport is
		// prologue(A), send(A), retry(B); the refresh reached the token server twice
		assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		awaitCapturedCalls(this.capturedTransport.get(), 3);
		assertThat(this.capturedTransport.get().authorizationHeaders()).containsExactly("Bearer tok-a", "Bearer tok-a",
				"Bearer tok-b");
		assertThat(this.tokenServer.requestCount()).isEqualTo(2);
	}

	private static void awaitCapturedCalls(final AuthorizationCaptureTransport transport, final int expected) {
		final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		// a scripted 401 surfaces as the SDK authorization exception inside the relay;
		// the relay maps it into the retry, so only the capture count is awaited here
		while (System.nanoTime() < deadline && transport.capturedCalls().size() < expected) {
			try {
				Thread.sleep(10);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		assertThat(transport.capturedCalls()).hasSize(expected);
	}

}
