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

package io.inspector.mcp.webflux.proxy;

import java.net.URI;
import java.util.Optional;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthHeaders;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.BearerProfile;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.webflux.auth.ReactiveSessionOwnerResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the D8/D9 auth-profile connect paths of {@link ProxyHandler}: opening an
 * SSE or Streamable-HTTP proxy session against an owner-scoped auth profile, plus the
 * rejection of unknown profiles, rejected one-time binds and unwired auth support.
 */
@Epic("MCP Inspector WebFlux")
@Feature("ProxyHandler auth-profile connect (D8/D9)")
class ProxyHandlerAuthProfileTests {

	private static final String OWNER = "owner-1";

	private static final String PROFILE_ID = "pid-1";

	private static final HandlerStrategies STRATEGIES = HandlerStrategies.withDefaults();

	private final ProxySessionRegistry registry = mock(ProxySessionRegistry.class);

	private final ProxyTransportFactory transportFactory = mock(ProxyTransportFactory.class);

	private final McpProxy mcpProxy = mock(McpProxy.class);

	private final TransportDetector transportDetector = mock(TransportDetector.class);

	private final JsonMapper objectMapper = new JsonMapper();

	private final ReactiveSessionOwnerResolver sessionOwnerResolver = mock(ReactiveSessionOwnerResolver.class);

	private final AuthProfileStore authProfileStore = mock(AuthProfileStore.class);

	private final OAuth2ClientCredentialsTokenManager ccTokenManager = mock(OAuth2ClientCredentialsTokenManager.class);

	private final OAuth2AuthCodeTokenExchanger authCodeExchanger = mock(OAuth2AuthCodeTokenExchanger.class);

	private ProxyHandler handler() {
		final McpInspectorProperties properties = new McpInspectorProperties();
		given(this.mcpProxy.start(any())).willReturn(Mono.empty());
		return new ProxyHandler(this.registry, this.transportFactory, this.mcpProxy, this.transportDetector,
				this.objectMapper, properties, this.sessionOwnerResolver, this.authProfileStore, this.ccTokenManager,
				this.authCodeExchanger);
	}

	private ProxyHandler unwiredHandler() {
		given(this.mcpProxy.start(any())).willReturn(Mono.empty());
		return new ProxyHandler(this.registry, this.transportFactory, this.mcpProxy, this.transportDetector,
				this.objectMapper);
	}

	private static ServerRequest toServerRequest(final MockServerHttpRequest request) {
		final MockServerWebExchange exchange = MockServerWebExchange.from(request);
		return ServerRequest.create(exchange, STRATEGIES.messageReaders());
	}

	private static ServerRequest sseRequest(final String profileId) {
		final StringBuilder uri = new StringBuilder("/mcp-inspector-api/sse?transportType=sse&url=http://up/sse");
		if (profileId != null) {
			uri.append("&profileId=").append(profileId);
		}
		return toServerRequest(MockServerHttpRequest.get(uri.toString()).build());
	}

	private static ServerRequest streamableRequest(final String profileId) {
		final StringBuilder uri = new StringBuilder("/mcp-inspector-api/mcp?profileId=");
		uri.append((profileId != null) ? profileId : "");
		return toServerRequest(MockServerHttpRequest.post(uri.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
	}

	@Nested
	@DisplayName("openSse() with an auth profile")
	class OpenSseWithProfile {

		@Test
		@Story("SSE proxy open with profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("openSse() with a bound bearer profile resolves the profile into headers and opens the session")
		void openSse_withBoundBearerProfile_appliesProfileAuth() {
			// given
			given(ProxyHandlerAuthProfileTests.this.sessionOwnerResolver.resolve(any())).willReturn(OWNER);
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.resolve(OWNER, PROFILE_ID))
				.willReturn(Optional.of(new BearerProfile("prod", "tok")));
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.bind(eq(OWNER), eq(PROFILE_ID), any()))
				.willReturn(true);
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerAuthProfileTests.this.transportFactory.openSseWithAuth(any(URI.class),
					any(AuthHeaders.class), any()))
				.willReturn(target);

			// when
			final ServerResponse response = ProxyHandlerAuthProfileTests.this.handler()
				.openSse(sseRequest(PROFILE_ID))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
			verify(ProxyHandlerAuthProfileTests.this.authProfileStore).resolve(OWNER, PROFILE_ID);
			verify(ProxyHandlerAuthProfileTests.this.authProfileStore).bind(eq(OWNER), eq(PROFILE_ID), any());
			verify(ProxyHandlerAuthProfileTests.this.transportFactory).openSseWithAuth(any(URI.class),
					any(AuthHeaders.class), any());
			verify(ProxyHandlerAuthProfileTests.this.registry).put(any(ProxySession.class));
		}

		@Test
		@Story("SSE proxy open with profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() rejects an unknown or foreign profile with a structured 400")
		void openSse_withUnknownProfile_returns400() {
			// given
			given(ProxyHandlerAuthProfileTests.this.sessionOwnerResolver.resolve(any())).willReturn(OWNER);
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.resolve(OWNER, "pid-unknown"))
				.willReturn(Optional.empty());

			// when
			final ServerResponse response = ProxyHandlerAuthProfileTests.this.handler()
				.openSse(sseRequest("pid-unknown"))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			verify(ProxyHandlerAuthProfileTests.this.authProfileStore, org.mockito.Mockito.never()).bind(eq(OWNER),
					eq("pid-unknown"), any());
		}

