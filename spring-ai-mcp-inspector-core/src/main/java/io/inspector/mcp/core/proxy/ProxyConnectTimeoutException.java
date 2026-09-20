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

/**
 * Signals that a phase of the proxy connect attempt exhausted its budget.
 *
 * <p>
 * Distinct from {@link ProxyConnectFailureException}: that one wraps a transport-level
 * failure classified by {@link ProxyConnectFailure} (DNS, refused, not-found, generic
 * timeout from the JDK client). This one is raised by the proxy itself when a phase it
 * times explicitly overruns the configured per-phase budget, and carries the numbers the
 * structured {@code connection_timeout} payload needs.
 *
 * <p>
 * Phases are deliberately coarse. {@link Phase#CONNECT} covers everything the transport's
 * {@code connect()} does (DNS, TCP, TLS, HTTP handshake) because the JDK
 * {@code HttpClient} does not expose per-subphase timing; {@link Phase#INITIALIZE} covers
 * the first JSON-RPC {@code initialize} request that the browser relays on the freshly
 * opened session.
 *
 * @author Artem Simeshin
 */
public final class ProxyConnectTimeoutException extends RuntimeException {

	/** The connect phase that overran its budget. */
	public enum Phase {

		/** Transport bring-up: DNS, TCP, TLS, HTTP handshake as a single budget. */
		CONNECT("connect"),

		/** First JSON-RPC {@code initialize} request after the transport is up. */
		INITIALIZE("initialize");

		private final String wire;

		Phase(final String wire) {
			this.wire = wire;
		}

		/**
		 * The wire representation used in the {@code connection_timeout} payload.
		 * @return the wire value (never {@code null})
		 */
		public String wire() {
			return this.wire;
		}

	}

	private final Phase phase;

	private final long elapsedMs;

	private final long budgetMs;

	public ProxyConnectTimeoutException(final Phase phase, final long elapsedMs, final long budgetMs) {
		super("connection " + phase.wire() + " timed out after " + elapsedMs + "ms of " + budgetMs + "ms budget");
		this.phase = phase;
		this.elapsedMs = elapsedMs;
		this.budgetMs = budgetMs;
	}

	/**
	 * The phase whose budget was exceeded.
	 * @return the phase (never {@code null})
	 */
	public Phase phase() {
		return this.phase;
	}

	/**
	 * Wall-clock time spent in the phase when the budget fired.
	 * @return elapsed milliseconds
	 */
	public long elapsedMs() {
		return this.elapsedMs;
	}

	/**
	 * The per-phase budget that was applied.
	 * @return budget milliseconds
	 */
	public long budgetMs() {
		return this.budgetMs;
	}

}
