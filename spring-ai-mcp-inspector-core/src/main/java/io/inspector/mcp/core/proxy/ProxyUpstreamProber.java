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

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import io.inspector.mcp.core.config.McpInspectorProperties;

/**
 * Scheduled liveness prober for proxied SSE sessions. Periodically checks every active
 * session for upstream inactivity. When a session has been idle longer than the
 * configured threshold, the prober sends a JSON-RPC {@code ping} request through the
 * session's transport. If the probe fails (timeout / transport error) the session is torn
 * down via {@link ProxySession#failUpstream}, which closes the downstream SSE stream and
 * triggers the amber {@code disconnected-remote} banner on the client.
 *
 * <p>
 * Probe responses are detected by their JSON-RPC id (registered via
 * {@link ProxySession#registerProbeId(int)}) and filtered out of the browser stream by
 * {@link McpProxy#start(ProxySession)}.
 *
 * <p>
 * Every active session is probed regardless of transport type (SSE, streamable-HTTP,
 * stdio).
 *
 * @author Artem Simeshin
 */
public class ProxyUpstreamProber {

	private static final Logger LOG = LoggerFactory.getLogger(ProxyUpstreamProber.class);

	private final ProxySessionRegistry registry;

	private final McpInspectorProperties.Timeouts timeouts;

	/**
	 * Creates a prober that checks the given registry.
	 * @param registry the session registry to probe (never {@code null})
	 * @param timeouts the timeout configuration (never {@code null})
	 */
	public ProxyUpstreamProber(final ProxySessionRegistry registry, final McpInspectorProperties.Timeouts timeouts) {
		this.registry = registry;
		this.timeouts = timeouts;
	}

	/**
	 * Scheduled probe tick. Runs at a fixed rate configured via
	 * {@code spring.ai.mcp.inspector.timeouts.upstream-probe-interval} (default 10s).
	 *
	 * <p>
	 * For each active session whose {@code lastActivity} is older than the idle
	 * threshold, sends a JSON-RPC ping. If the {@code sendMessage} call fails (within the
	 * per-request timeout), the session is terminated.
	 */
	@Scheduled(fixedDelayString = "${spring.ai.mcp.inspector.timeouts.upstream-probe-interval:PT10S}")
	public void probe() {
		final Instant now = Instant.now();
		final Duration idleThreshold = this.timeouts.getUpstreamProbeIdleThreshold();
		final Duration probeTimeout = this.timeouts.getUpstreamProbeTimeout();

		this.registry.forEachSession((session) -> {
			if (session.isClosed() || session.isUpstreamTerminated()) {
				return;
			}
			final Instant lastActivity = session.lastActivity();
			if (lastActivity == null) {
				return;
			}
			final Duration idle = Duration.between(lastActivity, now);
			if (idle.compareTo(idleThreshold) < 0) {
				// Session has recent activity - no probe needed.
				return;
			}
			// Session is idle - send a ping probe.
			final int probeId = session.nextProbeId();
			final JSONRPCMessage ping = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "ping", probeId, null);
			LOG.debug("proxy[{}] sending liveness probe (id={}, idle={}s)", session.sessionId(), probeId,
					idle.toSeconds());

			session.targetTransport().sendMessage(ping).timeout(probeTimeout).onErrorResume((err) -> {
				LOG.warn("proxy[{}] liveness probe {} failed: {}", session.sessionId(), probeId, err.toString());
				session.failUpstream(err);
				return Mono.empty();
			}).subscribeOn(Schedulers.boundedElastic()).subscribe((ignored) -> {
				// sendMessage completed (HTTP 202 accepted). The JSON-RPC
				// response will arrive on the inbound flux, update
				// lastActivity via session.touch(), and be filtered from
				// the browser stream by McpProxy.
			}, (err) -> {
				// onErrorResume handles this already.
			});
		});
	}

}
