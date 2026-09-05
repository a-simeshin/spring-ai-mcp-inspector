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

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

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
 * <li>fires {@link #closeSignal()} so every browser-facing stream terminates,</li>
 * <li>completes both sinks so subscribers tear down,</li>
 * <li>calls {@code closeGracefully()} on the upstream transport.</li>
 * </ol>
 *
 * @author Artem Simeshin
 */
public final class ProxySession {

	private static final Logger LOG = LoggerFactory.getLogger(ProxySession.class);

	/**
	 * Upper bound on the upstream {@code closeGracefully()} wait — see {@link #close()}.
	 */
	private static final Duration UPSTREAM_CLOSE_TIMEOUT = Duration.ofSeconds(5);

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

	/**
	 * Set by {@link #failUpstream(Throwable)}. Lets tests and the controller observe that
	 * the upstream transport has terminally failed without inspecting the sink.
	 */
	private final AtomicBoolean upstreamTerminated = new AtomicBoolean(false);

	/**
	 * Fired by {@link #close()}. Lock-free, hence never lost — see
	 * {@link #closeSignal()}.
	 */
	private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

	private final Mono<Void> closeSignal;

	/**
	 * IDs of active liveness-probe requests that must not be forwarded to the browser.
	 */
	private final Set<Integer> probeIds = ConcurrentHashMap.newKeySet();

	/** Monotonic counter for generating unique probe request IDs. */
	private static final AtomicInteger PROBE_ID_SEQ = new AtomicInteger(0);

	public ProxySession(final String sessionId, final McpClientTransport targetTransport,
			final Sinks.Many<JsonNode> browserToTarget, final Sinks.Many<JsonNode> targetToBrowser) {
		this.sessionId = sessionId;
		this.targetTransport = targetTransport;
		this.browserToTarget = browserToTarget;
		this.targetToBrowser = targetToBrowser;
		this.lastActivity = Instant.now();
		// suppressCancel: one subscriber going away (a browser tab closing its SSE
		// stream) must not cancel — and so terminate — the future every other
		// subscriber is waiting on.
		this.closeSignal = Mono.fromFuture(() -> this.closeFuture, true);
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
	 * Returns {@code true} once the upstream transport has signalled terminal failure
	 * (its {@code connect()} Mono errored, an inbound frame errored, or the
	 * {@link McpProxy} pump called {@link #failUpstream(Throwable)}).
	 * @return {@code true} if the upstream transport has terminated
	 */
	public boolean isUpstreamTerminated() {
		return this.upstreamTerminated.get();
	}

	/**
	 * Propagates an upstream-transport terminal failure to the browser side. Idempotent —
	 * only the first call fails the {@link #targetToBrowser} sink; subsequent calls are
	 * no-ops. After this call {@link #isUpstreamTerminated()} returns {@code true}.
	 *
	 * <p>
	 * Failing the {@code targetToBrowser} sink makes both the long-lived SSE backchannel
	 * subscriber and every per-request POST awaiter fail fast instead of blocking to the
	 * streamable-request timeout.
	 * @param error the terminal error from the upstream transport (may be {@code null},
	 * in which case the sink is completed rather than errored)
	 */
	public void failUpstream(final Throwable error) {
		if (!this.upstreamTerminated.compareAndSet(false, true)) {
			return;
		}
		try {
			if (error != null) {
				this.targetToBrowser.tryEmitError(error);
			}
			else {
				this.targetToBrowser.tryEmitComplete();
			}
		}
		catch (final Exception ignored) {
			// tryEmitError / tryEmitComplete never throw; defensive only
		}
	}

	/**
	 * Completes as soon as {@link #close()} is called, and immediately on subscribe if
	 * the session is already closed. Every long-lived browser-facing stream ends itself
	 * with {@code takeUntilOther(session.closeSignal())}.
	 *
	 * <p>
	 * It exists because completing the sinks is <em>not</em> a reliable way to end those
	 * streams. Both sinks are wrapped in Reactor's {@code SinkManySerialized}, whose emit
	 * methods return {@code FAIL_NON_SERIALIZED} the moment another thread owns the sink;
	 * the {@code targetToBrowser} subscriber serialises JSON and writes it to the
	 * browser's socket inside {@code tryEmitNext} on the emitting thread, so a session
	 * relaying frames when {@code SIGTERM} arrives loses the race often (measured: 176 of
	 * 200 completions dropped). Retrying with {@code emitComplete(busyLooping(...))} lost
	 * far fewer but still lost some — and paid for it with an uninterruptible spin on
	 * whatever thread called {@code close()}, which on webflux is the Netty event loop.
	 *
	 * <p>
	 * A {@link CompletableFuture} contends with nothing, so termination no longer depends
	 * on winning a lock the emitting thread happens to hold.
	 * @return a {@link Mono} that completes when this session closes
	 */
	public Mono<Void> closeSignal() {
		return this.closeSignal;
	}

	/**
	 * Registers a probe request ID so that the proxy's inbound handler can recognise the
	 * matching response and skip forwarding it to the browser.
	 * @param id the JSON-RPC request id of the probe
	 */
	public void registerProbeId(final int id) {
		this.probeIds.add(id);
	}

	/**
	 * Returns {@code true} if the given JSON-RPC id belongs to an internal liveness probe
	 * whose response must not be forwarded to the browser.
	 * @param id the JSON-RPC request/response id to check
	 * @return {@code true} if this is a probe id
	 */
	public boolean isProbeId(final int id) {
		return this.probeIds.contains(id);
	}

	/**
	 * Removes a previously registered probe ID from the set. Called after the matching
	 * probe response has been filtered out, preventing unbounded growth of the set.
	 * @param id the probe id to remove
	 */
	public void removeProbeId(final int id) {
		this.probeIds.remove(id);
	}

	/**
	 * Generates the next unique probe request ID. The ID is automatically registered so
	 * its response will be filtered from the browser stream.
	 * @return a unique negative probe ID
	 */
	public int nextProbeId() {
		// Use negative IDs to avoid collision with normal MCP request IDs
		final int id = -Math.abs(PROBE_ID_SEQ.incrementAndGet());
		this.probeIds.add(id);
		return id;
	}

	/**
	 * Tears the session down. Safe to call from multiple threads — only the first
	 * invocation does work; subsequent calls are no-ops.
	 */
	public void close() {
		if (!this.closed.compareAndSet(false, true)) {
			return;
		}
		// First, and lock-free: this is what actually ends the browser-facing streams.
		this.closeFuture.complete(null);
		// Best-effort. Terminates the sinks for anything subscribed straight to them
		// (per-request awaiters, the browser->target pump) when uncontended, and is
		// simply skipped when another thread owns the sink — closeSignal() has already
		// covered the streams that matter.
		this.browserToTarget.tryEmitComplete();
		this.targetToBrowser.tryEmitComplete();
		final Mono<Void> upstreamClose = this.targetTransport.closeGracefully();
		if (Schedulers.isInNonBlockingThread()) {
			// On the reactive stack close() arrives straight from a Netty event loop —
			// ProxyHandler.deleteMcp calls it inline, as do the SSE doOnTerminate hooks.
			// Reactor refuses to block such a thread: block() throws
			// IllegalStateException before it ever subscribes, so the upstream transport
			// was never closed at all and every proxied session stayed open on the target
			// server. This went unseen because those tests used to boot Tomcat-reactive,
			// whose servlet threads are blocking-friendly. Hand the close to a worker and
			// let the DELETE answer immediately; the timeout keeps a wedged upstream from
			// pinning that worker forever.
			upstreamClose.timeout(UPSTREAM_CLOSE_TIMEOUT)
				.subscribeOn(Schedulers.boundedElastic())
				.subscribe((ignored) -> {
				}, (ex) -> LOG.debug("proxy[{}] upstream close failed: {}", this.sessionId, ex.toString()));
			return;
		}
		try {
			// Bounded: close() also runs on the context-shutdown thread, serially for
			// every open session. An unbounded block there lets one wedged upstream
			// (dead remote server, hung stdio child) hang shutdown forever.
			upstreamClose.block(UPSTREAM_CLOSE_TIMEOUT);
		}
		catch (final Exception ex) {
			// Best-effort shutdown, but never silent: a swallowed exception here is
			// exactly what hid the event-loop bug above for as long as it lasted.
			LOG.debug("proxy[{}] upstream close failed: {}", this.sessionId, ex.toString());
		}
	}

}
