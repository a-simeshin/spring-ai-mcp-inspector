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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-switch hint attached to a failed Streamable HTTP connect when the SSE probe
 * ({@link SseConnectProbe}) found evidence of the legacy SSE transport (spec card
 * t_42b235d6, issue #250).
 *
 * <p>
 * The hint is additive only: it never replaces the classified reason, message or HTTP
 * status of the original {@code MCP_CONNECT_FAILED} error, it may only be appended to the
 * payload (spec section 4, failure isolation).
 *
 * @author Artem Simeshin
 * @param kind the hint strength: {@code sse_only} when the probe proved an SSE endpoint
 * alive, {@code maybe_sse} when the SSE sibling path was checked but nothing was proved
 * @param probePath the path that produced the verdict: the path that proved SSE alive for
 * {@code sse_only}, the checked sibling path for {@code maybe_sse}
 * @param refs stable issue references rendered into the wire payload
 */
public record ConnectHint(Kind kind, String probePath, Map<String, String> refs) {

	/** Upstream reference: the Spring AI auto-configuration gap this hint explains. */
	public static final String REF_UPSTREAM = "spring-projects/spring-ai#7207";

	/** Local reference: the inspector issue that asked for the hint. */
	public static final String REF_LOCAL = "#250";

	/** Strength of the hint (wire: {@code sse_only} / {@code maybe_sse}). */
	public enum Kind {

		/**
		 * The probe proved an SSE endpoint alive: strong hint (decision matrix M1/M2).
		 */
		SSE_ONLY("sse_only"),

		/** The probe got definite non-alive answers but nothing more: weak hint (M3). */
		MAYBE_SSE("maybe_sse");

		private final String wire;

		Kind(final String wire) {
			this.wire = wire;
		}

		/**
		 * The wire representation of this kind.
		 * @return the wire representation used in the {@code hint} object
		 */
		public String wire() {
			return this.wire;
		}

	}

	/**
	 * Compact constructor with null-safety.
	 */
	public ConnectHint {
		Objects.requireNonNull(kind, "kind must not be null");
		Objects.requireNonNull(probePath, "probePath must not be null");
		refs = (refs != null) ? Map.copyOf(refs) : Map.of();
	}

	/**
	 * Strong hint factory (M1/M2: the probe proved SSE alive on {@code probePath}).
	 * @param probePath the path that proved SSE alive
	 * @return a new {@code sse_only} hint (never {@code null})
	 */
	public static ConnectHint sseOnly(final String probePath) {
		return new ConnectHint(Kind.SSE_ONLY, probePath, Map.of("upstream", REF_UPSTREAM, "local", REF_LOCAL));
	}

	/**
	 * Weak hint factory (M3: trigger was a 404, the probe ruled nothing out on the
	 * sibling path).
	 * @param probePath the checked sibling path
	 * @return a new {@code maybe_sse} hint (never {@code null})
	 */
	public static ConnectHint maybeSse(final String probePath) {
		return new ConnectHint(Kind.MAYBE_SSE, probePath, Map.of("upstream", REF_UPSTREAM, "local", REF_LOCAL));
	}

	/**
	 * Wire shape of the optional {@code hint} object inside the
	 * {@code MCP_CONNECT_FAILED} error payload (spec section 7): {@code {"kind": ...,
	 * "probePath": ..., "refs": ...}}.
	 * @return an insertion-ordered map ready to be serialized into the error payload
	 */
	public Map<String, Object> toWire() {
		final Map<String, Object> wire = new LinkedHashMap<>();
		wire.put("kind", this.kind.wire());
		wire.put("probePath", this.probePath);
		wire.put("refs", this.refs);
		return wire;
	}

}
