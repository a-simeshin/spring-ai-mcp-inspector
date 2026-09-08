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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;

import io.inspector.mcp.core.shutdown.ShutdownDrain;

/**
 * In-memory map of active proxy sessions, keyed by web-app session id.
 *
 * <p>
 * Thread-safe — the underlying {@link ConcurrentHashMap} permits concurrent lookups
 * (browser POSTs) and writes (new {@code GET /sse} requests, session closures).
 *
 * <p>
 * A scheduled {@link #reap()} sweep evicts dead sessions so the proxy does not leak
 * sessions forever after an upstream loss or a {@code 504} (see
 * {@link ProxySession#close()}). A session is reaped when it is already closed or when it
 * has been idle longer than the configured inactivity budget
 * ({@link #setInactivityBudget(Duration)}); an upstream-terminated session stops
 * refreshing its activity timestamp and is reaped once it crosses that budget. Scheduling
 * is enabled by the inspector auto-configurations via {@code @EnableScheduling}.
 *
 * <p>
 * Every session pins a browser-facing SSE request open, so the registry drains itself on
 * {@link ContextClosedEvent} — the first step of context close, before Boot's
 * {@code WebServerGracefulShutdownLifecycle} starts waiting for in-flight requests.
 * Destruction callbacks ({@code @PreDestroy}, {@code DisposableBean},
 * {@code destroyMethod}) run after every lifecycle phase and would fire only once that
 * wait had already timed out.
 *
 * <p>
 * The hook is {@link EventListener}-annotated rather than an implemented
 * {@code ApplicationListener<ContextClosedEvent>}, which keeps subclasses free to be an
 * {@code ApplicationListener} of their own event type. The trade is that it needs
 * {@code EventListenerMethodProcessor} in the context — present in every Spring Boot
 * application, and therefore in every context this starter configures, but not in a bare
 * {@code GenericApplicationContext} assembled by hand.
 *
 *
 * <h2>Cleanup contract</h2>
 *
 * <p>
 * The registry provides three cleanup paths, but only one is used in production:
 *
 * <dl>
 * <dt>{@link #removeAndClose(String)}</dt>
 * <dd><b>Canonical cleanup method.</b> Removes the session from the map and closes its
 * transport. Used by all 17 external production call sites and by internal callers
 * ({@link #closeAll()}, {@link #reap()}, and the double-check guard in
 * {@link #put(ProxySession)}). The caller owns the session id; the method owns the
 * transport close.</dd>
 *
 * <dt>{@link #put(ProxySession)}</dt>
 * <dd>Registers a new session. When the key already exists in the map, the <b>old</b>
 * session is replaced and its transport is closed immediately — the registry never holds
 * two sessions under the same key. The new session's transport is left open. This is the
 * only path that closes a session without an explicit removal call.</dd>
 *
 * <dt>{@code closeSession(String)}</dt>
 * <dd><b>Dead code.</b> Exists only in the PR #89 branch, not on {@code develop/2.x}. The
 * call graph analysis (see {@code VERDICT.md}) found zero production callers across all
 * three modules. All 17 production call sites use {@link #removeAndClose(String)}
 * instead. The method is kept for backward compatibility until PR #89 is merged.</dd>
 * </dl>
 *
 * <p>
 * Invariants:
 * <ul>
 * <li>Every session removed from the map has its {@link ProxySession#close()} called
 * before the method returns.</li>
 * <li>Every session that loses a key collision in {@link #put(ProxySession)} is closed
 * immediately — no stale session survives in the map or leaks its transport.</li>
 * <li>Removal is idempotent: {@link ProxySession#close()} is a no-op after the first
 * call, and {@link #removeAndClose(String)} returns {@code false} when the id is
 * unknown.</li>
 * </ul>
 *
 * @author Artem Simeshin
 */
public class ProxySessionRegistry implements ApplicationContextAware {

	private static final Logger LOG = LoggerFactory.getLogger(ProxySessionRegistry.class);

	/**
	 * Total wall-clock budget for {@link #closeAll()}, however many sessions there are.
	 */
	private static final Duration CLOSE_ALL_BUDGET = Duration.ofSeconds(5);

	/** Default inactivity budget when none is configured. */
	private static final Duration DEFAULT_INACTIVITY_BUDGET = Duration.ofMinutes(30);

	private final ConcurrentMap<String, ProxySession> sessions = new ConcurrentHashMap<>();

	/** Idle budget after which a quiet session is evicted; never {@code null}. */
	private volatile Duration inactivityBudget = DEFAULT_INACTIVITY_BUDGET;

	/** The context this bean belongs to; {@code null} when built outside a container. */
	private ApplicationContext applicationContext;

	/**
	 * Set once {@link #closeAll()} has started. Never reset — a registry whose context is
	 * closing does not reopen.
	 */
	private volatile boolean closed;

	/**
	 * Registers {@code session} under {@code session.sessionId()}.
	 *
	 * <p>
	 * If the registry has already been drained (see {@link #closeAll()}) the session is
	 * closed immediately instead of being registered.
	 *
	 * <p>
	 * If the key already exists in the map, the <b>old</b> session is replaced and its
	 * transport is closed immediately — the registry never holds two sessions under the
	 * same key. The new session is left open. This is the only path that closes a session
	 * without an explicit removal call.
	 *
	 * <p>
	 * Concurrent {@code put()} calls with the same key are safe: the underlying
	 * {@link java.util.concurrent.ConcurrentHashMap} guarantees that exactly one writer
	 * wins, and the loser's transport is closed before the method returns. See
	 * {@link #removeAndClose(String)} for the canonical cleanup method.
	 * @param session the session to register (never {@code null})
	 */
	public void put(final ProxySession session) {
		if (this.closed) {
			session.close();
			return;
		}
		final ProxySession old = this.sessions.put(session.sessionId(), session);
		if (old != null && old != session) {
			// Replaced an existing session under the same key -- close the old one.
			old.close();
		}
		if (this.closed) {
			// Lost the race: closeAll() flipped the flag after our first check but swept
			// before our put landed. Double-checking closes it without needing a lock.
			removeAndClose(session.sessionId());
		}
	}

