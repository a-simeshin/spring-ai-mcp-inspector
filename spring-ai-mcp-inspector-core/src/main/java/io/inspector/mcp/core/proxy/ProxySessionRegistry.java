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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * In-memory map of active proxy sessions, keyed by web-app session id.
 *
 * <p>
 * Thread-safe — the underlying {@link ConcurrentHashMap} permits concurrent lookups
 * (browser POSTs) and writes (new {@code GET /sse} requests, session closures).
 *
 * <p>
 * Every session pins a browser-facing SSE request open, so the registry drains itself on
 * {@link ContextClosedEvent} — the first step of context close, before Boot's
 * {@code WebServerGracefulShutdownLifecycle} starts waiting for in-flight requests.
 * Destruction callbacks ({@code @PreDestroy}, {@code DisposableBean},
 * {@code destroyMethod}) run after every lifecycle phase and would fire only once that
 * wait had already timed out.
 *
 * @author Artem Simeshin
 */
public class ProxySessionRegistry implements ApplicationListener<ContextClosedEvent> {

	private final ConcurrentMap<String, ProxySession> sessions = new ConcurrentHashMap<>();

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

	/**
	 * Closes and removes every session. Idempotent — {@link ProxySession#close()} is a
	 * no-op after the first call, which matters because a child context (an actuator
	 * management server, say) republishes {@link ContextClosedEvent} to the parent.
	 */
	public void closeAll() {
		this.sessions.values().forEach(ProxySession::close);
		this.sessions.clear();
	}

	@Override
	public void onApplicationEvent(final ContextClosedEvent event) {
		closeAll();
	}

	/**
	 * Current session count — intended for tests / metrics.
	 * @return number of active sessions
	 */
	public int size() {
		return this.sessions.size();
	}

}
