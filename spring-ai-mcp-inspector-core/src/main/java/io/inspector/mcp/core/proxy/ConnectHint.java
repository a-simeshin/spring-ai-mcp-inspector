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

/**
 * Optional hint appended to a structured {@code MCP_CONNECT_FAILED} payload when the SSE
 * connect probe found evidence that the upstream server serves the legacy SSE transport
 * instead of Streamable HTTP (issue #250, upstream spring-projects/spring-ai#7207).
 *
 * @author Artem Simeshin
 * @param kind the hint kind: {@code sse_only} when the probe confirmed SSE is alive,
 * {@code maybe_sse} when the probe ruled SSE out but the failure still looks like a
 * transport mismatch
 * @param probePath the request path the verdict is based on: the path that proved
 * SSE-alive for {@code sse_only}, the checked sibling path for {@code maybe_sse}
 * @param refs stable references rendered as links by the UI (never {@code null})
 */
public record ConnectHint(String kind, String probePath, Refs refs) {

	/** Wire value of the strong hint (probe confirmed SSE is alive). */
	public static final String KIND_SSE_ONLY = "sse_only";

	/** Wire value of the weak hint (SSE ruled out, failure still looks mismatched). */
	public static final String KIND_MAYBE_SSE = "maybe_sse";

	/** Reference pair attached to every hint. */
	public static final Refs DEFAULT_REFS = new Refs("spring-projects/spring-ai#7207", "#250");

	/**
	 * Builds the wire {@code error} object of the {@code MCP_CONNECT_FAILED} payload
	 * shared verbatim by both stacks (WebMVC controller and WebFlux handler), so the JSON
	 * shape is identical field for field. The optional {@code hint} object is appended
	 * only when the SSE probe produced one (issue #250).
	 * @param failure the classified connect failure (must not be {@code null})
	 * @param hint the probe hint, or {@code null} to omit the {@code hint} key
	 * @param errorCode the {@code MCP_CONNECT_FAILED} error code constant
	 * @return the mutable wire map of the {@code error} object
	 */
	public static Map<String, Object> errorPayload(final ProxyConnectFailure failure, final ConnectHint hint,
			final String errorCode) {
		final Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", errorCode);
		error.put("reason", failure.reason().wire());
		error.put("message", failure.message());
		error.put("retryable", Boolean.TRUE);
		if (hint != null) {
			final Map<String, Object> hintMap = new LinkedHashMap<>();
			hintMap.put("kind", hint.kind());
			hintMap.put("probePath", hint.probePath());
			hintMap.put("refs", Map.of("upstream", hint.refs().upstream(), "local", hint.refs().local()));
			error.put("hint", hintMap);
		}
		return error;
	}

	/**
	 * Upstream and local issue references.
	 *
	 * @author Artem Simeshin
	 * @param upstream the upstream Spring AI issue reference
	 * @param local the local inspector issue reference
	 */
	public record Refs(String upstream, String local) {

	}

}
