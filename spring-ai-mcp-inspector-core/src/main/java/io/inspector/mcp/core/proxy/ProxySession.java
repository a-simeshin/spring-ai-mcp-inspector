/*
 * Copyright 2025-present the original author or authors.
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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.Sinks;

/**
 * A single inspector-proxy session: pairs a browser-facing SSE/HTTP channel with an
 * upstream {@link McpClientTransport} that talks to the target MCP server.
 *
 * <p>
 * The session is a pure JSON-RPC frame relay. Two unicast sinks shuttle frames between
 * the two halves:
 *
 * <ul>
 * <li>{@link #browserToTarget}: frames the browser POSTs in are pushed here; a subscriber
 * forwards each to {@link McpClientTransport#sendMessage(JSONRPCMessage)}.</li>
 * <li>{@link #targetToBrowser}: the handler registered on
 * {@link McpClientTransport#connect(java.util.function.Function)} emits every inbound
 * message here; the SSE endpoint streams it to the browser.</li>
 * </ul>
 *
 * <p>
 * {@code browserToTarget} uses {@link Sinks.Many#unicast()} because exactly one
 * subscriber consumes it (the {@link McpProxy#start(ProxySession)} pump that forwards
 * each frame to {@link McpClientTransport#sendMessage(JSONRPCMessage)}).
 *
 * <p>
 * {@code targetToBrowser} uses
 * {@link reactor.core.publisher.Sinks.MulticastReplaySpec#limit(int)} — a bounded-replay
 * multicast sink — because the Streamable-HTTP transport needs two concurrent
 * subscribers:
 *
 * <ul>
 * <li>the long-lived {@code GET /mcp} SSE stream that pushes server-originated frames to
 * the browser, and</li>
 * <li>per-request {@code POST /mcp} awaiters that subscribe with an {@code id}-matching
 * filter to capture the JSON-RPC response that the upstream MCP server emits in reply to
 * a request frame.</li>
 * </ul>
 *
 * <p>
 * The replay buffer (256 frames) means a late {@code POST} subscriber still sees a frame
 * that already arrived from the upstream before the controller had time to subscribe. For
 * the SSE proxy (single subscriber on {@code /sse}) the multi-subscriber sink is a strict
 * superset of the previous unicast contract.
 *
 * <p>
 * Calls to {@link Sinks#tryEmitNext(Object)} are idempotent on failure — the result is
 * logged but not surfaced to the producer.
 *
 * <p>
 * The session is closed via {@link #close()} which:
 *
 * <ol>
 * <li>marks the session closed (idempotent via {@link AtomicBoolean}),</li>
 * <li>completes both sinks so subscribers tear down,</li>
 * <li>calls {@code closeGracefully()} on the upstream transport.</li>
 * </ol>
 *
 * @author Artem Simeshin
 */
public final class ProxySession {

	/** Web-app session identifier. Random UUID by default. */
	private final String sessionId;

	/** Upstream transport to the target MCP server (SSE / Streamable / Stdio). */
	private final McpClientTransport targetTransport;

	/**
	 * Frames from browser → target. Subscriber forwards to
	 * {@link McpClientTransport#sendMessage(JSONRPCMessage)}.
	 */
	private final Sinks.Many<JsonNode> browserToTarget;

	/**
	 * Frames from target → browser. SSE endpoint streams these out as
	 * {@code event: message data: <json>} blocks.
	 */
	private final Sinks.Many<JsonNode> targetToBrowser;

	/** Optional MCP session id captured from the upstream transport's response. */
	private volatile String upstreamSessionId;

	/** Updated on every frame routed in either direction. */
	private volatile Instant lastActivity;

	private final AtomicBoolean closed = new AtomicBoolean(false);

	public ProxySession(final String sessionId, final McpClientTransport targetTransport,
			final Sinks.Many<JsonNode> browserToTarget, final Sinks.Many<JsonNode> targetToBrowser) {
		this.sessionId = sessionId;
		this.targetTransport = targetTransport;
		this.browserToTarget = browserToTarget;
		this.targetToBrowser = targetToBrowser;
		this.lastActivity = Instant.now();
	}

	public String sessionId() {
		return this.sessionId;
	}

	public McpClientTransport targetTransport() {
		return this.targetTransport;
	}

	public Sinks.Many<JsonNode> browserToTarget() {
		return this.browserToTarget;
	}

	public Sinks.Many<JsonNode> targetToBrowser() {
		return this.targetToBrowser;
	}

	public String upstreamSessionId() {
		return this.upstreamSessionId;
	}

	public void upstreamSessionId(final String value) {
		this.upstreamSessionId = value;
	}

	public Instant lastActivity() {
		return this.lastActivity;
	}

	/** Records that a frame was just relayed; called by the proxy controllers. */
	public void touch() {
		this.lastActivity = Instant.now();
	}

	public boolean isClosed() {
		return this.closed.get();
	}

	/**
	 * Tears the session down. Safe to call from multiple threads — only the first
	 * invocation does work; subsequent calls are no-ops.
	 */
	public void close() {
		if (!this.closed.compareAndSet(false, true)) {
			return;
		}
		try {
			this.browserToTarget.tryEmitComplete();
		}
		catch (final Exception ignored) {
			// tryEmitComplete never throws; defensive only
		}
		try {
			this.targetToBrowser.tryEmitComplete();
		}
		catch (final Exception ignored) {
			// tryEmitComplete never throws; defensive only
		}
		try {
			this.targetTransport.closeGracefully().block();
		}
		catch (final Exception ignored) {
			// best-effort shutdown
		}
	}

}
