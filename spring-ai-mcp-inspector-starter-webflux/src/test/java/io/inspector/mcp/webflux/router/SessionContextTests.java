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

package io.inspector.mcp.webflux.router;

import java.util.List;

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

import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Unit tests for the package-private {@link SessionContext}. */
@Epic("MCP Inspector WebFlux")
@Feature("SessionContext per-session state")
class SessionContextTests {

	private McpSyncClient client;

	private Sinks.Many<ServerSentEvent<String>> sink;

	private SessionContext context;

	@BeforeEach
	void setUp() {
		this.client = mock(McpSyncClient.class);
		this.sink = Sinks.many().multicast().onBackpressureBuffer(16, false);
		this.context = new SessionContext(this.client, this.sink);
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
			SessionContextTests.this.context.replaceRoots(List.of(new RootDto("file:///a", "a")));

			// when
			SessionContextTests.this.context
				.replaceRoots(List.of(new RootDto("file:///b", "b"), new RootDto("file:///c", "c")));

			// then
			assertThat(SessionContextTests.this.context.roots()).hasSize(2)
				.extracting(RootDto::uri)
				.containsExactly("file:///b", "file:///c");
		}

		@Test
		@Story("Roots state")
		@Severity(SeverityLevel.MINOR)
		@Description("replaceRoots(null) clears the roots list to empty")
		void replaceRoots_withNull_clearsRoots() {
			// given
			SessionContextTests.this.context.replaceRoots(List.of(new RootDto("file:///a", "a")));

			// when
			SessionContextTests.this.context.replaceRoots(null);

			// then
			assertThat(SessionContextTests.this.context.roots()).isEmpty();
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
			final OAuthTokenResponse token = new OAuthTokenResponse("at", "Bearer", 60L, "rt", "openid");

			// when
			SessionContextTests.this.context.oauthState("state-1");
			SessionContextTests.this.context.oauthClientId("client-1");
			SessionContextTests.this.context.oauthRedirectUri("https://app/cb");
			SessionContextTests.this.context.oauthTokenEndpoint("https://idp/token");
			SessionContextTests.this.context.oauthToken(token);

			// then
			assertThat(SessionContextTests.this.context.oauthState()).isEqualTo("state-1");
			assertThat(SessionContextTests.this.context.oauthClientId()).isEqualTo("client-1");
			assertThat(SessionContextTests.this.context.oauthRedirectUri()).isEqualTo("https://app/cb");
			assertThat(SessionContextTests.this.context.oauthTokenEndpoint()).isEqualTo("https://idp/token");
			assertThat(SessionContextTests.this.context.oauthToken()).isEqualTo(token);
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
			given(SessionContextTests.this.client.closeGracefully()).willReturn(true);

			// when
			SessionContextTests.this.context.closeQuietly();

			// then
			assertThat(SessionContextTests.this.context.pendingServerRequests().size()).isZero();
			assertThat(SessionContextTests.this.sink.currentSubscriberCount()).isZero();
			verify(SessionContextTests.this.client).closeGracefully();
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("closeQuietly() falls back to close() when graceful shutdown throws")
		void closeQuietly_whenGracefulCloseThrows_fallsBackToClose() {
			// given
			given(SessionContextTests.this.client.closeGracefully()).willThrow(new RuntimeException("graceful failed"));

			// when
			SessionContextTests.this.context.closeQuietly();

			// then
			verify(SessionContextTests.this.client).closeGracefully();
			verify(SessionContextTests.this.client).close();
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("closeQuietly() completes the sink and clears pending requests even when no MCP client was wired (client == null branch)")
		void closeQuietly_whenClientNull_completesSinkWithoutClientCall() {
			// given — a context built with no MCP client (the client == null branch)
			final Sinks.Many<ServerSentEvent<String>> nullClientSink = Sinks.many()
				.multicast()
				.onBackpressureBuffer(16, false);
			final SessionContext clientless = new SessionContext(null, nullClientSink);
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
			assertThat(SessionContextTests.this.context.client()).isSameAs(SessionContextTests.this.client);
			assertThat(SessionContextTests.this.context.sink()).isSameAs(SessionContextTests.this.sink);
			assertThat(SessionContextTests.this.context.pendingServerRequests()).isNotNull();
		}

	}

}