		@Test
		@Story("SSE proxy open with profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() rejects a rejected one-time bind (reuse / foreign id) with a structured 400")
		void openSse_withRejectedBind_returns400() {
			// given
			given(ProxyHandlerAuthProfileTests.this.sessionOwnerResolver.resolve(any())).willReturn(OWNER);
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.resolve(OWNER, PROFILE_ID))
				.willReturn(Optional.of(new BearerProfile("prod", "tok")));
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.bind(eq(OWNER), eq(PROFILE_ID), any()))
				.willReturn(false);

			// when
			final ServerResponse response = ProxyHandlerAuthProfileTests.this.handler()
				.openSse(sseRequest(PROFILE_ID))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("SSE proxy open with profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("openSse() with a profile but no auth wiring returns a not-wired 400")
		void openSse_withoutAuthWiring_returns400() {
			// given — the 5-arg handler has no authProfileStore / sessionOwnerResolver

			// when
			final ServerResponse response = ProxyHandlerAuthProfileTests.this.unwiredHandler()
				.openSse(sseRequest(PROFILE_ID))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			verify(ProxyHandlerAuthProfileTests.this.registry, org.mockito.Mockito.never())
				.put(any(ProxySession.class));
		}

	}

	@Nested
	@DisplayName("postMcp() with an auth profile")
	class PostMcpWithProfile {

		@Test
		@Story("Streamable-HTTP open with profile")
		@Severity(SeverityLevel.CRITICAL)
		@Description("postMcp() with a bound bearer profile resolves it into headers and opens the streamable session")
		void postMcp_withBoundBearerProfile_appliesProfileAuth() {
			// given
			given(ProxyHandlerAuthProfileTests.this.sessionOwnerResolver.resolve(any())).willReturn(OWNER);
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.resolve(OWNER, PROFILE_ID))
				.willReturn(Optional.of(new BearerProfile("prod", "tok")));
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.bind(eq(OWNER), eq(PROFILE_ID), any()))
				.willReturn(true);
			final McpClientTransport target = mock(McpClientTransport.class);
			given(ProxyHandlerAuthProfileTests.this.transportFactory.openStreamableWithAuth(any(URI.class),
					any(AuthHeaders.class), any()))
				.willReturn(target);

			// when — notification frame (no id) takes the fire-and-forget 202 path
			final ServerResponse response = ProxyHandlerAuthProfileTests.this.handler()
				.postMcp(streamableRequest(PROFILE_ID))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.ACCEPTED);
			verify(ProxyHandlerAuthProfileTests.this.authProfileStore).bind(eq(OWNER), eq(PROFILE_ID), any());
			verify(ProxyHandlerAuthProfileTests.this.transportFactory).openStreamableWithAuth(any(URI.class),
					any(AuthHeaders.class), any());
			verify(ProxyHandlerAuthProfileTests.this.registry).put(any(ProxySession.class));
		}

		@Test
		@Story("Streamable-HTTP open with profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() rejects an unknown or foreign profile with a structured 400")
		void postMcp_withUnknownProfile_returns400() {
			// given
			given(ProxyHandlerAuthProfileTests.this.sessionOwnerResolver.resolve(any())).willReturn(OWNER);
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.resolve(OWNER, "pid-unknown"))
				.willReturn(Optional.empty());

			// when
			final ServerResponse response = ProxyHandlerAuthProfileTests.this.handler()
				.postMcp(streamableRequest("pid-unknown"))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			verify(ProxyHandlerAuthProfileTests.this.authProfileStore, org.mockito.Mockito.never()).bind(eq(OWNER),
					eq("pid-unknown"), any());
		}

		@Test
		@Story("Streamable-HTTP open with profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() rejects a rejected one-time bind (reuse / foreign id) with a structured 400")
		void postMcp_withRejectedBind_returns400() {
			// given
			given(ProxyHandlerAuthProfileTests.this.sessionOwnerResolver.resolve(any())).willReturn(OWNER);
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.resolve(OWNER, PROFILE_ID))
				.willReturn(Optional.of(new BearerProfile("prod", "tok")));
			given(ProxyHandlerAuthProfileTests.this.authProfileStore.bind(eq(OWNER), eq(PROFILE_ID), any()))
				.willReturn(false);

			// when
			final ServerResponse response = ProxyHandlerAuthProfileTests.this.handler()
				.postMcp(streamableRequest(PROFILE_ID))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@Story("Streamable-HTTP open with profile")
		@Severity(SeverityLevel.NORMAL)
		@Description("postMcp() with a profile but no auth wiring returns a not-wired 400")
		void postMcp_withoutAuthWiring_returns400() {
			// given — the 5-arg handler has no authProfileStore / sessionOwnerResolver

			// when
			final ServerResponse response = ProxyHandlerAuthProfileTests.this.unwiredHandler()
				.postMcp(streamableRequest(PROFILE_ID))
				.block();

			// then
			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

	}

}
