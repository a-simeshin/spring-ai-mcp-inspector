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

package io.inspector.mcp.core.timeline;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import reactor.core.publisher.Mono;

/**
 * Decorating {@link McpClientTransport} that records every JSON-RPC message crossing the
 * transport boundary into the {@link McpClientTrafficRecorder}.
 *
 * <p>
 * Outbound messages ({@link #sendMessage}) are captured before they reach the delegate.
 * Inbound messages are captured by wrapping the handler function passed to
 * {@link #connect(Function)}. The decorator is transport-agnostic and works identically
 * for stdio, SSE and streamable-HTTP transports.
 *
 * @author Artem Simeshin
 */
public final class RecordingMcpClientTransport implements McpClientTransport {

	private final McpClientTransport delegate;

	private final String clientName;

	private final String transportType;

	private final McpClientTrafficRecorder trafficRecorder;

	/**
	 * Creates a new recording transport decorator.
	 * @param delegate the real transport to delegate to (must not be {@code null})
	 * @param clientName the MCP client name as configured in
	 * {@code spring.ai.mcp.client.*.connections.<name>} (must not be {@code null})
	 * @param transportType a human-readable transport type label, e.g. {@code "stdio"},
	 * {@code "sse"} or {@code "streamable-http"} (must not be {@code null})
	 * @param trafficRecorder the recorder to push events to (must not be {@code null})
	 */
	public RecordingMcpClientTransport(final McpClientTransport delegate, final String clientName,
			final String transportType, final McpClientTrafficRecorder trafficRecorder) {
		if (delegate == null) {
			throw new IllegalArgumentException("delegate must not be null");
		}
		if (clientName == null) {
			throw new IllegalArgumentException("clientName must not be null");
		}
		if (transportType == null) {
			throw new IllegalArgumentException("transportType must not be null");
		}
		if (trafficRecorder == null) {
			throw new IllegalArgumentException("trafficRecorder must not be null");
		}
		this.delegate = delegate;
		this.clientName = clientName;
		this.transportType = transportType;
		this.trafficRecorder = trafficRecorder;
	}

	@Override
	public Mono<Void> sendMessage(final JSONRPCMessage message) {
		if (message instanceof JSONRPCRequest request) {
			this.trafficRecorder.recordClientRequest(this.clientName, this.transportType, request);
		}
		else if (message instanceof JSONRPCNotification notification) {
			this.trafficRecorder.recordClientNotification(this.clientName, this.transportType, notification);
		}
		else if (message instanceof JSONRPCResponse response) {
			// Client answers server-initiated requests (sampling, elicitation,
			// roots/list)
			// with a JSONRPCResponse via sendMessage; record it with client response
			// semantics so the srv:-prefixed pending correlation is released.
			this.trafficRecorder.recordClientResponse(this.clientName, this.transportType, response);
		}
		return this.delegate.sendMessage(message);
	}

	@Override
	public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> recordingHandler = (inbound) -> {
			if (inbound == null) {
				return handler.apply(null);
			}
			return inbound.flatMap((msg) -> {
				recordInbound(msg);
				return Mono.just(msg);
			}).transform(handler::apply);
		};
		return this.delegate.connect(recordingHandler);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	@Override
	public <T> T unmarshalFrom(final Object source, final TypeRef<T> typeRef) {
		return this.delegate.unmarshalFrom(source, typeRef);
	}

	@Override
	public void setExceptionHandler(final Consumer<Throwable> exceptionHandler) {
		this.delegate.setExceptionHandler(exceptionHandler);
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public List<String> protocolVersions() {
		return this.delegate.protocolVersions();
	}

	/**
	 * Returns the client name this transport decorator is associated with.
	 * @return the client name (never {@code null})
	 */
	public String clientName() {
		return this.clientName;
	}

	/**
	 * Returns the transport type label.
	 * @return the transport type (never {@code null})
	 */
	public String transportType() {
		return this.transportType;
	}

	/**
	 * Returns the underlying delegate transport.
	 * @return the delegate (never {@code null})
	 */
	public McpClientTransport delegate() {
		return this.delegate;
	}

	private void recordInbound(final JSONRPCMessage message) {
		if (message instanceof JSONRPCResponse response) {
			this.trafficRecorder.recordClientResponse(this.clientName, this.transportType, response);
		}
		else if (message instanceof JSONRPCRequest request) {
			// Server-initiated request (sampling, elicitation, roots/list)
			this.trafficRecorder.recordServerRequest(this.clientName, this.transportType, request);
		}
		else if (message instanceof JSONRPCNotification notification) {
			this.trafficRecorder.recordServerNotification(this.clientName, this.transportType, notification);
		}
	}

}
