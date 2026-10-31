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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpTransportException;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Decorates an SSE {@link McpClientTransport} with a single header-only handshake
 * preflight.
 *
 * <p>
 * The MCP SDK's {@code HttpClientSseClientTransport.connect()} only maps a 2xx answer to
 * readiness. On a non-2xx handshake whose body produces no SSE event (empty, whitespace
 * or comment-only, e.g. a bare {@code 401} from a misconfigured server) the SDK parses
 * the body as an SSE stream, never emits an {@code endpoint} event, and the
 * {@code connect()} {@link Mono} never completes: {@code sendMessage(initialize)} then
 * hangs until the request timeout instead of surfacing the real reason. A non-SSE line
 * happens to error in the SDK, but that is an accident of the body's shape, not a
 * contract.
 *
 * <p>
 * This wrapper sends a {@code GET} to the same SSE endpoint URI the delegate connects to
 * (including any query string), but it does not reproduce the SDK request byte for byte:
 * the SDK's own connect adds {@code Cache-Control} and {@code MCP-Protocol-Version}
 * headers this probe omits, and the delegate receives the endpoint path only, without the
 * query. The probe checks reachability, not identity: on a server that answers the probe
 * differently from the real handshake (for example {@code /sse?token=x} probing 200 while
 * the path-only connect gets 401), the probe stands down in favour of the delegate's own
 * semantics. The body handler completes on the response headers and immediately cancels
 * the subscription, so the probe exchange is closed; that close is asynchronous and is
 * observed by the server some time after the headers arrive, not necessarily before the
 * delegate opens the real stream. A status the probe cannot interpret as a verdict on the
 * SSE endpoint (anything other than a clean 2xx or the definitive {@code 404 Not Found})
 * is <strong>passed through</strong>: the wrapper lets the delegate try and keeps
 * whatever error or behaviour the SDK transport produces. Only a definitive {@code 404}
 * (the endpoint does not exist) fails fast. This is deliberately conservative: the probe
 * runs on the connect path of every proxy session, and a false negative here would reject
 * upstreams that connect fine today. HTTP HEAD is not used: Starlette and Spring MVC
 * auto-map HEAD onto the GET handler, so a HEAD probe creates the very server-side SSE
 * session it is meant to avoid.
 *
 * <p>
 * The probe reuses the delegate's {@link HttpClient} (see
 * {@link SharedHttpClientBuilder}) instead of allocating its own: each extra
 * {@code HttpClient} costs a selector thread that survives
 * {@link io.modelcontextprotocol.client.transport.HttpClientSseClientTransport#closeGracefully()},
 * and a per-session probe client doubles that cost (measured on this codebase: 10
 * sessions with a dedicated probe client spawn 20 selector threads that outlive the
 * sessions).
 *
 * <p>
 * The probe uses the shared HTTP executor and HTTP/1.1, matching the SDK's own client
 * configuration, so the probe and the real connect negotiate the same protocol. The probe
 * timeout is supplied by the factory (bound to {@code spring.ai.mcp.inspector.timeouts.*}
 * when the factory is wired by the auto-configuration) instead of a hardcoded constant.
 *
 * @author Artem Simeshin
 */
final class SsePreflightTransport implements McpClientTransport {

	private static final String ACCEPT_EVENT_STREAM = "text/event-stream";

	private final McpClientTransport delegate;

	private final URI targetUri;

	private final HttpRequest.Builder requestTemplate;

	private final McpSyncHttpClientRequestCustomizer requestCustomizer;

	private final HttpClient sharedClient;

	/**
	 * Wall-clock budget for the preflight probe. On expiry the in-flight exchange is
	 * cancelled and the connect fails with an {@link McpTransportException}.
	 */
	private final Duration probeTimeout;

	/**
	 * Tracks the in-flight preflight HTTP exchange so it can be cancelled on timeout,
	 * downstream cancellation, or {@link #closeGracefully()}.
	 */
	private volatile CompletableFuture<?> pendingProbe;

	SsePreflightTransport(final McpClientTransport delegate, final URI targetUri,
			final HttpRequest.Builder requestTemplate, final McpSyncHttpClientRequestCustomizer requestCustomizer,
			final HttpClient sharedClient, final Duration probeTimeout) {
		this.delegate = delegate;
		this.targetUri = targetUri;
		this.requestTemplate = requestTemplate;
		this.requestCustomizer = requestCustomizer;
		this.sharedClient = sharedClient;
		this.probeTimeout = probeTimeout;
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
		return Mono.fromRunnable(this::cancelPendingProbe).then(this.delegate.closeGracefully());
	}

	@Override
	public <T> T unmarshalFrom(final Object data, final TypeRef<T> typeRef) {
		return this.delegate.unmarshalFrom(data, typeRef);
	}

	private void cancelPendingProbe() {
		final CompletableFuture<?> pending = this.pendingProbe;
		if (pending != null && !pending.isDone()) {
			pending.cancel(true);
		}
		this.pendingProbe = null;
	}

	/**
	 * Sends the header-only {@code GET} probe and interprets the status.
	 *
	 * <p>
	 * Status handling is intentionally minimal:
	 * <ul>
	 * <li>2xx: the endpoint answers, the delegate may connect.</li>
	 * <li>404: the endpoint does not exist on this server. Fails fast so the user sees
	 * the real reason instead of the SDK hanging on a non-SSE body.</li>
	 * <li>anything else (401, 403, 405, 5xx, 3xx, ...): not a verdict this probe can
	 * defend. The status is passed through to the delegate, which performs the real
	 * handshake with its own (redirect, server-error) semantics. Hard-failing here would
	 * regress upstreams that connect today: develop/1.x had no preflight, so a server
	 * answering 501 to a probe while its GET works would stop connecting.</li>
	 * </ul>
	 * @return a {@link Mono} completing when the endpoint is confirmed reachable, or
	 * erroring with {@link McpTransportException} on a definitive rejection or timeout
	 */
	private Mono<Void> preflight() {
		return Mono.deferContextual((context) -> {
			final HttpRequest probeRequest = buildProbeRequest(context);
			final CompletableFuture<HttpResponse<Void>> future = this.sharedClient.sendAsync(probeRequest,
					headerOnlyBodyHandler());
			this.pendingProbe = future;
			final AtomicBoolean cancelled = new AtomicBoolean();
			return Mono.<HttpResponse<Void>>create((sink) -> {
				sink.onCancel(() -> {
					cancelled.set(true);
					SsePreflightTransport.this.cancelPendingProbe();
				});
				future.whenComplete((result, error) -> {
					if (cancelled.get()) {
						return;
					}
					if (error != null) {
						sink.error(error);
					}
					else {
						sink.success(result);
					}
				});
			}).timeout(this.probeTimeout).flatMap((response) -> {
				this.pendingProbe = null;
				final int status = response.statusCode();
				if (status >= 200 && status < 300) {
					return Mono.<Void>empty();
				}
				if (status == 404) {
					return Mono.<Void>error(new McpTransportException(
							"SSE endpoint not found on upstream (HTTP status " + status + ")"));
				}
				// Not a definitive verdict on the endpoint: let the delegate try with
				// its own auth/redirect semantics. The probe exchange has already been
				// closed by the header-only body handler.
				return Mono.<Void>empty();
			}).onErrorResume(TimeoutException.class, (e) -> {
				SsePreflightTransport.this.cancelPendingProbe();
				return Mono
					.error(new McpTransportException("SSE preflight probe timed out after " + this.probeTimeout));
			});
		});
	}

	/**
	 * Builds the header-only GET probe, applying the same auth headers and customizers
	 * the SDK transport uses on the real connect.
	 * @param context the reactor context carrying the transport context
	 * @return the built probe {@link HttpRequest}
	 */
	private HttpRequest buildProbeRequest(final ContextView context) {
		final HttpRequest.Builder builder = this.requestTemplate.copy()
			.uri(this.targetUri)
			.header("Accept", ACCEPT_EVENT_STREAM)
			.GET();
		if (this.requestCustomizer != null) {
			final McpTransportContext transportContext = context.getOrDefault(McpTransportContext.KEY,
					McpTransportContext.EMPTY);
			this.requestCustomizer.customize(builder, "GET", this.targetUri, null, transportContext);
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
