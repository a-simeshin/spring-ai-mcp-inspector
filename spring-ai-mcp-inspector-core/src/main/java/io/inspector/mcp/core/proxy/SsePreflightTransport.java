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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;
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
 * Decorates an SSE {@link McpClientTransport} with a handshake preflight status check.
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
 * sends the same {@code GET} the SDK's {@code connect()} would (same URI, same auth
 * headers via the request customizer) with a body handler that reads only the response
 * headers and cancels — so a held-open successful SSE stream answers instantly, and a
 * non-2xx answer surfaces immediately. A non-2xx status is turned into a
 * {@link McpTransportException} whose message carries the status, which the D3
 * {@link ProxyErrorMapper} maps to the structured DTO and the D9 one-retry recognises as
 * a refreshable 401.
 *
 * @author Artem Simeshin
 */
final class SsePreflightTransport implements McpClientTransport {

	private static final String ACCEPT_EVENT_STREAM = "text/event-stream";

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
		return preflight().then(this.delegate.connect(handler));
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
	 * Sends the handshake {@code GET} with the same URI and auth headers the SDK would
	 * and completes when the status is 2xx, or errors with the status embedded in the
	 * message otherwise.
	 * @return a {@link Mono} completing on a 2xx preflight, erroring on any other status
	 */
	private Mono<Void> preflight() {
		return Mono.deferContextual((context) -> {
			final HttpRequest.Builder builder = this.requestTemplate.copy()
				.uri(this.targetUri)
				.header("Accept", ACCEPT_EVENT_STREAM)
				.GET();
			if (this.requestCustomizer != null) {
				final McpTransportContext transportContext = context.getOrDefault(McpTransportContext.KEY,
						McpTransportContext.EMPTY);
				this.requestCustomizer.customize(builder, "GET", this.targetUri, null, transportContext);
			}
			final HttpRequest request = builder.build();
			return Mono.fromFuture(this.preflightClient.sendAsync(request, headerOnlyBodyHandler()))
				.map((response) -> response.statusCode())
				.flatMap((status) -> {
					if (status >= 200 && status < 300) {
						return Mono.empty();
					}
					return Mono.error(
							new McpTransportException("SSE handshake rejected by upstream with HTTP status " + status));
				});
		});
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
