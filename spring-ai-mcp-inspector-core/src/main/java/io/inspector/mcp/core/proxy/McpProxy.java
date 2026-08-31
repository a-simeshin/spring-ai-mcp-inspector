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
import java.util.Optional;
import java.util.function.Function;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.mcpJsonMapper = new JacksonMcpJsonMapper(this.objectMapper);
		this.authProfileStore = authProfileStore;
		this.ccTokenManager = ccTokenManager;
		this.authCodeExchanger = authCodeExchanger;
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
					LOG.debug("proxy[{}] initialize completed, opening gate: {}", session.sessionId(), typed);
					handshakeGate.tryEmitEmpty();
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
			// relaying. Same classification as the browser->target pump: only genuine
			// transport-level failures (refused / dns / timeout) justify tearing the
			// session down.
			if (ProxyConnectFailure.classify(err).reason() != ProxyConnectFailure.Reason.UNKNOWN) {
				session.failUpstream(err);
			}
		});

		// Target → browser: the connect handler is called once per inbound frame.
		// We serialize the typed frame back to a JsonNode and emit it on the
		// targetToBrowser sink. Returning Mono.empty() tells the SDK we have no
		// further response to send.
		//
		// The inbound flux's terminal signals are surfaced too: an upstream
		// disconnect that completes/errors the inbound stream is propagated via
		// failUpstream so awaiters and the SSE subscriber are released promptly.
		final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundHandler = (inbound) -> inbound
			.flatMap((message) -> {
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
			})
			.doOnError((err) -> session.failUpstream(err));
		return connectWithOneRetry(session, inboundHandler, 0);
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
		return session.targetTransport().sendMessage(typed).onErrorResume((err) -> {
			if (isRetryableAuthError(err, session)) {
				return Mono.defer(() -> refreshToken(session).then(session.targetTransport().sendMessage(typed)))
					.doOnError((err2) -> {
						LOG.warn("proxy[{}] sendMessage failed after one retry: {}", session.sessionId(),
								err2.toString());
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
		return Mono.fromCallable(() -> {
			final OAuth2ClientCredentialsTokenManager.TokenHandle handle = this.ccTokenManager
				.getAccessToken(session.profileId(), true);
			LOG.warn("proxy[{}] refreshToken succeeded: len={} ccTokenManager={} profileId={}", session.sessionId(),
					Integer.toHexString(handle.accessToken().hashCode()), System.identityHashCode(this.ccTokenManager),
					session.profileId());
			return handle;
		})
			.doOnError((err) -> LOG.warn("proxy[{}] refreshToken failed: {} (ccTokenManager={}, profileId={})",
					session.sessionId(), err.toString(), System.identityHashCode(this.ccTokenManager),
					session.profileId()))
			.subscribeOn(Schedulers.boundedElastic())
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
