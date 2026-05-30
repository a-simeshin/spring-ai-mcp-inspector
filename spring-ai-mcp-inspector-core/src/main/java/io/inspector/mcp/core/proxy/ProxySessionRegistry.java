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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory map of active proxy sessions, keyed by web-app session id.
 *
 * <p>
 * Thread-safe — the underlying {@link ConcurrentHashMap} permits concurrent lookups
 * (browser POSTs) and writes (new {@code GET /sse} requests, session closures).
 */
public class ProxySessionRegistry {

	private final ConcurrentMap<String, ProxySession> sessions = new ConcurrentHashMap<>();

	/** Adds {@code session} under {@code session.sessionId()}. */
	public void put(ProxySession session) {
		sessions.put(session.sessionId(), session);
	}

	/** Returns the session for {@code id}, or {@code null} if unknown. */
	public ProxySession get(String id) {
		return (id == null) ? null : sessions.get(id);
	}

	/**
	 * Removes and closes the session for {@code id}. Returns {@code true} if a session
	 * was actually removed.
	 */
	public boolean removeAndClose(String id) {
		if (id == null) {
			return false;
		}
		ProxySession session = sessions.remove(id);
		if (session == null) {
			return false;
		}
		session.close();
		return true;
	}

	/** Closes and removes every session. Called on app shutdown. */
	public void closeAll() {
		sessions.values().forEach(ProxySession::close);
		sessions.clear();
	}

	/** Current session count — intended for tests / metrics. */
	public int size() {
		return sessions.size();
	}

}
