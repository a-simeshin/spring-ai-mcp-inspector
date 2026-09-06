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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpTransportException;
import reactor.core.publisher.Mono;

/**
 * Decorates an SSE {@link McpClientTransport} with a lightweight handshake preflight
 * status check.
 *
 * <p>
 * The MCP SDK's {@code HttpClientSseClientTransport.connect()} only maps a 2xx answer to
 * readiness. On a non-2xx handshake whose body produces no SSE event (empty, whitespace
 * or comment-only — e.g. a bare {@code 401} from a misconfigured server) the SDK parses
 * the body as an SSE stream, never emits an {@code endpoint} event, and the
 * {@code connect()} {@link Mono} never completes: {@code sendMessage(initialize)} then
 * hangs until the request timeout instead of surfacing the real reason. A non-SSE line
 * happens to error in the SDK, but that is an accident of the body's shape, not a
 * contract.
 *
 * <p>
 * This wrapper makes the contract explicit: before delegating to the inner transport, it
 * probes the upstream server with an HTTP HEAD request to the SSE endpoint. Per RFC 9110
 * Section 9.3.1, HEAD is identical to GET except the server MUST NOT send a message body.
 * Since no body is sent, the server never creates an SSE session, eliminating the
 * orphaned session problem that the previous GET-based probe suffered from. If the server
 * rejects HEAD with 405 (Method Not Allowed), the wrapper falls back to a GET-based probe
 * with a header-only body handler that cancels immediately on response headers. A non-2xx
 * status is turned into a {@link McpTransportException} whose message carries the status,
 * which the D3 {@code ProxyErrorMapper} maps to the structured DTO and the D9 one-retry
 * recognises as a refreshable 401.
 *
 * @author Artem Simeshin
 */
final class SsePreflightTransport implements McpClientTransport {

	private static final String ACCEPT_EVENT_STREAM = "text/event-stream";

	private static final Duration PREFLIGHT_TIMEOUT = Duration.ofSeconds(10);

	private final McpClientTransport delegate;

	private final URI targetUri;

	private final HttpRequest.Builder requestTemplate;

	private final McpSyncHttpClientRequestCustomizer requestCustomizer;

	private final HttpClient preflightClient;

	SsePreflightTransport(final McpClientTransport delegate, final URI targetUri,
			final HttpRequest.Builder requestTemplate, final McpSyncHttpClientRequestCustomizer requestCustomizer,
			final HttpClient preflightClient) {
		this.delegate = delegate;
		this.targetUri = targetUri;
		this.requestTemplate = requestTemplate;
		this.requestCustomizer = requestCustomizer;
		this.preflightClient = preflightClient;
	}

