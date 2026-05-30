/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux.router;

import java.util.List;

import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;
import io.modelcontextprotocol.client.McpSyncClient;
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
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the package-private {@link SessionContext}. */
@Epic("MCP Inspector WebFlux")
@Feature("SessionContext per-session state")
class SessionContextTest {

	private McpSyncClient client;

	private Sinks.Many<ServerSentEvent<String>> sink;

	private SessionContext context;

	@BeforeEach
	void setUp() {
		client = mock(McpSyncClient.class);
		sink = Sinks.many().multicast().onBackpressureBuffer(16, false);
		context = new SessionContext(client, sink);
	}

	@Nested
	@DisplayName("replaceRoots()")
	class ReplaceRoots {

		@Test
		@Story("Roots state")
		@Severity(SeverityLevel.NORMAL)
		@Description("replaceRoots() clears the previous roots and stores the new list")
		void replaceRoots_withNewList_replacesPreviousRoots() {
			// given
			context.replaceRoots(List.of(new RootDto("file:///a", "a")));

			// when
			context.replaceRoots(List.of(new RootDto("file:///b", "b"), new RootDto("file:///c", "c")));

			// then
			assertThat(context.roots()).hasSize(2).extracting(RootDto::uri).containsExactly("file:///b", "file:///c");
		}

		@Test
		@Story("Roots state")
		@Severity(SeverityLevel.MINOR)
		@Description("replaceRoots(null) clears the roots list to empty")
		void replaceRoots_withNull_clearsRoots() {
			// given
			context.replaceRoots(List.of(new RootDto("file:///a", "a")));

			// when
			context.replaceRoots(null);

			// then
			assertThat(context.roots()).isEmpty();
		}

	}

	@Nested
	@DisplayName("OAuth state accessors")
	class OAuthState {

		@Test
		@Story("OAuth state")
		@Severity(SeverityLevel.NORMAL)
		@Description("OAuth state / client / redirect / token-endpoint / token round-trip through their accessors")
		void oauthAccessors_setThenGet_returnStoredValues() {
			// given
			OAuthTokenResponse token = new OAuthTokenResponse("at", "Bearer", 60L, "rt", "openid");

			// when
			context.oauthState("state-1");
			context.oauthClientId("client-1");
			context.oauthRedirectUri("https://app/cb");
			context.oauthTokenEndpoint("https://idp/token");
			context.oauthToken(token);

			// then
			assertThat(context.oauthState()).isEqualTo("state-1");
			assertThat(context.oauthClientId()).isEqualTo("client-1");
			assertThat(context.oauthRedirectUri()).isEqualTo("https://app/cb");
			assertThat(context.oauthTokenEndpoint()).isEqualTo("https://idp/token");
			assertThat(context.oauthToken()).isEqualTo(token);
		}

	}

	@Nested
	@DisplayName("closeQuietly()")
	class CloseQuietly {

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.CRITICAL)
		@Description("closeQuietly() completes the SSE sink, clears pending requests and closes the MCP client gracefully")
		void closeQuietly_always_completesSinkAndClosesClient() {
			// given
			when(client.closeGracefully()).thenReturn(true);

			// when
			context.closeQuietly();

			// then
			assertThat(context.pendingServerRequests().size()).isZero();
			assertThat(sink.currentSubscriberCount()).isZero();
			verify(client).closeGracefully();
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("closeQuietly() falls back to close() when graceful shutdown throws")
		void closeQuietly_whenGracefulCloseThrows_fallsBackToClose() {
			// given
			when(client.closeGracefully()).thenThrow(new RuntimeException("graceful failed"));

			// when
			context.closeQuietly();

			// then
			verify(client).closeGracefully();
			verify(client).close();
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("closeQuietly() completes the sink and clears pending requests even when no MCP client was wired (client == null branch)")
		void closeQuietly_whenClientNull_completesSinkWithoutClientCall() {
			// given — a context built with no MCP client (the client == null branch)
			Sinks.Many<ServerSentEvent<String>> nullClientSink = Sinks.many()
				.multicast()
				.onBackpressureBuffer(16, false);
			SessionContext clientless = new SessionContext(null, nullClientSink);
			clientless.pendingServerRequests().create("pending-1");

			// when
			clientless.closeQuietly();

			// then — no NPE, sink completed, pending requests cleared
			assertThat(clientless.pendingServerRequests().size()).isZero();
			assertThat(nullClientSink.currentSubscriberCount()).isZero();
			assertThat(clientless.client()).isNull();
		}

	}

	@Nested
	@DisplayName("accessors")
	class Accessors {

		@Test
		@Story("State")
		@Severity(SeverityLevel.MINOR)
		@Description("client() and sink() expose the collaborators supplied at construction")
		void accessors_returnConstructorCollaborators() {
			// when & then
			assertThat(context.client()).isSameAs(client);
			assertThat(context.sink()).isSameAs(sink);
			assertThat(context.pendingServerRequests()).isNotNull();
		}

	}

}
