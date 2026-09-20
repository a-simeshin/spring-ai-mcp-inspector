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

package io.inspector.mcp.core.keepalive;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import reactor.core.publisher.Mono;

/**
 * Decorating {@link McpClientTransport} that taps inbound JSON-RPC frames, records
 * keep-alive {@code ping} requests against a {@link KeepAliveTracker}, then forwards
 * every frame to the delegate unchanged so the SDK's built-in ping responder still
 * answers.
 *
 * <p>
 * Ping detection is intentionally narrow: a frame is a keep-alive ping iff it is a
 * {@link JSONRPCRequest} whose {@code method} equals {@value #PING_METHOD}. Notifications
 * named {@code ping} and outbound pings issued by the inspector itself do not match,
 * because the SDK models client-issued {@code ping} on the outbound half and the
 * inspector never sends a request with that method through the decorated transport.
 *
 * <p>
 * The decorator is stateless with respect to the wrapped transport: {@code connect},
 * {@code sendMessage}, {@code closeGracefully}, {@code setExceptionHandler},
 * {@code protocolVersions} and {@code unmarshalFrom} all forward to the delegate.
 *
 * @author Artem Simeshin
 */
public final class PingDetectingClientTransport implements McpClientTransport {

	/** MCP method name for keep-alive ping. */
	public static final String PING_METHOD = "ping";

	private final McpClientTransport delegate;

	private final KeepAliveTracker tracker;

	/**
	 * Wraps {@code delegate} so inbound pings are recorded on {@code tracker}.
	 * @param delegate the transport to wrap (must not be {@code null})
	 * @param tracker the per-session tracker (must not be {@code null})
	 */
	public PingDetectingClientTransport(final McpClientTransport delegate, final KeepAliveTracker tracker) {
		if (delegate == null || tracker == null) {
			throw new IllegalArgumentException("delegate and tracker must not be null");
		}
		this.delegate = delegate;
		this.tracker = tracker;
	}

	@Override
	public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return this.delegate.connect((inbound) -> handler.apply(inbound.doOnNext(this::recordIfPing)));
	}

	@Override
	public Mono<Void> sendMessage(final JSONRPCMessage message) {
		return this.delegate.sendMessage(message);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public <T> T unmarshalFrom(final Object data, final TypeRef<T> typeRef) {
		return this.delegate.unmarshalFrom(data, typeRef);
	}

	@Override
	public List<String> protocolVersions() {
		return this.delegate.protocolVersions();
	}

	@Override
	public void setExceptionHandler(final java.util.function.Consumer<Throwable> handler) {
		this.delegate.setExceptionHandler(handler);
	}

	/**
	 * Returns the tracker this decorator records into.
	 * @return the tracker (never {@code null})
	 */
	public KeepAliveTracker tracker() {
		return this.tracker;
	}

	private void recordIfPing(final JSONRPCMessage message) {
		if (message instanceof JSONRPCRequest request && PING_METHOD.equals(request.method())) {
			this.tracker.recordPing(Instant.now());
		}
	}

}
