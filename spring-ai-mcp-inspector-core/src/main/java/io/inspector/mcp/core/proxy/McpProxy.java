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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.AuthProfile;
import io.inspector.mcp.core.auth.AuthProfileStore;
import io.inspector.mcp.core.auth.OAuth2AuthCodeTokenExchanger;
import io.inspector.mcp.core.auth.OAuth2ClientCredentialsTokenManager;
import io.inspector.mcp.core.auth.OAuth2GrantMode;
import io.inspector.mcp.core.auth.OAuth2Profile;
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
 * <p>
 * <strong>OAuth2 one-retry (D9).</strong> When the session is bound to a
 * client-credentials profile and the upstream answers a transport call with {@code 401},
 * the proxy refreshes the token once via
 * {@link OAuth2ClientCredentialsTokenManager#getAccessToken(String, boolean)} and
 * re-issues the SAME call (connect or sendMessage) exactly once. The refreshed
 * authorization value is pushed into {@code session.authorizationRef()} so the
 * transport's request customizer picks it up without rebuilding the transport. A second
 * {@code 401} (or any other failure) propagates to the call site, which maps it to the
 * structured D3 DTO. The SDK's implicit authorization retry is disabled at the transport
 * builder (see {@link ProxyTransportFactory}).
 *
 * @author Artem Simeshin
 */
public final class McpProxy {

	private static final Logger LOG = LoggerFactory.getLogger(McpProxy.class);

	/**
	 * Hard cap on the warm-up wait before the handshake gate opens anyway: a slow
	 * upstream that has not answered the pings must not hold the browser's first call
	 * hostage indefinitely. Measured settle is well under a second on loopback, but the
	 * gate must not open mid-warm-up on a contended runner: a half-warm pool sends the
	 * burst back into the acceptor-freeze ladder this warm-up exists to prevent, which
	 * costs far more than a longer wait on an upstream that answered initialize one round
	 * trip earlier.
	 */
	static final Duration WARMUP_CONNECTIONS_CAP = Duration.ofSeconds(10);

	/**
	 * Window warm-up ping ids stay registered after the pool settles, so a late response
	 * can never leak to the browser before the probe filter drops it.
	 */
	private static final Duration WARMUP_ID_WINDOW = Duration.ofSeconds(10);

	private final JsonMapper objectMapper;

	private final JacksonMcpJsonMapper mcpJsonMapper;

	/** Owner-scoped auth-profile store; {@code null} in bare (non-Spring) wiring. */
	private final AuthProfileStore authProfileStore;

	/** Client-credentials token manager; {@code null} in bare wiring. */
	private final OAuth2ClientCredentialsTokenManager ccTokenManager;

	/** Auth-code exchanger; {@code null} in bare wiring. */
	private final OAuth2AuthCodeTokenExchanger authCodeExchanger;

	/** Traffic recorder; {@code null} to skip recording. */
	private final McpTrafficRecorder trafficRecorder;

	/**
	 * Spare upstream keep-alive connections to warm at session open; {@code 0} disables
	 * the warm-up (bare wiring default; Spring wiring passes
	 * {@code spring.ai.mcp.inspector.proxy.upstream-warm-connections}).
	 */
	private final int upstreamWarmConnections;

	public McpProxy(final JsonMapper objectMapper) {
		this(objectMapper, null, null, null, null);
	}

	/**
	 * Creates a proxy with traffic recording, no auth wiring.
	 * @param objectMapper the JSON mapper (may be {@code null} to use a default)
	 * @param trafficRecorder the traffic recorder, or {@code null} to skip recording
	 */
	public McpProxy(final JsonMapper objectMapper, final McpTrafficRecorder trafficRecorder) {
		this(objectMapper, null, null, null, trafficRecorder);
	}

	/**
	 * Creates a proxy with the OAuth2 wiring for the D9 one-retry, no traffic recording.
	 * @param objectMapper the JSON mapper backing the relay
	 * @param authProfileStore the owner-scoped profile store (may be {@code null})
	 * @param ccTokenManager the client-credentials token manager (may be {@code null})
	 * @param authCodeExchanger the auth-code exchanger (may be {@code null})
	 */
	public McpProxy(final JsonMapper objectMapper, final AuthProfileStore authProfileStore,
			final OAuth2ClientCredentialsTokenManager ccTokenManager,
			final OAuth2AuthCodeTokenExchanger authCodeExchanger) {
		this(objectMapper, authProfileStore, ccTokenManager, authCodeExchanger, null);
	}

	/**
	 * Creates a proxy with the OAuth2 wiring and optional traffic recording.
	 * @param objectMapper the JSON mapper backing the relay
	 * @param authProfileStore the owner-scoped profile store (may be {@code null})
	 * @param ccTokenManager the client-credentials token manager (may be {@code null})
	 * @param authCodeExchanger the auth-code exchanger (may be {@code null})
	 * @param trafficRecorder the traffic recorder, or {@code null} to skip recording
	 */
	public McpProxy(final JsonMapper objectMapper, final AuthProfileStore authProfileStore,
			final OAuth2ClientCredentialsTokenManager ccTokenManager,
			final OAuth2AuthCodeTokenExchanger authCodeExchanger, final McpTrafficRecorder trafficRecorder) {
		this(objectMapper, authProfileStore, ccTokenManager, authCodeExchanger, trafficRecorder, 0);
	}

	/**
	 * Creates a proxy with the OAuth2 wiring, optional traffic recording and an upstream
	 * connection warm-up.
	 * @param objectMapper the JSON mapper backing the relay
	 * @param authProfileStore the owner-scoped profile store (may be {@code null})
	 * @param ccTokenManager the client-credentials token manager (may be {@code null})
	 * @param authCodeExchanger the auth-code exchanger (may be {@code null})
	 * @param trafficRecorder the traffic recorder, or {@code null} to skip recording
	 * @param upstreamWarmConnections spare upstream connections to warm at session open;
	 * {@code 0} disables the warm-up
	 */
	public McpProxy(final JsonMapper objectMapper, final AuthProfileStore authProfileStore,
			final OAuth2ClientCredentialsTokenManager ccTokenManager,
			final OAuth2AuthCodeTokenExchanger authCodeExchanger, final McpTrafficRecorder trafficRecorder,
			final int upstreamWarmConnections) {
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.mcpJsonMapper = new JacksonMcpJsonMapper(this.objectMapper);
		this.authProfileStore = authProfileStore;
		this.ccTokenManager = ccTokenManager;
		this.authCodeExchanger = authCodeExchanger;
		this.trafficRecorder = trafficRecorder;
		this.upstreamWarmConnections = upstreamWarmConnections;
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
		// is deserialized then forwarded to the upstream transport, with the D9
		// OAuth2 one-retry applied on 401.
		// takeUntilOther: close() may fail to complete the sink if another thread owns
		// it at that instant, so the pump is unsubscribed off the session's lock-free
		// close signal instead of trusting the sink's terminal event to arrive.
		// Handshake gate: the initialize request must complete its send before any
		// non-handshake frame is dispatched. Without this, flatMap (which replaced
		// concatMap to avoid a circular wait on server-initiated roots/list) lets
		// tools/list overtain initialize and arrive at the upstream before the
		// session is registered, causing a 404 that tears the SSE stream down (PR #89).
		// The gate is a Sinks.One that opens when initialize's send completes.
		// notifications/initialized is part of the handshake and passes through.
		// If no initialize is ever sent (e.g. unit tests that inject tools/list
		// directly), the gate auto-opens on the first non-handshake request.
		final Sinks.One<Void> handshakeGate = Sinks.one();
		final java.util.concurrent.atomic.AtomicBoolean initializeSeen = new java.util.concurrent.atomic.AtomicBoolean();
		session.browserToTarget().asFlux().takeUntilOther(session.closeSignal()).flatMap((frame) -> {
			final JSONRPCMessage typed = toTyped(frame);
			if (typed == null) {
				return Mono.empty();
			}
			recordOutbound(session, typed, frame);
			// Initialize opens the gate when its send completes.
			if (typed instanceof McpSchema.JSONRPCRequest req && McpSchema.METHOD_INITIALIZE.equals(req.method())) {
				initializeSeen.set(true);
				LOG.debug("proxy[{}] forwarding initialize (opens handshake gate): {}", session.sessionId(), typed);
				return sendWithOneRetry(session, typed).timeout(Duration.ofMinutes(1)).doOnSuccess((v) -> {
					LOG.debug("proxy[{}] initialize completed: {}", session.sessionId(), typed);
					warmUpUpstreamConnections(session, handshakeGate);
				}).onErrorResume((err) -> {
					handshakeGate.tryEmitError(err);
					session.failUpstream(err);
					if (err instanceof java.util.concurrent.TimeoutException) {
						LOG.warn("proxy[{}] sendMessage timed out for initialize", session.sessionId());
					}
					else {
						final ProxyConnectFailure failure = ProxyConnectFailure.classify(err);
						LOG.warn("proxy[{}] initialize stream error ({}): {}", session.sessionId(),
								failure.reason().wire(), err.toString());
					}
					return Mono.empty();
				});
			}
			// Notifications need no response; send async so they do not
			// occupy a flatMap slot while the server processes them, which
			// would delay concurrent frame processing (e.g. the browser
			// response to a roots/list request triggered by
			// roots/list_changed).
			if (typed instanceof McpSchema.JSONRPCNotification) {
				sendWithOneRetry(session, typed).timeout(Duration.ofMinutes(1))
					.doOnSuccess((v) -> LOG.debug("proxy[{}] notification completed: {}", session.sessionId(), typed))
					.onErrorResume((err) -> {
						LOG.warn("proxy[{}] notification send failed: {}: {}", session.sessionId(), typed,
								err.toString());
						return Mono.empty();
					})
					.subscribe();
				return Mono.empty();
			}
			// All other frames wait for the handshake gate before sending. If no
			// initialize was ever sent (e.g. direct unit test injection), auto-open
			// the gate so the frame is not blocked forever.
			if (!initializeSeen.get()) {
				handshakeGate.tryEmitEmpty();
			}
			LOG.debug("proxy[{}] forwarding frame (awaiting gate): {}", session.sessionId(), typed);
			return handshakeGate.asMono()
				.or(session.closeSignal())
				.then(sendWithOneRetry(session, typed).timeout(Duration.ofMinutes(1))
					.doOnSuccess((v) -> LOG.debug("proxy[{}] frame completed: {}", session.sessionId(), typed))
					.onErrorResume((err) -> {
						if (err instanceof java.util.concurrent.TimeoutException) {
							LOG.warn("proxy[{}] sendMessage timed out for frame: {}", session.sessionId(), typed);
							return Mono.empty();
						}
						final ProxyConnectFailure failure = ProxyConnectFailure.classify(err);
						LOG.warn("proxy[{}] browser->target stream error ({}): {}", session.sessionId(),
								failure.reason().wire(), err.toString());
						if (failure.reason() != ProxyConnectFailure.Reason.UNKNOWN) {
							session.failUpstream(err);
						}
						return Mono.empty();
					}));
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
			// D9: a retryable 401 (client-credentials session) must NOT fail the
			// session here. The SDK invokes this handler BEFORE the sendMessage error
			// reaches sendWithOneRetry's onErrorResume, so failing the session first
			// would release the awaiter with the first 401 and tear the session down
			// (evicting the stored credentials) before the one-retry could refresh and
			// re-send. The retry path fails the session itself when the retried call
			// fails.
			if (isRetryableAuthError(err, session)) {
				return;
			}
			// D3: a protocol reply from a LIVE server must not tear the session. The
			// SDK's streamable transport re-surfaces a reconnect-backchannel failure
			// (e.g. the SSE GET after markInitialized answering "Unrecognized server
			// error when connecting to SSE stream, status code: 400" when the session
			// id raced) through this handler; that is SDK dirt, not the death of the
			// appstream - the request/response POST path is still alive and must keep
			// relaying. Same classification as the browser->target pump: genuine
			// transport-level failures (refused / dns / timeout) and the proxy's own
			// invalid_request rejection (restricted header name or illegal value: every
			// further frame fails the same customizer check, the session is unusable)
			// justify tearing the session down. Foreign IllegalArgumentExceptions stay
			// UNKNOWN here, so a one-off NumberFormatException no longer kills the
			// relay.
			if (ProxyConnectFailure.classify(err).reason() != ProxyConnectFailure.Reason.UNKNOWN) {
				session.failUpstream(err);
			}
		});

		// Target → browser: the connect handler is called once per inbound frame.
		// We serialize the typed frame back to a JsonNode and emit it on the
		// targetToBrowser sink. Returning Mono.empty() tells the SDK we have no
		// further response to send.
		//
		// Terminal-signal wiring: neither the connect() Mono nor the per-frame
		// publisher the SDK hands to this handler may be treated as "the inbound
		// stream". connect() succeeds as soon as the client is set up (for the
		// SSE transport that is the endpoint prologue), and the handler is invoked
		// once per inbound message on a Mono that completes after that single
		// frame (HttpClientSseClientTransport applies it as
		// handler.apply(Mono.just(msg))),
		// so a doOnSuccess/doOnTerminate hook on either would complete the
		// targetToBrowser sink after the FIRST relayed frame and close the
		// browser-facing stream on a perfectly live session. Only a frame-level
		// ERROR is routed to failUpstream here; upstream death is otherwise
		// detected by the transport exception handler, sendMessage failures and
		// the liveness prober.
		//
		// Internal liveness-probe responses are detected by their JSON-RPC id
		// (registered via session.registerProbeId) and filtered out - they must
		// never reach the browser. Receiving one also removes the id from the
		// answer filter set (the prober's window timer re-checks the id).
		// The inbound flux's error signal is surfaced too: an upstream
		// disconnect that ERRORS the inbound stream is propagated via
		// failUpstream so awaiters and the SSE subscriber are released promptly.
		// A clean COMPLETE terminates nothing: the SSE inbound completes on
		// ordinary upstream shutdown, and a session whose stream simply ended
		// stays up for the prober to diagnose.
		final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundHandler = (inbound) -> inbound
			.flatMap((message) -> {
				// Skip internal probe responses - they are not real MCP messages
				// and must not be forwarded to the browser.
				if (message instanceof McpSchema.JSONRPCResponse response) {
					final Object id = response.id();
					if (id instanceof String strId && session.isProbeId(strId)) {
						// Probe responses are internal traffic - they must not count as
						// activity for session reaping. Removing the probe id both bounds
						// the probeIds set and ends the answer filtering for that probe
						// (the prober's window timer re-checks the id before dropping
						// it).
						session.removeProbeId(strId);
						return Mono.<JSONRPCMessage>empty();
					}
				}
				final JsonNode body = toJsonNode(message);
				if (body != null) {
					recordInbound(session, message, body);
					// Concurrent responses arrive on different transport threads; a
					// dropped
					// frame here strands the per-request awaiter until the timeout.
					session.emitFromTarget(body);
					session.touch();
				}
				return Mono.<JSONRPCMessage>empty();
			})
			.doOnError((err) -> session.failUpstream(err));
		return connectWithOneRetry(session, inboundHandler, 0);
	}

	/**
	 * Returns the number of spare upstream connections this proxy warms at session open.
	 * Exposed for the starters' wiring tests, which pin the pass-through of
	 * {@code spring.ai.mcp.inspector.proxy.upstream-warm-connections}.
	 * @return the configured warm-up connection count
	 */
	public int getUpstreamWarmConnections() {
		return this.upstreamWarmConnections;
	}

	/**
	 * Warms spare upstream keep-alive connections with concurrent JSON-RPC pings and
	 * opens the handshake gate once the pool has settled (or the cap elapses).
	 *
	 * <p>
	 * Why this exists (issue #325): on a WebFlux MCP server a synchronous tool blocks its
	 * event loop for the tool's whole duration, and the server's TCP acceptor runs on one
	 * of those loops. A tools/call burst forwarded by the pump necessarily trails the
	 * first tool's start by a few milliseconds; upstream POST connects arriving after the
	 * acceptor froze wait out one tool duration per accept wave, and the trickled frames
	 * then ride one keep-alive connection, pinning the whole burst to a single event
	 * loop: the strictly serial ladder the concurrent-call IT caught. Pings sent before
	 * the first tool starts place idle, loop-spread connections in the client's pool, so
	 * the burst reuses them without needing a single new accept. The gate stays shut
	 * until the pool is ready (or {@link #WARMUP_CONNECTIONS_CAP} elapses on a slow
	 * upstream), keeping the extra first-call latency bounded.
	 * @param session the proxy session to warm (never {@code null})
	 * @param handshakeGate the gate to open once the pool is ready
	 */
	private void warmUpUpstreamConnections(final ProxySession session, final Sinks.One<Void> handshakeGate) {
		final int warm = this.upstreamWarmConnections;
		if (warm <= 0) {
			handshakeGate.tryEmitEmpty();
			return;
		}
		LOG.debug("proxy[{}] warming up to {} spare upstream connections", session.sessionId(), warm);
		// Prime the shared executor: the pool keeps about one idle worker alive across
		// this loop, and that live worker is what the warm-up needs. From a cold pool,
		// the first ping pays a thread spawn before it dispatches; that delay lets an
		// earlier ping complete and hand its connection back to the JDK client's pool,
		// and a later ping reuses it instead of opening a new one: the warm-up then
		// finishes with a mostly-cold pool and the burst races the upstream acceptor
		// again (measured: an un-primed warm-up intermittently warmed only a fraction
		// of the requested connections and the wall went back to the serial ladder).
		for (int i = 0; i < warm; i++) {
			try {
				ProxyTransportFactory.sharedHttpExecutor().submit(() -> null).get(1, TimeUnit.SECONDS);
			}
			catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
			catch (final ExecutionException | TimeoutException ex) {
				LOG.debug("proxy[{}] warm-up executor priming cut short at {}/{}", session.sessionId(), i, warm);
				break;
			}
		}
		// Registered up front on one thread so a ping that fails fast still has its
		// id in the filter set when the answer races the sendMessage completion.
		final List<String> warmIds = new ArrayList<>(warm);
		for (int i = 0; i < warm; i++) {
			warmIds.add(session.nextProbeId());
		}
		final long warmupStart = System.nanoTime();
		final Mono<Void> warmup = Flux.fromIterable(warmIds)
			.flatMap((probeId) -> warmConnection(session, probeId))
			.then();
		// The gate opens on whichever comes first: the pool settling, the cap on a
		// slow upstream, or the session closing (takeUntilOther completes on close
		// too; tryEmitEmpty is a no-op on a settled gate, and pending frames also
		// hold .or(closeSignal), so nothing can wedge).
		Mono.delay(WARMUP_CONNECTIONS_CAP).takeUntilOther(session.closeSignal()).subscribe((v) -> {
		}, (err) -> handshakeGate.tryEmitEmpty(), () -> handshakeGate.tryEmitEmpty());
		warmup.takeUntilOther(session.closeSignal()).subscribe((v) -> {
		}, (err) -> handshakeGate.tryEmitEmpty(), () -> logWarmUpSettled(session, warm, warmupStart, handshakeGate));
		Mono.delay(WARMUP_ID_WINDOW).takeUntilOther(session.closeSignal()).subscribe((v) -> {
		}, (err) -> {
		}, () -> warmIds.forEach(session::removeProbeId));
	}

	private Mono<Void> warmConnection(final ProxySession session, final String probeId) {
		final JSONRPCMessage ping = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "ping", probeId, null);
		// Dispatch like a relayed frame (see sendWithOneRetry): a legacy SSE transport
		// blocks the subscribing thread for the whole round trip, so subscribing the
		// pings directly would serialize the whole warm-up on the
		// initialize-completion worker: about one connection warmed instead of the
		// configured count, and a boundedElastic worker held for the sum of the
		// round trips.
		return session.targetTransport()
			.sendMessage(ping)
			.subscribeOn(Schedulers.boundedElastic())
			.takeUntilOther(session.closeSignal())
			.onErrorResume((err) -> {
				LOG.debug("proxy[{}] warm-up ping {} failed: {}", session.sessionId(), probeId, err.toString());
				return Mono.empty();
			});
	}

	private void logWarmUpSettled(final ProxySession session, final int warm, final long warmupStart,
			final Sinks.One<Void> handshakeGate) {
		LOG.debug("proxy[{}] warm-up pool of {} settled in {} ms", session.sessionId(), warm,
				(System.nanoTime() - warmupStart) / 1_000_000);
		handshakeGate.tryEmitEmpty();
	}

	/**
	 * Sends one frame upstream, applying the D9 one-retry: a {@code 401} on a session
	 * bound to a client-credentials profile triggers a token refresh and a single re-send
	 * with the fresh token. The first {@code 401} does NOT fail the session (the awaiter
	 * must see the retried response); any later failure does.
	 * @param session the session
	 * @param typed the typed frame to send
	 * @return the send result
	 */
	private Mono<Void> sendWithOneRetry(final ProxySession session, final JSONRPCMessage typed) {
		// Subscribe the upstream send on boundedElastic: the thread that subscribes
		// this Mono is the thread that emitted the browser frame (on the reactive
		// stack the Netty event loop of the inbound /message POST), and legacy
		// transports execute a blocking HttpClient.send there, serializing all
		// concurrent frames of one session behind the slowest one (issue #356).
		// The handshake gate is unaffected: it opens when the initialize send
		// completes, regardless of which thread ran it.
		final Mono<Void> send = session.targetTransport().sendMessage(typed).subscribeOn(Schedulers.boundedElastic());
		return send.onErrorResume((err) -> {
			if (isRetryableAuthError(err, session)) {
				return Mono.defer(() -> {
					final Mono<Void> resend = session.targetTransport()
						.sendMessage(typed)
						.subscribeOn(Schedulers.boundedElastic());
					return refreshToken(session).then(resend);
				}).doOnError((err2) -> {
					LOG.warn("proxy[{}] sendMessage failed after one retry: {}", session.sessionId(), err2.toString());
					session.failUpstream(err2);
				});
			}
			LOG.warn("proxy[{}] sendMessage failed: {}", session.sessionId(), err.toString());
			session.failUpstream(err);
			return Mono.error(err);
		});
	}

	/**
	 * Runs the upstream {@code connect()} with the D9 one-retry: a {@code 401} on a
	 * session bound to a client-credentials profile triggers a token refresh and a single
	 * re-connect. Any later failure propagates to the caller.
	 * @param session the session
	 * @param handler the inbound frame handler
	 * @param attempt current attempt (0 = first)
	 * @return the connect result
	 */
	private Mono<Void> connectWithOneRetry(final ProxySession session,
			final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler, final int attempt) {
		return session.targetTransport().connect(handler).onErrorResume((err) -> {
			if (attempt == 0 && isRetryableAuthError(err, session)) {
				return Mono.defer(() -> refreshToken(session).then(connectWithOneRetry(session, handler, 1)));
			}
			return Mono.error(err);
		});
	}

	/**
	 * Whether {@code err} qualifies for the one-retry: a {@code 401} extracted by the D3
	 * status rule, on a session bound to an OAuth2 CLIENT_CREDENTIALS profile with the
	 * token manager wired.
	 * @param err the transport failure
	 * @param session the session
	 * @return {@code true} when the retry applies
	 */
	private boolean isRetryableAuthError(final Throwable err, final ProxySession session) {
		if (this.ccTokenManager == null || this.authProfileStore == null || session.profileId() == null) {
			return false;
		}
		final Optional<Integer> status = ProxyErrorMapper.extractStatus(err);
		if (status.isEmpty() || status.get() != 401) {
			return false;
		}
		final Optional<AuthProfile> profile = this.authProfileStore.resolve(session.ownerId(), session.profileId());
		return profile.isPresent() && profile.get() instanceof OAuth2Profile oauth2
				&& oauth2.grantMode() == OAuth2GrantMode.CLIENT_CREDENTIALS;
	}

	/**
	 * Refreshes the session's client-credentials token and pushes the fresh
	 * {@code Bearer} value into the transport's live authorization reference. Runs the
	 * blocking token exchange off the reactive thread.
	 * @param session the session
	 * @return a {@link Mono} completing after the refresh
	 */
	private Mono<Void> refreshToken(final ProxySession session) {
		return Mono.fromCallable(() -> this.ccTokenManager.getAccessToken(session.profileId(), true))
			.subscribeOn(Schedulers.boundedElastic())
			.doOnError((err) -> LOG.warn("proxy[{}] refreshToken failed: {} (profileId={})", session.sessionId(),
					err.toString(), session.profileId()))
			.doOnNext((handle) -> session.authorizationRef().set("Bearer " + handle.accessToken()))
			.then();
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
	 * Records a JSON-RPC exchange that the proxy answered locally (e.g. an intercepted
	 * {@code tasks/*} method) without relaying anything upstream. The request is recorded
	 * as outbound traffic and the locally produced response as inbound traffic, so the
	 * pair reaches the traffic timeline through the same instrumentation the relay path
	 * uses. Frames that fail to deserialize are skipped on their side only; recording
	 * failures never propagate to the caller.
	 * @param session the proxy session (must not be {@code null})
	 * @param request the raw request frame received from the browser
	 * @param response the raw response frame produced locally
	 */
	public void recordLocalExchange(final ProxySession session, final JsonNode request, final JsonNode response) {
		final JSONRPCMessage typedRequest = toTyped(request);
		if (typedRequest != null) {
			recordOutbound(session, typedRequest, request);
		}
		final JSONRPCMessage typedResponse = toTyped(response);
		if (typedResponse != null) {
			recordInbound(session, typedResponse, response);
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
