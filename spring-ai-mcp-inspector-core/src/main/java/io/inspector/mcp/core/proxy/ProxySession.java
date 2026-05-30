/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 * forwards each to {@link #targetTransport#sendMessage}.</li>
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

	public ProxySession(String sessionId, McpClientTransport targetTransport, Sinks.Many<JsonNode> browserToTarget,
			Sinks.Many<JsonNode> targetToBrowser) {
		this.sessionId = sessionId;
		this.targetTransport = targetTransport;
		this.browserToTarget = browserToTarget;
		this.targetToBrowser = targetToBrowser;
		this.lastActivity = Instant.now();
	}

	public String sessionId() {
		return sessionId;
	}

	public McpClientTransport targetTransport() {
		return targetTransport;
	}

	public Sinks.Many<JsonNode> browserToTarget() {
		return browserToTarget;
	}

	public Sinks.Many<JsonNode> targetToBrowser() {
		return targetToBrowser;
	}

	public String upstreamSessionId() {
		return upstreamSessionId;
	}

	public void upstreamSessionId(String value) {
		this.upstreamSessionId = value;
	}

	public Instant lastActivity() {
		return lastActivity;
	}

	/** Records that a frame was just relayed; called by the proxy controllers. */
	public void touch() {
		this.lastActivity = Instant.now();
	}

	public boolean isClosed() {
		return closed.get();
	}

	/**
	 * Tears the session down. Safe to call from multiple threads — only the first
	 * invocation does work; subsequent calls are no-ops.
	 */
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			browserToTarget.tryEmitComplete();
		}
		catch (Exception ignored) {
			// tryEmitComplete never throws; defensive only
		}
		try {
			targetToBrowser.tryEmitComplete();
		}
		catch (Exception ignored) {
			// tryEmitComplete never throws; defensive only
		}
		try {
			targetTransport.closeGracefully().block();
		}
		catch (Exception ignored) {
			// best-effort shutdown
		}
	}

}
