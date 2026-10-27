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

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-mutating SSE probe run after a Streamable HTTP connect failure (issue #250,
 * upstream spring-projects/spring-ai#7207).
 *
 * <p>
 * A Spring AI MCP server with {@code spring.ai.mcp.server.protocol} unset silently
 * activates the legacy SSE transport, so nothing answers on {@code /mcp} and a streamable
 * client sees a bare 404/405. This probe GETs the failed target path and its {@code /sse}
 * sibling with {@code Accept: text/event-stream} and decides whether the server is
 * SSE-alive, so the proxy can attach a transport-switch hint instead of a bare error.
 *
 * <p>
 * Safety contract: GET only; no {@code Authorization}, cookies or custom headers are ever
 * sent (credentials must not leak to a possibly different endpoint); a 200 event-stream
 * response body is closed immediately so the server stream does not linger; the probe
 * never throws - every failure (timeout, DNS, TLS, reset) counts as "SSE not confirmed"
 * and leaves the original classified failure untouched.
 *
 * @author Artem Simeshin
 */
public final class SseConnectProbe {

	private static final Logger LOG = LoggerFactory.getLogger(SseConnectProbe.class);

	/** Wire signature of the mcp-core 2.0.0 SDK bucket that hides HTTP 405/5xx. */
	static final String FAILED_TO_SEND_MESSAGE_SIGNATURE = "Failed to send message";

	/** Content type / Allow token that identifies an SSE endpoint. */
	private static final String EVENT_STREAM = "text/event-stream";

	/** User agent identifying the probe on the wire. */
	private static final String PROBE_USER_AGENT = "mcp-inspector-probe/1.0";

	/** Default per-path budget (connect + response headers). */
	public static final Duration DEFAULT_PATH_TIMEOUT = Duration.ofMillis(2500);

	/** Default hard cap for the whole probe sequence. */
	public static final Duration DEFAULT_TOTAL_BUDGET = Duration.ofSeconds(5);

	/** Shared daemon-thread executor so the probe never pins JVM shutdown. */
	private static final ExecutorService PROBE_EXECUTOR = Executors.newCachedThreadPool((runnable) -> {
		final Thread thread = new Thread(runnable, "mcp-inspector-sse-probe");
		thread.setDaemon(true);
		return thread;
	});

	/** Shared HTTP client (bounded by per-request timeouts; no redirects). */
	private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
		.executor(PROBE_EXECUTOR)
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	private final Duration pathTimeout;

	private final Duration totalBudget;

	/**
	 * Creates a probe with the default budgets (2500 ms per path, 5000 ms total).
	 */
	public SseConnectProbe() {
		this(DEFAULT_PATH_TIMEOUT, DEFAULT_TOTAL_BUDGET);
	}

	/**
	 * Creates a probe with explicit budgets (used by tests to keep wall time short).
	 * @param pathTimeout the per-path budget (connect + response headers)
	 * @param totalBudget the hard cap for the whole probe sequence
	 */
	SseConnectProbe(final Duration pathTimeout, final Duration totalBudget) {
		this.pathTimeout = pathTimeout;
		this.totalBudget = totalBudget;
	}

	/**
	 * Trigger gate (spec section 3): the probe runs only for a proxy-mode Streamable HTTP
	 * connect whose classified failure is {@link ProxyConnectFailure.Reason#NOT_FOUND}
	 * (the SDK "Server Not Found" bucket, HTTP 404) or
	 * {@link ProxyConnectFailure.Reason#UNKNOWN} carrying the SDK "Failed to send
	 * message" signature (the bucket that hides HTTP 405 and 5xx, because mcp-core 2.0.0
	 * does not surface the numeric status).
	 * @param failure the classified connect failure (must not be {@code null})
	 * @param cause the original connect exception whose chain carries the SDK message
	 * @return {@code true} when the probe should run
	 */
	public static boolean shouldProbe(final ProxyConnectFailure failure, final Throwable cause) {
		if (failure.reason() == ProxyConnectFailure.Reason.NOT_FOUND) {
			return true;
		}
		return failure.reason() == ProxyConnectFailure.Reason.UNKNOWN && hasFailedToSendMessageSignature(cause);
	}

	private static boolean hasFailedToSendMessageSignature(final Throwable cause) {
		Throwable current = cause;
		while (current != null) {
			final String message = current.getMessage();
			if (message != null && message.trim().startsWith(FAILED_TO_SEND_MESSAGE_SIGNATURE)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/**
	 * Runs the gate, the probe and the spec decision matrix (M1-M6) in one call.
	 *
	 * <p>
	 * Paths probed, in order: the exact resolved URI of the failed connect, then the same
	 * origin with the path's last segment replaced by {@code sse} ({@code /sse} when the
	 * path has no segments). Stops at the first SSE-alive path.
	 * @param failure the classified connect failure (must not be {@code null})
	 * @param cause the original connect exception (may be {@code null})
	 * @param resolvedTarget the resolved target URI of the failed connect, or
	 * {@code null} when it could not be resolved (the verdict is then {@code not_run})
	 * @return the verdict carrying the hint (or {@code null}) and the probe outcome
	 */
	public SseProbeVerdict evaluate(final ProxyConnectFailure failure, final Throwable cause,
			final URI resolvedTarget) {
		if (!shouldProbe(failure, cause) || resolvedTarget == null) {
			return SseProbeVerdict.notRun();
		}
		final URI sibling = siblingSseUri(resolvedTarget);
		final List<URI> paths = (sibling.equals(resolvedTarget)) ? List.of(resolvedTarget)
				: List.of(resolvedTarget, sibling);
		return probe(failure, paths, sibling);
	}

	private SseProbeVerdict probe(final ProxyConnectFailure failure, final List<URI> paths, final URI sibling) {
		final long deadline = System.nanoTime() + this.totalBudget.toNanos();
		final List<String> probedPaths = new ArrayList<>();
		boolean indefinite = false;
		for (final URI path : paths) {
			probedPaths.add(path.getRawPath());
			final long remainingNanos = deadline - System.nanoTime();
			if (remainingNanos <= 0) {
				indefinite = true;
				break;
			}
			final Duration timeout = (this.pathTimeout.toNanos() < remainingNanos) ? this.pathTimeout
					: Duration.ofNanos(remainingNanos);
			final PathOutcome outcome = probePath(path, timeout);
			if (outcome == PathOutcome.ALIVE) {
				LOG.debug("sse probe: {} is SSE-alive", path);
				return new SseProbeVerdict(
						new ConnectHint(ConnectHint.KIND_SSE_ONLY, path.getRawPath(), ConnectHint.DEFAULT_REFS),
						SseProbeVerdict.OUTCOME_ALIVE, List.copyOf(probedPaths), path.getRawPath(),
						sibling.getRawPath());
			}
			if (outcome == PathOutcome.INDEFINITE) {
				indefinite = true;
			}
		}
		if (indefinite) {
			// M5: a timeout or transport error means SSE is neither confirmed nor
			// ruled out - no hint, original error preserved.
			return new SseProbeVerdict(null, SseProbeVerdict.OUTCOME_TIMEOUT, List.copyOf(probedPaths), null,
					sibling.getRawPath());
		}
		// Both paths answered definitively non-alive: only the 404 case earns the
		// weak hint (M3); a 405/5xx bucket failure stays bare (M4).
		if (failure.reason() == ProxyConnectFailure.Reason.NOT_FOUND) {
			return new SseProbeVerdict(
					new ConnectHint(ConnectHint.KIND_MAYBE_SSE, sibling.getRawPath(), ConnectHint.DEFAULT_REFS),
					SseProbeVerdict.OUTCOME_DEAD, List.copyOf(probedPaths), null, sibling.getRawPath());
		}
		return new SseProbeVerdict(null, SseProbeVerdict.OUTCOME_DEAD, List.copyOf(probedPaths), null,
				sibling.getRawPath());
	}

	private PathOutcome probePath(final URI uri, final Duration timeout) {
		try {
			final HttpRequest request = HttpRequest.newBuilder(uri)
				.header("Accept", EVENT_STREAM)
				.header("User-Agent", PROBE_USER_AGENT)
				.timeout(timeout)
				.GET()
				.build();
			final HttpResponse<InputStream> response = SHARED_CLIENT.send(request,
					HttpResponse.BodyHandlers.ofInputStream());
			try {
				final int status = response.statusCode();
				if (isEventStream(response.headers().firstValue("Content-Type").orElse(null))) {
					return (status >= 200 && status < 300) ? PathOutcome.ALIVE : PathOutcome.NOT_ALIVE;
				}
				if (status == 405 && isEventStream(response.headers().firstValue("Allow").orElse(null))) {
					return PathOutcome.ALIVE;
				}
				return PathOutcome.NOT_ALIVE;
			}
			finally {
				// Never consume the body: closing the stream cancels the exchange
				// immediately so a live SSE stream does not linger server-side.
				response.body().close();
			}
		}
		catch (final Exception ex) {
			LOG.debug("sse probe: {} failed: {}", uri, ex.toString());
			return PathOutcome.INDEFINITE;
		}
	}

	private static boolean isEventStream(final String headerValue) {
		return headerValue != null && headerValue.toLowerCase().contains(EVENT_STREAM);
	}

	/**
	 * Same origin as {@code target} with the path's last segment replaced by {@code sse}:
	 * {@code /mcp} -> {@code /sse}, {@code /api/mcp} -> {@code /api/sse}, {@code /} or
	 * empty -> {@code /sse}. Query and fragment are dropped.
	 * @param target the failed connect target (must not be {@code null})
	 * @return the sibling SSE URI
	 */
	static URI siblingSseUri(final URI target) {
		final String rawPath = target.getRawPath();
		final String path = (rawPath == null || rawPath.isBlank()) ? "/" : rawPath;
		final int lastSlash = path.lastIndexOf('/');
		final String sibling = (lastSlash <= 0) ? "/sse" : path.substring(0, lastSlash + 1) + "sse";
		return URI.create(target.getScheme() + "://" + target.getRawAuthority() + sibling);
	}

	private enum PathOutcome {

		ALIVE, NOT_ALIVE, INDEFINITE

	}

	/**
	 * Outcome of the gate + probe + decision matrix for one failed connect.
	 *
	 * @author Artem Simeshin
	 * @param hint the hint to attach to the {@code MCP_CONNECT_FAILED} payload, or
	 * {@code null} when the matrix says no hint (M4/M5/M6)
	 * @param outcome wire outcome: {@code alive}, {@code dead}, {@code timeout} or
	 * {@code not_run}
	 * @param probedPaths the request paths the probe actually touched (empty for
	 * {@code not_run})
	 * @param alivePath the path that proved SSE-alive, or {@code null}
	 * @param siblingPath the {@code /sse} sibling path on the same origin
	 */
	public record SseProbeVerdict(ConnectHint hint, String outcome, List<String> probedPaths, String alivePath,
			String siblingPath) {

		/** Wire value: the probe found an SSE-alive path. */
		public static final String OUTCOME_ALIVE = "alive";

		/** Wire value: every probed path answered definitively non-alive. */
		public static final String OUTCOME_DEAD = "dead";

		/**
		 * Wire value: the probe timed out or errored (SSE neither confirmed nor ruled
		 * out).
		 */
		public static final String OUTCOME_TIMEOUT = "timeout";

		/** Wire value: the trigger gate did not fire (no probe at all). */
		public static final String OUTCOME_NOT_RUN = "not_run";

		/**
		 * The verdict for a gate miss: no probe, no hint, no probed paths.
		 * @return the {@code not_run} verdict
		 */
		public static SseProbeVerdict notRun() {
			return new SseProbeVerdict(null, OUTCOME_NOT_RUN, List.of(), null, null);
		}

	}

}