	@Override
	public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return preflight().then(Mono.defer(() -> this.delegate.connect(handler)));
	}

	@Override
	public Mono<Void> sendMessage(final JSONRPCMessage message) {
		return this.delegate.sendMessage(message);
	}

	/**
	 * Exposes the wrapped transport for tests that assert the underlying SDK type.
	 * @return the decorated SSE transport
	 */
	McpClientTransport unwrap() {
		return this.delegate;
	}

	@Override
	public void setExceptionHandler(final Consumer<Throwable> handler) {
		this.delegate.setExceptionHandler(handler);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	@Override
	public <T> T unmarshalFrom(final Object data, final TypeRef<T> typeRef) {
		return this.delegate.unmarshalFrom(data, typeRef);
	}

	/**
	 * Sends the lightweight handshake probe and completes when the status is 2xx, or
	 * errors with the status embedded in the message otherwise.
	 *
	 * <p>
	 * The primary probe uses HTTP HEAD, which never creates a server-side SSE session. If
	 * the server rejects HEAD with 405, falls back to a GET probe whose body handler
	 * cancels on response headers so the connection is never held open.
	 * @return a {@link Mono} completing on a 2xx preflight, erroring on any other status
	 */
	private Mono<Void> preflight() {
		return Mono.deferContextual((context) -> {
			final HttpRequest headRequest = buildRequest("HEAD", context);
			final CompletableFuture<HttpResponse<Void>> headFuture = this.preflightClient
				.sendAsync(headRequest, HttpResponse.BodyHandlers.discarding())
				.orTimeout(PREFLIGHT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
			return Mono.fromFuture(headFuture).flatMap((response) -> {
				final int status = response.statusCode();
				if (status >= 200 && status < 300) {
					return Mono.<Void>empty();
				}
				if (status == 405) {
					return preflightGet(context);
				}
				return Mono.<Void>error(
						new McpTransportException("SSE handshake rejected by upstream with HTTP status " + status));
			})
				.onErrorMap(TimeoutException.class,
						(e) -> new McpTransportException("SSE preflight HEAD timed out after " + PREFLIGHT_TIMEOUT));
		});
	}

	/**
	 * GET-based fallback for servers that reject the HEAD probe. Sends the same handshake
	 * {@code GET} the SDK would, with a header-only body handler that cancels on the
	 * first response chunk so the stream is never consumed.
	 * @param context the reactor context carrying the transport context
	 * @return a {@link Mono} completing on a 2xx preflight, erroring on any other status
	 */
	private Mono<Void> preflightGet(final reactor.util.context.ContextView context) {
		final HttpRequest getRequest = buildRequest("GET", context);
		final CompletableFuture<HttpResponse<Void>> getFuture = this.preflightClient
			.sendAsync(getRequest, headerOnlyBodyHandler())
			.orTimeout(PREFLIGHT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
		return Mono.fromFuture(getFuture)
			.map((response) -> response.statusCode())
			.flatMap((status) -> (status >= 200 && status < 300) ? Mono.empty()
					: Mono.<Void>error(
							new McpTransportException("SSE handshake rejected by upstream with HTTP status " + status)))
			.onErrorMap(TimeoutException.class,
					(e) -> new McpTransportException("SSE preflight GET timed out after " + PREFLIGHT_TIMEOUT));
	}

	/**
	 * Builds an HTTP request for the given method, applying the same auth headers and
	 * customizers the SDK would use.
	 * @param method the HTTP method (e.g. "HEAD" or "GET")
	 * @param context the reactor context carrying the transport context
	 * @return a fully built {@link HttpRequest}
	 */
	private HttpRequest buildRequest(final String method, final reactor.util.context.ContextView context) {
		final HttpRequest.Builder builder = this.requestTemplate.copy()
			.uri(this.targetUri)
			.header("Accept", ACCEPT_EVENT_STREAM)
			.method(method, HttpRequest.BodyPublishers.noBody());
		if (this.requestCustomizer != null) {
			final McpTransportContext transportContext = context.getOrDefault(McpTransportContext.KEY,
					McpTransportContext.EMPTY);
			this.requestCustomizer.customize(builder, method, this.targetUri, null, transportContext);
		}
		return builder.build();
	}

	/**
	 * A body handler that completes as soon as the response headers arrive and cancels
	 * the subscription, so the preflight never consumes a held-open SSE stream.
	 * @return the header-only body handler
	 */
	private static HttpResponse.BodyHandler<Void> headerOnlyBodyHandler() {
		return (responseInfo) -> new HttpResponse.BodySubscriber<Void>() {
			private final CompletableFuture<Void> body = new CompletableFuture<>();

			@Override
			public void onSubscribe(final Subscription subscription) {
				subscription.cancel();
				this.body.complete(null);
			}

			@Override
			public void onNext(final List<ByteBuffer> item) {
				// discarded
			}

			@Override
			public void onError(final Throwable throwable) {
				this.body.completeExceptionally(throwable);
			}

			@Override
			public void onComplete() {
				this.body.complete(null);
			}

			@Override
			public CompletionStage<Void> getBody() {
				return this.body;
			}
		};
	}

}
