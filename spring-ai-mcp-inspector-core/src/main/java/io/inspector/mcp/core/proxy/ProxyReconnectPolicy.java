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
 * Reconnect policy the SSE proxy advertises to browsers when the upstream target is dead.
 * <p>
 * A browser EventSource reconnects on its own whenever the SSE stream ends, by default
 * every 3 seconds. When the proxy closes a stream because the upstream is DEAD (nothing
 * listens, the name does not resolve, nobody answers), every reconnect attempt is doomed:
 * it mints a fresh proxy session that immediately fails to start, so the loop churns
 * sessions forever. The SSE spec's {@code retry} field is the in-band way for the server
 * to control exactly that cadence, so both stacks emit a terminal retry value before the
 * stream of a dead-upstream generation ends; spec-compliant clients (the native
 * EventSource and the eventsource polyfill alike) then stop hammering.
 * <p>
 * The death classification is deliberately the same one the upstream liveness probe
 * already uses (transport-level failures only); a live upstream's protocol rejection (say
 * HTTP 404) is not death and keeps the normal reconnect cadence.
 *
 * @author Artem Simeshin
 */
public final class ProxyReconnectPolicy {

	/**
	 * Terminal SSE {@code retry} value in milliseconds: about 24.8 days, the largest
	 * delay every EventSource implementation can schedule, so a spec-compliant client
	 * effectively stops reconnecting.
	 */
	public static final long TERMINAL_RECONNECT_MILLIS = 2_147_483_000L;

	private ProxyReconnectPolicy() {
	}

	/**
	 * Whether {@code failure} proves the upstream target is gone (nothing listens on the
	 * port, the host name does not resolve, the server never answers), as opposed to a
	 * live upstream's protocol response or an unclassifiable transport hiccup.
	 * @param failure the failure to classify (never {@code null})
	 * @return {@code true} when the failure means a dead upstream
	 */
	public static boolean isUpstreamDeath(final Throwable failure) {
		final ProxyConnectFailure.Reason reason = ProxyConnectFailure.classify(failure).reason();
		return reason == ProxyConnectFailure.Reason.CONNECTION_REFUSED || reason == ProxyConnectFailure.Reason.DNS
				|| reason == ProxyConnectFailure.Reason.TIMEOUT;
	}

}
