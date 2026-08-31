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

package io.inspector.mcp.webmvc.controller;

import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
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

import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link SessionState} — the per-session holder of the loopback client,
 * roots, pending server requests and OAuth state.
 */
@Epic("WebMvc Inspector")
@Feature("SessionState")
class SessionStateTests {

	private McpSyncClient client;

	private SessionState state;

	@BeforeEach
	void setUp() {
		this.client = mock(McpSyncClient.class);
		this.state = new SessionState(this.client);
	}

	@Nested
	@DisplayName("roots")
	class Roots {

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("replaceRoots() swaps in the supplied roots")
		void replaceRoots_withList_replacesContents() {
			// given
			SessionStateTests.this.state.replaceRoots(List.of(new RootDto("file:///a", "a")));

			// when
			SessionStateTests.this.state
				.replaceRoots(List.of(new RootDto("file:///b", "b"), new RootDto("file:///c", "c")));

			// then
			assertThat(SessionStateTests.this.state.roots()).extracting(RootDto::uri)
				.containsExactly("file:///b", "file:///c");
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("replaceRoots(null) clears the roots without error")
		void replaceRoots_withNull_clearsRoots() {
			// given
			SessionStateTests.this.state.replaceRoots(List.of(new RootDto("file:///a", "a")));

			// when
			SessionStateTests.this.state.replaceRoots(null);

			// then
			assertThat(SessionStateTests.this.state.roots()).isEmpty();
		}

	}

	@Nested
	@DisplayName("oauth state")
	class OAuthState {

		@Test
		@Story("OAuth")
		@Severity(SeverityLevel.NORMAL)
		@Description("the OAuth fields round-trip through their setters and getters")
		void oauthAccessors_roundTripValues() {
			// given
			final OAuthTokenResponse token = new OAuthTokenResponse("acc", "Bearer", 1L, "ref", "openid");

			// when
			SessionStateTests.this.state.oauthState("st");
			SessionStateTests.this.state.oauthTokenEndpoint("https://idp/token");
			SessionStateTests.this.state.oauthClientId("client");
			SessionStateTests.this.state.oauthRedirectUri("https://app/cb");
			SessionStateTests.this.state.oauthToken(token);

			// then
			assertThat(SessionStateTests.this.state.oauthState()).isEqualTo("st");
			assertThat(SessionStateTests.this.state.oauthTokenEndpoint()).isEqualTo("https://idp/token");
			assertThat(SessionStateTests.this.state.oauthClientId()).isEqualTo("client");
			assertThat(SessionStateTests.this.state.oauthRedirectUri()).isEqualTo("https://app/cb");
			assertThat(SessionStateTests.this.state.oauthToken()).isSameAs(token);
		}

	}

	@Nested
	@DisplayName("client() / pendingServerRequests()")
	class Accessors {

		@Test
		@Story("Accessors")
		@Severity(SeverityLevel.MINOR)
		@Description("client() and pendingServerRequests() expose the constructed collaborators")
		void accessors_exposeCollaborators() {
			// then
			assertThat(SessionStateTests.this.state.client()).isSameAs(SessionStateTests.this.client);
			assertThat(SessionStateTests.this.state.pendingServerRequests()).isNotNull();
		}

	}

