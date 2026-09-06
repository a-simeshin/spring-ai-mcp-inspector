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
 * requests. The server is shut down after each test.
 */
@Epic("MCP Inspector Core")
@Feature("SSE preflight transport")
class SsePreflightTransportTests {

	private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

	private HttpServer server;

	private int port;

	private McpClientTransport delegate;

	private SsePreflightTransport transport;

	@BeforeEach
	void setUp() throws Exception {
		SsePreflightTransportTests.this.server = HttpServer.create(new InetSocketAddress(0), 0);
		SsePreflightTransportTests.this.server.setExecutor((command) -> new Thread(command).start());
		SsePreflightTransportTests.this.server.start();
		SsePreflightTransportTests.this.port = SsePreflightTransportTests.this.server.getAddress().getPort();
		SsePreflightTransportTests.this.delegate = mock(McpClientTransport.class);
		given(SsePreflightTransportTests.this.delegate.connect(any())).willReturn(Mono.empty());
		given(SsePreflightTransportTests.this.delegate.closeGracefully()).willReturn(Mono.empty());
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

	private URI sseUriNoPath() {
		return URI.create("http://127.0.0.1:" + SsePreflightTransportTests.this.port);
	}

	private SsePreflightTransport createTransport(final McpClientTransport delegate, final URI uri) {
		return createTransport(delegate, uri, null, null);
	}

	private SsePreflightTransport createTransport(final McpClientTransport delegate, final URI uri,
			final String authorization, final Map<String, String> customHeaders) {
		final HttpClient preflightClient = HttpClient.newBuilder().build();
		final HttpRequest.Builder requestTemplate = HttpRequest.newBuilder();
		final McpSyncHttpClientRequestCustomizer customizer = buildCustomizer(authorization, customHeaders);
		return new SsePreflightTransport(delegate, uri, requestTemplate, customizer, preflightClient);
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

	@Nested
	@DisplayName("HEAD preflight success")
	class HeadPreflightSuccess {

		@Test
		@Story("HEAD 2xx")
		@Severity(SeverityLevel.CRITICAL)
		@Description("HEAD 2xx from the upstream server completes the preflight and delegates to the inner transport")
		void head2xx_completesPreflightAndDelegates() {
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
			});
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectComplete()
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate).connect(any());
		}

	}

	@Nested
	@DisplayName("HEAD 405 -> GET fallback")
	class Head405Fallback {

		@Test
		@Story("HEAD 405 falls back to GET and succeeds")
		@Severity(SeverityLevel.CRITICAL)
		@Description("When the server rejects HEAD with 405, the preflight falls back to GET header-only probe")
		void head405_fallsBackToGetAndSucceeds() {
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				if ("HEAD".equals(exchange.getRequestMethod())) {
					exchange.sendResponseHeaders(405, -1);
				}
				else {
					exchange.sendResponseHeaders(200, -1);
				}
				exchange.close();
			});
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectComplete()
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate).connect(any());
		}

		@Test
		@Story("HEAD 405 -> GET also fails")
		@Severity(SeverityLevel.NORMAL)
		@Description("When both HEAD and GET return non-2xx, the preflight errors with the GET status")
		void head405_fallsBackToGetAndFails() {
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				exchange.sendResponseHeaders(405, -1);
				exchange.close();
			});
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

	}

	@Nested
	@DisplayName("Non-2xx preflight")
	class Non2xxPreflight {

		@Test
		@Story("HEAD 401 is rejected")
		@Severity(SeverityLevel.CRITICAL)
		@Description("HEAD 401 from the upstream server produces an McpTransportException with the status code")
		void head401_rejectedWithMcpTransportException() {
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				exchange.sendResponseHeaders(401, -1);
				exchange.close();
			});
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
		@Story("HEAD 403 is rejected")
		@Severity(SeverityLevel.NORMAL)
		@Description("HEAD 403 from the upstream server produces an McpTransportException with the status code")
		void head403_rejectedWithMcpTransportException() {
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				exchange.sendResponseHeaders(403, -1);
				exchange.close();
			});
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

	}

	@Nested
	@DisplayName("Forwarded headers")
	class ForwardedHeaders {

		@Test
		@Story("Authorization header forwarded")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The preflight HEAD request carries the forwarded Authorization header")
		void preflightCarriesAuthorizationHeader() {
			final String[] capturedAuth = new String[1];
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				capturedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
			});
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri(), "Bearer tok-123", null);
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectComplete()
				.verify(TEST_TIMEOUT);
			assertThat(capturedAuth[0]).isEqualTo("Bearer tok-123");
		}

		@Test
		@Story("Custom headers forwarded")
		@Severity(SeverityLevel.NORMAL)
		@Description("The preflight HEAD request carries the forwarded custom headers")
		void preflightCarriesCustomHeaders() {
			final String[] capturedTenant = new String[1];
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				capturedTenant[0] = exchange.getRequestHeaders().getFirst("X-Tenant");
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
			});
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri(), null, Map.of("X-Tenant", "acme"));
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectComplete()
				.verify(TEST_TIMEOUT);
			assertThat(capturedTenant[0]).isEqualTo("acme");
		}

	}

	@Nested
	@DisplayName("Delegate call only after successful preflight")
	class DelegateCallAfterPreflight {

		@Test
		@Story("Delegate called after successful preflight")
		@Severity(SeverityLevel.CRITICAL)
		@Description("The inner delegate transport is only called after the preflight succeeds")
		void delegateNotCalledBeforePreflight() {
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
			});
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectComplete()
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate).connect(any());
		}

		@Test
		@Story("Delegate not called on failed preflight")
		@Severity(SeverityLevel.CRITICAL)
		@Description("When the preflight fails, the inner delegate transport is never called")
		void delegateNotCalledOnFailedPreflight() {
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				exchange.sendResponseHeaders(401, -1);
				exchange.close();
			});
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectError(McpTransportException.class)
				.verify(TEST_TIMEOUT);
			verify(SsePreflightTransportTests.this.delegate, never()).connect(any());
		}

	}

	@Nested
	@DisplayName("Timeout")
	class Timeout {

		@Test
		@Story("HEAD preflight timeout")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A HEAD preflight that does not respond within the timeout produces an McpTransportException")
		void headPreflightTimeout_errorsWithMcpTransportException() {
			// server accepts the connection but never responds
			SsePreflightTransportTests.this.server.createContext("/sse", (exchange) -> {
				// never send response headers
			});
			SsePreflightTransportTests.this.transport = createTransport(SsePreflightTransportTests.this.delegate,
					SsePreflightTransportTests.this.sseUri());
			StepVerifier.create(SsePreflightTransportTests.this.transport.connect((msg) -> Mono.empty()))
				.expectErrorSatisfies((error) -> {
					assertThat(error).isInstanceOf(McpTransportException.class);
					assertThat(error).hasMessageContaining("timed out");
				})
				.verify(Duration.ofSeconds(15));
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
