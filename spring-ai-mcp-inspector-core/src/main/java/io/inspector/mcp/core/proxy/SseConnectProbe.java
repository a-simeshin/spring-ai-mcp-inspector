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
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Read-only SSE probe run after a failed Streamable HTTP connect (spec card t_42b235d6,
 * issue #250).
 *
 * <p>
 * When a Spring AI server serves only the legacy SSE transport, a Streamable HTTP connect
 * fails with a bare 404/405. The probe checks, with two cheap non-mutating GETs, whether
 * the server actually serves SSE: first the exact URI the connect attempted, then the
 * conventional sibling path ({@code /sse}). A path counts as SSE-alive when it answers
 * 2xx with {@code Content-Type: text/event-stream}, or 405 with an {@code Allow} header
 * advertising {@code text/event-stream} (decision matrix M1/M2).
 *
 * <p>
 * Failure isolation (spec section 4): the probe never throws, never sends credentials,
 * never mutates upstream state, never consumes response bodies, and is bounded to 2.5 s
 * per path with a 5 s hard cap for the whole sequence.
 *
 * @author Artem Simeshin
 */
public final class SseConnectProbe {

	/** Probe budget per path (connect plus response headers), in milliseconds. */
	private static final long PATH_TIMEOUT_MS = 2500;

	/** Hard cap for the whole two-path probe sequence, in milliseconds. */
	private static final long TOTAL_BUDGET_MS = 5000;

	/** Do not start another path when the remaining sequence budget drops below this. */
	private static final long MIN_REMAINING_MS = 200;

	/** Distinctive User-Agent so server operators can tell probes from real traffic. */
	private static final String PROBE_USER_AGENT = "mcp-inspector-probe/1.0";

	/** Content type the probe considers proof of an SSE endpoint. */
	private static final String EVENT_STREAM = "text/event-stream";

	private static final ExecutorService PROBE_EXECUTOR = Executors.newFixedThreadPool(2, new ThreadFactory() {
		@Override
		public Thread newThread(final Runnable runnable) {
			final Thread thread = new Thread(runnable, "mcp-inspector-sse-probe");
			thread.setDaemon(true);
			return thread;
		}
	});

	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofMillis(PATH_TIMEOUT_MS))
		.executor(PROBE_EXECUTOR)
		.build();

	private SseConnectProbe() {
	}

	/**
	 * Gate plus probe plus hint derivation in one call: everything a controller needs to
	 * enrich a failed Streamable HTTP connect (spec sections 5 and 6).
	 * @param failure the classified connect failure
	 * @param error the original exception behind the failure (may be {@code null}); used
	 * only for the UNKNOWN-bucket "Failed to send message" signature
	 * @param target the resolved URI the connect attempted (may be {@code null}, in which
	 * case no probe runs)
	 * @return the diagnosis (never {@code null}): probe result and hint, both possibly
	 * {@code null} (gate closed or probe inconclusive)
	 */
	public static Diagnosis diagnose(final ProxyConnectFailure failure, final Throwable error, final URI target) {
		Objects.requireNonNull(failure, "failure must not be null");
		if (target == null || !shouldProbe(failure, error)) {
			return new Diagnosis(null, null);
		}
		final ProbeResult result = probe(target);
		return new Diagnosis(result, hintFor(failure, result));
	}

	/**
	 * Whether the probe should run for this failure at all (spec section 3): the
	 * 404/405-shaped NOT_FOUND bucket, or the UNKNOWN bucket with the "Failed to send
	 * message" signature (mcp-core does not surface the POST status).
	 * @param failure the classified connect failure
	 * @param error the original exception behind the failure (may be {@code null})
	 * @return {@code true} when the probe gate is open
	 */
	public static boolean shouldProbe(final ProxyConnectFailure failure, final Throwable error) {
		Objects.requireNonNull(failure, "failure must not be null");
		if (failure.reason() == ProxyConnectFailure.Reason.NOT_FOUND) {
			return true;
		}
		return failure.reason() == ProxyConnectFailure.Reason.UNKNOWN && hasFailedToSendMessageSignature(error);
	}

	/**
	 * Derives the transport-switch hint from the trigger and the probe outcome (spec
	 * section 6 decision matrix).
	 * @param failure the classified connect failure (the trigger)
	 * @param result the probe result
	 * @return the hint, or {@code null} for matrix rows M4/M5 (no hint)
	 */
	public static ConnectHint hintFor(final ProxyConnectFailure failure, final ProbeResult result) {
		Objects.requireNonNull(failure, "failure must not be null");
		Objects.requireNonNull(result, "result must not be null");
		if (result.outcome() == Outcome.ALIVE) {
			return ConnectHint.sseOnly(result.alivePath());
		}
		if (failure.reason() == ProxyConnectFailure.Reason.NOT_FOUND && result.outcome() == Outcome.DEAD) {
			return ConnectHint.maybeSse(result.siblingPath());
		}
		return null;
	}

	/**
	 * Runs the bounded two-path probe sequence: the resolved target URI first, then the
	 * same-origin SSE sibling. Never throws.
	 * @param target the resolved URI the connect attempted
	 * @return the probe result (never {@code null})
	 */
	public static ProbeResult probe(final URI target) {
		Objects.requireNonNull(target, "target must not be null");
		final long deadline = System.nanoTime() + Duration.ofMillis(TOTAL_BUDGET_MS).toNanos();
		final List<URI> candidates = candidates(target);
		final String siblingPath = (candidates.size() > 1) ? candidates.get(1).getPath() : candidates.get(0).getPath();
		final List<String> probed = new ArrayList<>(candidates.size());
		boolean definite = true;
		for (final URI candidate : candidates) {
			final long remainingMs = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
			if (remainingMs < MIN_REMAINING_MS) {
				return new ProbeResult(Outcome.TIMEOUT, null, siblingPath, probed);
			}
			probed.add(candidate.getPath());
			final PathVerdict verdict = probePath(candidate, Duration.ofMillis(Math.min(PATH_TIMEOUT_MS, remainingMs)));
			if (verdict == PathVerdict.ALIVE) {
				return new ProbeResult(Outcome.ALIVE, candidate.getPath(), siblingPath, probed);
			}
			if (verdict == PathVerdict.INDEFINITE) {
				definite = false;
			}
		}
		return new ProbeResult(definite ? Outcome.DEAD : Outcome.TIMEOUT, null, siblingPath, probed);
	}

	/**
	 * Builds the MCP_CONNECT_FAILED timeline payload shared by both stacks (spec section
	 * 8): {@code {reason, hintKind, probedPaths, probeOutcome, url}}.
	 * @param factory the JSON node factory to use
	 * @param failure the classified connect failure
	 * @param diagnosis the diagnosis (never {@code null})
	 * @param target the resolved URI the connect attempted (may be {@code null})
	 * @return the payload node (never {@code null})
	 */
	public static ObjectNode timelinePayload(final JsonNodeFactory factory, final ProxyConnectFailure failure,
			final Diagnosis diagnosis, final URI target) {
		final ObjectNode payload = factory.objectNode();
		payload.put("reason", failure.reason().wire());
		if (diagnosis.hintKind() == null) {
			payload.putNull("hintKind");
		}
		else {
			payload.put("hintKind", diagnosis.hintKind());
		}
		final ArrayNode probedPaths = payload.putArray("probedPaths");
		if (diagnosis.probe != null) {
			for (final String path : diagnosis.probe.probedPaths()) {
				probedPaths.add(path);
			}
		}
		payload.put("probeOutcome", diagnosis.probeOutcome());
		if (target == null) {
			payload.putNull("url");
		}
		else {
			payload.put("url", ProxyErrorDto.redactUrl(target));
		}
		return payload;
	}

	private static boolean hasFailedToSendMessageSignature(final Throwable error) {
		Throwable current = error;
		while (current != null) {
			final String message = current.getMessage();
			if (message != null && message.startsWith("Failed to send message")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static PathVerdict probePath(final URI uri, final Duration timeout) {
		try {
			final HttpRequest request = HttpRequest.newBuilder(uri)
				.header("Accept", EVENT_STREAM)
				.header("User-Agent", PROBE_USER_AGENT)
				.timeout(timeout)
				.GET()
				.build();
			final HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
			// The body is deliberately never read: closing the stream releases the
			// connection immediately, so a 200 event-stream answer cannot hang.
			try (InputStream body = response.body()) {
				final int status = response.statusCode();
				if (status >= 200 && status < 300 && headerContains(response, "Content-Type")) {
					return PathVerdict.ALIVE;
				}
				if (status == 405 && headerContains(response, "Allow")) {
					return PathVerdict.ALIVE;
				}
				return PathVerdict.DEAD;
			}
		}
		catch (final Exception ex) {
			// Timeout, DNS, TLS, reset: the path is simply not confirmed alive, and the
			// original connect error stays the only thing reported to the client.
			return PathVerdict.INDEFINITE;
		}
	}

	private static boolean headerContains(final HttpResponse<?> response, final String header) {
		for (final String value : response.headers().allValues(header)) {
			if (value.toLowerCase(Locale.ROOT).contains(EVENT_STREAM)) {
				return true;
			}
		}
		return false;
	}

	private static List<URI> candidates(final URI target) {
		final List<URI> list = new ArrayList<>(2);
		list.add(target);
		final String path = (target.getPath() == null || target.getPath().isEmpty()) ? "/" : target.getPath();
		final int lastSlash = path.lastIndexOf('/');
		final String siblingPath = (lastSlash <= 0) ? "/sse" : path.substring(0, lastSlash) + "/sse";
		try {
			final URI sibling = new URI(target.getScheme(), target.getUserInfo(), target.getHost(), target.getPort(),
					siblingPath, null, null);
			if (!sibling.equals(target)) {
				list.add(sibling);
			}
		}
		catch (final URISyntaxException ex) {
			// A sibling that cannot even be built cannot be probed; the target alone is.
		}
		return list;
	}

	private static String wireNameOf(final Outcome outcome) {
		return switch (outcome) {
			case ALIVE -> "alive";
			case DEAD -> "dead";
			case TIMEOUT -> "timeout";
		};
	}

	/** Verdict for one probed path. */
	private enum PathVerdict {

		/** The path proved SSE alive (2xx event-stream, or 405 allowing event-stream). */
		ALIVE,

		/** The path answered with a definite non-alive response. */
		DEAD,

		/** The path timed out or errored: nothing definite either way. */
		INDEFINITE

	}

	/** Outcome of one probe sequence. */
	public enum Outcome {

		/** Some probed path proved SSE alive. */
		ALIVE,

		/** Every probed path got a definite non-alive answer. */
		DEAD,

		/** The sequence timed out or errored before reaching a definite answer. */
		TIMEOUT

	}

	/**
	 * Result of one probe sequence (spec section 5).
	 *
	 * @param outcome the sequence outcome
	 * @param alivePath the path that proved SSE alive, or {@code null} when none did
	 * @param siblingPath the conventional SSE sibling path derived from the target
	 * @param probedPaths the paths actually probed, in order
	 */
	public record ProbeResult(Outcome outcome, String alivePath, String siblingPath, List<String> probedPaths) {

		// Defensive copy, so callers cannot mutate the recorded path list.
		public ProbeResult {
			probedPaths = List.copyOf(probedPaths);
		}

	}

	/**
	 * Outcome of {@link #diagnose}: the probe result (when the gate was open) plus the
	 * derived hint (when the decision matrix produced one). Both fields are {@code null}
	 * when the probe did not run.
	 *
	 * @param probe the probe result, or {@code null} when the gate was closed
	 * @param hint the derived hint, or {@code null} when the matrix produced none
	 */
	public record Diagnosis(ProbeResult probe, ConnectHint hint) {

		/**
		 * Wire value for the timeline payload: {@code not_run} when the probe did not
		 * run, otherwise the probe outcome.
		 * @return {@code alive}, {@code dead}, {@code timeout} or {@code not_run}
		 */
		public String probeOutcome() {
			return (this.probe == null) ? "not_run" : wireNameOf(this.probe.outcome());
		}

		/**
		 * Wire value for the timeline payload, or {@code null} when there is no hint.
		 * @return the hint kind wire value or {@code null}
		 */
		public String hintKind() {
			return (this.hint == null) ? null : this.hint.kind().wire();
		}

	}

}
