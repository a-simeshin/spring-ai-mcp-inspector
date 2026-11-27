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
 * Fallback DTOs for terminal proxy failures {@link ProxyErrorMapper#map} cannot classify
 * (it yields {@code null} for failures without an HTTP status). An SSE response that has
 * already started can only deliver its terminal failure as an in-stream named
 * {@code error} event: completing the stream with the raw exception dispatches to Boot's
 * {@code /error}, whose LinkedHashMap JSON body has no converter for the committed
 * {@code text/event-stream} response, so the log carries
 * {@code HttpMessageNotWritableException} while the browser sees a silent cut. These DTOs
 * keep the D3 shape ({@code {status, code, reason, guidance, url}}) so the existing
 * client reader renders them without a new contract.
 *
 * @author Artem Simeshin
 */
public final class ProxyErrorFallback {

	private ProxyErrorFallback() {
	}

	/**
	 * DTO for a dead upstream: the target is unreachable (classified as death by
	 * {@link ProxyReconnectPolicy#isUpstreamDeath}).
	 * @return the 502 {@code upstream_unreachable} DTO
	 */
	public static ProxyErrorDto upstreamDeath() {
		return new ProxyErrorDto(502, "upstream_unreachable", "The upstream MCP server is unreachable.",
				"Check the upstream server, then reconnect.", null);
	}

}
