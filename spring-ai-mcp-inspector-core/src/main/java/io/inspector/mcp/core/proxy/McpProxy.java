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

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.timeline.McpTrafficRecorder;

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

	private final JsonMapper objectMapper;

	private final JacksonMcpJsonMapper mcpJsonMapper;

	private final McpTrafficRecorder trafficRecorder;

	/**
	 * Creates a proxy with no traffic recording.
	 * @param objectMapper the JSON mapper (may be {@code null} to use a default)
	 */
	public McpProxy(final JsonMapper objectMapper) {
		this(objectMapper, null);
	}

	/**
	 * Creates a proxy with an optional traffic recorder.
	 * @param objectMapper the JSON mapper (may be {@code null} to use a default)
	 * @param trafficRecorder the traffic recorder, or {@code null} to skip recording
	 */
	public McpProxy(final JsonMapper objectMapper, final McpTrafficRecorder trafficRecorder) {
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.mcpJsonMapper = new JacksonMcpJsonMapper(this.objectMapper);
		this.trafficRecorder = trafficRecorder;
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
			recordOutbound(session, typed, frame);
			return session.targetTransport().sendMessage(typed).doOnError((err) -> {
				LOG.warn("proxy[{}] sendMessage failed: {}", session.sessionId(), err.toString());
				// A failed send to a dead upstream must release any per-request awaiter
				// and the SSE backchannel immediately rather than waiting for the
				// streamable-request timeout.
				session.failUpstream(err);
			});
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
			// justify tearing the session down - anything unrecognized is logged and
			// the relay keeps forwarding. failUpstream is idempotent, so the first
			// terminal signal wins.
			if (failure.reason() != ProxyConnectFailure.Reason.UNKNOWN) {
				session.failUpstream(err);
			}
		}).subscribe();

		// When the session closes, drop its pending request correlations so abandoned
		// calls leave no residue in the recorder.
		if (this.trafficRecorder != null) {
			session.closeSignal().subscribe((ignored) -> this.trafficRecorder.clearSession(session.sessionId()));
		}

		// Route any terminal transport failure (e.g. the upstream MCP server dies
		// mid-session) onto the targetToBrowser sink so the per-request POST awaiter
		// and the SSE backchannel subscriber fail fast instead of blocking to the
		// streamable-request timeout.
		session.targetTransport().setExceptionHandler((err) -> {
			LOG.warn("proxy[{}] upstream transport error: {}", session.sessionId(), String.valueOf(err));
			session.failUpstream(err);
		});

		// Target → browser: the connect handler is called once per inbound frame.
		// We serialize the typed frame back to a JsonNode and emit it on the
		// targetToBrowser sink. Returning Mono.empty() tells the SDK we have no
		// further response to send.
		//
		// The inbound flux's terminal signals are surfaced too: an upstream
		// disconnect that completes/errors the inbound stream is propagated via
		// failUpstream so awaiters and the SSE subscriber are released promptly.
		//
		// Internal liveness-probe responses are detected by their JSON-RPC id
		// (registered via session.registerProbeId) and filtered out - they must
		// never reach the browser.
		return session.targetTransport().connect((inbound) -> inbound.flatMap((message) -> {
			// Skip internal probe responses - they are not real MCP messages
			// and must not be forwarded to the browser.
			if (message instanceof io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse response) {
				final Object id = response.id();
				if (id instanceof Number numId && session.isProbeId(numId.intValue())) {
					session.touch();
					return Mono.<JSONRPCMessage>empty();
				}
			}
			final JsonNode body = toJsonNode(message);
			if (body != null) {
				recordInbound(session, message, body);
				final Sinks.EmitResult er = session.targetToBrowser().tryEmitNext(body);
				if (er.isFailure()) {
					LOG.debug("proxy[{}] target->browser emit failure: {}", session.sessionId(), er.name());
				}
				session.touch();
			}
			return Mono.<JSONRPCMessage>empty();
		}).doOnError((err) -> session.failUpstream(err))).doOnError((err) -> session.failUpstream(err));
	}

	/**
	 * Records an outbound (browser → target) message via the traffic recorder, if
	 * configured.
	 * @param session the proxy session (must not be {@code null})
	 * @param typed the typed JSON-RPC message (must not be {@code null})
	 * @param frame the raw JSON frame (may be {@code null})
	 */
	private void recordOutbound(final ProxySession session, final JSONRPCMessage typed, final JsonNode frame) {
		if (this.trafficRecorder != null) {
			try {
				this.trafficRecorder.recordOutbound(session.sessionId(), typed, frame);
			}
			catch (final Exception ex) {
				LOG.warn("proxy[{}] traffic recorder outbound failed: {}", session.sessionId(), ex.toString());
			}
		}
	}

	/**
	 * Records an inbound (target → browser) message via the traffic recorder, if
	 * configured.
	 * @param session the proxy session (must not be {@code null})
	 * @param message the typed JSON-RPC message (must not be {@code null})
	 * @param body the serialised JSON body (may be {@code null})
	 */
	private void recordInbound(final ProxySession session, final JSONRPCMessage message, final JsonNode body) {
		if (this.trafficRecorder != null) {
			try {
				this.trafficRecorder.recordInbound(session.sessionId(), message, body);
			}
			catch (final Exception ex) {
				LOG.warn("proxy[{}] traffic recorder inbound failed: {}", session.sessionId(), ex.toString());
			}
		}
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
