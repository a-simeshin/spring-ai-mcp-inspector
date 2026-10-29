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

package io.inspector.mcp.webflux.router;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

import io.inspector.mcp.core.keepalive.KeepAliveTracker;

/**
 * Holds the per-session SSE sinks and trackers that back keep-alive tracking for proxy
 * sessions (the UI's default connection mode). The proxy handlers register a session here
 * when it opens; {@link InspectorHandler} streams the sink through {@code GET /events}
 * and resolves the tracker for {@code GET /keepalive}.
 *
 * <p>
 * Sinks are created eagerly at session open and stay warm across browser re-subscriptions
 * ({@code autoCancel} disabled). Emissions made before the first subscriber attaches are
 * dropped on the floor: the polled {@code /keepalive} snapshot remains the source of
 * truth, the SSE stream is a live-view convenience.
 *
 * @author Artem Simeshin
 */
public final class KeepAliveEventRegistry {

	private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

	/**
	 * Registers a proxy session for keep-alive streaming.
	 * @param sessionId the proxy session identifier
	 * @param tracker the session's keep-alive tracker
	 * @return the warm sink the listener emits {@code mcp:keepalive-ping} events into
	 */
	public Sinks.Many<ServerSentEvent<String>> register(final String sessionId, final KeepAliveTracker tracker) {
		final Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer(256, false);
		this.entries.put(sessionId, new Entry(sink, tracker));
		return sink;
	}

	/**
	 * Returns the warm keep-alive sink for a live session, when registered.
	 * @param sessionId the proxy session identifier
	 * @return the sink, or {@code null} when the session is not registered
	 */
	public Sinks.Many<ServerSentEvent<String>> sink(final String sessionId) {
		final Entry entry = this.entries.get(sessionId);
		return (entry != null) ? entry.sink() : null;
	}

	/**
	 * Returns the keep-alive tracker for a live session, when registered.
	 * @param sessionId the proxy session identifier
	 * @return the tracker, or {@code null} when the session is not registered
	 */
	public KeepAliveTracker tracker(final String sessionId) {
		final Entry entry = this.entries.get(sessionId);
		return (entry != null) ? entry.tracker() : null;
	}

	/**
	 * Emits an event into the session's sink. Failures (no subscriber yet, sink
	 * terminated) are ignored: the polled snapshot stays authoritative.
	 * @param sessionId the proxy session identifier
	 * @param event the event to emit
	 */
	public void emit(final String sessionId, final ServerSentEvent<String> event) {
		final Entry entry = this.entries.get(sessionId);
		if (entry != null) {
			entry.sink().tryEmitNext(event);
		}
	}

	/**
	 * Completes and removes the session's sink.
	 * @param sessionId the proxy session identifier
	 */
	public void close(final String sessionId) {
		final Entry entry = this.entries.remove(sessionId);
		if (entry != null) {
			entry.sink().tryEmitComplete();
		}
	}

	private record Entry(Sinks.Many<ServerSentEvent<String>> sink, KeepAliveTracker tracker) {

	}

}
