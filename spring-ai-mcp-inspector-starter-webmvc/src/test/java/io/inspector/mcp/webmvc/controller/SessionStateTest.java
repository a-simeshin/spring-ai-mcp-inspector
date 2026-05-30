/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.controller;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link SessionState} — the per-session holder of the loopback client,
 * roots, pending server requests and OAuth state.
 */
@Epic("WebMvc Inspector")
@Feature("SessionState")
class SessionStateTest {

	private McpSyncClient client;

	private SessionState state;

	@BeforeEach
	void setUp() {
		client = mock(McpSyncClient.class);
		state = new SessionState(client);
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
			state.replaceRoots(List.of(new RootDto("file:///a", "a")));

			// when
			state.replaceRoots(List.of(new RootDto("file:///b", "b"), new RootDto("file:///c", "c")));

			// then
			assertThat(state.roots()).extracting(RootDto::uri).containsExactly("file:///b", "file:///c");
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.MINOR)
		@Description("replaceRoots(null) clears the roots without error")
		void replaceRoots_withNull_clearsRoots() {
			// given
			state.replaceRoots(List.of(new RootDto("file:///a", "a")));

			// when
			state.replaceRoots(null);

			// then
			assertThat(state.roots()).isEmpty();
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
			OAuthTokenResponse token = new OAuthTokenResponse("acc", "Bearer", 1L, "ref", "openid");

			// when
			state.oauthState("st");
			state.oauthTokenEndpoint("https://idp/token");
			state.oauthClientId("client");
			state.oauthRedirectUri("https://app/cb");
			state.oauthToken(token);

			// then
			assertThat(state.oauthState()).isEqualTo("st");
			assertThat(state.oauthTokenEndpoint()).isEqualTo("https://idp/token");
			assertThat(state.oauthClientId()).isEqualTo("client");
			assertThat(state.oauthRedirectUri()).isEqualTo("https://app/cb");
			assertThat(state.oauthToken()).isSameAs(token);
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
			assertThat(state.client()).isSameAs(client);
			assertThat(state.pendingServerRequests()).isNotNull();
		}

	}

	@Nested
	@DisplayName("closeQuietly()")
	class CloseQuietly {

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("closeQuietly() clears pending requests and closes the client")
		void closeQuietly_clearsPendingAndClosesClient() {
			// given
			state.pendingServerRequests().create("req-1");

			// when
			state.closeQuietly();

			// then
			assertThat(state.pendingServerRequests().size()).isZero();
			verify(client).close();
		}

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.MINOR)
		@Description("closeQuietly() swallows exceptions thrown while closing the client")
		void closeQuietly_whenClientCloseThrows_swallowsException() {
			// given
			doThrow(new RuntimeException("boom")).when(client).close();

			// when / then — no exception escapes
			state.closeQuietly();
			verify(client).close();
		}

	}

}
