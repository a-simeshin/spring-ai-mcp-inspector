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

package io.inspector.mcp.core.proxy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpTransportException;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Server-backed unit tests for {@link SsePreflightTransport}. Each test starts a
 * lightweight {@link HttpServer} on a random port so the preflight performs real HTTP
 * requests. The server records every request method it saw. The server is shut down after
 * each test.
 *
 * <p>
 * Verdict policy (D3): any non-2xx probe status fails fast with the real handshake status
 * embedded in the error. With auth profiles, 401/403 are the everyday rejection, and on
 * this SDK line the delegate's own connect would hang on a non-SSE body, so the probe is
 * the only place the status can surface.
 */
@Epic("MCP Inspector Core")
@Feature("SSE preflight transport")
class SsePreflightTransportTests {

	private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

	private HttpServer server;

	private int port;

	private McpClientTransport delegate;

	private SsePreflightTransport transport;

	private final ConcurrentLinkedQueue<String> methods = new ConcurrentLinkedQueue<>();

	private final AtomicInteger headRequests = new AtomicInteger();

	@BeforeEach
	void setUp() throws Exception {
		SsePreflightTransportTests.this.server = HttpServer.create(new InetSocketAddress(0), 0);
		SsePreflightTransportTests.this.server.setExecutor((command) -> new Thread(command).start());
		SsePreflightTransportTests.this.server.start();
		SsePreflightTransportTests.this.port = SsePreflightTransportTests.this.server.getAddress().getPort();
		SsePreflightTransportTests.this.delegate = mock(McpClientTransport.class);
		given(SsePreflightTransportTests.this.delegate.connect(any())).willReturn(Mono.empty());
		given(SsePreflightTransportTests.this.delegate.closeGracefully()).willReturn(Mono.empty());
		SsePreflightTransportTests.this.methods.clear();
		SsePreflightTransportTests.this.headRequests.set(0);
	}

	@AfterEach
	void tearDown() {
		if (SsePreflightTransportTests.this.server != null) {
			SsePreflightTransportTests.this.server.stop(0);
		}
	}

	private URI sseUri() {
		return URI.create("http://127.0.0.1:" + SsePreflightTransportTests.this.port + "/sse");
	}

	private SsePreflightTransport createTransport(final McpClientTransport delegate, final URI uri) {
		return createTransport(delegate, uri, null, null, Duration.ofSeconds(3));
	}

	private SsePreflightTransport createTransport(final McpClientTransport delegate, final URI uri,
			final String authorization, final Map<String, String> customHeaders, final Duration probeTimeout) {
		final HttpClient sharedClient = HttpClient.newBuilder().build();
		final HttpRequest.Builder requestTemplate = HttpRequest.newBuilder();
		final McpSyncHttpClientRequestCustomizer customizer = buildCustomizer(authorization, customHeaders);
		return new SsePreflightTransport(delegate, uri, requestTemplate, customizer, sharedClient, probeTimeout);
	}

	private static McpSyncHttpClientRequestCustomizer buildCustomizer(final String authorization,
			final Map<String, String> customHeaders) {
		final boolean hasAuth = authorization != null && !authorization.isBlank();
		final boolean hasCustom = customHeaders != null && !customHeaders.isEmpty();
		if (!hasAuth && !hasCustom) {
			return null;
		}
		return (builder, method, endpoint, body, context) -> {
			if (hasAuth) {
				builder.setHeader("Authorization", authorization);
			}
			if (hasCustom) {
				customHeaders.forEach((name, value) -> {
					if (name != null && !name.isBlank() && value != null) {
						builder.setHeader(name, value);
					}
				});
			}
		};
	}

