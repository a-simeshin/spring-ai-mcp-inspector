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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

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
 * @author Artem Simeshin
 */
public class ProxySessionRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(ProxySessionRegistry.class);

	/** Default inactivity budget when none is configured. */
	private static final Duration DEFAULT_INACTIVITY_BUDGET = Duration.ofMinutes(30);

	private final ConcurrentMap<String, ProxySession> sessions = new ConcurrentHashMap<>();

	/** Idle budget after which a quiet session is evicted; never {@code null}. */
	private volatile Duration inactivityBudget = DEFAULT_INACTIVITY_BUDGET;

	/**
	 * Adds {@code session} under {@code session.sessionId()}.
	 * @param session the session to register (never {@code null})
	 */
	public void put(final ProxySession session) {
		this.sessions.put(session.sessionId(), session);
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
	 * Removes and closes the session for {@code id}.
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

	/** Closes and removes every session. Called on app shutdown. */
	public void closeAll() {
		this.sessions.values().forEach(ProxySession::close);
		this.sessions.clear();
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
