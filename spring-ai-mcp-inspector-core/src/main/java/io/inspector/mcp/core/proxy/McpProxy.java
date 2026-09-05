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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Wires a {@link ProxySession}'s two sinks to the target {@link McpClientTransport}.
 *
 * <p>
 * Port of upstream {@code server/src/mcpProxy.ts}. Pure JSON-RPC frame relay:
 *
 * <pre>
 *   browser ──POST──→ browserToTarget ──→ targetTransport.sendMessage(...)
 *
 *   browser ←──SSE── targetToBrowser ←── connect(handler that emits to sink)
 * </pre>
 *
 * <p>
 * The handler registered on
 * {@link McpClientTransport#connect(java.util.function.Function)} is invoked by the SDK
 * for every inbound message from the target. The handler emits the message body into the
 * {@code targetToBrowser} sink and returns {@link Mono#empty()}; the proxy never
 * originates JSON-RPC frames itself, so there is nothing to send back through the
 * handler.
 *
 * @author Artem Simeshin
 */
public final class McpProxy {

	private static final Logger LOG = LoggerFactory.getLogger(McpProxy.class);

	private final ObjectMapper objectMapper;

	private final JacksonMcpJsonMapper mcpJsonMapper;

	public McpProxy(final ObjectMapper objectMapper) {
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
		this.mcpJsonMapper = new JacksonMcpJsonMapper(this.objectMapper);
	}

	/**
	 * Starts both relay halves on {@code session}. Returns a {@link Mono} that completes
	 * once the upstream transport's {@code connect()} call has emitted its readiness
	 * signal (or errors on failure).
	 *
	 * <p>
	 * Subscribes the {@code browserToTarget} sink to {@code targetTransport.sendMessage}.
	 * Each browser frame is deserialized into a typed
	 * {@link io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage} via
	 * {@link McpSchema#deserializeJsonRpcMessage(io.modelcontextprotocol.json.McpJsonMapper, String)}.
	 * @param session the proxy session to wire up (never {@code null})
	 * @return a {@link Mono} that completes when the upstream transport is connected
	 */
	public Mono<Void> start(final ProxySession session) {
		// Browser → target: every frame the controllers push into browserToTarget
		// is deserialized then forwarded to the upstream transport.
		// takeUntilOther: close() may fail to complete the sink if another thread owns
		// it at that instant, so the pump is unsubscribed off the session's lock-free
		// close signal instead of trusting the sink's terminal event to arrive.
		session.browserToTarget().asFlux().takeUntilOther(session.closeSignal()).flatMap((frame) -> {
			final JSONRPCMessage typed = toTyped(frame);
			if (typed == null) {
				return Mono.empty();
			}
			return session.targetTransport()
				.sendMessage(typed)
				.doOnError((err) -> LOG.warn("proxy[{}] sendMessage failed: {}", session.sessionId(), err.toString()));
		}).onErrorContinue((err, obj) -> {
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(err);
			LOG.warn("proxy[{}] browser->target stream error ({}): {}", session.sessionId(), failure.reason().wire(),
					err.toString());
			// Surface the failure to the browser side too. The SDK masks sendMessage
			// errors (its onErrorComplete hook) so the doOnError above is not a
			// reliable probe: without this, a DEAD upstream leaves the per-request
			// POST awaiter and the SSE backchannel blocked until the
			// streamable-request timeout. But the SDK also re-surfaces protocol-level
			// replies from a LIVE server (e.g. a 404 "session not found") through this
			// same pump, so only transport-level failures (refused / dns / timeout)
			// justify tearing the session down — anything unrecognized is logged and
			// the relay keeps forwarding. failUpstream is idempotent, so the first
			// terminal signal wins.
			if (failure.reason() != ProxyConnectFailure.Reason.UNKNOWN) {
				session.failUpstream(err);
			}
		}).subscribe();

		// Target → browser: the connect handler is called once per inbound frame.
		// We serialize the typed frame back to a JsonNode and emit it on the
		// targetToBrowser sink. Returning Mono.empty() tells the SDK we have no
		// further response to send.
		return session.targetTransport().connect((inbound) -> inbound.flatMap((message) -> {
			// Skip internal probe responses - they are not real MCP messages
			// and must not be forwarded to the browser.
			if (message instanceof McpSchema.JSONRPCResponse response) {
				final Object id = response.id();
				if (id instanceof Number numId && session.isProbeId(numId.intValue())) {
					// Probe responses are internal traffic - they must not count as
					// activity for session reaping, and the probe id must be removed
					// to prevent unbounded growth of the probeIds set.
					session.removeProbeId(numId.intValue());
					return Mono.<JSONRPCMessage>empty();
				}
			}
			final JsonNode body = toJsonNode(message);
			if (body != null) {
				final Sinks.EmitResult er = session.targetToBrowser().tryEmitNext(body);
				if (er.isFailure()) {
					LOG.debug("proxy[{}] target->browser emit failure: {}", session.sessionId(), er.name());
				}
				session.touch();
			}
			return Mono.<JSONRPCMessage>empty();
		}).doOnTerminate(() -> session.failUpstream(null)));
	}

	/**
	 * Deserializes a raw {@link JsonNode} into a typed JSON-RPC message via the SDK
	 * schema parser.
	 * @param frame the raw JSON node to deserialize
	 * @return the typed message, or {@code null} if deserialization fails
	 */
	private JSONRPCMessage toTyped(final JsonNode frame) {
		try {
			return McpSchema.deserializeJsonRpcMessage(this.mcpJsonMapper, this.objectMapper.writeValueAsString(frame));
		}
		catch (final Exception ex) {
			LOG.warn("proxy: malformed JSON-RPC frame from browser: {}", ex.toString());
			return null;
		}
	}

	/**
	 * Converts a typed JSON-RPC message back to a {@link JsonNode}.
	 * @param message the typed message to serialize
	 * @return the JSON node representation, or {@code null} if serialization fails
	 */
	private JsonNode toJsonNode(final JSONRPCMessage message) {
		try {
			return this.objectMapper.valueToTree(message);
		}
		catch (final Exception ex) {
			LOG.warn("proxy: failed to serialize JSON-RPC frame for browser: {}", ex.toString());
			return null;
		}
	}

}