	/**
	 * Registers a handler answering every request (any method) with {@code status} and
	 * recording the request method.
	 * @param status the status to answer
	 */
	private void answerEveryMethodWith(final int status) {
		SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
			SsePreflightTransportTests.this.methods.add(exchange.getRequestMethod());
			if ("HEAD".equals(exchange.getRequestMethod())) {
				SsePreflightTransportTests.this.headRequests.incrementAndGet();
			}
			if (status == 200) {
				exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
				exchange.sendResponseHeaders(200, -1);
			}
			else {
				exchange.sendResponseHeaders(status, -1);
			}
			exchange.close();
		});
	}

	@Nested
	@DisplayName("GET-only probe")
	class GetOnlyProbe {

		@Test
		@Story("Probe is a single GET, never HEAD")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The preflight sends exactly one GET probe and no HEAD request: HEAD is auto-mapped "
				+ "onto the GET handler by Starlette and Spring MVC, so a HEAD probe would create the "
				+ "orphaned server-side SSE session the probe exists to avoid")
		void probe_isSingleGet_neverHead() {
			answerEveryMethodWith(200);
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectComplete()
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate).connect(any());
			assertThat(SsePreflightTransportTests.this.methods).as("Probe requests sent").containsExactly("GET");
		}

		@Test
		@Story("2xx probe completes and delegates")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A 2xx probe answer completes the preflight and delegates to the inner transport")
		void get2xx_completesPreflightAndDelegates() {
			answerEveryMethodWith(200);
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectComplete()
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate).connect(any());
		}

	}

	@Nested
	@DisplayName("Non-2xx fails fast (D3)")
	class Non2xxFailsFast {

		@Test
		@Story("401 fails fast with status")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A 401 probe answer fails fast with the status in the message and the delegate is "
				+ "never called: with auth profiles 401 is the everyday rejection, and on this SDK line "
				+ "the delegate's own connect would hang on the non-SSE body, so the status must surface "
				+ "here (D3)")
		void status401_failsFastWithStatus() {
			answerEveryMethodWith(401);
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectErrorSatisfies((error) -> {
					assertThat(error).isInstanceOf(McpTransportException.class);
					assertThat(error).hasMessageContaining("401");
				})
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate, never()).connect(any());
		}

		@Test
		@Story("403 fails fast with status")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A 403 probe answer fails fast with the status in the message: the user's token lacks "
				+ "permission for this endpoint and no amount of delegating changes that, so the reason "
				+ "surfaces immediately (D3)")
		void status403_failsFastWithStatus() {
			answerEveryMethodWith(403);
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectErrorSatisfies((error) -> {
					assertThat(error).isInstanceOf(McpTransportException.class);
					assertThat(error).hasMessageContaining("403");
				})
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate, never()).connect(any());
		}

		@Test
		@Story("405 fails fast with status")
		@Severity(SeverityLevel.NORMAL)
		@Description("A 405 probe answer (the server rejects the method on the probe path) fails fast with "
				+ "the status in the message: the real handshake uses the same GET, so it cannot succeed "
				+ "where the probe was rejected")
		void status405_failsFastWithStatus() {
			answerEveryMethodWith(405);
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectErrorSatisfies((error) -> {
					assertThat(error).isInstanceOf(McpTransportException.class);
					assertThat(error).hasMessageContaining("405");
				})
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate, never()).connect(any());
		}

		@Test
		@Story("501 fails fast with status")
		@Severity(SeverityLevel.NORMAL)
		@Description("RFC 9110 'not implemented' (e.g. a gateway that answers 501 to the probe path) fails "
				+ "fast with the status in the message: the delegate would hang on the non-SSE body on "
				+ "this SDK line, so the status surfaces here (D3)")
		void status501_failsFastWithStatus() {
			answerEveryMethodWith(501);
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectErrorSatisfies((error) -> {
					assertThat(error).isInstanceOf(McpTransportException.class);
					assertThat(error).hasMessageContaining("501");
				})
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate, never()).connect(any());
		}

		@Test
		@Story("3xx fails fast with status")
		@Severity(SeverityLevel.NORMAL)
		@Description("A redirect answer fails fast with the status in the message: the header-only probe "
				+ "follows no redirects (it must not create the server-side session a redirect target "
				+ "would open), and the status in the error tells the user the endpoint moved")
		void status302_failsFastWithStatus() {
			answerEveryMethodWith(302);
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectErrorSatisfies((error) -> {
					assertThat(error).isInstanceOf(McpTransportException.class);
					assertThat(error).hasMessageContaining("302");
				})
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate, never()).connect(any());
		}

	}

	@Nested
	@DisplayName("Definitive endpoint verdict")
	class DefinitiveVerdict {

		@Test
		@Story("404 fails fast")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A 404 probe answer means the SSE endpoint does not exist: the connect fails fast with "
				+ "the status in the message and the delegate is never called")
		void status404_failsFastWithStatus() {
			answerEveryMethodWith(404);
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectErrorSatisfies((error) -> {
					assertThat(error).isInstanceOf(McpTransportException.class);
					assertThat(error).hasMessageContaining("404");
				})
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate, never()).connect(any());
		}

	}

	@Nested
	@DisplayName("Forwarded headers")
	class ForwardedHeaders {

		@Test
		@Story("Authorization header forwarded")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The preflight GET carries the forwarded Authorization header")
		void preflightCarriesAuthorizationHeader() {
			final String[] capturedAuth = new String[1];
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				capturedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
			});
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri(), "Bearer tok-123", null, Duration.ofSeconds(3));
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectComplete()
				.verify(TEST_TIMEOUT);
			assertThat(capturedAuth[0]).isEqualTo("Bearer tok-123");
		}

		@Test
		@Story("Custom headers forwarded")
		@Severity(SeverityLevel.NORMAL)
		@Description("The preflight GET carries the forwarded custom headers")
		void preflightCarriesCustomHeaders() {
			final String[] capturedTenant = new String[1];
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				capturedTenant[0] = exchange.getRequestHeaders().getFirst("X-Tenant");
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
			});
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri(), null, Map.of("X-Tenant", "acme"), Duration.ofSeconds(3));
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectComplete()
				.verify(TEST_TIMEOUT);
			assertThat(capturedTenant[0]).isEqualTo("acme");
		}

	}

	@Nested
	@DisplayName("Configurable probe timeout")
	class ProbeTimeout {

		@Test
		@Story("Probe timeout is configurable")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A probe timeout supplied by the factory bounds the probe: a hanging server fails after "
				+ "the configured duration (here 500ms), not after a hardcoded 10s")
		void probeTimeout_isConfigurable() {
			// server accepts the connection but never responds
			SsePreflightTransportTests.this.server.createContext("/sse",
					(exchange) -> SsePreflightTransportTests.this.methods.add(exchange.getRequestMethod()));
			final long start = System.nanoTime();
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri(), null, null, Duration.ofMillis(500));
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectErrorSatisfies((error) -> {
					assertThat(error).isInstanceOf(McpTransportException.class);
					assertThat(error).hasMessageContaining("timed out");
				})
				.verify(Duration.ofSeconds(10));
			final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
			assertThat(elapsedMs).as("Probe failed after the configured 500ms budget, not a hardcoded 10s")
				.isLessThan(5000);
			verify(SsePreflightTransportTests.this.delegate, never()).connect(any());
		}

	}

	@Nested
	@DisplayName("sendMessage and closeGracefully passthrough")
	class Passthrough {

		@Test
		@Story("sendMessage delegates to inner transport")
		@Severity(SeverityLevel.NORMAL)
		@Description("sendMessage() is delegated directly to the inner transport without preflight")
		void sendMessage_delegatesToInner() {
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			SsePreflightTransportTests.this.transport.sendMessage(null);
			verify(SsePreflightTransportTests.this.delegate).sendMessage(null);
		}

		@Test
		@Story("closeGracefully delegates to inner transport")
		@Severity(SeverityLevel.NORMAL)
		@Description("closeGracefully() is delegated directly to the inner transport")
		void closeGracefully_delegatesToInner() {
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			SsePreflightTransportTests.this.transport.closeGracefully();
			verify(SsePreflightTransportTests.this.delegate).closeGracefully();
		}

		@Test
		@Story("unwrap returns inner transport")
		@Severity(SeverityLevel.MINOR)
		@Description("unwrap() returns the decorated inner transport")
		void unwrap_returnsInnerTransport() {
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			assertThat(SsePreflightTransportTests.this.transport.unwrap())
				.isSameAs(SsePreflightTransportTests.this.delegate);
		}

	}

}
