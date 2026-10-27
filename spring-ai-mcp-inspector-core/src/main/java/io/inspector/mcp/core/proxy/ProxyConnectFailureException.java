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
 * Signals that a proxy session could not be opened because connecting to the upstream MCP
 * server failed.
 *
 * <p>
 * Carries the classified {@link ProxyConnectFailure} so the HTTP layer can map it onto
 * the structured {@code MCP_CONNECT_FAILED} response without re-inspecting the cause. The
 * cause is attached for server-side logging only — it is never serialized into the HTTP
 * response.
 *
 * @author Artem Simeshin
 */
public final class ProxyConnectFailureException extends RuntimeException {

	private final ProxyConnectFailure failure;

	private final ConnectHint hint;

	private final SseConnectProbe.SseProbeVerdict probeVerdict;

	/**
	 * Creates a connect failure carrying only the classified failure (no probe hint).
	 * @param failure the classified connect failure
	 * @param cause the original connect exception, for server-side logging only
	 */
	public ProxyConnectFailureException(final ProxyConnectFailure failure, final Throwable cause) {
		this(failure, cause, null, null);
	}

	/**
	 * Creates a connect failure with the optional SSE probe verdict of issue #250.
	 * @param failure the classified connect failure
	 * @param cause the original connect exception, for server-side logging only
	 * @param hint the hint to attach to the {@code MCP_CONNECT_FAILED} payload, or
	 * {@code null} when the probe found nothing
	 * @param probeVerdict the full probe verdict for timeline recording, or {@code null}
	 */
	public ProxyConnectFailureException(final ProxyConnectFailure failure, final Throwable cause,
			final ConnectHint hint, final SseConnectProbe.SseProbeVerdict probeVerdict) {
		super(failure.message(), cause);
		this.failure = failure;
		this.hint = hint;
		this.probeVerdict = probeVerdict;
	}

	/**
	 * The classified connect failure carried by this exception.
	 * @return the classified connect failure (never {@code null})
	 */
	public ProxyConnectFailure failure() {
		return this.failure;
	}

	/**
	 * The optional SSE-probe hint for the {@code MCP_CONNECT_FAILED} payload.
	 * @return the hint, or {@code null} when the probe produced none
	 */
	public ConnectHint hint() {
		return this.hint;
	}

	/**
	 * The full SSE probe verdict (timeline payload), also answering "did the probe run".
	 * @return the verdict, or {@code null} when the exception predates the probe
	 */
	public SseConnectProbe.SseProbeVerdict probeVerdict() {
		return this.probeVerdict;
	}

}