	/**
	 * Returns the session for {@code id}, or {@code null} if unknown.
	 * @param id the session id to look up
	 * @return the matching session, or {@code null} if not found
	 */
	public ProxySession get(final String id) {
		return (id != null) ? this.sessions.get(id) : null;
	}

	/**
	 * Canonical cleanup method. Removes the session from the map and closes its
	 * transport.
	 *
	 * <p>
	 * This is the only production cleanup path — all 17 external call sites and all
	 * internal callers ({@link #closeAll()}, {@link #reap()}, and the double-check guard
	 * in {@link #put(ProxySession)}) use this method. See the class-level cleanup
	 * contract for the full picture.
	 *
	 * <p>
	 * Idempotent: returns {@code false} when the id is unknown or {@code null}.
	 * @param id the session id to remove
	 * @return {@code true} if a session was actually removed, {@code false} otherwise
	 */
	public boolean removeAndClose(final String id) {
		if (id == null) {
			return false;
		}
		final ProxySession session = this.sessions.remove(id);
		if (session == null) {
			return false;
		}
		session.close();
		return true;
	}

	/**
	 * Closes and removes every session. Idempotent — {@link ProxySession#close()} is a
	 * no-op after the first call, which matters because a child context (an actuator
	 * management server, say) republishes {@link ContextClosedEvent} to the parent.
	 *
	 * <p>
	 * Each {@link ProxySession#close()} blocks up to 5s on its upstream transport, so a
	 * serial sweep would cost 5s <em>per</em> wedged session. They run in parallel
	 * against one 5s deadline instead, which bounds the whole sweep regardless of session
	 * count.
	 */
	public void closeAll() {
		this.closed = true;
		final List<Runnable> closers = this.sessions.keySet()
			.stream()
			.<Runnable>map((id) -> () -> removeAndClose(id))
			.toList();
		ShutdownDrain.drain("proxy session", CLOSE_ALL_BUDGET, closers);
	}

	/**
	 * Drains every session on context close — the first step of
	 * {@code AbstractApplicationContext.doClose()}, before any lifecycle phase stops.
	 *
	 * <p>
	 * An annotated method rather than {@code implements ApplicationListener<...>}: this
	 * class is public and user-extensible, and Java allows only one parameterisation of a
	 * generic interface per hierarchy, so implementing it here would stop any subclass
	 * from being an {@code ApplicationListener} of a different event type.
	 *
	 * <p>
	 * Only <em>this</em> context's close counts.
	 * {@code AbstractApplicationContext.publishEvent} forwards every event to the parent,
	 * so a child context closing — Spring Boot's actuator management context is the
	 * everyday case, it comes and goes independently — delivers a
	 * {@link ContextClosedEvent} here while this context is still serving traffic. Acting
	 * on it would drain live sessions and latch {@link #closed}, permanently disabling
	 * the inspector in a running application.
	 * @param event the context-closed event
	 */
	@EventListener
	@Order(100)
	public void onContextClosed(final ContextClosedEvent event) {
		if (this.applicationContext != null && event.getApplicationContext() != this.applicationContext) {
			return;
		}
		closeAll();
	}

	/**
	 * Records the owning context so {@link #onContextClosed(ContextClosedEvent)} can tell
	 * this context's close from a child's.
	 * @param applicationContext the context this bean was created in
	 */
	@Override
	public void setApplicationContext(final ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * Current session count — intended for tests / metrics.
	 * @return number of active sessions
	 */
	public int size() {
		return this.sessions.size();
	}

	/**
	 * Sets the inactivity budget used by {@link #reap()}. Falls back to the 30m default
	 * when {@code budget} is {@code null} or non-positive.
	 * @param budget the idle budget after which a quiet session is evicted
	 */
	public void setInactivityBudget(final Duration budget) {
		this.inactivityBudget = (budget != null && !budget.isNegative() && !budget.isZero()) ? budget
				: DEFAULT_INACTIVITY_BUDGET;
	}

	/**
	 * Scheduled sweep that evicts dead or idle sessions. Runs on a fixed delay;
	 * scheduling is activated by the inspector auto-configurations.
	 *
	 * <p>
	 * Evicts every session that is already closed or that has been idle longer than the
	 * configured inactivity budget. An upstream-terminated session stops refreshing its
	 * activity timestamp (its sinks are already errored, so new requests fail fast), so
	 * it is reaped once it crosses the idle budget — we deliberately do not evict purely
	 * on {@link ProxySession#isUpstreamTerminated()} to avoid tearing down a session on a
	 * single transient send failure. Each eviction routes through
	 * {@link #removeAndClose(String)} so the upstream transport is torn down and the
	 * sinks are completed.
	 */
	@Scheduled(fixedDelayString = "${spring.ai.mcp.inspector.timeouts.reaper-interval:PT1M}")
	public void reap() {
		final Instant now = Instant.now();
		final Duration budget = this.inactivityBudget;
		for (final ProxySession session : this.sessions.values()) {
			final boolean closed = session.isClosed();
			final boolean idle = session.lastActivity() != null
					&& Duration.between(session.lastActivity(), now).compareTo(budget) > 0;
			if (closed || idle) {
				if (removeAndClose(session.sessionId())) {
					LOG.debug("proxy[{}] reaped (closed={}, idle={})", session.sessionId(), closed, idle);
				}
			}
		}
	}

}
