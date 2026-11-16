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
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.Mono;

/**
 * Test stand transport for the OAuth2 token-refresh pinning (issue #375).
 *
 * Unlike a Mockito mock, this is a real {@link McpClientTransport}: it reads the shared
 * live authorization reference lazily at subscription time, once per outgoing call (the
 * connect prologue and every {@link #sendMessage(JSONRPCMessage)} subscription), the way
 * the production HTTP transports read their request customizer's reference, and records
 * one snapshot per outgoing call in call order.
 *
 * <p>
 * Wire it the way {@code ProxyHandler} wires a session: hand the SAME
 * {@link AtomicReference} instance to this transport and to the {@link ProxySession}
 * constructor. When the OAuth2 one-retry path refreshes the token through
 * {@code session.authorizationRef().set(...)}, the next snapshot must carry the fresh
 * header. If the session ever stops sharing the reference, the snapshots keep showing the
 * stale token, which is exactly the defect class this stand exists to catch.
 *
 * @author Artem Simeshin
 */
public final class AuthorizationCaptureTransport implements McpClientTransport {

	private final AtomicReference<String> authorizationRef;

	private final List<OutgoingCall> calls = new ArrayList<>();

	private final List<Throwable> scriptedSendFailures = new ArrayList<>();

	/**
	 * Constructs a capturing transport bound to the live authorization reference.
	 * @param authorizationRef the shared live authorization reference; the same instance
	 * must also be handed to the session under test
	 */
	public AuthorizationCaptureTransport(final AtomicReference<String> authorizationRef) {
		this.authorizationRef = authorizationRef;
	}

	/**
	 * Scripts the next {@link #sendMessage(JSONRPCMessage)} to fail with an HTTP 401
	 * authorization error after the snapshot has been recorded: the SDK transport
	 * exception the proxy classifies as retryable, so the one-retry path refreshes the
	 * token and re-sends.
	 */
	public void failNextSendWithUnauthorized() {
		synchronized (this.scriptedSendFailures) {
			this.scriptedSendFailures.add(unauthorized());
		}
	}

	/**
	 * Scripts the next {@link #sendMessage(JSONRPCMessage)} to fail with the given error
	 * after the snapshot has been recorded.
	 * @param cause the error to fail the next send with
	 */
	public void failNextSendWith(final Throwable cause) {
		synchronized (this.scriptedSendFailures) {
			this.scriptedSendFailures.add(cause);
		}
	}

	/**
	 * Returns the ordered snapshot of every outgoing call so far: index 0 is the earliest
	 * call, whether the connect prologue or the first message.
	 * @return an immutable ordered snapshot list
	 */
	public List<OutgoingCall> capturedCalls() {
		synchronized (this.calls) {
			return List.copyOf(this.calls);
		}
	}

	/**
	 * Convenience view over {@link #capturedCalls()}: the Authorization header of every
	 * outgoing call, in call order.
	 * @return the ordered Authorization header values
	 */
	public List<String> authorizationHeaders() {
		return this.capturedCalls().stream().map(OutgoingCall::authorization).toList();
	}

	@Override
	public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return Mono.defer(() -> {
			this.record(null);
			return Mono.empty();
		});
	}

	// Mono.defer on purpose: the proxy composes the one-retry as
	// refreshToken().then(sendMessage(typed)), so sendMessage runs eagerly while
	// building the retry chain. Production transports are lazy Mono factories that
	// read the Authorization reference at subscription time; recording inside
	// defer reproduces exactly that, which is what makes the retry snapshot carry
	// the refreshed header.
	@Override
	public Mono<Void> sendMessage(final JSONRPCMessage message) {
		return Mono.defer(() -> {
			this.record(message);
			final Throwable scripted;
			synchronized (this.scriptedSendFailures) {
				scripted = this.scriptedSendFailures.isEmpty() ? null : this.scriptedSendFailures.remove(0);
			}
			return (scripted != null) ? Mono.error(scripted) : Mono.empty();
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.empty();
	}

	@Override
	public <T> T unmarshalFrom(final Object data, final TypeRef<T> typeRef) {
		throw new UnsupportedOperationException("the capture stand never receives inbound wire payloads");
	}

	private void record(final JSONRPCMessage message) {
		// Lazy read at subscription time: this is the pinning surface. A detached
		// (copied) reference would keep returning the stale token here after a
		// refresh.
		final String header = this.authorizationRef.get();
		synchronized (this.calls) {
			this.calls.add(new OutgoingCall(header, message));
		}
	}

	private static McpHttpClientTransportAuthorizationException unauthorized() {
		final HttpRequestSnapshot snapshot = new HttpRequestSnapshot(URI.create("https://target.example/mcp"), "POST",
				HttpHeaders.of(Map.of(), (name, value) -> true));
		final HttpResponse.ResponseInfo responseInfo = new HttpResponse.ResponseInfo() {

			@Override
			public int statusCode() {
				return 401;
			}

			@Override
			public HttpHeaders headers() {
				return HttpHeaders.of(Map.of(), (name, value) -> true);
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_1_1;
			}

		};
		return new McpHttpClientTransportAuthorizationException("Unauthorized", snapshot, responseInfo);
	}

	/**
	 * One outgoing call snapshot: the Authorization header this transport would have put
	 * on the wire, and the outgoing JSON-RPC message ({@code null} for the connect
	 * prologue).
	 *
	 * @param authorization the Authorization header value at call time (may be
	 * {@code null})
	 * @param message the outgoing JSON-RPC message ({@code null} for connect)
	 */
	public record OutgoingCall(String authorization, JSONRPCMessage message) {
	}

}
