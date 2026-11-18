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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Decorating {@link McpClientTransport} that records every JSON-RPC message crossing the
 * transport boundary into the {@link McpClientTrafficRecorder} and tracks connection
 * state in the {@link ClientConnectionRegistry}.
 *
 * <p>
 * Outbound messages ({@link #sendMessage}) are captured before they reach the delegate.
 * Inbound messages are captured by wrapping the handler function passed to
 * {@link #connect(Function)}. Outbound {@code JSONRPCResponse} answers to
 * server-initiated requests are recorded as outbound responses (client&rarr;server), not
 * as inbound server responses. The decorator is transport-agnostic and works identically
 * for stdio, SSE and streamable-HTTP transports.
 *
 * @author Artem Simeshin
 */
public final class RecordingMcpClientTransport implements McpClientTransport {

	private static final Logger LOG = LoggerFactory.getLogger(RecordingMcpClientTransport.class);

	private final McpClientTransport delegate;

	private final McpClientTrafficRecorder.ClientEndpoint endpoint;

	private final McpClientTrafficRecorder trafficRecorder;

	private final ClientConnectionRegistry connectionRegistry;

	/**
	 * Creates a new recording transport decorator.
	 * @param delegate the real transport to delegate to (must not be {@code null})
	 * @param endpoint the client endpoint provenance (must not be {@code null})
	 * @param trafficRecorder the recorder to push events to (must not be {@code null})
	 * @param connectionRegistry the registry to track connection state in (must not be
	 * {@code null})
	 */
	public RecordingMcpClientTransport(final McpClientTransport delegate,
			final McpClientTrafficRecorder.ClientEndpoint endpoint, final McpClientTrafficRecorder trafficRecorder,
			final ClientConnectionRegistry connectionRegistry) {
		if (delegate == null) {
			throw new IllegalArgumentException("delegate must not be null");
		}
		if (endpoint == null) {
			throw new IllegalArgumentException("endpoint must not be null");
		}
		if (trafficRecorder == null) {
			throw new IllegalArgumentException("trafficRecorder must not be null");
		}
		if (connectionRegistry == null) {
			throw new IllegalArgumentException("connectionRegistry must not be null");
		}
		this.delegate = delegate;
		this.endpoint = endpoint;
		this.trafficRecorder = trafficRecorder;
		this.connectionRegistry = connectionRegistry;
	}

	@Override
	public Mono<Void> sendMessage(final JSONRPCMessage message) {
		final String clientName = this.endpoint.clientName();
		this.connectionRegistry.recordOutbound(clientName);
		if (message instanceof JSONRPCRequest request) {
			try {
				this.trafficRecorder.recordClientRequest(this.endpoint, request);
			}
			catch (final Exception ex) {
				LOG.warn("client[{}] traffic recorder outbound request failed: {}", clientName, ex.toString());
			}
		}
		else if (message instanceof JSONRPCNotification notification) {
			try {
				this.trafficRecorder.recordClientNotification(this.endpoint, notification);
			}
			catch (final Exception ex) {
				LOG.warn("client[{}] traffic recorder outbound notification failed: {}", clientName, ex.toString());
			}
		}
		else if (message instanceof JSONRPCResponse response) {
			// Client answers server-initiated requests (sampling, elicitation,
			// roots/list) with a JSONRPCResponse via sendMessage; record it as
			// an outbound response (client->server), not as an inbound server
			// response.
			try {
				this.trafficRecorder.recordOutboundResponse(this.endpoint, response);
			}
			catch (final Exception ex) {
				LOG.warn("client[{}] traffic recorder outbound response failed: {}", clientName, ex.toString());
			}
		}
		return this.delegate.sendMessage(message);
	}

	@Override
	public Mono<Void> connect(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		final String clientName = this.endpoint.clientName();
		this.connectionRegistry.markInitializing(clientName, this.endpoint.transportType(), this.endpoint.target());
		final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> recordingHandler = (inbound) -> {
			if (inbound == null) {
				return handler.apply(null);
			}
			return inbound.flatMap((msg) -> {
				this.connectionRegistry.recordInbound(clientName);
				recordInbound(msg);
				return Mono.just(msg);
			}).transform(handler::apply);
		};
		return this.delegate.connect(recordingHandler)
			.doOnSuccess((ignore) -> this.connectionRegistry.markConnected(clientName))
			.doOnError((ex) -> this.connectionRegistry.markFailed(clientName, ex.toString()));
	}

	@Override
	public Mono<Void> closeGracefully() {
		final String clientName = this.endpoint.clientName();
		return this.delegate.closeGracefully().doOnSuccess((ignore) -> this.connectionRegistry.markClosed(clientName));
	}

	@Override
	public <T> T unmarshalFrom(final Object source, final TypeRef<T> typeRef) {
		return this.delegate.unmarshalFrom(source, typeRef);
	}

	@Override
	public void setExceptionHandler(final Consumer<Throwable> exceptionHandler) {
		final String clientName = this.endpoint.clientName();
		this.delegate.setExceptionHandler((ex) -> {
			this.connectionRegistry.markFailed(clientName, ex.toString());
			exceptionHandler.accept(ex);
		});
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public List<String> protocolVersions() {
		return this.delegate.protocolVersions();
	}

	String clientName() {
		return this.endpoint.clientName();
	}

	McpClientTrafficRecorder.ClientEndpoint endpoint() {
		return this.endpoint;
	}

	McpClientTransport delegate() {
		return this.delegate;
	}

	private void recordInbound(final JSONRPCMessage message) {
		if (message instanceof JSONRPCResponse response) {
			try {
				this.trafficRecorder.recordClientResponse(this.endpoint, response);
			}
			catch (final Exception ex) {
				LOG.warn("client[{}] traffic recorder inbound response failed: {}", this.endpoint.clientName(),
						ex.toString());
			}
		}
		else if (message instanceof JSONRPCRequest request) {
			// Server-initiated request (sampling, elicitation, roots/list)
			try {
				this.trafficRecorder.recordServerRequest(this.endpoint, request);
			}
			catch (final Exception ex) {
				LOG.warn("client[{}] traffic recorder inbound request failed: {}", this.endpoint.clientName(),
						ex.toString());
			}
		}
		else if (message instanceof JSONRPCNotification notification) {
			try {
				this.trafficRecorder.recordServerNotification(this.endpoint, notification);
			}
			catch (final Exception ex) {
				LOG.warn("client[{}] traffic recorder inbound notification failed: {}", this.endpoint.clientName(),
						ex.toString());
			}
		}
	}

}
