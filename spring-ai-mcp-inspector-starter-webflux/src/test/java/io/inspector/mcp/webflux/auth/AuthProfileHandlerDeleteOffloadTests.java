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

package io.inspector.mcp.webflux.auth;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthProfilePrefillProvider;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.webflux.router.InspectorRouterConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * DELETE during an in-flight refresh: {@code store.delete} drains the per-profile
 * single-flight lock the refresh holds, for up to the 10s exchange timeout. The handler
 * must run that blocking call off the event loop so other requests keep being served
 * while the delete waits. This test pins both properties: another request completes while
 * the delete is parked inside {@code store.delete}, and the delete itself executes on a
 * boundedElastic thread.
 */
@Epic("MCP Inspector WebFlux")
@Feature("Auth profile API")
class AuthProfileHandlerDeleteOffloadTests {

	private static final String OWNER_A = "owner-a";

	private static final String API_BASE = "/mcp-inspector/api/auth-profile";

	private HttpServer stubServer;

	private CountDownLatch tokenEndpointArrived;

	private CountDownLatch tokenEndpointRelease;

	private WebTestClient client;

	@BeforeEach
	void setUp() throws IOException {
		this.stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.tokenEndpointArrived = new CountDownLatch(1);
		this.tokenEndpointRelease = new CountDownLatch(1);
		this.stubServer.createContext("/token", (exchange) -> {
			this.tokenEndpointArrived.countDown();
			try {
				this.tokenEndpointRelease.await(30, TimeUnit.SECONDS);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			final byte[] bytes = "{\"access_token\":\"tok-1\",\"expires_in\":3600,\"refresh_token\":\"rt-1\"}"
				.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.getResponseBody().close();
		});
		this.stubServer.start();
	}

	@AfterEach
	void tearDown() {
		if (this.stubServer != null) {
			this.stubServer.stop(0);
		}
	}

	@Test
	@Story("DELETE during refresh does not hold the event loop")
	@Severity(SeverityLevel.CRITICAL)
	@Description("delete() runs store.delete on boundedElastic: a list request is served while the delete "
			+ "waits for the per-profile lock held by a blocked refresh")
	void deleteDuringRefresh_otherRequestsServed_deleteOffEventLoop() throws Exception {
		// given: a real store wired with the composite evictor, exactly as the
		// auto-configuration wires it, instrumented to observe the delete
		final AtomicReference<String> deleteThreadName = new AtomicReference<>();
		final CountDownLatch deleteEntered = new CountDownLatch(1);
		final AuthProfileStore store = new AuthProfileStore() {
			@Override
			public boolean delete(final String ownerId, final String profileId) {
				deleteThreadName.set(Thread.currentThread().getName());
				deleteEntered.countDown();
				return super.delete(ownerId, profileId);
			}
		};
		final OAuth2ClientCredentialsTokenManager manager = new OAuth2ClientCredentialsTokenManager(
				HttpClient.newHttpClient(), new JsonMapper(), store::currentGeneration);
		final OAuth2AuthCodeTokenExchanger exchanger = new OAuth2AuthCodeTokenExchanger();
		store.setTokenEvictor((profileId) -> {
			manager.evict(profileId);
			exchanger.evict(profileId);
		});

		final OAuth2Profile profile = new OAuth2Profile("cc", OAuth2GrantMode.CLIENT_CREDENTIALS,
				"http://127.0.0.1:" + this.stubServer.getAddress().getPort() + "/token", "cid", "sec", null, null, null,
				null, null);
		final String profileId = store.register(OWNER_A, profile);
		assertThat(store.update(OWNER_A, profileId, profile)).isTrue();
		manager.storeIfCurrent(profileId, store.currentGeneration(profileId),
				new OAuth2ClientCredentialsTokenManager.TokenHandle("tok-0", Instant.now().plusSeconds(3600)), profile);

		final ReactiveSessionOwnerResolver resolver = mock(ReactiveSessionOwnerResolver.class);
		given(resolver.resolve(any())).willReturn(OWNER_A);
		final AuthProfileHandler handler = new AuthProfileHandler(store, mock(AuthProfilePrefillProvider.class),
				manager, exchanger, resolver);
		final InspectorRouterConfig routerConfig = new InspectorRouterConfig();
		this.client = WebTestClient
			.bindToRouterFunction(routerConfig.authProfileRouter(handler, new McpInspectorProperties()))
			.configureClient()
			.responseTimeout(Duration.ofSeconds(2))
			.build();

		// when: a forced refresh parks at the blocked token endpoint, holding the
		// per-profile single-flight lock
		final AtomicReference<String> refreshedToken = new AtomicReference<>();
		final CompletableFuture<Void> refreshFuture = CompletableFuture
			.runAsync(() -> refreshedToken.set(manager.getAccessToken(profileId, true).accessToken()));
		assertThat(this.tokenEndpointArrived.await(5, TimeUnit.SECONDS))
			.as("refresh reached the blocked token endpoint")
			.isTrue();

		// and: DELETE the profile while the refresh holds the lock
		final CompletableFuture<Void> deleteFuture = CompletableFuture
			.runAsync(() -> AuthProfileHandlerDeleteOffloadTests.this.client.delete()
				.uri(API_BASE + "/" + profileId)
				.exchange()
				.expectStatus()
				.isNoContent());
		assertThat(deleteEntered.await(5, TimeUnit.SECONDS)).as("delete entered store.delete").isTrue();

		// then: another request is served while the delete is still parked
		this.client.get().uri(API_BASE).exchange().expectStatus().isOk();
		// and: the blocking store.delete ran off the event loop
		assertThat(deleteThreadName.get()).as("store.delete thread").startsWith("boundedElastic");

		// cleanup: release the endpoint so the refresh completes and the delete drains
		this.tokenEndpointRelease.countDown();
		refreshFuture.get(10, TimeUnit.SECONDS);
		assertThat(refreshedToken.get()).as("the refresh genuinely held the lock").isEqualTo("tok-1");
		deleteFuture.get(10, TimeUnit.SECONDS);
		assertThat(store.resolve(OWNER_A, profileId)).isEmpty();
	}

}
