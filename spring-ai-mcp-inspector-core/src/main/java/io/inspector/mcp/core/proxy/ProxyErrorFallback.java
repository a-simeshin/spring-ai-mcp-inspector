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

import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Terminal error payload for the SSE {@code error} event of the 1.x line. This line has
 * no ProxyErrorDto contract (owner decision on PR #178, 2026-11-01); a dead upstream
 * still deserves a readable wire form, so both stacks deliver a simple {@code {"message":
 * ..., "reason": ...}} payload instead: the message is fixed and human-readable, and the
 * reason reuses the {@link ProxyConnectFailure.Reason} vocabulary the inspector client
 * already maps onto its ConnectFailure reasons.
 *
 * @author Artem Simeshin
 */
public final class ProxyErrorFallback {

	/**
	 * Fixed human-readable message for the terminal error event of a dead upstream (the
	 * sidebar banner renders it verbatim).
	 */
	public static final String UPSTREAM_DEATH_MESSAGE = "The upstream server is no longer reachable";

	private ProxyErrorFallback() {
	}

	/**
	 * Whether {@code failure} classifies as an upstream death (same classification the
	 * reconnect policy uses).
	 * @param failure the terminal failure to classify (never {@code null})
	 * @return {@code true} when the upstream target is dead
	 */
	public static boolean isUpstreamDeath(final Throwable failure) {
		return ProxyReconnectPolicy.isUpstreamDeath(failure);
	}

	/**
	 * The simple terminal payload of a dead upstream: {@code message} plus the classified
	 * {@code reason} in the client's lowercase vocabulary.
	 * @param failure the terminal upstream failure (never {@code null})
	 * @return the payload fields for the SSE {@code error} event
	 */
	public static Map<String, String> upstreamDeathPayload(final Throwable failure) {
		final ProxyConnectFailure.Reason reason = ProxyConnectFailure.classify(failure).reason();
		return Map.of("message", UPSTREAM_DEATH_MESSAGE, "reason", reason.name().toLowerCase(Locale.ROOT));
	}

	/**
	 * Serialises the terminal payload for the SSE {@code error} event.
	 * @param mapper the stack's Jackson mapper (never {@code null})
	 * @param failure the terminal upstream failure (never {@code null})
	 * @return the JSON payload, or a hand-built equivalent when serialization fails
	 */
	public static String payloadJson(final ObjectMapper mapper, final Throwable failure) {
		try {
			return mapper.writeValueAsString(upstreamDeathPayload(failure));
		}
		catch (final JsonProcessingException ex) {
			// Cannot happen for a Map<String,String>; keep the stream readable anyway.
			return "{\"message\":\"" + UPSTREAM_DEATH_MESSAGE + "\",\"reason\":\"unknown\"}";
		}
	}

}