	@Nested
	@DisplayName("closeQuietly()")
	class CloseQuietly {

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("closeQuietly() clears pending requests and awaits the client teardown")
		void closeQuietly_clearsPendingAndClosesClient() {
			// given
			SessionStateTests.this.state.pendingServerRequests().create("req-1");

			// when
			SessionStateTests.this.state.closeQuietly();

			// then — closeGracefully(), not close(): close() only dispatches the
			// teardown, which then races Boot's graceful-shutdown phase
			assertThat(SessionStateTests.this.state.pendingServerRequests().size()).isZero();
			verify(SessionStateTests.this.client).closeGracefully();
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.CRITICAL)
		@Description("When closeGracefully() reports failure, close() is called to force the transport down. "
				+ "McpSyncClient.closeGracefully() never throws — it blocks 10s, logs 'Client didn't close "
				+ "within timeout' and returns false — so a catch block around it is unreachable and the "
				+ "wedged transport that matters during shutdown was never forced closed")
		void closeQuietly_whenGracefulCloseReportsFailure_forcesClose() {
			// given
			given(SessionStateTests.this.client.closeGracefully()).willReturn(false);

			// when
			SessionStateTests.this.state.closeQuietly();

			// then
			verify(SessionStateTests.this.client).closeGracefully();
			verify(SessionStateTests.this.client).close();
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("A successful graceful close does not also force close()")
		void closeQuietly_whenGracefulCloseSucceeds_doesNotForceClose() {
			// given
			given(SessionStateTests.this.client.closeGracefully()).willReturn(true);

			// when
			SessionStateTests.this.state.closeQuietly();

			// then
			verify(SessionStateTests.this.client).closeGracefully();
			verify(SessionStateTests.this.client, never()).close();
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.MINOR)
		@Description("closeQuietly() swallows exceptions thrown while closing the client")
		void closeQuietly_whenClientCloseThrows_swallowsException() {
			// given
			willThrow(new RuntimeException("boom")).given(SessionStateTests.this.client).close();
			willThrow(new RuntimeException("boom")).given(SessionStateTests.this.client).closeGracefully();

			// when / then — no exception escapes
			SessionStateTests.this.state.closeQuietly();
			verify(SessionStateTests.this.client).closeGracefully();
			verify(SessionStateTests.this.client).close();
		}

	}

	@Nested
	@DisplayName("initializeSnapshot()")
	class InitializeSnapshot {

		@Test
		@Story("Initialize snapshot")
		@Severity(SeverityLevel.NORMAL)
		@Description("initializeSnapshot() captures the server version, capabilities and protocol version")
		void initializeSnapshot_capturesFromInitializeResult() {
			// given
			final McpSchema.InitializeResult result = McpSchema.InitializeResult
				.builder("2025-11-25", McpSchema.ServerCapabilities.builder().logging().tools(true).build(),
						McpSchema.Implementation.builder("my-server", "2.0.0").build())
				.build();

			// when
			SessionStateTests.this.state.initializeSnapshot(result);

			// then
			final SessionState.InitializeSnapshot snapshot = SessionStateTests.this.state.initializeSnapshot();
			assertThat(snapshot).isNotNull();
			assertThat(snapshot.clientRequestedVersion()).isEqualTo("2025-11-25");
			assertThat(snapshot.negotiatedVersion()).isEqualTo("2025-11-25");
			assertThat(snapshot.serverName()).isEqualTo("my-server");
			assertThat(snapshot.serverVersion()).isEqualTo("2.0.0");
			assertThat(snapshot.capabilities()).containsEntry("logging", true);
			assertThat(snapshot.capabilities()).containsEntry("tools", true);
		}

		@Test
		@Story("Initialize snapshot")
		@Severity(SeverityLevel.NORMAL)
		@Description("initializeSnapshot() with empty capabilities produces an empty capabilities map")
		void initializeSnapshot_withEmptyCapabilities_returnsEmptyMap() {
			// given
			final McpSchema.InitializeResult result = McpSchema.InitializeResult
				.builder("2025-11-25", McpSchema.ServerCapabilities.builder().build(),
						McpSchema.Implementation.builder("s", "1").build())
				.build();

			// when
			SessionStateTests.this.state.initializeSnapshot(result);

			// then
			assertThat(SessionStateTests.this.state.initializeSnapshot().capabilities()).isEmpty();
		}

		@Test
		@Story("Initialize snapshot")
		@Severity(SeverityLevel.MINOR)
		@Description("initializeSnapshot() with null result is a no-op")
		void initializeSnapshot_withNullResult_isNoOp() {
			// when
			SessionStateTests.this.state.initializeSnapshot(null);

			// then
			assertThat(SessionStateTests.this.state.initializeSnapshot()).isNull();
		}

		@Test
		@Story("Initialize snapshot")
		@Severity(SeverityLevel.MINOR)
		@Description("initializeSnapshot() is null before any capture")
		void initializeSnapshot_beforeCapture_isNull() {
			assertThat(SessionStateTests.this.state.initializeSnapshot()).isNull();
		}

	}

}
